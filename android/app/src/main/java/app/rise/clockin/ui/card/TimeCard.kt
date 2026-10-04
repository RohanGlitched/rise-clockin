package app.rise.clockin.ui.card

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.rise.clockin.data.PactView
import app.rise.clockin.solana.Member
import app.rise.clockin.solana.Pact
import app.rise.clockin.ui.components.Avatar
import app.rise.clockin.ui.components.skr
import app.rise.clockin.ui.theme.Bricolage
import app.rise.clockin.ui.theme.Doto
import app.rise.clockin.ui.theme.Rise
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlin.random.Random

/** Manila card stock with a notched corner, like a factory time card. */
val CardShape = GenericShape { size, _ ->
    val notch = 26f * (size.width / 360f).coerceAtLeast(1f)
    moveTo(0f, 0f)
    lineTo(size.width - notch, 0f)
    lineTo(size.width, notch)
    lineTo(size.width, size.height)
    lineTo(0f, size.height)
    close()
}

private val hm24 = DateTimeFormatter.ofPattern("H:mm")
private val hm12 = DateTimeFormatter.ofPattern("h:mm")
private val hm12a = DateTimeFormatter.ofPattern("h:mm a")

/** Compact clock reading for the card, following the phone's 12/24-hour setting. */
fun cardTime(epochSec: Long): String = Instant.ofEpochSecond(epochSec).atZone(ZoneId.systemDefault()).format(if (Clock24.on) hm24 else hm12)

/** Clock reading for sentences: "6:30 am" or "06:30". */
fun localTime(epochSec: Long): String = Instant.ofEpochSecond(epochSec).atZone(ZoneId.systemDefault())
    .format(if (Clock24.on) hm24 else hm12a).lowercase()

object Clock24 { var on = false }

/**
 * The pact's time card. One row per member, one column per morning. On-time punches
 * print in blue-black ink, late ones in red; a missed morning is punched clean through.
 */
@Composable
fun TimeCard(view: PactView, now: Long, modifier: Modifier = Modifier, highlightOwner: String? = view.me?.owner, maxRows: Int = 8) {
    val p = view.pact
    val today = p.dayIndex(now).coerceIn(-1, p.days - 1)
    val windowEnd = (maxOf(today, 0) + 3).coerceAtMost(p.days - 1).coerceAtLeast(minOf(6, p.days - 1))
    val windowStart = maxOf(0, windowEnd - 6)
    val days = (windowStart..windowEnd).toList()
    val rows = view.ranked.take(maxRows)
    val seed = p.address.hashCode()

    Box(
        modifier
            .shadow(18.dp, CardShape, ambientColor = Color.Black, spotColor = Color.Black)
            .clip(CardShape)
            .background(Rise.Manila),
    ) {
        PaperFibres(seed, Modifier.matchParentSize())
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 14.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text(p.name, fontFamily = Bricolage, fontWeight = FontWeight(800), fontSize = 20.sp, color = Rise.InkBlue, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(cardSubtitle(p, now), fontFamily = Bricolage, fontWeight = FontWeight(500), fontSize = 13.sp, color = Rise.InkBlue.copy(alpha = 0.65f))
                }
                Spacer(Modifier.width(30.dp))
            }
            Spacer(Modifier.height(12.dp))
            // Column heads: weekday initial and date.
            Row(verticalAlignment = Alignment.Bottom) {
                Spacer(Modifier.width(NAME_W))
                days.forEach { d ->
                    val date = Instant.ofEpochSecond(p.startTs + d.toLong() * p.daySecs + 43200).atZone(ZoneId.systemDefault())
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()).take(2),
                            fontFamily = Bricolage, fontWeight = FontWeight(if (d == today) 800 else 600), fontSize = 11.sp,
                            color = if (d == today) Rise.InkRed else Rise.InkBlue.copy(alpha = 0.7f),
                        )
                        Text("${date.dayOfMonth}", fontFamily = Bricolage, fontWeight = FontWeight(700), fontSize = 12.sp, color = if (d == today) Rise.InkRed else Rise.InkBlue.copy(alpha = 0.55f))
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            Rule()
            rows.forEach { m ->
                Row(Modifier.fillMaxWidth().height(ROW_H), verticalAlignment = Alignment.CenterVertically) {
                    Row(Modifier.width(NAME_W), verticalAlignment = Alignment.CenterVertically) {
                        Avatar(m.avatar, 22.dp)
                        Spacer(Modifier.width(6.dp))
                        Text(
                            if (m.owner == highlightOwner) "You" else m.name,
                            fontFamily = Bricolage, fontWeight = FontWeight(if (m.owner == highlightOwner) 800 else 600), fontSize = 13.sp,
                            color = Rise.InkBlue, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                    days.forEach { d -> Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { Cell(p, m, d, now, seed) } }
                }
                Rule()
            }
            if (view.members.size > rows.size) {
                Text("and ${view.members.size - rows.size} more", fontFamily = Bricolage, fontSize = 12.sp, color = Rise.InkBlue.copy(alpha = 0.6f), modifier = Modifier.padding(top = 6.dp))
            }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Legend(Rise.InkBlue, "On time")
                Spacer(Modifier.width(12.dp))
                Legend(Rise.InkRed, "Late")
                Spacer(Modifier.width(12.dp))
                Hole(Modifier.size(10.dp))
                Spacer(Modifier.width(5.dp))
                Text("Missed", fontFamily = Bricolage, fontSize = 11.sp, color = Rise.InkBlue.copy(alpha = 0.7f))
                Spacer(Modifier.weight(1f))
                Text("Pot ${skr(p.potSoFar(view.members, now))} SKR", fontFamily = Bricolage, fontWeight = FontWeight(800), fontSize = 13.sp, color = Rise.InkBlue)
            }
        }
    }
}

private val NAME_W = 74.dp
private val ROW_H = 36.dp

fun cardSubtitle(p: Pact, now: Long): String {
    val d = p.dayIndex(now)
    return when {
        d < 0 -> "Starts ${localTime(p.startTs)}"
        p.isOver(now) -> "Finished after ${p.days} mornings"
        else -> "Morning ${d + 1} of ${p.days}, ${skr(p.stakePerDay)} SKR a morning"
    }
}

@Composable
private fun Cell(p: Pact, m: Member, d: Int, now: Long, seed: Int) {
    val target = m.target(p, d)
    when {
        d < m.firstDay -> Text("–", color = Rise.InkBlue.copy(alpha = 0.25f), fontSize = 13.sp)
        m.isIn(d) -> {
            val delta = m.offsets[d].toInt()
            val r = Random(seed + d * 31 + m.owner.hashCode())
            Text(
                cardTime(target + delta),
                fontFamily = Doto, fontWeight = FontWeight(900), fontSize = 12.sp, maxLines = 1, softWrap = false,
                letterSpacing = (-0.05).em,
                color = (if (delta > 0) Rise.InkRed else Rise.InkBlue).copy(alpha = 0.9f + r.nextFloat() * 0.1f),
                modifier = Modifier.rotate(r.nextFloat() * 6f - 3f).semantics { contentDescription = "Clocked in at ${cardTime(target + delta)}" },
                textAlign = TextAlign.Center,
            )
        }
        now > m.windowCloses(p, d) -> Hole(Modifier.size(14.dp).semantics { contentDescription = "Missed" })
        else -> Box(Modifier.size(5.dp).background(Rise.InkBlue.copy(alpha = 0.18f), RoundedCornerShape(3.dp)))
    }
}

/** A hole punched through the card: the night shows through. */
@Composable
fun Hole(modifier: Modifier) {
    Canvas(modifier) {
        val r = size.minDimension / 2
        drawCircle(Brush.radialGradient(listOf(Rise.NightDeep, Rise.Night), center = Offset(r * 0.8f, r * 0.7f), radius = r), r)
        drawCircle(Color.Black.copy(alpha = 0.25f), r, style = androidx.compose.ui.graphics.drawscope.Stroke(r * 0.25f))
    }
}

@Composable
private fun Legend(color: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).background(color, RoundedCornerShape(2.dp)))
        Spacer(Modifier.width(5.dp))
        Text(text, fontFamily = Bricolage, fontSize = 11.sp, color = Rise.InkBlue.copy(alpha = 0.7f))
    }
}

@Composable
private fun Rule() = Box(Modifier.fillMaxWidth().height(1.dp).background(Rise.CardRule.copy(alpha = 0.7f)))

@Composable
private fun PaperFibres(seed: Int, modifier: Modifier) {
    val fibres = remember(seed) {
        val r = Random(seed)
        List(90) { listOf(r.nextFloat(), r.nextFloat(), r.nextFloat() * 0.05f + 0.01f, r.nextFloat() * 6.28f, r.nextFloat()) }
    }
    Canvas(modifier) {
        // A warm vignette and fine fibres give the card some tooth.
        drawRect(Brush.radialGradient(listOf(Color.Transparent, Rise.ManilaShade.copy(alpha = 0.55f)), center = Offset(size.width * 0.4f, size.height * 0.3f), radius = size.maxDimension))
        fibres.forEach { (x, y, len, ang, a) ->
            val start = Offset(x * size.width, y * size.height)
            val end = Offset(start.x + kotlin.math.cos(ang) * len * size.width, start.y + kotlin.math.sin(ang) * len * size.width)
            drawLine(Rise.CardRule.copy(alpha = 0.18f + a * 0.2f), start, end, strokeWidth = 1f)
        }
    }
}

/**
 * The clock-in stamp. Slams onto the card with a spring and a slight skew,
 * printed in the ink of the moment: blue-black on time, red when late.
 */
@Composable
fun Stamp(time: String, line: String, late: Boolean, modifier: Modifier = Modifier, play: Boolean = true) {
    val ink = if (late) Rise.InkRed else Rise.InkBlue
    val scale = remember { Animatable(if (play) 2.4f else 1f) }
    val alpha = remember { Animatable(if (play) 0f else 1f) }
    LaunchedEffect(play) {
        if (!play) return@LaunchedEffect
        alpha.animateTo(1f, tween(90))
        scale.animateTo(1f, spring(dampingRatio = 0.42f, stiffness = Spring.StiffnessMediumLow))
    }
    Box(
        modifier
            .graphicsLayer { scaleX = scale.value; scaleY = scale.value; this.alpha = alpha.value; rotationZ = -4f }
            .border(3.dp, ink.copy(alpha = 0.85f), RoundedCornerShape(12.dp))
            .padding(5.dp)
            .border(1.5.dp, ink.copy(alpha = 0.7f), RoundedCornerShape(8.dp))
            .padding(horizontal = 18.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(if (late) "Clocked in late" else "Clocked in", fontFamily = Bricolage, fontWeight = FontWeight(800), fontSize = 15.sp, color = ink.copy(alpha = 0.9f))
            app.rise.clockin.ui.components.ClockText(time, androidx.compose.ui.text.TextStyle(fontFamily = Doto, fontWeight = FontWeight(900), fontSize = 40.sp), ink.copy(alpha = 0.92f))
            Text(line, fontFamily = Bricolage, fontWeight = FontWeight(600), fontSize = 12.sp, color = ink.copy(alpha = 0.8f))
        }
    }
}
