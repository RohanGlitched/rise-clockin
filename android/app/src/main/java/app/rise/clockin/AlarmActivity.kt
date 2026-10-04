package app.rise.clockin

import android.content.Intent
import android.media.AudioAttributes
import android.media.SoundPool
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import app.rise.clockin.alarm.AlarmPhase
import app.rise.clockin.alarm.AlarmService
import app.rise.clockin.data.ClockInResult
import app.rise.clockin.data.Mission
import app.rise.clockin.data.Store
import app.rise.clockin.solana.Config
import app.rise.clockin.solana.RiseProgram
import app.rise.clockin.ui.card.CardShape
import app.rise.clockin.ui.card.Stamp
import app.rise.clockin.ui.card.localTime
import app.rise.clockin.ui.components.Avatar
import app.rise.clockin.ui.components.GhostButton
import app.rise.clockin.ui.components.Notice
import app.rise.clockin.ui.components.SunButton
import app.rise.clockin.ui.components.TextAction
import app.rise.clockin.ui.components.skr
import app.rise.clockin.ui.screens.LightMission
import app.rise.clockin.ui.screens.MissionOption
import app.rise.clockin.ui.screens.SpotMission
import app.rise.clockin.ui.screens.StreakPill
import app.rise.clockin.ui.screens.WalkMission
import app.rise.clockin.ui.screens.rememberNow
import app.rise.clockin.ui.sky.SkyBackground
import app.rise.clockin.ui.theme.Rise
import app.rise.clockin.ui.theme.RiseTheme
import app.rise.clockin.ui.theme.RiseType
import com.solana.mobilewalletadapter.clientlib.ActivityResultSender
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

private enum class Stage { Sunrise, Ringing, Mission, Signing, Stamped, Failed }

/** The morning, on the lock screen: sunrise, alarm, mission, clock-in and stamp. */
class AlarmActivity : ComponentActivity() {
    companion object { const val EXTRA_DIRECT = "direct" }

    private lateinit var sender: ActivityResultSender
    private var sounds: SoundPool? = null
    private var stampSound = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(0), navigationBarStyle = SystemBarStyle.dark(0))
        super.onCreate(savedInstanceState)
        Store.init(this)
        app.rise.clockin.ui.card.Clock24.on = android.text.format.DateFormat.is24HourFormat(this)
        sender = ActivityResultSender(this)
        if (Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true) }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        sounds = SoundPool.Builder().setMaxStreams(2)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION).build()).build()
            .also { stampSound = it.load(this, R.raw.rise_stamp, 1) }
        val direct = intent.getBooleanExtra(EXTRA_DIRECT, false)
        setContent {
            RiseTheme {
                CompositionLocalProvider(LocalSender provides sender) {
                    Morning(
                        direct = direct,
                        setBrightness = { b -> window.attributes = window.attributes.apply { screenBrightness = b } },
                        onStamp = { stamp() },
                        onClose = { AlarmService.stop(this); finish() },
                    )
                }
            }
        }
    }

    private fun stamp() {
        sounds?.play(stampSound, 1f, 1f, 1, 0, 1f)
        val v = if (Build.VERSION.SDK_INT >= 31) getSystemService(VibratorManager::class.java).defaultVibrator
        else @Suppress("DEPRECATION") getSystemService(Vibrator::class.java)
        v.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 60, 40, 140), intArrayOf(0, 255, 0, 180), -1))
    }

    override fun onResume() {
        super.onResume()
        AlarmService.shown(this)
    }

    override fun onDestroy() {
        sounds?.release()
        super.onDestroy()
    }
}

@Composable
private fun Morning(direct: Boolean, setBrightness: (Float) -> Unit, onStamp: () -> Unit, onClose: () -> Unit) {
    val alarm by AlarmService.state.collectAsState()
    val profile by Store.profile.collectAsState()
    val sender = LocalSender.current
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    val now = rememberNow(250)
    var stage by remember { mutableStateOf(if (direct) Stage.Mission else Stage.Sunrise) }
    var mission by remember { mutableStateOf(profile.mission) }
    var result by remember { mutableStateOf<ClockInResult?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var switching by remember { mutableStateOf(false) }

    // Follow the alarm service from sunrise to ringing; close if it stands down
    // (for example, you already clocked in this morning).
    var sawAlarm by remember { mutableStateOf(false) }
    LaunchedEffect(alarm.phase) {
        if (alarm.phase != AlarmPhase.Idle) sawAlarm = true
        if (stage == Stage.Sunrise && alarm.phase == AlarmPhase.Ringing) stage = Stage.Ringing
        if (sawAlarm && alarm.phase == AlarmPhase.Idle && (stage == Stage.Sunrise || stage == Stage.Ringing)) onClose()
    }
    BackHandler(enabled = stage != Stage.Stamped && stage != Stage.Failed) { /* the alarm can't be dismissed with back */ }

    val nowMs = now * 1000
    val dawn = when (stage) {
        Stage.Sunrise -> if (alarm.wakeAt > alarm.startedAt) (0.12f + 0.6f * ((nowMs - alarm.startedAt).toFloat() / (alarm.wakeAt - alarm.startedAt))).coerceIn(0.12f, 0.72f) else 0.4f
        Stage.Ringing -> 0.82f
        Stage.Mission, Stage.Signing -> 0.9f
        Stage.Stamped, Stage.Failed -> 1f
    }
    // Sunrise lamp: the screen brightens with the sky.
    LaunchedEffect(stage, dawn) { setBrightness(if (stage == Stage.Sunrise) (0.02f + dawn).coerceAtMost(1f) else -1f) }

    // What the sensors saw during the mission, scored for the on-chain proof.
    var trace by remember { mutableStateOf(app.rise.clockin.ai.ProofTrace()) }
    var proof by remember { mutableStateOf<app.rise.clockin.ai.ProofScore?>(null) }
    fun startMission() { AlarmService.enterMission(context); trace = app.rise.clockin.ai.ProofTrace(); stage = Stage.Mission }
    fun clockIn() {
        if (proof == null) proof = trace.analyze(mission.id)
        stage = Stage.Signing; error = null
        scope.launch {
            runCatching { Store.clockIn(mission, sender, proof) }
                .onSuccess { result = it; stage = Stage.Stamped; AlarmService.stop(context) }
                .onFailure { error = RiseProgram.explain(it); stage = Stage.Failed }
        }
    }

    SkyBackground(dawn, sunX = 0.5f, horizon = if (stage == Stage.Stamped) 0.98f else 0.84f) {
        AnimatedContent(stage, transitionSpec = { (fadeIn(tween(400)) + slideInVertically(tween(420)) { it / 8 }) togetherWith fadeOut(tween(200)) }, label = "stage") { s ->
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = 24.dp)) {
                when (s) {
                    Stage.Sunrise -> SunriseStage(alarm.wakeAt, alarm.rehearsal, ::startMission)
                    Stage.Ringing -> RingingStage(alarm.rehearsal, ::startMission)
                    Stage.Mission -> MissionStage(mission, profile.spotCode, switching, { switching = it }, { mission = it; switching = false; trace = app.rise.clockin.ai.ProofTrace() }, ::clockIn, trace)
                    Stage.Signing -> SigningStage(alarm.rehearsal)
                    Stage.Stamped -> StampedStage(result!!, onStamp, onClose, proof)
                    Stage.Failed -> FailedStage(error ?: "Clock-in failed.", ::clockIn, onClose)
                }
            }
        }
    }
}

private val clockFmt = DateTimeFormatter.ofPattern("h:mm")
private val secFmt = DateTimeFormatter.ofPattern("ss")

@Composable
private fun BigClock(now: Long = rememberNow(1000)) {
    val t = Instant.ofEpochSecond(now).atZone(ZoneId.systemDefault())
    Row(verticalAlignment = Alignment.Bottom) {
        app.rise.clockin.ui.components.ClockText(t.format(clockFmt), RiseType.clockHuge, Rise.Ivory)
        Spacer(Modifier.width(6.dp))
        Text(t.format(secFmt), style = RiseType.clockMid, color = Rise.Sun, modifier = Modifier.padding(bottom = 18.dp))
    }
}

@Composable
private fun ColumnScope.SunriseStage(wakeAt: Long, rehearsal: Boolean, onUp: () -> Unit) {
    Spacer(Modifier.weight(0.5f))
    Text(if (rehearsal) "Rehearsal" else "Sunrise", style = RiseType.bodyStrong, color = Rise.Sun)
    BigClock()
    Text("The light is coming up slowly. Your alarm rings at ${localTime(wakeAt / 1000)}.", style = RiseType.body, color = Rise.Ivory.copy(alpha = 0.85f))
    Spacer(Modifier.weight(1f))
    GhostButton("I'm already up", onUp, Modifier.fillMaxWidth().padding(bottom = 24.dp))
}

@Composable
private fun ColumnScope.RingingStage(rehearsal: Boolean, onSlide: () -> Unit) {
    val state by Store.state.collectAsState()
    val now = System.currentTimeMillis() / 1000
    val open = state.pacts.filter { v -> v.me?.openDay(v.pact, now) != null }
    val stake = open.sumOf { it.pact.stakePerDay }
    val up = open.flatMap { v -> val d = v.pact.dayIndex(now); v.members.filter { it.owner != v.me?.owner && it.isIn(d) } }.distinctBy { it.owner }
    val closes = open.minOfOrNull { v -> v.me!!.windowCloses(v.pact, v.me.openDay(v.pact, now)!!) }
    val pulse = rememberInfiniteTransition(label = "ring").animateFloat(1f, 1.04f, infiniteRepeatable(tween(600), RepeatMode.Reverse), label = "s")

    Spacer(Modifier.weight(0.4f))
    Text(AlarmService.greeting(), style = RiseType.bodyStrong, color = Rise.Sun)
    Box(Modifier.scale(pulse.value)) { BigClock() }
    Spacer(Modifier.height(10.dp))
    when {
        rehearsal -> Text("This is a rehearsal. Tomorrow, your stake rides on this.", style = RiseType.heading, color = Rise.Ivory)
        open.isEmpty() -> Text("Time to rise.", style = RiseType.heading, color = Rise.Ivory)
        else -> {
            Text("${skr(stake)} SKR is on the line until ${localTime(closes!!)}.", style = RiseType.heading, color = Rise.Ivory)
            if (up.isNotEmpty()) {
                Spacer(Modifier.height(14.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    up.take(4).forEachIndexed { i, m -> Avatar(m.avatar, 34.dp, Modifier.offset(x = (-10 * i).dp), ring = Rise.Apricot) }
                    Spacer(Modifier.width(6.dp))
                    Text(if (up.size == 1) "${up[0].name} is already up" else "${up[0].name} and ${up.size - 1} more are up", style = RiseType.bodyStrong, color = Rise.Ivory)
                }
            }
        }
    }
    Spacer(Modifier.weight(1f))
    SunSlider("Slide to start your mission", onSlide, Modifier.padding(bottom = 28.dp))
}

/** Drag the sun across to start; a tap won't do it, so a sleepy hand can't dismiss the alarm. */
@Composable
private fun SunSlider(label: String, onDone: () -> Unit, modifier: Modifier = Modifier) {
    val offset = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    BoxWithConstraints(
        modifier.fillMaxWidth().height(72.dp).clip(RoundedCornerShape(36.dp)).background(Rise.Night.copy(alpha = 0.85f))
            .semantics { contentDescription = label; onClick(label) { onDone(); true } },
    ) {
        val knob = 64.dp
        val maxPx = with(density) { (maxWidth - knob - 8.dp).toPx() }
        Text(label, style = RiseType.bodyStrong, color = Rise.Ivory.copy(alpha = 0.8f * (1f - offset.value / maxPx)), modifier = Modifier.align(Alignment.Center).padding(start = 40.dp))
        Box(
            Modifier.offset { IntOffset(offset.value.roundToInt() + with(density) { 4.dp.roundToPx() }, with(density) { 4.dp.roundToPx() }) }
                .size(knob).clip(RoundedCornerShape(32.dp)).background(Rise.Sun)
                .pointerInput(maxPx) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            scope.launch {
                                if (offset.value > maxPx * 0.85f) { offset.animateTo(maxPx, tween(120)); onDone() }
                                else offset.animateTo(0f, spring())
                            }
                        },
                    ) { _, d -> scope.launch { offset.snapTo((offset.value + d).coerceIn(0f, maxPx)) } }
                },
        )
    }
}

@Composable
private fun ColumnScope.MissionStage(
    mission: Mission, spot: String, switching: Boolean, setSwitching: (Boolean) -> Unit,
    onSwitch: (Mission) -> Unit, onDone: () -> Unit, trace: app.rise.clockin.ai.ProofTrace,
) {
    val level = app.rise.clockin.ai.currentDifficulty()
    Spacer(Modifier.height(24.dp))
    Text(mission.title, style = RiseType.title, color = Rise.Ivory)
    Text(mission.line, style = RiseType.body, color = Rise.Ivory.copy(alpha = 0.85f))
    if (app.rise.clockin.ai.WakeCoach.current != null) {
        Text("Rise Coach set today to ${level.label}.", style = RiseType.small, color = Rise.Sun, modifier = Modifier.padding(top = 6.dp))
    }
    Spacer(Modifier.height(28.dp))
    if (switching) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Mission.entries.forEach { m -> MissionOption(m, m == mission) { onSwitch(m) } }
        }
    } else {
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            Box(Modifier.clip(RoundedCornerShape(32.dp)).background(Rise.Night.copy(alpha = 0.82f)).padding(24.dp)) {
                when (mission) {
                    Mission.Light -> LightMission(onDone, Modifier.fillMaxWidth(), trace, level.lux)
                    Mission.Walk -> WalkMission(onDone, Modifier.fillMaxWidth(), level.steps, trace)
                    Mission.Spot -> SpotMission(spot, onDone, Modifier.fillMaxWidth(), trace)
                    Mission.Photo -> app.rise.clockin.ui.screens.PhotoMission(onDone, Modifier.fillMaxWidth(), level.visionConfidence, trace)
                }
            }
        }
    }
    TextAction(if (switching) "Keep this mission" else "Switch mission", { setSwitching(!switching) }, Modifier.padding(bottom = 16.dp), color = Rise.Ivory)
}

@Composable
private fun ColumnScope.SigningStage(rehearsal: Boolean) {
    Spacer(Modifier.weight(1f))
    Text(if (rehearsal) "Stamping your card" else "Clocking you in", style = RiseType.title, color = Rise.Ivory)
    Spacer(Modifier.height(8.dp))
    Text(
        if (rehearsal) "A rehearsal stays on your phone. On a pact morning, this is where you approve the clock-in in your wallet."
        else "Approve it in your wallet. Solana records the exact second you made it.",
        style = RiseType.body, color = Rise.Ivory.copy(alpha = 0.85f),
    )
    Spacer(Modifier.weight(1.4f))
}

@Composable
private fun ColumnScope.StampedStage(r: ClockInResult, onStamp: () -> Unit, onClose: () -> Unit, proof: app.rise.clockin.ai.ProofScore?) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val t = Instant.ofEpochSecond(r.clockedAt).atZone(ZoneId.systemDefault())
    val first = r.pacts.firstOrNull()
    val day = first?.pact?.dayIndex(r.clockedAt) ?: 0
    val late = first?.me?.let { it.offsets.getOrNull(day)?.toInt()?.let { d -> d > 0 && d != Config.NOT_IN.toInt() } } ?: false
    val card = remember { Animatable(400f) }
    var stamped by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        card.animateTo(0f, spring(dampingRatio = 0.8f, stiffness = 300f))
        delay(150); stamped = true; onStamp()
    }
    Spacer(Modifier.height(20.dp))
    Text(if (r.rehearsal) "Rehearsal done" else "You made it", style = RiseType.title, color = Rise.Ivory)
    Spacer(Modifier.height(18.dp))
    Column(
        Modifier.fillMaxWidth().offset { IntOffset(0, card.value.roundToInt()) }.clip(CardShape).background(Rise.Manila).padding(horizontal = 20.dp, vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.height(150.dp), contentAlignment = Alignment.Center) {
            if (stamped) Stamp(
                t.format(DateTimeFormatter.ofPattern("h:mm:ss")),
                t.format(DateTimeFormatter.ofPattern("EEEE d MMMM")),
                late = late,
            )
        }
        Spacer(Modifier.height(18.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(Rise.CardRule))
        Spacer(Modifier.height(16.dp))
        if (r.rehearsal) {
            Text("No pact window is open right now, so nothing went on chain. On a pact morning this stamp is a Solana transaction.", style = RiseType.body, color = Rise.InkBlue)
        } else {
            val kept = r.pacts.sumOf { it.pact.stakePerDay }
            val streak = r.pacts.maxOf { it.me?.streak ?: 0 }
            Text("${skr(kept)} SKR kept for today", style = RiseType.heading, color = Rise.InkBlue)
            Spacer(Modifier.height(4.dp))
            Text(if (streak == 1) "First morning of your streak." else "$streak mornings in a row.", style = RiseType.body, color = Rise.InkBlue.copy(alpha = 0.8f))
            proof?.let { pr ->
                Spacer(Modifier.height(10.dp))
                Text("${pr.summary}. Proof confidence: ${pr.confidence.label}, recorded on chain.", style = RiseType.small, color = Rise.InkBlue.copy(alpha = 0.75f), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
            r.signature?.let { sig ->
                TextAction("See the proof on Solana", { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Config.explorerTx(sig)))) }, color = Rise.InkRed)
            }
        }
    }
    Spacer(Modifier.weight(1f))
    SunButton("Done", onClose, Modifier.fillMaxWidth().padding(bottom = 20.dp))
}

@Composable
private fun ColumnScope.FailedStage(error: String, retry: () -> Unit, onClose: () -> Unit) {
    Spacer(Modifier.weight(1f))
    Text("The clock-in didn't go through", style = RiseType.title, color = Rise.Ivory)
    Spacer(Modifier.height(12.dp))
    Notice(error, tone = Rise.InkRed)
    Spacer(Modifier.weight(1f))
    SunButton("Try again", retry, Modifier.fillMaxWidth())
    Spacer(Modifier.height(10.dp))
    GhostButton("Close", onClose, Modifier.fillMaxWidth().padding(bottom = 20.dp))
}
