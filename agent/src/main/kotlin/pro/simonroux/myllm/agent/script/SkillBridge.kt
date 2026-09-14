package pro.simonroux.myllm.agent.script

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import pro.simonroux.myllm.agent.ToolHost
import pro.simonroux.myllm.core.model.Skill
import pro.simonroux.myllm.core.model.SkillPermission

/**
 * Turns one bridge call from the sandbox into a host call, or refuses it.
 *
 * This is where the permission model is actually enforced. The sandbox controls
 * what a script can reach; this controls what it is allowed to do with it. A
 * skill that never declared NETWORK gets an error string, not a socket, however
 * the call is dressed up.
 *
 * Calls block by design: Rhino has no event loop, and the whole execution
 * already runs off the main thread under a deadline.
 */
internal class SkillBridge(
    private val skill: Skill,
    private val host: ToolHost,
    private val logSink: (String) -> Unit,
) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun handle(channel: String, payload: String): String {
        val arguments = runCatching {
            json.parseToJsonElement(payload).jsonObject
        }.getOrElse { JsonObject(emptyMap()) }

        return runCatching { dispatch(channel, arguments) }
            .getOrElse { failure(it.message ?: "Erreur interne du pont") }
    }

    private fun dispatch(channel: String, args: JsonObject): String = when (channel) {

        "log" -> {
            val message = args.string("message").orEmpty()
            logSink(message)
            host.log(skill.id, message)
            EMPTY
        }

        "http" -> requirePermission(SkillPermission.NETWORK) {
            val url = args.string("url").orEmpty()
            if (!url.startsWith("https://") && !url.startsWith("http://")) {
                return@requirePermission failure("URL invalide : $url")
            }
            runBlocking {
                if (!host.networkAllowed()) return@runBlocking failure("Mode hors ligne actif")
                val response = host.httpRequest(
                    method = args.string("method") ?: "GET",
                    url = url,
                    headers = args["headers"].asStringMap(),
                    body = args.string("body"),
                )
                buildJsonObject {
                    put("status", response.status)
                    put("body", response.body)
                    putJsonObject("headers") {
                        response.headers.forEach { (key, value) -> put(key, value) }
                    }
                }.toString()
            }
        }

        "memory.get" -> requirePermission(SkillPermission.MEMORY) {
            val value = runBlocking { host.readMemory(skill.id, args.string("key").orEmpty()) }
            nullable("value", value)
        }

        "memory.set" -> requirePermission(SkillPermission.MEMORY) {
            runBlocking {
                host.writeMemory(skill.id, args.string("key").orEmpty(), args.string("value"))
            }
            EMPTY
        }

        "memory.keys" -> requirePermission(SkillPermission.MEMORY) {
            val keys = runBlocking { host.listMemoryKeys(skill.id) }
            json.encodeToString(KeysResponse.serializer(), KeysResponse(keys))
        }

        "files.read" -> requirePermission(SkillPermission.STORAGE) {
            val path = args.string("path").orEmpty()
            if (!isSafeRelativePath(path)) return@requirePermission failure("Chemin refuse : $path")
            val content = runBlocking { host.readFile(skill.id, path) }
            nullable("content", content)
        }

        "files.write" -> requirePermission(SkillPermission.STORAGE) {
            val path = args.string("path").orEmpty()
            if (!isSafeRelativePath(path)) return@requirePermission failure("Chemin refuse : $path")
            runBlocking { host.writeFile(skill.id, path, args.string("content").orEmpty()) }
            EMPTY
        }

        "files.list" -> requirePermission(SkillPermission.STORAGE) {
            val files = runBlocking { host.listFiles(skill.id) }
            json.encodeToString(FilesResponse.serializer(), FilesResponse(files))
        }

        "notify" -> requirePermission(SkillPermission.NOTIFY) {
            runBlocking {
                host.notify(args.string("title").orEmpty(), args.string("body").orEmpty())
            }
            EMPTY
        }

        "device" -> requirePermission(SkillPermission.DEVICE_INFO) {
            val info = runBlocking { host.deviceInfo() }
            buildJsonObject { info.forEach { (key, value) -> put(key, value) } }.toString()
        }

        "ask" -> requirePermission(SkillPermission.LLM) {
            val text = runBlocking {
                host.askModel(
                    prompt = args.string("prompt").orEmpty(),
                    maxTokens = args.string("maxTokens")?.toIntOrNull() ?: 512,
                )
            }
            json.encodeToString(TextResponse.serializer(), TextResponse(text))
        }

        else -> failure("Canal inconnu : $channel")
    }

    private inline fun requirePermission(permission: SkillPermission, block: () -> String): String =
        if (permission in skill.permissions) {
            block()
        } else {
            failure("Permission ${permission.name} non accordee a la skill ${skill.name}")
        }

    private fun failure(message: String): String =
        json.encodeToString(ErrorResponse.serializer(), ErrorResponse(message))

    private fun nullable(key: String, value: String?): String = buildJsonObject {
        if (value == null) put(key, JsonNull) else put(key, value)
    }.toString()

    private fun JsonObject.string(key: String): String? {
        val element = this[key] ?: return null
        if (element is JsonNull) return null
        return runCatching { element.jsonPrimitive.content }.getOrNull()
    }

    private fun JsonElement?.asStringMap(): Map<String, String> {
        if (this == null) return emptyMap()
        return runCatching {
            jsonObject.mapValues { (_, value) -> value.jsonPrimitive.content }
        }.getOrElse { emptyMap() }
    }

    /**
     * Rejects anything that could climb out of the skill's own directory.
     *
     * Absolute paths, parent traversal, backslashes and NUL are refused outright
     * rather than normalised away: normalising is where these checks usually go
     * wrong, and a skill has no legitimate reason to use any of them.
     */
    private fun isSafeRelativePath(path: String): Boolean =
        path.isNotBlank() &&
            !path.startsWith('/') &&
            !path.contains("..") &&
            !path.contains('\\') &&
            path.none { it.code == 0 }

    private companion object {
        const val EMPTY = "{}"
    }
}

@Serializable
private data class ErrorResponse(val error: String)

@Serializable
private data class KeysResponse(val keys: List<String>)

@Serializable
private data class FilesResponse(val files: List<String>)

@Serializable
private data class TextResponse(val text: String)
