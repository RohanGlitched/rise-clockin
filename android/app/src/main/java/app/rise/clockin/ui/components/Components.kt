package app.rise.clockin.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.rise.clockin.ui.theme.Rise
import app.rise.clockin.ui.theme.RiseType
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** The one action colour: sun gold. */
@Composable
fun SunButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    busy: Boolean = false,
    busyText: String? = null,
) {
    val haptic = LocalHapticFeedback.current
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.97f else 1f, spring(stiffness = 900f), label = "press")
    val active = enabled && !busy
    Box(
        modifier
            .scale(scale)
            .defaultMinSize(minHeight = 58.dp)
            .clip(RoundedCornerShape(29.dp))
            .background(if (active) Rise.Sun else Rise.Sun.copy(alpha = 0.35f))
            .clickable(source, null, enabled = active, role = Role.Button) {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress); onClick()
            }
            .padding(horizontal = 26.dp, vertical = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (busy) CircularProgressIndicator(Modifier.size(18.dp), color = Rise.Night, strokeWidth = 2.dp)
            Text(if (busy) busyText ?: text else text, style = RiseType.button, color = Rise.Night.copy(alpha = if (active || busy) 1f else 0.6f), textAlign = TextAlign.Center)
        }
    }
}

@Composable
fun GhostButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Box(
        modifier
            .defaultMinSize(minHeight = 52.dp)
            .clip(RoundedCornerShape(26.dp))
            .border(1.dp, Rise.Ivory.copy(alpha = if (enabled) 0.45f else 0.15f), RoundedCornerShape(26.dp))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 22.dp, vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = RiseType.button, color = Rise.Ivory.copy(alpha = if (enabled) 1f else 0.4f))
    }
}

@Composable
fun TextAction(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, color: Color = Rise.Sun) {
    Box(
        modifier.defaultMinSize(minHeight = 44.dp).clip(RoundedCornerShape(10.dp)).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 8.dp, vertical = 10.dp),
        contentAlignment = Alignment.CenterStart,
    ) { Text(text, style = RiseType.bodyStrong, color = color) }
}

/** A frosted panel that sits on the sky. */
@Composable
fun SkyPanel(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(
        modifier
            .clip(RoundedCornerShape(22.dp))
            .background(Rise.NightDeep.copy(alpha = 0.42f))
            .border(1.dp, Rise.Ivory.copy(alpha = 0.10f), RoundedCornerShape(22.dp))
            .padding(18.dp),
    ) { content() }
}

/** Inline message for an error or a hint, in the interface's voice. */
@Composable
fun Notice(text: String, modifier: Modifier = Modifier, tone: Color = Rise.Rose) {
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Rise.NightDeep.copy(alpha = 0.72f))
            .border(1.dp, tone.copy(alpha = 0.5f), RoundedCornerShape(14.dp)).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(Modifier.padding(top = 7.dp).size(7.dp).clip(CircleShape).background(tone))
        Spacer(Modifier.width(10.dp))
        Text(text, style = RiseType.small, color = Rise.Ivory)
    }
}

// ------------------------------------------------------------------- avatars

/** Eight sky glyphs people choose from. Index is stored on chain. */
val AvatarColors = listOf(
    Color(0xFFFFD166), Color(0xFFF2A0A1), Color(0xFF7FC8A9), Color(0xFF9DB4FF),
    // The eighth sign used to be manila, which vanished on the manila time card; steel blue reads everywhere.
    Color(0xFFFFB877), Color(0xFFC9A7F5), Color(0xFF6FD3E3), Color(0xFF4C8DAE),
)
val AvatarNames = listOf("Sun", "Rose", "Fern", "Moon", "Ember", "Iris", "Tide", "Dune")

@Composable
fun Avatar(index: Int, size: Dp = 40.dp, modifier: Modifier = Modifier, ring: Color? = null) {
    val i = Math.floorMod(index, AvatarColors.size)
    val c = AvatarColors[i]
    Canvas(modifier.size(size).semantics { contentDescription = "${AvatarNames[i]} avatar" }) {
        val r = this.size.minDimension / 2
        val center = Offset(r, r)
        drawCircle(Rise.NightDeep, r)
        when (i % 4) {
            0 -> { // sun with rays
                drawCircle(c, r * 0.42f, center)
                for (k in 0 until 8) {
                    val a = (k / 8f) * 2 * PI.toFloat()
                    drawLine(c, Offset(r + cos(a) * r * 0.58f, r + sin(a) * r * 0.58f), Offset(r + cos(a) * r * 0.78f, r + sin(a) * r * 0.78f), strokeWidth = r * 0.09f)
                }
            }
            1 -> { // crescent
                drawCircle(c, r * 0.55f, center)
                drawCircle(Rise.NightDeep, r * 0.48f, Offset(r * 1.28f, r * 0.82f))
            }
            2 -> { // horizon: half sun over a line
                drawArc(c, 180f, 180f, true, topLeft = Offset(r * 0.42f, r * 0.55f), size = androidx.compose.ui.geometry.Size(r * 1.16f, r * 1.16f))
                drawLine(c, Offset(r * 0.25f, r * 1.13f), Offset(r * 1.75f, r * 1.13f), strokeWidth = r * 0.1f)
            }
            else -> { // star
                val path = Path()
                for (k in 0 until 10) {
                    val a = -PI.toFloat() / 2 + k * PI.toFloat() / 5
                    val rr = if (k % 2 == 0) r * 0.6f else r * 0.25f
                    val p = Offset(r + cos(a) * rr, r + sin(a) * rr)
                    if (k == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
                }
                path.close(); drawPath(path, c)
            }
        }
        ring?.let { drawCircle(it, r - 1.5f, center, style = Stroke(3f)) }
    }
}

@Composable
fun LabeledValue(label: String, value: String, modifier: Modifier = Modifier, valueColor: Color = Rise.Ivory) {
    Column(modifier) {
        Text(value, style = RiseType.amount, color = valueColor)
        Spacer(Modifier.height(2.dp))
        Text(label, style = RiseType.tiny, color = Rise.Mist)
    }
}

fun Modifier.dim(on: Boolean) = if (on) alpha(0.5f) else this

fun skr(units: Long): String {
    val whole = units / 1_000_000
    val frac = (units % 1_000_000) / 10_000
    return if (frac == 0L) "%,d".format(whole) else "%,d.%02d".format(whole, frac)
}

fun shortAddress(a: String) = if (a.length > 10) a.take(4) + "…" + a.takeLast(4) else a

fun Modifier.fullWidth() = fillMaxWidth()
