package ai.hindidub.ui

import android.media.MediaPlayer
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import kotlinx.coroutines.delay
import java.io.File

/**
 * Player for a finished dub.
 *
 * Uses the framework [MediaPlayer] rather than pulling in a media library: the file is a local
 * MP4 the system already knows how to play, and one less dependency is one less thing to break.
 * The waveform is the real one — the engine writes a peak sidecar next to each output.
 */
@Composable
fun PlayerScreen(vm: AppViewModel, state: UiState) {
    val context = LocalContext.current
    val path = state.outputPath
    var playing by remember { mutableStateOf(false) }
    var positionMs by remember { mutableStateOf(0) }
    var durationMs by remember { mutableStateOf(0) }

    val player = remember { MediaPlayer() }

    DisposableEffect(path) {
        if (path != null) {
            runCatching {
                player.reset()
                player.setDataSource(path)
                player.prepare()
                durationMs = player.duration
            }
        }
        onDispose { runCatching { player.release() } }
    }

    LaunchedEffect(playing) {
        while (playing) {
            runCatching { positionMs = player.currentPosition }
            delay(200)
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        ScreenHeader(
            title = "Player",
            subtitle = path?.let { File(it).name } ?: "No dub selected"
        )

        if (path == null) {
            Text(
                "Pick a finished dub from the Library on the Home screen.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 20.dp)
            )
            return@Column
        }

        GlassCard(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            shape = RoundedCornerShape(18.dp)
        ) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Waveform(state.peaks)
                Slider(
                    value = if (durationMs > 0) positionMs.toFloat() / durationMs else 0f,
                    onValueChange = { f ->
                        val target = (f * durationMs).toInt()
                        runCatching { player.seekTo(target) }
                        positionMs = target
                    }
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${positionMs / 1000}s / ${durationMs / 1000}s",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    Button(onClick = {
                        if (playing) {
                            runCatching { player.pause() }
                            playing = false
                        } else {
                            runCatching { player.start() }
                            playing = true
                        }
                    }) { Text(if (playing) "Pause" else "Play") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        val file = File(path)
                        if (!file.exists()) return@OutlinedButton
                        val uri = FileProvider.getUriForFile(
                            context, context.packageName + ".fileprovider", file
                        )
                        val share = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                            type = "video/mp4"
                            putExtra(android.content.Intent.EXTRA_STREAM, uri)
                            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(
                            android.content.Intent.createChooser(share, "Share dubbed video")
                        )
                    }) {
                        Icon(painterResource(ai.hindidub.R.drawable.ic_share), null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Share")
                    }
                    OutlinedButton(onClick = {
                        runCatching { player.pause() }
                        playing = false
                        val out = state.outputs.firstOrNull { it.file.absolutePath == path }
                        if (out != null) {
                            vm.deleteOutput(out)
                            vm.back()
                        }
                    }) {
                        Icon(painterResource(ai.hindidub.R.drawable.ic_delete), null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Delete")
                    }
                }
            }
        }

        if (state.lastStats.isNotBlank()) {
            GlassCard(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                shape = RoundedCornerShape(18.dp)
            ) {
                Column(Modifier.padding(18.dp)) {
                    Text("How this run went", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(6.dp))
                    Text(state.lastStats, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun Waveform(peaks: FloatArray?) {
    val primary = MaterialTheme.colorScheme.primary
    val idle = MaterialTheme.colorScheme.surfaceVariant
    Canvas(Modifier.fillMaxWidth().height(90.dp)) {
        if (peaks == null || peaks.isEmpty()) {
            drawLine(idle, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 3f)
            return@Canvas
        }
        val step = size.width / peaks.size
        peaks.forEachIndexed { i, p ->
            val h = (size.height * p.coerceIn(0.02f, 1f))
            drawLine(
                color = primary,
                start = Offset(i * step + step / 2f, (size.height - h) / 2f),
                end = Offset(i * step + step / 2f, (size.height + h) / 2f),
                strokeWidth = step * 0.6f
            )
        }
    }
}
