package ai.hindidub.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.sin

/**
 * Launch screen.
 *
 * One screen, three beats: the mark fades up, the ring sweeps once, then the whole thing hands
 * over to the app, which arrives as a window that scales up from 92 % and fades in (see
 * [AppRoot]). The handover is the animation the user asked for — the logo does not just vanish,
 * it becomes the app.
 */
@Composable
fun SplashScreen(onDone: () -> Unit) {
    var visible by remember { mutableStateOf(false) }
    val transition = rememberInfiniteTransition(label = "splash")
    val sweep by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1600, easing = LinearEasing), RepeatMode.Restart),
        label = "sweep"
    )
    val pulse by transition.animateFloat(
        initialValue = 0.96f,
        targetValue = 1.04f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "pulse"
    )

    LaunchedEffect(Unit) {
        visible = true
        delay(1500)
        onDone()
    }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Canvas(Modifier.size(168.dp).scale(pulse).alpha(if (visible) 1f else 0f)) {
                    val r = size.minDimension / 2f
                    drawCircle(
                        brush = Brush.sweepGradient(BrandGradient),
                        radius = r * 0.92f,
                        style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round)
                    )
                    // waveform inside the ring
                    val bars = 9
                    val w = r * 0.10f
                    val gap = r * 0.16f
                    val total = (bars - 1) * gap
                    for (i in 0 until bars) {
                        val phase = sweep * 2f * PI.toFloat() + i * 0.55f
                        val h = r * (0.22f + 0.42f * kotlin.math.abs(sin(phase.toDouble()).toFloat()))
                        val x = center.x - total / 2f + i * gap
                        drawLine(
                            brush = Brush.verticalGradient(BrandGradient),
                            start = Offset(x, center.y - h / 2f),
                            end = Offset(x, center.y + h / 2f),
                            strokeWidth = w,
                            cap = StrokeCap.Round
                        )
                    }
                }
            }
            Text(
                "HindiDubber",
                style = MaterialTheme.typography.headlineSmall.copy(fontSize = 26.sp),
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.alpha(if (visible) 1f else 0f)
            )
            Text(
                "on-device dubbing studio",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.alpha(if (visible) 0.9f else 0f)
            )
        }
    }
}

/** Soft coloured blobs behind the glass, so the translucency has something to pick up. */
@Composable
fun AmbientBackdrop() {
    Box(
        Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(
                        MaterialTheme.colorScheme.background,
                        MaterialTheme.colorScheme.surface.copy(alpha = 0.6f),
                        MaterialTheme.colorScheme.background
                    )
                )
            )
    ) {
        Canvas(Modifier.fillMaxSize()) {
            drawCircle(
                brush = Brush.radialGradient(
                    listOf(Color(0xFF5FE3F0).copy(alpha = 0.16f), Color.Transparent),
                    center = Offset(size.width * 0.18f, size.height * 0.12f),
                    radius = size.minDimension * 0.55f
                ),
                radius = size.minDimension * 0.55f,
                center = Offset(size.width * 0.18f, size.height * 0.12f)
            )
            drawCircle(
                brush = Brush.radialGradient(
                    listOf(Color(0xFF9D8CFF).copy(alpha = 0.14f), Color.Transparent),
                    center = Offset(size.width * 0.85f, size.height * 0.75f),
                    radius = size.minDimension * 0.6f
                ),
                radius = size.minDimension * 0.6f,
                center = Offset(size.width * 0.85f, size.height * 0.75f)
            )
        }
    }
}
