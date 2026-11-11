package ai.hindidub.ui

import ai.hindidub.core.LanguageCatalog
import ai.hindidub.core.StorageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
fun HomeScreen(vm: AppViewModel, state: UiState) {
    val context = LocalContext.current
    val pickVideo = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            vm.attach(uri, uri.lastPathSegment?.substringAfterLast('/') ?: "video")
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        ScreenHeader("HindiDubber", state.statusLine)

        // ---- setup / download ------------------------------------------------
        if (!state.modelsReady) {
            GlassCard(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                shape = RoundedCornerShape(18.dp)
            ) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (state.busy) PulsingDot() else StepDot(done = false, active = false)
                        Spacer(Modifier.width(8.dp))
                        Text("One-tap setup", style = MaterialTheme.typography.titleMedium)
                    }
                    Text(
                        "Downloads the Whisper model your phone can run plus the translation model. " +
                            "Resumes if the connection drops.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        state.poolSummary,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (state.busy) {
                        AnimatedStageText(state.busyLabel, style = MaterialTheme.typography.labelSmall)
                        AnimatedProgressBar(state.busyFraction)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { vm.downloadEverything() },
                            enabled = !state.busy
                        ) { Text("Download all packages") }
                        OutlinedButton(
                            onClick = { vm.installBundledModel() },
                            enabled = !state.busy
                        ) { Text("Use bundled model") }
                    }
                }
            }
        }

        // ---- attach + dub ----------------------------------------------------
        GlassCard(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            shape = RoundedCornerShape(18.dp)
        ) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("New dub", style = MaterialTheme.typography.titleMedium)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = { pickVideo.launch(arrayOf("video/*")) }) {
                        Icon(painterResource(ai.hindidub.R.drawable.ic_folder), null,
                            Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Attach")
                    }
                    Spacer(Modifier.width(10.dp))
                    Text(
                        state.inputName.ifBlank { "No video attached" },
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                }

                Text(
                    "Dub into",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(vm.popular()) { lang ->
                        FilterChip(
                            selected = state.settings.targetLang == lang.code,
                            onClick = { vm.setTargetLang(lang.code) },
                            label = { Text(lang.nativeName) }
                        )
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { vm.startDub() },
                        enabled = state.inputUri != null && !state.running,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(painterResource(ai.hindidub.R.drawable.ic_mic), null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Start dubbing")
                    }
                    if (state.running) {
                        OutlinedButton(onClick = { vm.cancelJob() }) { Text("Stop") }
                    }
                }
                if (state.queueCount > 0) {
                    Text(
                        "${state.queueCount} queued",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // ---- library ---------------------------------------------------------
        GlassCard(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            shape = RoundedCornerShape(18.dp)
        ) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Library", style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f))
                    Text(
                        StorageManager.human(state.storageTotal),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (state.outputs.isEmpty()) {
                    Text(
                        "Finished dubs appear here, with their size, so nothing piles up invisibly.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                } else {
                    state.outputs.take(8).forEach { out ->
                        OutputRow(out, onPlay = { vm.openPlayer(out.file.absolutePath) },
                            onDelete = { vm.deleteOutput(out) })
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun OutputRow(
    out: StorageManager.Output,
    onPlay: () -> Unit,
    onDelete: () -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            painterResource(ai.hindidub.R.drawable.ic_player), null,
            Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                out.name, style = MaterialTheme.typography.bodyMedium,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            Text(
                StorageManager.human(out.bytes),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        TextButton(onClick = onPlay) { Text("Play") }
        IconButton(onClick = onDelete) {
            Icon(painterResource(ai.hindidub.R.drawable.ic_delete), "Delete",
                Modifier.size(18.dp))
        }
    }
}
