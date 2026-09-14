package pro.simonroux.myllm.ui.skills

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import pro.simonroux.myllm.agent.ToolContext
import pro.simonroux.myllm.agent.script.ScriptResult
import pro.simonroux.myllm.agent.script.SkillTool
import pro.simonroux.myllm.core.AppContainer
import pro.simonroux.myllm.core.model.Skill

class SkillsViewModel(private val container: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow(SkillsUiState())
    val state: StateFlow<SkillsUiState> = _state.asStateFlow()

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    init {
        viewModelScope.launch {
            container.skillRepository.observeAll().collect { skills ->
                _state.update { it.copy(skills = skills) }
            }
        }
    }

    fun setEnabled(skill: Skill, enabled: Boolean) {
        viewModelScope.launch {
            container.skillRepository.setEnabled(skill.id, enabled)
            container.refreshSkillTools()
        }
    }

    fun delete(skill: Skill) {
        viewModelScope.launch {
            container.skillRepository.delete(skill.id)
            container.refreshSkillTools()
        }
    }

    /**
     * Saves an edited body as a new version.
     *
     * The code is compiled first: storing something that does not parse would
     * leave a skill registered that fails on every call, and the agent would
     * keep trying it.
     */
    fun save(skill: Skill, code: String) {
        viewModelScope.launch {
            when (val check = container.sandbox.validate(code)) {
                is ScriptResult.Failure -> {
                    _state.update { it.copy(lastRunOutput = "Erreur de syntaxe : ${check.message}") }
                    return@launch
                }
                is ScriptResult.Success -> Unit
            }

            container.skillRepository.upsert(
                skill.copy(
                    code = code,
                    version = skill.version + 1,
                    updatedAt = System.currentTimeMillis(),
                    authoredByModel = false,
                    lastError = null,
                ),
                note = "Édition manuelle",
            )
            container.refreshSkillTools()
            _state.update { it.copy(lastRunOutput = "Enregistrée en version ${skill.version + 1}") }
        }
    }

    fun test(skill: Skill, argumentsJson: String) {
        viewModelScope.launch {
            val arguments: JsonObject = runCatching {
                json.parseToJsonElement(argumentsJson.ifBlank { "{}" }).jsonObject
            }.getOrElse {
                _state.update { state -> state.copy(lastRunOutput = "Arguments JSON invalides") }
                return@launch
            }

            val runner = SkillTool(skill, container.sandbox)
            val result = runner.execute(
                arguments,
                ToolContext(conversationId = "editor", host = container.toolHost),
            )
            container.skillRepository.recordRun(
                skill.id,
                result.ok,
                if (result.ok) null else result.content.take(300),
            )

            _state.update {
                it.copy(
                    lastRunOutput = buildString {
                        append(if (result.ok) "OK" else "Échec")
                        append(" en ").append(result.durationMs).append(" ms\n\n")
                        append(result.content)
                        if (runner.lastRunLog.isNotEmpty()) {
                            append("\n\nJournal :\n")
                            runner.lastRunLog.forEach { line -> append("  ").append(line).append('\n') }
                        }
                    },
                )
            }
        }
    }

    fun clearRunOutput() = _state.update { it.copy(lastRunOutput = null) }
}

data class SkillsUiState(
    val skills: List<Skill> = emptyList(),
    val lastRunOutput: String? = null,
)
