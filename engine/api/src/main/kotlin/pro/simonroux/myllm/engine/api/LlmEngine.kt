package pro.simonroux.myllm.engine.api

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import pro.simonroux.myllm.core.model.ChatMessage
import pro.simonroux.myllm.core.model.EngineKind
import pro.simonroux.myllm.core.model.GenerationEvent
import pro.simonroux.myllm.core.model.GenerationParams
import pro.simonroux.myllm.core.model.Outcome
import pro.simonroux.myllm.core.model.ToolSpec

/**
 * One way of turning a conversation into tokens.
 *
 * Implementations are interchangeable: a llama.cpp model mapped into this
 * process and an HTTP endpoint on the other side of the world present the same
 * surface, so routing, the agent loop and the UI never branch on which is in use.
 *
 * Contract:
 *  - [prepare] is idempotent and safe to call from any thread.
 *  - [generate] is cold; collecting starts work, cancelling the collector stops it.
 *  - An engine may be prepared and released many times over its lifetime.
 */
interface LlmEngine {

    val id: String

    val kind: EngineKind

    /** Human readable, shown in the engine picker. */
    val label: String

    val capabilities: EngineCapabilities

    val state: StateFlow<EngineState>

    /**
     * Loads whatever the engine needs to serve requests: mapping weights for a
     * local engine, validating credentials and reachability for a remote one.
     */
    suspend fun prepare(): Outcome<Unit>

    /**
     * Streams a completion.
     *
     * The flow always terminates with exactly one [GenerationEvent.Completed] or
     * [GenerationEvent.Failed] unless the collector cancels first.
     */
    fun generate(request: CompletionRequest): Flow<GenerationEvent>

    /** Best-effort token count, used for context budgeting. */
    suspend fun countTokens(text: String): Int

    /** Frees weights, sockets and caches. The engine can be prepared again afterwards. */
    suspend fun release()
}

data class CompletionRequest(
    val messages: List<ChatMessage>,
    val systemPrompt: String? = null,
    val params: GenerationParams = GenerationParams(),
    val tools: List<ToolSpec> = emptyList(),
    /** Ask the model to emit reasoning, when it supports a separate channel for it. */
    val thinking: Boolean = false,
)

data class EngineCapabilities(
    val supportsTools: Boolean,
    val supportsStreaming: Boolean,
    val supportsThinking: Boolean,
    val supportsImages: Boolean = false,
    /** Maximum context in tokens, or 0 when unknown. */
    val contextLength: Int = 0,
    /** True when running this engine requires no network at all. */
    val worksOffline: Boolean,
)

sealed interface EngineState {
    data object Idle : EngineState

    data class Preparing(val progress: Float, val stage: String) : EngineState

    data object Ready : EngineState

    data object Generating : EngineState

    data class Failed(val message: String) : EngineState
}
