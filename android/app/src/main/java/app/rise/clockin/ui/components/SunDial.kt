package app.rise.clockin.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.rise.clockin.ui.theme.Rise
import app.rise.clockin.ui.theme.RiseType
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

fun formatMinutes(m: Int, h24: Boolean = false): String {
    val h = m / 60; val mm = m % 60
    if (h24) return "%d:%02d".format(h, mm)
    val h12 = if (h % 12 == 0) 12 else h % 12
    return "%d:%02d".format(h12, mm)
}
fun amPm(m: Int) = if (m < 720) "am" else "pm"

/**
 * Pick a wake time by dragging the sun around a 24-hour sky. Midnight sits at the
 * bottom under the horizon, noon at the top; mornings rise up the left side.
 */
@Composable
fun SunDial(minutes: Int, onChange: (Int) -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val haptic = LocalHapticFeedback.current
    val current by rememberUpdatedState(minutes)
    fun angleFor(m: Int) = (m / 1440f) * 2f * PI.toFloat() + PI.toFloat() / 2f
    fun minutesFor(pos: Offset, c: Offset): Int {
        var a = atan2(pos.y - c.y, pos.x - c.x) - PI.toFloat() / 2f
        while (a < 0) a += 2f * PI.toFloat()
        val raw = (a / (2f * PI.toFloat()) * 1440f).toInt()
        return (Math.round(raw / 5f) * 5) % 1440
    }
    fun update(m: Int) { if (m != current) { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); onChange(m) } }

    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f), contentAlignment = Alignment.Center) {
            Canvas(
                Modifier.fillMaxWidth().aspectRatio(1f)
                    .semantics { contentDescription = "Wake time ${formatMinutes(minutes)} ${amPm(minutes)}. Drag the sun to change." }
                    .pointerInput(enabled) {
                        if (!enabled) return@pointerInput
                        detectDragGestures { change, _ ->
                            update(minutesFor(change.position, Offset(size.width / 2f, size.height / 2f)))
                        }
                    }
                    .pointerInput(enabled) {
                        if (!enabled) return@pointerInput
                        detectTapGestures { pos -> update(minutesFor(pos, Offset(size.width / 2f, size.height / 2f))) }
                    },
            ) {
                val c = Offset(size.width / 2, size.height / 2)
                val r = size.minDimension * 0.40f
                val ring = size.minDimension * 0.075f
                // The day as a ring: night at the bottom, dawn and dusk at the sides, day on top.
                drawCircle(
                    Brush.sweepGradient(
                        // Sweep starts at 3 o'clock (18:00) and runs clockwise through midnight at the bottom.
                        0f to Rise.Rose, 0.12f to Color(0xFF5B4A8A), 0.25f to Rise.NightDeep, 0.40f to Color(0xFF5B4A8A),
                        0.5f to Rise.Apricot, 0.62f to Rise.Sun, 0.75f to Rise.Sun, 0.88f to Rise.Apricot, 1f to Rise.Rose,
                        center = c,
                    ),
                    r, c, style = Stroke(ring),
                )
                // Hour ticks.
                for (h in 0 until 24) {
                    val a = angleFor(h * 60)
                    val inner = r - ring * 0.9f - (if (h % 6 == 0) 10f else 0f)
                    val outer = r - ring * 0.65f
                    drawLine(Rise.Ivory.copy(alpha = if (h % 6 == 0) 0.7f else 0.25f), Offset(c.x + cos(a) * inner, c.y + sin(a) * inner), Offset(c.x + cos(a) * outer, c.y + sin(a) * outer), strokeWidth = if (h % 6 == 0) 3f else 2f)
                }
                // Horizon.
                drawLine(Rise.Ivory.copy(alpha = 0.22f), Offset(c.x - r * 1.22f, c.y), Offset(c.x - r - ring * 0.6f, c.y), strokeWidth = 2f)
                drawLine(Rise.Ivory.copy(alpha = 0.22f), Offset(c.x + r + ring * 0.6f, c.y), Offset(c.x + r * 1.22f, c.y), strokeWidth = 2f)
                // The sun knob with a glow.
                val a = angleFor(minutes)
                val sp = Offset(c.x + cos(a) * r, c.y + sin(a) * r)
                drawCircle(Brush.radialGradient(listOf(Rise.Sun.copy(alpha = 0.55f), Color.Transparent), center = sp, radius = ring * 2.4f), ring * 2.4f, sp)
                drawCircle(Rise.Sun, ring * 0.82f, sp)
                drawCircle(Rise.Ivory, ring * 0.82f, sp, style = Stroke(3f))
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Row(verticalAlignment = Alignment.Bottom) {
                    ClockText(formatMinutes(minutes), RiseType.clockLarge, Rise.Ivory)
                    Text(amPm(minutes), style = RiseType.clockMid, color = Rise.Sun, modifier = Modifier.semantics { })
                }
                Text(dialHint(minutes), style = RiseType.small, color = Rise.Mist)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            GhostButton("−5 min", { onChange(Math.floorMod(minutes - 5, 1440)) }, enabled = enabled)
            GhostButton("+5 min", { onChange(Math.floorMod(minutes + 5, 1440)) }, enabled = enabled)
        }
    }
}

private fun dialHint(m: Int) = when (m) {
    in 240..359 -> "Before the birds"
    in 360..449 -> "With the sunrise"
    in 450..539 -> "A gentle start"
    in 540..719 -> "A late riser"
    else -> "Night shift?"
}

@Composable
fun Dot(color: Color, size: androidx.compose.ui.unit.Dp = 8.dp) = Canvas(Modifier.size(size)) { drawCircle(color) }
