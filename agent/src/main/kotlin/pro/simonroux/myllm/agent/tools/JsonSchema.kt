package pro.simonroux.myllm.agent.tools

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Small builder for the JSON Schema fragments that describe tool arguments.
 *
 * Every function-calling API wants the same shape, and writing it by hand for a
 * dozen tools is where typos turn into a model that silently stops calling one
 * of them.
 */
object JsonSchema {

    fun obj(vararg properties: Pair<String, JsonObject>, required: List<String> = emptyList()): JsonObject =
        buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                properties.forEach { (name, schema) -> put(name, schema) }
            }
            if (required.isNotEmpty()) {
                putJsonArray("required") { required.forEach { add(it) } }
            }
            put("additionalProperties", false)
        }

    fun string(description: String, enum: List<String>? = null): JsonObject = buildJsonObject {
        put("type", "string")
        put("description", description)
        enum?.let { values -> putJsonArray("enum") { values.forEach { add(it) } } }
    }

    fun integer(description: String, default: Int? = null): JsonObject = buildJsonObject {
        put("type", "integer")
        put("description", description)
        default?.let { put("default", it) }
    }

    fun boolean(description: String, default: Boolean? = null): JsonObject = buildJsonObject {
        put("type", "boolean")
        put("description", description)
        default?.let { put("default", it) }
    }

    fun stringArray(description: String): JsonObject = buildJsonObject {
        put("type", "array")
        put("description", description)
        putJsonObject("items") { put("type", "string") }
    }

    fun freeObject(description: String): JsonObject = buildJsonObject {
        put("type", "object")
        put("description", description)
    }

    val empty: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {}
    }

    fun buildArray(values: List<String>) = buildJsonArray { values.forEach { add(it) } }
}
