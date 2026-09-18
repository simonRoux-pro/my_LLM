package pro.simonroux.myllm.ui.devloop

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
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
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import pro.simonroux.myllm.core.AppContainer
import pro.simonroux.myllm.core.model.ChangeRequest
import pro.simonroux.myllm.core.model.ChangeStatus

/**
 * The queue of changes to the app itself, and the update channel.
 *
 * Runtime skills cover what can be done in JavaScript. Everything else lands
 * here as a written brief: copy it into a coding agent, get a pull request, and
 * the build that comes out is what the updater installs. That round trip is the
 * app's only way to grow a new screen or a new dependency.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DevLoopScreen(container: AppContainer) {
    val viewModel: DevLoopViewModel = viewModel(
        factory = viewModelFactory { initializer { DevLoopViewModel(container) } },
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var draftTitle by remember { mutableStateOf("") }
    var draftBody by remember { mutableStateOf("") }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = { TopAppBar(title = { Text("Évolutions") }) },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // First card on the screen when it exists: a crash the owner cannot
            // see is a crash that never gets fixed.
            val diagnostics = state.lastCrash ?: state.problems
            if (diagnostics != null) {
                item {
                    DiagnosticsCard(
                        report = diagnostics,
                        isFatal = state.lastCrash != null,
                        onCopy = { copyToClipboard(context, diagnostics) },
                        onShare = { shareText(context, "Plantage myLLM", diagnostics) },
                        onFile = viewModel::reportCrash,
                        onClear = viewModel::clearDiagnostics,
                    )
                }
            }

            item {
                UpdateCard(
                    versionName = state.installedVersion,
                    status = state.updateStatus,
                    onCheck = viewModel::checkForUpdate,
                    onInstall = viewModel::downloadAndInstall,
                    hasUpdate = state.availableUpdate != null,
                    updateLabel = state.availableUpdate?.versionName,
                )
            }

            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp)) {
                        Text("Nouvelle demande", style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.size(8.dp))
                        OutlinedTextField(
                            value = draftTitle,
                            onValueChange = { draftTitle = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Titre") },
                            singleLine = true,
                        )
                        Spacer(Modifier.size(8.dp))
                        OutlinedTextField(
                            value = draftBody,
                            onValueChange = { draftBody = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Ce qui manque et pourquoi") },
                            minLines = 3,
                            maxLines = 8,
                        )
                        Spacer(Modifier.size(8.dp))
                        OutlinedButton(
                            onClick = {
                                viewModel.create(draftTitle, draftBody)
                                draftTitle = ""
                                draftBody = ""
                            },
                            enabled = draftTitle.isNotBlank() && draftBody.isNotBlank(),
                        ) {
                            Text("Ajouter")
                        }
                    }
                }
            }

            items(state.requests, key = { it.id }) { request ->
                RequestCard(
                    request = request,
                    onCopy = {
                        copyToClipboard(context, viewModel.renderBrief(request))
                        viewModel.markExported(request)
                    },
                    onShare = {
                        shareText(context, request.title, viewModel.renderBrief(request))
                        viewModel.markExported(request)
                    },
                    onDone = { viewModel.setStatus(request, ChangeStatus.DONE) },
                    onDelete = { viewModel.delete(request) },
                )
            }
        }
    }
}

@Composable
private fun DiagnosticsCard(
    report: String,
    isFatal: Boolean,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    onFile: () -> Unit,
    onClear: () -> Unit,
) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
        ),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(
                if (isFatal) "L'app a planté" else "Une erreur a été enregistrée",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Spacer(Modifier.size(6.dp))
            Text(
                "Copie ce rapport et envoie-le, c'est ce qui permet de corriger. " +
                    "Il reste sur l'appareil tant que tu ne le partages pas.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Spacer(Modifier.size(8.dp))
            Text(
                report.take(1200),
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Spacer(Modifier.size(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onCopy) { Text("Copier") }
                TextButton(onClick = onShare) { Text("Partager") }
                TextButton(onClick = onFile) { Text("Créer une demande") }
                TextButton(onClick = onClear) { Text("Effacer") }
            }
        }
    }
}

@Composable
private fun UpdateCard(
    versionName: String,
    status: String?,
    hasUpdate: Boolean,
    updateLabel: String?,
    onCheck: () -> Unit,
    onInstall: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Text("Version installée : $versionName", style = MaterialTheme.typography.titleSmall)
            status?.let {
                Spacer(Modifier.size(4.dp))
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.size(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onCheck) { Text("Vérifier") }
                if (hasUpdate) {
                    OutlinedButton(onClick = onInstall) { Text("Installer $updateLabel") }
                }
            }
        }
    }
}

@Composable
private fun RequestCard(
    request: ChangeRequest,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    onDone: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Text(request.title, style = MaterialTheme.typography.titleSmall)
            Text(
                "${request.kind.name} · ${request.status.name}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.size(6.dp))
            Text(
                request.body,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 6,
            )
            Spacer(Modifier.size(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onCopy) { Text("Copier") }
                TextButton(onClick = onShare) { Text("Partager") }
                TextButton(onClick = onDone) { Text("Fait") }
                TextButton(onClick = onDelete) { Text("Supprimer") }
            }
        }
    }
}

private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText("Brief myLLM", text))
}

private fun shareText(context: Context, subject: String, text: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, subject)
        putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(Intent.createChooser(intent, "Envoyer le brief"))
}
