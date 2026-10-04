package app.rise.clockin.ai

import kotlin.math.abs
import kotlin.math.sqrt

enum class Confidence(val code: Int, val label: String) { Low(1, "low"), Medium(2, "medium"), High(3, "high") }

data class ProofScore(val confidence: Confidence, val score: Double, val summary: String, val flags: List<String>)

/**
 * Records what the phone's sensors saw during a mission and scores how much it looks like a
 * real person getting up. Real sensors are noisy and continuous; pasted-in or emulated values
 * jump in clean steps and repeat exactly. Steps from a real walk have a human rhythm.
 *
 * The result is written on chain with the clock-in (inside the `mission` byte), so a pact can
 * see how each morning was proven.
 */
class ProofTrace {
    private val lux = ArrayList<Pair<Long, Float>>()
    private val steps = ArrayList<Long>()
    private val accel = ArrayList<Float>()
    private var vision = 0f
    private var visionLabel: String? = null
    private var scannedAfterMs = -1L
    val startedAt = System.currentTimeMillis()

    fun lux(v: Float) { lux += System.currentTimeMillis() to v }
    fun step() { steps += System.currentTimeMillis() }
    fun accel(magnitude: Float) { if (accel.size < 4000) accel += magnitude }
    fun vision(label: String, confidence: Float) { if (confidence > vision) { vision = confidence; visionLabel = label } }
    fun scanned() { scannedAfterMs = System.currentTimeMillis() - startedAt }

    fun analyze(missionId: Int): ProofScore {
        var s = 1.0
        val flags = ArrayList<String>()
        var summary = ""
        when (missionId) {
            0 -> { // light
                val values = lux.map { it.second }
                if (values.isNotEmpty()) {
                    val distinct = values.map { (it * 10).toInt() }.toSet().size
                    val first = values.take(5).average()
                    val peak = values.max()
                    summary = "Light rose from ${first.toInt()} to ${peak.toInt()} lux"
                    if (distinct < 6) { s -= 0.45; flags += "Light readings came in a few exact steps, like typed-in values" }
                    if (first >= 450) { s -= 0.25; flags += "It was already bright when the mission started" }
                    val jumps = values.zipWithNext().count { (a, b) -> b > 8 * (a + 1) }
                    if (jumps >= 1 && values.size < 25) { s -= 0.15; flags += "The light jumped instantly instead of rising" }
                }
            }
            1 -> { // walk
                summary = "${steps.size} steps"
                if (steps.size >= 6) {
                    val gaps = steps.zipWithNext { a, b -> (b - a).toDouble() }
                    val mean = gaps.average()
                    val cv = sqrt(gaps.map { (it - mean) * (it - mean) }.average()) / mean
                    summary += ", ${"%.1f".format(1000 / mean)} steps per second"
                    if (cv < 0.03) { s -= 0.45; flags += "Steps were metronome-perfect, unlike a person walking" }
                    if (mean < 220) { s -= 0.4; flags += "Faster than anyone walks" }
                }
                if (accel.size > 40) {
                    val m = accel.average(); val sd = sqrt(accel.map { (it - m) * (it - m) }.average())
                    if (sd < 0.25) { s -= 0.35; flags += "The phone barely moved while steps were counted" }
                }
            }
            2 -> { // wake spot
                summary = if (scannedAfterMs >= 0) "Wake spot scanned after ${scannedAfterMs / 1000}s" else "Wake spot scanned"
                if (scannedAfterMs in 0..4000) { s -= 0.3; flags += "Scanned within seconds: the code may be next to the bed" }
            }
            3 -> { // vision
                summary = "On-device vision saw ${visionLabel ?: "the morning"} (${(vision * 100).toInt()}%)"
                if (vision < 0.75) { s -= 0.25; flags += "The match was weak" }
            }
        }
        val c = when { s >= 0.75 -> Confidence.High; s >= 0.45 -> Confidence.Medium; else -> Confidence.Low }
        return ProofScore(c, s.coerceIn(0.0, 1.0), summary, flags)
    }

    companion object {
        /** On-chain `mission` byte: bits 0-1 mission, bits 2-3 confidence, bit 4 set when AI vision verified it. */
        fun encode(missionId: Int, score: ProofScore): Int =
            (missionId and 0x3) or ((score.confidence.code and 0x3) shl 2) or (if (missionId == 3) 0x10 else 0)

        @Volatile var last: ProofScore? = null
    }
}

@Suppress("unused") private fun Float.abs1() = abs(this)
