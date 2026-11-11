package ai.hindidub.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

/**
 * Glass card.
 *
 * Real backdrop blur (RenderEffect) only exists from API 31 and is expensive on the low-end
 * chips this app targets, so the effect is built from three cheap layers instead: a translucent
 * fill, a soft top-down gradient, and a 1 dp light border. On a dark backdrop that reads as
 * frosted glass at a fraction of the GPU cost — and it works on API 26.
 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(22.dp),
    tint: Color = MaterialTheme.colorScheme.surface,
    content: @Composable ColumnScope.() -> Unit
) {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val fill = if (dark) tint.copy(alpha = 0.62f) else tint.copy(alpha = 0.85f)
    val edge = if (dark) Color.White.copy(alpha = 0.10f) else Color.Black.copy(alpha = 0.06f)
    val sheen = if (dark) Color.White.copy(alpha = 0.06f) else Color.White.copy(alpha = 0.5f)

    Box(
        modifier
            .clip(shape)
            .background(fill)
            .background(
                Brush.verticalGradient(listOf(sheen, Color.Transparent, Color.Transparent))
            )
            .border(1.dp, edge, shape)
    ) {
        Column(Modifier.fillMaxWidth(), content = content)
    }
}

/** Pill-shaped translucent chip surface, used for the language row and small badges. */
@Composable
fun GlassPill(
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    content: @Composable BoxScope.() -> Unit
) {
    val shape = RoundedCornerShape(50)
    val accent = MaterialTheme.colorScheme.primary
    Box(
        modifier
            .clip(shape)
            .background(
                if (selected) accent.copy(alpha = 0.18f)
                else MaterialTheme.colorScheme.surface.copy(alpha = 0.55f)
            )
            .border(1.dp, if (selected) accent.copy(alpha = 0.7f) else Color.White.copy(alpha = 0.08f), shape),
        content = content
    )
}

private fun Color.luminance(): Float = 0.299f * red + 0.587f * green + 0.114f * blue
