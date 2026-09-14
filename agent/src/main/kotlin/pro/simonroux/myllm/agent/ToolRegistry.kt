package pro.simonroux.myllm.agent

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import pro.simonroux.myllm.core.model.ToolSpec

/**
 * The set of tools currently offered to the model.
 *
 * Skills are registered and unregistered while the app runs, so this is mutable
 * and guarded. Built-in tools are registered once at startup and cannot be
 * shadowed by a skill: a model-authored tool must never be able to take over
 * the name of the tool that edits skills.
 */
class ToolRegistry {

    private val mutex = Mutex()
    private val builtIn = LinkedHashMap<String, Tool>()
    private val skills = LinkedHashMap<String, Tool>()

    suspend fun registerBuiltIn(tool: Tool) = mutex.withLock {
        builtIn[tool.spec.name] = tool
    }

    /** Returns false when the name collides with a built-in, which is reserved. */
    suspend fun registerSkill(tool: Tool): Boolean = mutex.withLock {
        if (builtIn.containsKey(tool.spec.name)) return@withLock false
        skills[tool.spec.name] = tool
        true
    }

    suspend fun unregisterSkill(name: String) = mutex.withLock {
        skills.remove(name)
    }

    suspend fun replaceSkills(tools: List<Tool>) = mutex.withLock {
        skills.clear()
        tools.forEach { tool ->
            if (!builtIn.containsKey(tool.spec.name)) skills[tool.spec.name] = tool
        }
    }

    suspend fun find(name: String): Tool? = mutex.withLock {
        builtIn[name] ?: skills[name]
    }

    suspend fun specs(): List<ToolSpec> = mutex.withLock {
        (builtIn.values + skills.values).map { it.spec }
    }

    suspend fun names(): List<String> = mutex.withLock {
        (builtIn.keys + skills.keys).toList()
    }
}
