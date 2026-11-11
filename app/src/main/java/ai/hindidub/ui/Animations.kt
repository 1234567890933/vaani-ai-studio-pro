package ai.hindidub.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The animation vocabulary of the app. Four primitives, each with a job:
 *
 *  [PulsingDot]      — "the engine is working right now". A static spinner tells you nothing
 *                      about whether a 20-minute job is alive; a pulse does, and costs one
 *                      infinite transition.
 *  [AnimatedProgressBar] — progress arrives in discrete service callbacks (~4/s). Animating
 *                      between them removes the visible stepping without lying about the value.
 *  [AnimatedStageText] — stage names change abruptly; a short crossfade makes it readable.
 *  [BubbleEntrance]  — new chat messages slide in once, on first composition.
 *
 * Everything is driven by Compose's animation core, so it runs off the frame clock and stops
 * when the app is not visible — no timers, no allocations per frame.
 */

@Composable
fun PulsingDot(
    color: Color = MaterialTheme.colorScheme.primary,
    size: Dp = 8.dp,
    modifier: Modifier = Modifier
) {
    val transition = rememberInfiniteTransition(label = "pulse")
    val scale by transition.animateFloat(
        initialValue = 0.75f,
        targetValue = 1.25f,
        animationSpec = infiniteRepeatable(
            animation = tween(700, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )
    val alpha by transition.animateFloat(
        initialValue = 0.55f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(700, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )
    Box(
        modifier
            .size(size)
            .graphicsLayer { scaleX = scale; scaleY = scale; this.alpha = alpha }
            .background(color, RoundedCornerShape(percent = 50))
    )
}

@Composable
fun AnimatedProgressBar(
    fraction: Float,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary
) {
    val animated by animateFloatAsState(
        targetValue = fraction.coerceIn(0f, 1f),
        animationSpec = tween(450, easing = FastOutSlowInEasing),
        label = "progress"
    )
    Box(
        modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Box(
            Modifier
                .fillMaxWidth(animated)
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(color)
        )
    }
}

@Composable
fun AnimatedStageText(
    text: String,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
    modifier: Modifier = Modifier
) {
    AnimatedContent(
        targetState = text,
        transitionSpec = {
            (fadeIn(tween(180)) + slideInVertically(tween(180)) { it / 4 })
                .togetherWith(fadeOut(tween(120)))
        },
        label = "stage",
        modifier = modifier
    ) { value ->
        Text(value, style = style)
    }
}

/** Fades/slides a bubble in exactly once, when it first appears in the list. */
@Composable
fun BubbleEntrance(content: @Composable () -> Unit) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { visible = true }
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(220)) + slideInVertically(tween(220)) { it / 5 },
        exit = fadeOut(tween(120)) + slideOutVertically(tween(120)) { it / 8 }
    ) {
        content()
    }
}

/** Small helper for step markers on the setup screen. */
@Composable
fun StepDot(done: Boolean, active: Boolean, modifier: Modifier = Modifier) {
    when {
        done -> Box(
            modifier.size(10.dp).background(MaterialTheme.colorScheme.primary, RoundedCornerShape(50))
        )
        active -> PulsingDot(size = 10.dp, modifier = modifier)
        else -> Box(
            modifier.size(10.dp)
                .background(MaterialTheme.colorScheme.outline, RoundedCornerShape(50))
        )
    }
}


/**
 * Shimmer placeholder for a row that is waiting on data. One infinite transition, no
 * per-frame allocation, and it reads as "loading" without hiding what is loading.
 */
@Composable
fun ShimmerBox(modifier: Modifier = Modifier, height: Dp = 14.dp) {
    val transition = rememberInfiniteTransition(label = "shimmer")
    val shift by transition.animateFloat(
        initialValue = 0.45f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "shimmerAlpha"
    )
    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(6.dp))
            .graphicsLayer { alpha = shift }
            .background(MaterialTheme.colorScheme.surfaceVariant)
    )
}
