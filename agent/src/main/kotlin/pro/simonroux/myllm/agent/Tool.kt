package pro.simonroux.myllm.agent

import kotlinx.serialization.json.JsonObject
import pro.simonroux.myllm.core.model.ToolResult
import pro.simonroux.myllm.core.model.ToolSpec

/**
 * Something the model can invoke.
 *
 * Built-in tools are Kotlin objects; skills are the same interface backed by a
 * script, which is what makes a model-authored tool indistinguishable from a
 * compiled one at the call site.
 */
interface Tool {

    val spec: ToolSpec

    suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult
}

/**
 * What a tool is allowed to reach.
 *
 * Passing capabilities in rather than letting tools grab singletons is what
 * makes the offline switch and the permission model enforceable: a tool cannot
 * reach the network unless it was handed something that can.
 */
data class ToolContext(
    val conversationId: String,
    val host: ToolHost,
)

/**
 * The bridge from the sandbox to the device.
 *
 * Implemented by the Android layer. Every method is a capability that a skill
 * must hold the matching permission to reach, checked before the call lands here.
 */
interface ToolHost {

    /** False while the app-wide offline switch is on. */
    suspend fun networkAllowed(): Boolean

    /** Performs an HTTP request. Fails rather than queueing when offline. */
    suspend fun httpRequest(
        method: String,
        url: String,
        headers: Map<String, String>,
        body: String?,
    ): HostHttpResponse

    /** Key/value store scoped to one skill, surviving restarts. */
    suspend fun readMemory(namespace: String, key: String): String?

    suspend fun writeMemory(namespace: String, key: String, value: String?)

    suspend fun listMemoryKeys(namespace: String): List<String>

    /** Reads a file from the skill's own private directory. Paths outside it are rejected. */
    suspend fun readFile(namespace: String, relativePath: String): String?

    suspend fun writeFile(namespace: String, relativePath: String, content: String)

    suspend fun listFiles(namespace: String): List<String>

    suspend fun notify(title: String, body: String)

    suspend fun deviceInfo(): Map<String, String>

    /** Re-enters the model for a sub-completion, used by skills that summarise or classify. */
    suspend fun askModel(prompt: String, maxTokens: Int): String

    fun log(namespace: String, message: String)
}

data class HostHttpResponse(
    val status: Int,
    val body: String,
    val headers: Map<String, String> = emptyMap(),
)
