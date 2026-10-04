package app.rise.clockin.ai

import app.rise.clockin.data.PactView
import app.rise.clockin.solana.Member
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.roundToInt

/** How hard tomorrow's mission is, chosen by the coach. */
enum class Difficulty(val label: String, val steps: Int, val lux: Int, val visionConfidence: Float, val sunriseLead: Int) {
    Gentle("Gentle", 20, 350, 0.60f, 5),
    Normal("Normal", 30, 500, 0.70f, 10),
    Tough("Tough", 45, 800, 0.80f, 15),
}

data class CoachFactor(val text: String, val weight: Double)

data class CoachReport(
    /** Probability of missing or being late tomorrow, 0..1. */
    val risk: Double,
    val difficulty: Difficulty,
    val headline: String,
    val factors: List<CoachFactor>,
    /** How many past mornings the model was trained on. */
    val trainedOn: Int,
    val personalMornings: Int,
)

/**
 * Rise Coach: a small logistic-regression model trained on the phone from the public time
 * cards of every pact you're in (every member's mornings are on chain), then weighted toward
 * your own history. It predicts how likely you are to oversleep tomorrow and picks the
 * mission difficulty. Nothing leaves the device.
 */
object WakeCoach {
    private const val NOT_IN = Short.MAX_VALUE
    private val names = listOf("bias", "weekend", "slipped yesterday", "slip rate", "early wake time", "you")

    private data class Example(val x: DoubleArray, val y: Double, val weight: Double)

    private fun localDay(epochSec: Long) = Instant.ofEpochSecond(epochSec).atZone(ZoneId.systemDefault())

    /** Features for one morning: what was known before that morning started. */
    private fun features(target: Long, prevSlipped: Double?, slipRate: Double, isMe: Boolean): DoubleArray {
        val day = localDay(target)
        val weekend = if (day.dayOfWeek == DayOfWeek.SATURDAY || day.dayOfWeek == DayOfWeek.SUNDAY) 1.0 else 0.0
        val hour = day.hour + day.minute / 60.0
        val early = ((8.0 - hour) / 4.0).coerceIn(0.0, 1.5)
        return doubleArrayOf(1.0, weekend, prevSlipped ?: 0.3, slipRate, early, if (isMe) 1.0 else 0.0)
    }

    private fun examples(views: List<PactView>, me: String?, now: Long): List<Example> {
        val out = ArrayList<Example>()
        for (v in views) for (m in v.members) {
            var slips = 0; var seen = 0; var prev: Double? = null
            for (d in m.firstDay until v.pact.days) {
                if (now <= m.windowCloses(v.pact, d)) break
                val off = m.offsets[d]
                val slipped = if (off == NOT_IN || off > 0) 1.0 else 0.0
                val rate = (slips + 0.3 * 2) / (seen + 2.0) // Beta prior: 30% slip rate over 2 mornings
                val isMe = m.owner == me
                out += Example(features(m.target(v.pact, d), prev, rate, isMe), slipped, if (isMe) 3.0 else 1.0)
                slips += slipped.toInt(); seen++; prev = slipped
            }
        }
        return out
    }

    private fun sigmoid(z: Double) = 1.0 / (1.0 + exp(-z))

    /** Weighted logistic regression by gradient descent with L2, starting from a sensible prior. */
    private fun train(data: List<Example>): DoubleArray {
        val w = doubleArrayOf(-1.2, 0.6, 0.9, 1.5, 0.4, 0.0)
        val prior = w.copyOf()
        if (data.isEmpty()) return w
        val lr = 0.15; val l2 = 0.08
        val totalW = data.sumOf { it.weight }
        repeat(400) {
            val g = DoubleArray(w.size)
            for (e in data) {
                val p = sigmoid(e.x.indices.sumOf { w[it] * e.x[it] })
                for (i in w.indices) g[i] += e.weight * (p - e.y) * e.x[i]
            }
            for (i in w.indices) w[i] -= lr * (g[i] / totalW + l2 * (w[i] - prior[i]))
        }
        return w
    }

    fun report(views: List<PactView>, me: String?, now: Long = System.currentTimeMillis() / 1000): CoachReport? {
        val mine = views.mapNotNull { v -> v.me?.let { v to it } }.filter { (v, _) -> !v.pact.isOver(now) }
        if (mine.isEmpty() || me == null) return null
        val data = examples(views, me, now)
        val w = train(data)

        // Tomorrow's morning for the first active pact, with what we know about you so far.
        val (pv, m) = mine.first()
        val next = (m.firstDay until pv.pact.days).firstOrNull { d -> now < m.windowOpens(pv.pact, d) } ?: return null
        val past = (m.firstDay until next).filter { now > m.windowCloses(pv.pact, it) }
        val slipsList = past.map { d -> val o = m.offsets[d]; if (o == NOT_IN || o > 0) 1.0 else 0.0 }
        val prev = slipsList.lastOrNull()
        val rate = (slipsList.sum() + 0.6) / (slipsList.size + 2.0)
        val x = features(m.target(pv.pact, next), prev, rate, true)
        val risk = sigmoid(x.indices.sumOf { w[it] * x[it] })
        val contrib = (1 until x.size).map { i -> CoachFactor(names[i], w[i] * x[i]) }.filter { abs(it.weight) > 0.05 }.sortedByDescending { abs(it.weight) }

        val difficulty = when {
            risk >= 0.55 -> Difficulty.Tough
            risk <= 0.25 && past.isNotEmpty() -> Difficulty.Gentle
            else -> Difficulty.Normal
        }
        val streak = m.streak
        val pct = (risk * 100).roundToInt()
        val headline = when {
            difficulty == Difficulty.Tough && prev == 1.0 -> "You slipped last morning. Tomorrow is set to Tough: a longer sunrise and a harder mission."
            difficulty == Difficulty.Tough && x[1] == 1.0 -> "Weekend mornings are where people in your pacts slip most. Tomorrow is set to Tough."
            difficulty == Difficulty.Tough -> "Tomorrow looks risky ($pct%). The coach starts the sunrise earlier and asks more of you."
            difficulty == Difficulty.Gentle && streak >= 2 -> "$streak mornings in a row. You've earned a gentler mission tomorrow."
            difficulty == Difficulty.Gentle -> "You're reliably up on time. Tomorrow's mission is set to Gentle."
            past.isEmpty() -> "Your first pact morning is next. The coach starts at Normal and learns from every clock-in."
            else -> "Steady. Tomorrow stays at Normal."
        }
        return CoachReport(
            risk = risk,
            difficulty = difficulty,
            headline = headline,
            factors = contrib.take(3).map { f -> f.copy(text = factorText(f)) },
            trainedOn = data.size,
            personalMornings = past.size,
        )
    }

    private fun factorText(f: CoachFactor): String {
        val up = f.weight > 0
        return when (f.text) {
            "weekend" -> if (up) "Tomorrow is a weekend morning" else "Weekday routine helps"
            "slipped yesterday" -> if (up) "You slipped last morning" else "You made it last morning"
            "slip rate" -> if (up) "Your slip rate so far" else "Your on-time record"
            "early wake time" -> if (up) "An early wake time" else "A comfortable wake time"
            "you" -> if (up) "You slip more than your pact does" else "You're steadier than your pact"
            else -> f.text
        }
    }

    /** Last report, shared with the alarm so missions use the coach's difficulty. */
    @Volatile var current: CoachReport? = null
}

/** Which mission settings to use right now: the coach's, or Normal until it has a report. */
fun currentDifficulty(): Difficulty = WakeCoach.current?.difficulty ?: Difficulty.Normal

@Suppress("unused")
private fun Member.debug() = name
