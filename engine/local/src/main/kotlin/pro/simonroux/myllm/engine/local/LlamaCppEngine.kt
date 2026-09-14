package pro.simonroux.myllm.engine.local

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import pro.simonroux.myllm.core.model.AppError
import pro.simonroux.myllm.core.model.EngineKind
import pro.simonroux.myllm.core.model.FinishReason
import pro.simonroux.myllm.core.model.GenerationEvent
import pro.simonroux.myllm.core.model.MessageMeta
import pro.simonroux.myllm.core.model.Outcome
import pro.simonroux.myllm.core.model.RuntimeConfig
import pro.simonroux.myllm.engine.api.CompletionRequest
import pro.simonroux.myllm.engine.api.EngineCapabilities
import pro.simonroux.myllm.engine.api.EngineState
import pro.simonroux.myllm.engine.api.LlmEngine
import java.io.File
import java.util.concurrent.Executors

/**
 * llama.cpp running in this process.
 *
 * All native calls are funnelled onto one thread: a llama_context is not thread
 * safe and the cost of getting that wrong is a hard crash with no stack trace.
 *
 * Between turns the KV cache is kept and only the diverging suffix of the new
 * prompt is decoded. On a long conversation that is the difference between a
 * reply starting instantly and one starting fifteen seconds later.
 */
class LlamaCppEngine(
    override val id: String,
    override val label: String,
    private val modelPath: String,
    private val runtime: RuntimeConfig,
    private val chatTemplateOverride: String? = null,
) : LlmEngine {

    override val kind: EngineKind = EngineKind.LOCAL

    private val worker: CoroutineDispatcher =
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "llama-$id").apply { isDaemon = true }
        }.asCoroutineDispatcher()

    private val _state = MutableStateFlow<EngineState>(EngineState.Idle)
    override val state: StateFlow<EngineState> = _state.asStateFlow()

    private var modelPtr: Long = 0
    private var ctxPtr: Long = 0
    private var prompt: LocalPrompt? = null

    /** Tokens currently held in the KV cache, in order. */
    private val cachedTokens = TokenWindow()

    private var trainedContextLength: Int = 0

    override var capabilities: EngineCapabilities = EngineCapabilities(
        // Local GGUF models have no structured tool-call channel through this
        // API. The agent layer parses calls out of the text instead.
        supportsTools = false,
        supportsStreaming = true,
        supportsThinking = false,
        contextLength = runtime.contextSize,
        worksOffline = true,
    )
        private set

    override suspend fun prepare(): Outcome<Unit> = withContext(worker) {
        if (ctxPtr != 0L) return@withContext Outcome.Ok(Unit)

        if (!LlamaNative.ensureLoaded()) {
            val message = LlamaNative.loadError ?: "Bibliothèque native indisponible"
            _state.value = EngineState.Failed(message)
            return@withContext Outcome.Err(AppError.Unexpected(message))
        }

        val file = File(modelPath)
        if (!file.isFile) {
            val message = "Fichier modèle introuvable : $modelPath"
            _state.value = EngineState.Failed(message)
            return@withContext Outcome.Err(AppError.ModelNotLoaded(message))
        }

        _state.value = EngineState.Preparing(0.1f, "Chargement des poids")

        val loadMode = when {
            runtime.useMmap && runtime.useMlock -> LlamaNative.LoadMode.MMAP_MLOCK
            runtime.useMmap -> LlamaNative.LoadMode.MMAP
            else -> LlamaNative.LoadMode.NONE
        }

        modelPtr = LlamaNative.loadModel(modelPath, runtime.gpuLayers, loadMode)
        if (modelPtr == 0L) {
            val message = "llama.cpp n'a pas pu charger ${file.name}"
            _state.value = EngineState.Failed(message)
            return@withContext Outcome.Err(AppError.ModelNotLoaded(message))
        }

        trainedContextLength = LlamaNative.modelContextLength(modelPtr)

        _state.value = EngineState.Preparing(0.7f, "Allocation du contexte")

        val threads = if (runtime.threads > 0) runtime.threads else performanceCoreCount()
        val contextSize =
            if (trainedContextLength > 0) minOf(runtime.contextSize, trainedContextLength)
            else runtime.contextSize

        ctxPtr = LlamaNative.newContext(
            modelPtr = modelPtr,
            nCtx = contextSize,
            nBatch = runtime.batchSize,
            nThreads = threads,
            nThreadsBatch = threads,
            flashAttn = if (runtime.flashAttention) {
                LlamaNative.FlashAttention.AUTO
            } else {
                LlamaNative.FlashAttention.DISABLED
            },
        )

        if (ctxPtr == 0L) {
            LlamaNative.freeModel(modelPtr)
            modelPtr = 0
            val message = "Contexte de $contextSize tokens impossible à allouer, mémoire insuffisante"
            _state.value = EngineState.Failed(message)
            return@withContext Outcome.Err(AppError.OutOfMemory(message))
        }

        prompt = LocalPrompt(modelPtr, chatTemplateOverride)
        cachedTokens.clear()
        capabilities = capabilities.copy(contextLength = LlamaNative.contextLength(ctxPtr))
        _state.value = EngineState.Ready

        Outcome.Ok(Unit)
    }

    override fun generate(request: CompletionRequest): Flow<GenerationEvent> = flow {
        if (ctxPtr == 0L || modelPtr == 0L) {
            emit(GenerationEvent.Failed("Modèle non chargé", recoverable = false))
            return@flow
        }

        val builder = prompt ?: run {
            emit(GenerationEvent.Failed("Gabarit de conversation indisponible", recoverable = false))
            return@flow
        }

        _state.value = EngineState.Generating

        // Cancellation has to reach the native side directly: a decode already
        // in flight will not check the coroutine's state on its own.
        val abortHandle = currentCoroutineContext()[Job]?.invokeOnCompletion {
            if (ctxPtr != 0L) LlamaNative.setAbort(ctxPtr, true)
        }

        val startedAt = System.currentTimeMillis()
        var promptTokens = 0
        var generated = 0
        var finish = FinishReason.STOP
        val accumulator = Utf8Accumulator()
        val text = StringBuilder()
        var samplerPtr = 0L

        try {
            val contextSize = LlamaNative.contextLength(ctxPtr)
            val rendered = builder.render(request.messages, request.systemPrompt)
            var tokens = LlamaNative.tokenize(modelPtr, rendered, addSpecial = true, parseSpecial = true)

            val budget = contextSize - request.params.maxTokens
            if (budget <= 0) {
                emit(
                    GenerationEvent.Failed(
                        "maxTokens (${request.params.maxTokens}) dépasse la fenêtre de contexte ($contextSize)",
                        recoverable = true,
                    ),
                )
                return@flow
            }
            if (tokens.size > budget) {
                // Keep the tail: the system prompt is re-rendered every turn
                // anyway, and the most recent turns matter most.
                tokens = tokens.copyOfRange(tokens.size - budget, tokens.size)
                cachedTokens.clear()
                LlamaNative.clearMemory(ctxPtr)
            }
            promptTokens = tokens.size

            // At least one token must always be decoded, otherwise there are no
            // fresh logits to sample the first reply token from.
            val reused = cachedTokens.commonPrefixLength(tokens, limit = tokens.size - 1)
            if (reused < cachedTokens.size) {
                LlamaNative.trimMemory(ctxPtr, reused)
                cachedTokens.truncateTo(reused)
            }

            var nPast = reused
            val batchSize = runtime.batchSize

            // Ingest whatever the cache does not already hold.
            while (nPast < tokens.size) {
                currentCoroutineContext().ensureActive()
                val end = minOf(nPast + batchSize, tokens.size)
                val isLast = end == tokens.size
                val chunk = tokens.copyOfRange(nPast, end)

                val rc = LlamaNative.decode(ctxPtr, chunk, nPast, wantLogits = isLast)
                if (rc != 0) {
                    emit(
                        GenerationEvent.Failed(
                            "Échec du décodage du prompt (code $rc)",
                            recoverable = rc > 0,
                        ),
                    )
                    finish = FinishReason.ERROR
                    return@flow
                }
                nPast = end
            }

            cachedTokens.reset(tokens)
            emit(
                GenerationEvent.PromptProcessed(
                    tokens = promptTokens,
                    elapsedMs = System.currentTimeMillis() - startedAt,
                ),
            )

            val decodeStartedAt = System.currentTimeMillis()
            val params = request.params
            samplerPtr = LlamaNative.newSampler(
                modelPtr = modelPtr,
                temperature = params.temperature,
                topK = params.topK,
                topP = params.topP,
                minP = params.minP,
                repeatPenalty = params.repeatPenalty,
                repeatLastN = params.repeatLastN,
                seed = params.seed,
            )

            while (generated < params.maxTokens) {
                currentCoroutineContext().ensureActive()

                val token = LlamaNative.sample(ctxPtr, samplerPtr)
                if (token < 0) {
                    finish = FinishReason.ERROR
                    break
                }
                if (LlamaNative.isEndOfGeneration(modelPtr, token)) {
                    finish = FinishReason.STOP
                    break
                }

                val piece = accumulator.append(LlamaNative.tokenToBytes(modelPtr, token))
                if (piece.isNotEmpty()) {
                    text.append(piece)
                    emit(GenerationEvent.Token(piece))
                }

                generated++

                val stop = params.stopSequences.firstOrNull { text.endsWith(it) }
                if (stop != null) {
                    text.setLength(text.length - stop.length)
                    finish = FinishReason.STOP
                    break
                }

                if (nPast >= contextSize) {
                    finish = FinishReason.LENGTH
                    break
                }

                val rc = LlamaNative.decode(ctxPtr, intArrayOf(token), nPast, wantLogits = true)
                if (rc != 0) {
                    finish = FinishReason.ERROR
                    break
                }
                nPast++
                cachedTokens.append(token)
            }

            if (generated >= params.maxTokens) finish = FinishReason.LENGTH

            val tail = accumulator.flush()
            if (tail.isNotEmpty()) {
                text.append(tail)
                emit(GenerationEvent.Token(tail))
            }

            val decodeMs = System.currentTimeMillis() - decodeStartedAt
            emit(
                GenerationEvent.Completed(
                    MessageMeta(
                        engineId = id,
                        modelId = File(modelPath).name,
                        promptTokens = promptTokens,
                        completionTokens = generated,
                        tokensPerSecond = if (decodeMs > 0) generated * 1000.0 / decodeMs else 0.0,
                        latencyMs = System.currentTimeMillis() - startedAt,
                        finishReason = finish,
                        offline = true,
                    ),
                ),
            )
        } finally {
            abortHandle?.dispose()
            if (samplerPtr != 0L) LlamaNative.freeSampler(samplerPtr)
            if (ctxPtr != 0L) LlamaNative.setAbort(ctxPtr, false)
            if (_state.value is EngineState.Generating) _state.value = EngineState.Ready
        }
    }.flowOn(worker)

    override suspend fun countTokens(text: String): Int = withContext(worker) {
        if (modelPtr == 0L) return@withContext text.length / 4
        LlamaNative.tokenize(modelPtr, text, addSpecial = false, parseSpecial = false).size
    }

    override suspend fun release() {
        withContext(worker) {
            if (ctxPtr != 0L) {
                LlamaNative.freeContext(ctxPtr)
                ctxPtr = 0
            }
            if (modelPtr != 0L) {
                LlamaNative.freeModel(modelPtr)
                modelPtr = 0
            }
            prompt = null
            cachedTokens.clear()
            _state.value = EngineState.Idle
        }
    }

    /**
     * Big cores only. Scheduling decode threads onto efficiency cores costs more
     * than the extra parallelism gains, and on a big.LITTLE phone the slowest
     * thread sets the pace for the whole batch.
     */
    private fun performanceCoreCount(): Int {
        val total = Runtime.getRuntime().availableProcessors()
        return when {
            total >= 8 -> total / 2
            total >= 4 -> total - 1
            else -> 1
        }.coerceAtLeast(1)
    }
}
