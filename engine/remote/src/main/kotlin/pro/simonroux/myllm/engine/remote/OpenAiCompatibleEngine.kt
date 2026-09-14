package pro.simonroux.myllm.engine.remote

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import okhttp3.Call
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import pro.simonroux.myllm.core.model.AppError
import pro.simonroux.myllm.core.model.ChatRole
import pro.simonroux.myllm.core.model.EngineKind
import pro.simonroux.myllm.core.model.FinishReason
import pro.simonroux.myllm.core.model.GenerationEvent
import pro.simonroux.myllm.core.model.MessageMeta
import pro.simonroux.myllm.core.model.Outcome
import pro.simonroux.myllm.core.model.RemoteEngineConfig
import pro.simonroux.myllm.core.model.ToolCall
import pro.simonroux.myllm.engine.api.CompletionRequest
import pro.simonroux.myllm.engine.api.EngineCapabilities
import pro.simonroux.myllm.engine.api.EngineState
import pro.simonroux.myllm.engine.api.LlmEngine
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Any endpoint that speaks POST /chat/completions.
 *
 * Nothing here is provider specific on purpose: swapping OpenRouter for a
 * llama.cpp server on the home network is a change of base URL, not of code.
 *
 * @param apiKeyProvider resolves the key at call time rather than holding it,
 *   so the secret lives in the keystore-backed store and never in a long-lived
 *   object that could end up in a heap dump.
 * @param allowNetwork consulted before every request. This is how the app-wide
 *   offline switch is enforced at the last possible moment, after any
 *   configuration change.
 */
class OpenAiCompatibleEngine(
    override val id: String,
    override val label: String,
    private val config: RemoteEngineConfig,
    private val apiKeyProvider: suspend () -> String?,
    private val allowNetwork: suspend () -> Boolean = { true },
    httpClient: OkHttpClient? = null,
) : LlmEngine {

    override val kind: EngineKind = EngineKind.REMOTE

    override val capabilities: EngineCapabilities = EngineCapabilities(
        supportsTools = config.supportsTools,
        supportsStreaming = config.supportsStreaming,
        supportsThinking = true,
        contextLength = 0,
        worksOffline = false,
    )

    private val _state = MutableStateFlow<EngineState>(EngineState.Idle)
    override val state: StateFlow<EngineState> = _state.asStateFlow()

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
        explicitNulls = false
        isLenient = true
    }

    private val client: OkHttpClient = httpClient ?: OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        // Streaming responses are long-lived by design, so the read timeout
        // bounds the gap between chunks, not the whole exchange.
        .readTimeout(config.timeoutSeconds.toLong(), TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    override suspend fun prepare(): Outcome<Unit> {
        if (!allowNetwork()) {
            _state.value = EngineState.Failed("Mode hors ligne actif")
            return Outcome.Err(AppError.Offline())
        }
        if (config.baseUrl.isBlank()) {
            _state.value = EngineState.Failed("URL non configurée")
            return Outcome.Err(AppError.Unexpected("URL de l'endpoint manquante"))
        }
        _state.value = EngineState.Ready
        return Outcome.Ok(Unit)
    }

    override fun generate(request: CompletionRequest): Flow<GenerationEvent> = flow {
        if (!allowNetwork()) {
            emit(GenerationEvent.Failed("Mode hors ligne actif", recoverable = true))
            return@flow
        }

        _state.value = EngineState.Generating
        val startedAt = System.currentTimeMillis()
        var call: Call? = null

        try {
            val body = json.encodeToString(ChatRequestDto.serializer(), buildRequest(request))
            val httpRequest = Request.Builder()
                .url(config.baseUrl.trimEnd('/') + "/chat/completions")
                .headers(buildHeaders(apiKeyProvider()))
                .post(body.toRequestBody(JSON_MEDIA_TYPE))
                .build()

            call = client.newCall(httpRequest)
            val response = call.execute()

            response.use {
                if (!response.isSuccessful) {
                    val payload = response.body?.string().orEmpty()
                    emit(GenerationEvent.Failed(describeHttpError(response.code, payload), recoverable = response.code >= 500 || response.code == 429))
                    return@flow
                }

                val source = response.body?.source()
                if (source == null) {
                    emit(GenerationEvent.Failed("Réponse vide", recoverable = true))
                    return@flow
                }

                val accumulator = ToolCallAccumulator()
                var usage: UsageDto? = null
                var finish = FinishReason.STOP
                var sawContent = false

                while (true) {
                    currentCoroutineContext().ensureActive()
                    val line = source.readUtf8Line() ?: break
                    if (line.isBlank()) continue
                    if (!line.startsWith(SSE_DATA_PREFIX)) continue

                    val payload = line.removePrefix(SSE_DATA_PREFIX).trim()
                    if (payload == SSE_DONE) break

                    val chunk = runCatching {
                        json.decodeFromString(StreamChunkDto.serializer(), payload)
                    }.getOrNull() ?: continue

                    chunk.error?.let {
                        emit(GenerationEvent.Failed(it.message.ifBlank { "Erreur du fournisseur" }, recoverable = true))
                        return@flow
                    }

                    chunk.usage?.let { usage = it }

                    val choice = chunk.choices.firstOrNull() ?: continue

                    choice.delta.content?.takeIf { it.isNotEmpty() }?.let {
                        sawContent = true
                        emit(GenerationEvent.Token(it))
                    }

                    (choice.delta.reasoningContent ?: choice.delta.reasoning)
                        ?.takeIf { it.isNotEmpty() }
                        ?.let { emit(GenerationEvent.Thinking(it)) }

                    choice.delta.toolCalls?.forEach(accumulator::accept)

                    choice.finishReason?.let { finish = mapFinishReason(it) }
                }

                val toolCalls = accumulator.build(json)
                toolCalls.forEach { emit(GenerationEvent.ToolCallRequested(it)) }
                if (toolCalls.isNotEmpty()) finish = FinishReason.TOOL_CALLS

                if (!sawContent && toolCalls.isEmpty() && usage == null) {
                    emit(GenerationEvent.Failed("Le fournisseur n'a renvoyé aucun contenu", recoverable = true))
                    return@flow
                }

                val elapsed = System.currentTimeMillis() - startedAt
                emit(
                    GenerationEvent.Completed(
                        MessageMeta(
                            engineId = id,
                            modelId = config.modelId,
                            promptTokens = usage?.promptTokens ?: 0,
                            completionTokens = usage?.completionTokens ?: 0,
                            tokensPerSecond = usage?.completionTokens
                                ?.takeIf { elapsed > 0 }
                                ?.let { it * 1000.0 / elapsed } ?: 0.0,
                            latencyMs = elapsed,
                            finishReason = finish,
                            offline = false,
                        ),
                    ),
                )
            }
        } catch (e: IOException) {
            emit(GenerationEvent.Failed(e.message ?: "Erreur réseau", recoverable = true))
        } finally {
            call?.cancel()
            if (_state.value is EngineState.Generating) _state.value = EngineState.Ready
        }
    }.flowOn(Dispatchers.IO)

    /** Remote endpoints tokenize server side, so this is an estimate used only for budgeting. */
    override suspend fun countTokens(text: String): Int = (text.length / 3.6).toInt()

    override suspend fun release() {
        client.dispatcher.cancelAll()
        _state.value = EngineState.Idle
    }

    private fun buildRequest(request: CompletionRequest): ChatRequestDto {
        val messages = buildList {
            request.systemPrompt?.takeIf { it.isNotBlank() }?.let {
                add(MessageDto(role = "system", content = it))
            }
            request.messages.forEach { message ->
                add(
                    MessageDto(
                        role = when (message.role) {
                            ChatRole.SYSTEM -> "system"
                            ChatRole.USER -> "user"
                            ChatRole.ASSISTANT -> "assistant"
                            ChatRole.TOOL -> "tool"
                        },
                        content = message.content,
                        toolCallId = message.toolCallId,
                        toolCalls = message.toolCalls
                            .takeIf { it.isNotEmpty() }
                            ?.map { call ->
                                ToolCallDto(
                                    id = call.id,
                                    type = "function",
                                    function = ToolCallFunctionDto(
                                        name = call.name,
                                        arguments = call.arguments.toString(),
                                    ),
                                )
                            },
                    ),
                )
            }
        }

        val tools = request.tools
            .takeIf { it.isNotEmpty() && config.supportsTools }
            ?.map { spec ->
                ToolDto(
                    function = FunctionDto(
                        name = spec.name,
                        description = spec.description,
                        parameters = spec.parameters,
                    ),
                )
            }

        return ChatRequestDto(
            model = config.modelId,
            messages = messages,
            stream = config.supportsStreaming,
            temperature = request.params.temperature,
            topP = request.params.topP,
            maxTokens = request.params.maxTokens,
            stop = request.params.stopSequences.takeIf { it.isNotEmpty() },
            tools = tools,
            toolChoice = if (tools != null) "auto" else null,
            streamOptions = if (config.supportsStreaming) StreamOptionsDto() else null,
        )
    }

    private fun buildHeaders(apiKey: String?): Headers {
        val builder = Headers.Builder()
            .add("Content-Type", "application/json")
            .add("Accept", "text/event-stream")
        if (!apiKey.isNullOrBlank()) builder.add("Authorization", "Bearer $apiKey")
        config.extraHeaders.forEach { (name, value) -> builder.add(name, value) }
        return builder.build()
    }

    private fun describeHttpError(code: Int, payload: String): String {
        val detail = runCatching {
            json.decodeFromString(ErrorEnvelopeDto.serializer(), payload).error?.message
        }.getOrNull()?.takeIf { it.isNotBlank() }

        return when (code) {
            401, 403 -> "Clé API refusée (HTTP $code)"
            404 -> "Modèle ou endpoint introuvable (HTTP 404)"
            429 -> "Quota atteint (HTTP 429)" + (detail?.let { " : $it" } ?: "")
            in 500..599 -> "Erreur du fournisseur (HTTP $code)"
            else -> detail ?: "HTTP $code"
        }
    }

    private fun mapFinishReason(value: String): FinishReason = when (value) {
        "stop", "end_turn" -> FinishReason.STOP
        "length", "max_tokens" -> FinishReason.LENGTH
        "tool_calls", "function_call" -> FinishReason.TOOL_CALLS
        else -> FinishReason.UNKNOWN
    }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        const val SSE_DATA_PREFIX = "data:"
        const val SSE_DONE = "[DONE]"
    }
}

/**
 * Reassembles tool calls that arrive split across many SSE frames.
 *
 * The protocol streams the call id and function name once, then the JSON
 * arguments a few characters at a time, keyed by index. Parsing before the last
 * fragment arrives yields a syntax error, so nothing is decoded until the
 * stream ends.
 */
internal class ToolCallAccumulator {

    private class Partial(var id: String? = null, var name: String? = null) {
        val arguments = StringBuilder()
    }

    private val partials = LinkedHashMap<Int, Partial>()

    fun accept(delta: ToolCallDto) {
        val index = delta.index ?: partials.size
        val partial = partials.getOrPut(index) { Partial() }
        delta.id?.let { partial.id = it }
        delta.function?.name?.let { partial.name = it }
        delta.function?.arguments?.let { partial.arguments.append(it) }
    }

    fun build(json: Json): List<ToolCall> = partials.entries.mapNotNull { (index, partial) ->
        val name = partial.name ?: return@mapNotNull null
        val arguments: JsonObject = runCatching {
            json.parseToJsonElement(partial.arguments.toString().ifBlank { "{}" }).jsonObject
        }.getOrElse { JsonObject(emptyMap()) }

        ToolCall(
            id = partial.id ?: "call_$index",
            name = name,
            arguments = arguments,
        )
    }
}
