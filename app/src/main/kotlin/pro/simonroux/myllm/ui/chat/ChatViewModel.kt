package pro.simonroux.myllm.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import pro.simonroux.myllm.agent.AgentEvent
import pro.simonroux.myllm.core.AppContainer
import pro.simonroux.myllm.core.model.ChatMessage
import pro.simonroux.myllm.core.model.ChatRole
import pro.simonroux.myllm.core.model.Conversation
import pro.simonroux.myllm.core.model.Outcome
import pro.simonroux.myllm.core.model.ToolCall
import pro.simonroux.myllm.core.model.ToolResult
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException

/**
 * Drives one conversation.
 *
 * The streaming reply is held outside the persisted message list until it
 * completes. Writing every token to the database would turn a 500-token answer
 * into 500 writes and 500 recompositions of the whole list, which is what makes
 * naive on-device chat apps feel slower than the model actually is.
 */
class ChatViewModel(private val container: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow(ChatUiState())
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    private var generationJob: Job? = null

    /** Cancelled when another conversation is opened, so only one collector is live. */
    private var messagesJob: Job? = null

    /** Resolved by the UI when the agent asks permission to run a tool. */
    private var pendingConfirmation: ((Boolean) -> Unit)? = null

    init {
        viewModelScope.launch { openMostRecentOrNew() }
        viewModelScope.launch {
            container.engineManager.status.collect { status ->
                _state.update { it.copy(engineStatus = status) }
            }
        }
    }

    private suspend fun openMostRecentOrNew() {
        // first() rather than collect(): this is a one-off lookup at startup, and
        // a live collector here would reopen the conversation on every change.
        val conversation = container.chatRepository.observeConversations().first().firstOrNull()
            ?: container.chatRepository.createConversation()
        open(conversation)
    }

    fun open(conversation: Conversation) {
        _state.update {
            it.copy(conversation = conversation, messages = emptyList(), toolActivity = emptyList())
        }
        messagesJob?.cancel()
        messagesJob = viewModelScope.launch {
            container.chatRepository.observeMessages(conversation.id).collect { messages ->
                _state.update { it.copy(messages = messages) }
            }
        }
    }

    fun newConversation() {
        viewModelScope.launch {
            cancel()
            open(container.chatRepository.createConversation())
        }
    }

    fun send(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || _state.value.isGenerating) return

        val conversation = _state.value.conversation ?: return

        generationJob = viewModelScope.launch {
            val userMessage = ChatMessage(
                id = UUID.randomUUID().toString(),
                conversationId = conversation.id,
                role = ChatRole.USER,
                content = trimmed,
                createdAt = System.currentTimeMillis(),
            )
            container.chatRepository.save(userMessage)

            // Name the conversation from its first exchange, so the list is
            // browsable without opening every entry.
            if (_state.value.messages.isEmpty()) {
                val title = container.chatRepository.deriveTitle(trimmed)
                container.chatRepository.rename(conversation.id, title)
                _state.update { it.copy(conversation = conversation.copy(title = title)) }
            }

            runTurn(conversation)
        }
    }

    private suspend fun runTurn(conversation: Conversation) {
        _state.update {
            it.copy(isGenerating = true, streamingText = "", thinkingText = "", error = null)
        }

        val engine = when (val acquired = container.engineManager.acquire(conversation.pinnedEngineId)) {
            is Outcome.Ok -> acquired.value
            is Outcome.Err -> {
                _state.update {
                    it.copy(isGenerating = false, error = acquired.error.message)
                }
                return
            }
        }

        _state.update { it.copy(engineLabel = engine.label) }

        val settings = container.settingsStore.current()
        val history = container.chatRepository.history(conversation.id)

        try {
            container.agentLoop.run(
                engine = engine,
                conversationId = conversation.id,
                history = history,
                systemPrompt = conversation.systemPrompt ?: settings.systemPrompt,
                params = settings.generation,
                settings = settings.agent.copy(
                    enabled = settings.agent.enabled && conversation.agentEnabled,
                ),
                confirm = ::askUser,
            ).collect { event -> handle(event, conversation) }
        } catch (e: CancellationException) {
            // Whatever streamed before the stop is kept: a half answer the user
            // interrupted is usually still worth reading.
            persistPartial(conversation)
            throw e
        } catch (e: Exception) {
            _state.update { it.copy(error = e.message ?: "Erreur inattendue") }
        } finally {
            _state.update { it.copy(isGenerating = false) }
        }
    }

    private suspend fun handle(event: AgentEvent, conversation: Conversation) {
        when (event) {
            is AgentEvent.PromptIngested -> _state.update {
                it.copy(promptTokens = event.tokens, promptMillis = event.elapsedMs)
            }

            is AgentEvent.Token -> _state.update {
                it.copy(streamingText = it.streamingText + event.text)
            }

            is AgentEvent.Thinking -> _state.update {
                it.copy(thinkingText = it.thinkingText + event.text)
            }

            is AgentEvent.AssistantTurn -> {
                container.chatRepository.save(event.message)
                _state.update { it.copy(streamingText = "", thinkingText = "") }
            }

            is AgentEvent.ToolFinished -> {
                _state.update { it.copy(toolActivity = it.toolActivity + ToolRun(event.call, event.result)) }
                container.chatRepository.save(
                    ChatMessage(
                        id = UUID.randomUUID().toString(),
                        conversationId = conversation.id,
                        role = ChatRole.TOOL,
                        content = event.result.content,
                        createdAt = System.currentTimeMillis(),
                        toolCallId = event.call.id,
                    ),
                )
            }

            is AgentEvent.Finished -> {
                container.chatRepository.save(event.message)
                _state.update {
                    it.copy(streamingText = "", thinkingText = "", lastMeta = event.message.meta)
                }
            }

            is AgentEvent.Failed -> _state.update { it.copy(error = event.error) }
        }
    }

    private suspend fun persistPartial(conversation: Conversation) {
        val partial = _state.value.streamingText
        if (partial.isBlank()) return
        container.chatRepository.save(
            ChatMessage(
                id = UUID.randomUUID().toString(),
                conversationId = conversation.id,
                role = ChatRole.ASSISTANT,
                content = partial + "\n\n_(interrompu)_",
                createdAt = System.currentTimeMillis(),
            ),
        )
        _state.update { it.copy(streamingText = "") }
    }

    /** Suspends until the user answers the confirmation sheet. */
    private suspend fun askUser(call: ToolCall): Boolean =
        kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
            pendingConfirmation = { allowed ->
                pendingConfirmation = null
                _state.update { it.copy(pendingToolCall = null) }
                if (continuation.isActive) continuation.resumeWith(Result.success(allowed))
            }
            _state.update { it.copy(pendingToolCall = call) }

            continuation.invokeOnCancellation {
                pendingConfirmation = null
                _state.update { it.copy(pendingToolCall = null) }
            }
        }

    fun resolveConfirmation(allowed: Boolean) {
        pendingConfirmation?.invoke(allowed)
    }

    fun cancel() {
        generationJob?.cancel()
        generationJob = null
        _state.update { it.copy(isGenerating = false) }
    }

    fun dismissError() = _state.update { it.copy(error = null) }

    fun clearToolActivity() = _state.update { it.copy(toolActivity = emptyList()) }
}

data class ChatUiState(
    val conversation: Conversation? = null,
    val messages: List<ChatMessage> = emptyList(),
    /** The reply currently arriving, not yet written to the database. */
    val streamingText: String = "",
    val thinkingText: String = "",
    val isGenerating: Boolean = false,
    val engineLabel: String = "",
    val engineStatus: String? = null,
    val promptTokens: Int = 0,
    val promptMillis: Long = 0,
    val lastMeta: pro.simonroux.myllm.core.model.MessageMeta? = null,
    val toolActivity: List<ToolRun> = emptyList(),
    val pendingToolCall: ToolCall? = null,
    val error: String? = null,
)

data class ToolRun(val call: ToolCall, val result: ToolResult)
