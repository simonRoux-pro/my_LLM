package pro.simonroux.myllm.ui.models

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
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import pro.simonroux.myllm.core.AppContainer
import pro.simonroux.myllm.core.model.LocalModel
import pro.simonroux.myllm.core.model.ModelState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelsScreen(container: AppContainer) {
    val viewModel: ModelsViewModel = viewModel(
        factory = viewModelFactory { initializer { ModelsViewModel(container) } },
    )
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Modèles", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "%.1f Go libres".format(state.freeSpaceBytes / 1_000_000_000.0),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Text(
                    "Un modèle téléchargé fonctionne sans réseau. " +
                        "Compte environ 25 % de RAM en plus que la taille du fichier.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            items(state.models, key = { it.id }) { model ->
                ModelCard(
                    model = model,
                    isActive = model.id == state.activeModelId,
                    onDownload = { viewModel.download(model) },
                    onCancel = { viewModel.cancel(model) },
                    onActivate = { viewModel.activate(model) },
                    onDelete = { viewModel.delete(model) },
                )
            }

            state.error?.let { error ->
                item {
                    Text(
                        error,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

@Composable
private fun ModelCard(
    model: LocalModel,
    isActive: Boolean,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onActivate: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    model.displayName,
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.titleSmall,
                )
                if (isActive) {
                    FilterChip(selected = true, onClick = {}, label = { Text("Actif") })
                }
            }

            Text(
                "${model.parameterCount} · ${model.quantization} · " +
                    "%.1f Go · %d k de contexte".format(
                        model.sizeBytes / 1_000_000_000.0,
                        model.contextLength / 1024,
                    ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (model.notes.isNotBlank()) {
                Spacer(Modifier.size(6.dp))
                Text(model.notes, style = MaterialTheme.typography.bodySmall)
            }

            when (model.state) {
                ModelState.DOWNLOADING, ModelState.QUEUED, ModelState.VERIFYING -> {
                    Spacer(Modifier.size(10.dp))
                    LinearProgressIndicator(
                        progress = { model.progress },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.size(4.dp))
                    Text(
                        if (model.state == ModelState.VERIFYING) {
                            "Vérification de l'empreinte"
                        } else {
                            "%.0f %% · %.2f Go sur %.2f Go".format(
                                model.progress * 100,
                                model.downloadedBytes / 1_000_000_000.0,
                                model.sizeBytes / 1_000_000_000.0,
                            )
                        },
                        style = MaterialTheme.typography.labelSmall,
                    )
                    TextButton(onClick = onCancel) { Text("Mettre en pause") }
                }

                ModelState.READY -> {
                    Spacer(Modifier.size(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (!isActive) {
                            OutlinedButton(onClick = onActivate) { Text("Activer") }
                        }
                        TextButton(onClick = onDelete) { Text("Supprimer") }
                    }
                }

                ModelState.PAUSED -> {
                    Spacer(Modifier.size(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onDownload) { Text("Reprendre") }
                        Text(
                            "%.2f Go déjà récupérés".format(model.downloadedBytes / 1_000_000_000.0),
                            Modifier.align(Alignment.CenterVertically),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }

                ModelState.FAILED -> {
                    Spacer(Modifier.size(8.dp))
                    Text(
                        "Échec du téléchargement",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    OutlinedButton(onClick = onDownload) { Text("Réessayer") }
                }

                ModelState.AVAILABLE -> {
                    Spacer(Modifier.size(8.dp))
                    OutlinedButton(onClick = onDownload) { Text("Télécharger") }
                }
            }
        }
    }
}
