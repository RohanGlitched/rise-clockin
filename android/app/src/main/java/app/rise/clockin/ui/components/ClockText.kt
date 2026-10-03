package app.rise.clockin.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextStyle

/**
 * A clock reading in Doto with a hand-drawn two-dot colon; Doto's own colon is a
 * cluster of dots that reads as a cross at large sizes.
 */
@Composable
fun ClockText(text: String, style: TextStyle, color: Color, modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    val fontPx = with(density) { style.fontSize.toPx() }
    val dot = fontPx * 0.05f
    val colonW = with(density) { (fontPx * 0.26f).toDp() }
    val colonH = with(density) { (fontPx * 0.72f).toDp() }
    Row(modifier.clearAndSetSemantics { contentDescription = text }, verticalAlignment = Alignment.CenterVertically) {
        text.split(":").forEachIndexed { i, part ->
            if (i > 0) {
                Canvas(Modifier.size(colonW, colonH).padding(top = with(density) { (fontPx * 0.06f).toDp() })) {
                    drawCircle(color, dot, Offset(size.width / 2, size.height * 0.3f))
                    drawCircle(color, dot, Offset(size.width / 2, size.height * 0.82f))
                }
            }
            Text(part, style = style, color = color)
        }
    }
}
