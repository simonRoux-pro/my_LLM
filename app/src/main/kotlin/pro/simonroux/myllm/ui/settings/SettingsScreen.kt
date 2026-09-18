package pro.simonroux.myllm.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import pro.simonroux.myllm.core.AppContainer
import pro.simonroux.myllm.core.model.AppSettings
import pro.simonroux.myllm.core.model.RoutingPolicy
import pro.simonroux.myllm.core.model.ThemeMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(container: AppContainer, settings: AppSettings) {
    val viewModel: SettingsViewModel = viewModel(
        factory = viewModelFactory { initializer { SettingsViewModel(container) } },
    )
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = { TopAppBar(title = { Text("Réglages") }) },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Section("Confidentialité") {
                    SwitchRow(
                        label = "Mode hors ligne",
                        description = "Coupe toute sortie réseau, y compris pour les skills " +
                            "et la recherche de mises à jour. Le modèle local continue de fonctionner.",
                        checked = settings.offlineOnly,
                        onCheckedChange = viewModel::setOfflineOnly,
                    )
                }
            }

            item {
                Section("Routage") {
                    Text(
                        "Quel moteur répond en priorité.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.size(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        RoutingPolicy.entries.forEach { policy ->
                            FilterChip(
                                selected = settings.routingPolicy == policy,
                                onClick = { viewModel.setRoutingPolicy(policy) },
                                label = { Text(policy.label()) },
                            )
                        }
                    }
                }
            }

            item {
                Section("Endpoint distant") {
                    RemoteEndpointEditor(
                        baseUrl = state.remoteBaseUrl,
                        modelId = state.remoteModelId,
                        hasKey = state.hasRemoteKey,
                        onSave = viewModel::saveRemoteEndpoint,
                        onClearKey = viewModel::clearRemoteKey,
                    )
                }
            }

            item {
                Section("Prompt système") {
                    var prompt by remember(settings.systemPrompt) {
                        mutableStateOf(settings.systemPrompt)
                    }
                    OutlinedTextField(
                        value = prompt,
                        onValueChange = { prompt = it },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 4,
                        maxLines = 10,
                    )
                    Spacer(Modifier.size(8.dp))
                    OutlinedButton(onClick = { viewModel.setSystemPrompt(prompt) }) {
                        Text("Enregistrer")
                    }
                }
            }

            item {
                Section("Génération") {
                    SliderRow(
                        label = "Température",
                        value = settings.generation.temperature,
                        range = 0f..1.5f,
                        format = { "%.2f".format(it) },
                        onChange = viewModel::setTemperature,
                    )
                    SliderRow(
                        label = "Tokens maximum",
                        value = settings.generation.maxTokens.toFloat(),
                        range = 256f..8192f,
                        format = { it.toInt().toString() },
                        onChange = { viewModel.setMaxTokens(it.toInt()) },
                    )
                    SliderRow(
                        label = "Fenêtre de contexte",
                        value = settings.runtime.contextSize.toFloat(),
                        range = 1024f..16384f,
                        format = { it.toInt().toString() },
                        onChange = { viewModel.setContextSize(it.toInt()) },
                    )
                    Text(
                        "Une fenêtre plus large consomme plus de RAM et ralentit " +
                            "le premier jeton. 4096 suffit pour la plupart des échanges.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            item {
                Section("Agent") {
                    SwitchRow(
                        label = "Outils activés",
                        description = "Laisse le modèle appeler des outils et des skills.",
                        checked = settings.agent.enabled,
                        onCheckedChange = viewModel::setAgentEnabled,
                    )
                    SwitchRow(
                        label = "Auto-modification",
                        description = "Autorise le modèle à écrire et modifier ses propres skills.",
                        checked = settings.agent.selfModificationEnabled,
                        onCheckedChange = viewModel::setSelfModification,
                    )
                    SwitchRow(
                        label = "Confirmer chaque auto-modification",
                        description = "Demande avant qu'une skill soit créée, modifiée ou supprimée. " +
                            "Désactive-le seulement quand tu fais confiance au modèle chargé.",
                        checked = settings.agent.confirmSelfModification,
                        onCheckedChange = viewModel::setConfirmSelfModification,
                    )
                    SwitchRow(
                        label = "Confirmer chaque outil",
                        description = "Demande avant tout appel d'outil, y compris les plus anodins.",
                        checked = settings.agent.confirmEveryTool,
                        onCheckedChange = viewModel::setConfirmEveryTool,
                    )
                }
            }

            item {
                Section("Apparence") {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        ThemeMode.entries.forEach { mode ->
                            FilterChip(
                                selected = settings.appearance.theme == mode,
                                onClick = { viewModel.setTheme(mode) },
                                label = { Text(mode.label()) },
                            )
                        }
                    }
                    Spacer(Modifier.size(8.dp))
                    SwitchRow(
                        label = "Couleurs du système",
                        description = "Material You, à partir du fond d'écran.",
                        checked = settings.appearance.dynamicColor,
                        onCheckedChange = viewModel::setDynamicColor,
                    )
                    SwitchRow(
                        label = "Afficher les statistiques",
                        description = "Tokens par seconde et taille du contexte sous chaque réponse.",
                        checked = settings.appearance.showTokenStats,
                        onCheckedChange = viewModel::setShowTokenStats,
                    )
                }
            }

            item {
                Section("Données") {
                    Text(
                        "Tout est stocké sur cet appareil. Aucune sauvegarde cloud, " +
                            "aucune télémétrie, aucun envoi hors des endpoints configurés.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.size(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = viewModel::deleteAllConversations) {
                            Text("Effacer les conversations")
                        }
                        OutlinedButton(onClick = viewModel::clearNotes) {
                            Text("Vider la mémoire")
                        }
                    }
                }
            }

            state.message?.let { message ->
                item {
                    Text(message, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.size(8.dp))
            content()
        }
    }
}

@Composable
private fun SwitchRow(
    label: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(
                description,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.size(12.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun SliderRow(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    format: (Float) -> String,
    onChange: (Float) -> Unit,
) {
    var local by remember(value) { mutableStateOf(value) }
    Column(Modifier.padding(vertical = 4.dp)) {
        Row {
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Text(format(local), style = MaterialTheme.typography.labelSmall)
        }
        Slider(
            value = local,
            onValueChange = { local = it },
            // Committed on release rather than on every pixel: each change is a
            // disk write, and a drag would otherwise produce hundreds.
            onValueChangeFinished = { onChange(local) },
            valueRange = range,
        )
    }
}

@Composable
private fun RemoteEndpointEditor(
    baseUrl: String,
    modelId: String,
    hasKey: Boolean,
    onSave: (String, String, String) -> Unit,
    onClearKey: () -> Unit,
) {
    var url by remember(baseUrl) { mutableStateOf(baseUrl) }
    var model by remember(modelId) { mutableStateOf(modelId) }
    var key by remember { mutableStateOf("") }

    Column {
        Text(
            "Tout endpoint compatible OpenAI : OpenRouter, Groq, Cerebras, " +
                "Google AI Studio, ou un llama-server sur ton PC. " +
                "La clé est chiffrée par le Keystore et n'apparaît jamais dans un export.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.size(8.dp))
        OutlinedTextField(
            value = url,
            onValueChange = { url = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("URL de base") },
            placeholder = { Text("https://openrouter.ai/api/v1") },
            singleLine = true,
        )
        Spacer(Modifier.size(8.dp))
        OutlinedTextField(
            value = model,
            onValueChange = { model = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Identifiant du modèle") },
            placeholder = { Text("meta-llama/llama-3.3-70b-instruct:free") },
            singleLine = true,
        )
        Spacer(Modifier.size(8.dp))
        OutlinedTextField(
            value = key,
            onValueChange = { key = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(if (hasKey) "Clé API (une clé est enregistrée)" else "Clé API") },
            singleLine = true,
        )
        Spacer(Modifier.size(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = {
                    onSave(url, model, key)
                    key = ""
                },
                enabled = url.isNotBlank() && model.isNotBlank(),
            ) {
                Text("Enregistrer")
            }
            if (hasKey) {
                OutlinedButton(onClick = onClearKey) { Text("Oublier la clé") }
            }
        }
    }
}

private fun RoutingPolicy.label(): String = when (this) {
    RoutingPolicy.LOCAL_ONLY -> "Local seul"
    RoutingPolicy.LOCAL_FIRST -> "Local d'abord"
    RoutingPolicy.REMOTE_FIRST -> "Distant d'abord"
    RoutingPolicy.REMOTE_ONLY -> "Distant seul"
}

private fun ThemeMode.label(): String = when (this) {
    ThemeMode.SYSTEM -> "Système"
    ThemeMode.LIGHT -> "Clair"
    ThemeMode.DARK -> "Sombre"
    ThemeMode.BLACK -> "Noir"
}
