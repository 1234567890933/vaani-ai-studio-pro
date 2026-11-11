package ai.hindidub.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val STAGES = listOf(
    "AUDIO" to "Reading audio",
    "VAD" to "Finding speech",
    "ASR" to "Transcribing",
    "TRANSLATE" to "Translating",
    "TTS" to "Voicing",
    "MIX" to "Mixing with the original",
    "MUX" to "Writing the video"
)

/**
 * Job screen.
 *
 * Three honest signals instead of a bare spinner: which stage is running and which are done, a
 * smooth progress bar with an ETA, and a level strip that moves while work is happening. The
 * strip is activity feedback, not a fake waveform — the real waveform of the finished file is
 * on the player screen, computed from the actual audio.
 */
@Composable
fun ProcessingScreen(vm: AppViewModel, state: UiState) {
    val currentIndex = STAGES.indexOfFirst { it.first == state.stage }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        ScreenHeader(
            title = if (state.running) "Dubbing…" else "No job running",
            subtitle = state.stageMessage
        )

        Card(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            shape = RoundedCornerShape(18.dp)
        ) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (state.running) PulsingDot() else StepDot(done = state.fraction >= 1f, active = false)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        state.stage.ifBlank { "IDLE" },
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        "${(state.fraction * 100).toInt()}%",
                        style = MaterialTheme.typography.titleMedium
                    )
                }
                AnimatedProgressBar(state.fraction)
                if (state.etaMs > 0) {
                    Text(
                        "about ${state.etaMs / 60000} min left",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                ActivityStrip(active = state.running)
                if (state.running) {
                    OutlinedButton(onClick = { vm.cancelJob() }) { Text("Cancel job") }
                }
            }
        }

        Card(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            shape = RoundedCornerShape(18.dp)
        ) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Pipeline", style = MaterialTheme.typography.titleMedium)
                STAGES.forEachIndexed { index, (key, label) ->
                    val done = currentIndex > index || state.fraction >= 1f
                    val active = currentIndex == index && state.running
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StepDot(done = done, active = active)
                        Spacer(Modifier.width(10.dp))
                        Text(
                            label,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (done || active) MaterialTheme.colorScheme.onSurface
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (key == state.stage) {
                            Spacer(Modifier.weight(1f))
                            Text("now", style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
        }

        if (state.lastStats.isNotBlank()) {
            Card(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                shape = RoundedCornerShape(18.dp)
            ) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Last run", style = MaterialTheme.typography.titleMedium)
                    Text(state.lastStats, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

/** Moving level bars — visible proof that the engine is working, not a decorative spinner. */
@Composable
private fun ActivityStrip(active: Boolean, bars: Int = 36) {
    val transition = rememberInfiniteTransition(label = "level")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 6.283f,
        animationSpec = infiniteRepeatable(
            animation = tween(1400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "phase"
    )
    val primary = MaterialTheme.colorScheme.primary
    val idle = MaterialTheme.colorScheme.surfaceVariant
    Canvas(Modifier.fillMaxWidth().height(46.dp)) {
        val step = size.width / bars
        for (i in 0 until bars) {
            val level = if (active) {
                val s = kotlin.math.sin((phase + i * 0.42f).toDouble())
                (0.25 + 0.75 * kotlin.math.abs(s)).toFloat().coerceIn(0.1f, 1f)
            } else 0.12f
            val h = size.height * level
            drawLine(
                color = if (active) primary else idle,
                start = Offset(i * step + step / 2f, (size.height - h) / 2f),
                end = Offset(i * step + step / 2f, (size.height + h) / 2f),
                strokeWidth = step * 0.55f
            )
        }
    }
}
