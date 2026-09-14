package pro.simonroux.myllm.ui.skills

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import pro.simonroux.myllm.core.AppContainer
import pro.simonroux.myllm.core.model.Skill

/**
 * The skills the assistant has, and the ability to read and edit any of them.
 *
 * Showing the source is not a debugging affordance, it is the point: an app that
 * writes its own tools is only trustworthy if its owner can see exactly what
 * those tools do and turn any of them off.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SkillsScreen(container: AppContainer) {
    val viewModel: SkillsViewModel = viewModel(
        factory = viewModelFactory { initializer { SkillsViewModel(container) } },
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<Skill?>(null) }

    val target = editing
    if (target != null) {
        SkillEditor(
            skill = target,
            runOutput = state.lastRunOutput,
            onRun = { arguments -> viewModel.test(target, arguments) },
            onSave = { code ->
                viewModel.save(target, code)
                editing = null
            },
            onClose = {
                editing = null
                viewModel.clearRunOutput()
            },
        )
        return
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Skills") }) },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Text(
                    "Les skills sont des outils en JavaScript que l'assistant peut écrire " +
                        "lui-même et appeler ensuite. Elles tournent dans un bac à sable, " +
                        "sans accès au système, et chaque permission est déclarée.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (state.skills.isEmpty()) {
                item {
                    Text(
                        "Aucune skill. Demande à l'assistant d'en écrire une : " +
                            "\"écris une skill qui convertit des devises\".",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            items(state.skills, key = { it.id }) { skill ->
                SkillCard(
                    skill = skill,
                    onToggle = { viewModel.setEnabled(skill, it) },
                    onOpen = { editing = skill },
                    onDelete = { viewModel.delete(skill) },
                )
            }
        }
    }
}

@Composable
private fun SkillCard(
    skill: Skill,
    onToggle: (Boolean) -> Unit,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(skill.name, style = MaterialTheme.typography.titleSmall)
                    Text(
                        "v${skill.version}" +
                            if (skill.authoredByModel) " · écrite par le modèle" else "",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = skill.enabled, onCheckedChange = onToggle)
            }

            Spacer(Modifier.size(6.dp))
            Text(skill.description, style = MaterialTheme.typography.bodySmall)

            if (skill.permissions.isNotEmpty()) {
                Spacer(Modifier.size(8.dp))
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    skill.permissions.forEach { permission ->
                        AssistChip(onClick = {}, label = { Text(permission.name) })
                    }
                }
            }

            skill.lastError?.let { error ->
                Spacer(Modifier.size(6.dp))
                Text(
                    "Dernière erreur : $error",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onOpen) { Text("Ouvrir") }
                TextButton(onClick = onDelete) { Text("Supprimer") }
            }
        }
    }
}

@Composable
private fun SkillEditor(
    skill: Skill,
    runOutput: String?,
    onRun: (String) -> Unit,
    onSave: (String) -> Unit,
    onClose: () -> Unit,
) {
    var code by remember(skill.id, skill.version) { mutableStateOf(skill.code) }
    var arguments by remember(skill.id) { mutableStateOf("{}") }

    Column(
        Modifier
            .fillMaxSize()
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(skill.name, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = onClose) { Text("Fermer") }
        }

        OutlinedTextField(
            value = code,
            onValueChange = { code = it },
            modifier = Modifier.fillMaxWidth().weight(1f),
            label = { Text("Code JavaScript") },
            // Monospace matters here: skills are read to check what they do, and
            // proportional text hides indentation problems.
            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
        )

        OutlinedTextField(
            value = arguments,
            onValueChange = { arguments = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Arguments de test (JSON)") },
            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            maxLines = 3,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { onRun(arguments) }) { Text("Tester") }
            OutlinedButton(onClick = { onSave(code) }) { Text("Enregistrer") }
        }

        runOutput?.let { output ->
            Card(Modifier.fillMaxWidth()) {
                Text(
                    output,
                    Modifier.padding(10.dp),
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                )
            }
        }
    }
}
