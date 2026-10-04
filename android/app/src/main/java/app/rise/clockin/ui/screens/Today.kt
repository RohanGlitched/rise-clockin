package app.rise.clockin.ui.screens

import android.content.Intent
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.rise.clockin.AlarmActivity
import app.rise.clockin.alarm.AlarmScheduler
import app.rise.clockin.data.PactView
import app.rise.clockin.data.Store
import app.rise.clockin.ui.Nav
import app.rise.clockin.ui.Screen
import app.rise.clockin.ui.Tab
import app.rise.clockin.ui.BottomBarSpace
import app.rise.clockin.ui.card.CardShape
import app.rise.clockin.ui.card.TimeCard
import app.rise.clockin.ui.card.localTime
import app.rise.clockin.ui.components.Avatar
import app.rise.clockin.ui.components.GhostButton
import app.rise.clockin.ui.components.Notice
import app.rise.clockin.ui.components.SkyPanel
import app.rise.clockin.ui.components.SunButton
import app.rise.clockin.ui.components.amPm
import app.rise.clockin.ui.components.formatMinutes
import app.rise.clockin.ui.components.skr
import app.rise.clockin.ui.sky.SkyBackground
import app.rise.clockin.ui.sky.dawnForTime
import app.rise.clockin.ui.theme.Rise
import app.rise.clockin.ui.theme.RiseType
import kotlinx.coroutines.delay

/** Current time in epoch seconds, ticking. */
@Composable
fun rememberNow(periodMs: Long = 1000): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis() / 1000) }
    LaunchedEffect(periodMs) { while (true) { now = System.currentTimeMillis() / 1000; delay(periodMs) } }
    return now
}

fun untilText(seconds: Long): String {
    val h = seconds / 3600; val m = (seconds % 3600) / 60
    return when {
        seconds < 60 -> "less than a minute"
        h == 0L -> "$m min"
        else -> "$h h $m min"
    }
}

@Composable
fun TodayTab(nav: Nav) {
    val state by Store.state.collectAsState()
    val profile by Store.profile.collectAsState()
    val now = rememberNow()
    val context = androidx.compose.ui.platform.LocalContext.current
    val active = state.pacts.filter { !it.pact.isOver(now) }
    val open = state.pacts.filter { v -> v.me?.openDay(v.pact, now) != null }
    // Lead with the pact whose window is open, else the one with the most people in it.
    val primary = open.firstOrNull() ?: active.maxByOrNull { it.members.size } ?: state.pacts.firstOrNull()
    val nextWake = AlarmScheduler.nextWake(profile) / 1000
    val keptToday = active.any { v -> v.me?.let { me -> val d = v.pact.dayIndex(now); d >= me.firstDay && me.isIn(d) } == true }
    val nextIsToday = java.time.Instant.ofEpochSecond(nextWake).atZone(java.time.ZoneId.systemDefault()).toLocalDate() == java.time.LocalDate.now()
    val whenText = if (nextWake - now > 12 * 3600) "tomorrow at ${localTime(nextWake)}" else "in ${untilText(nextWake - now)}"
    val coachDay = if (nextIsToday) "today" else "tomorrow"
    val streak = state.pacts.maxOfOrNull { it.me?.streak ?: 0 } ?: 0
    val finishedUnclaimed = state.pacts.filter { it.pact.isOver(now) && it.me?.claimed == false }
    val coach by Store.coach.collectAsState()

    SkyBackground(dawnForTime(), horizon = 0.97f, sunX = 0.78f) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .windowInsetsPadding(WindowInsets.statusBars).padding(horizontal = 20.dp),
        ) {
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(12.dp).clip(RoundedCornerShape(6.dp)).background(Rise.Sun))
                Spacer(Modifier.width(8.dp))
                Text("Rise", style = RiseType.heading, color = Rise.Ivory)
                Spacer(Modifier.weight(1f))
                Box(
                    Modifier.size(48.dp).clip(RoundedCornerShape(24.dp)).clickable(role = Role.Button) { nav.tab.value = Tab.You }
                        .semantics { contentDescription = "Your profile" },
                    contentAlignment = Alignment.Center,
                ) { Avatar(profile.avatar, 38.dp) }
            }

            // The next alarm, as big as a bedside clock.
            Spacer(Modifier.height(26.dp))
            Text(if (profile.alarmOn) "Next alarm" else "Alarm off", style = RiseType.small, color = Rise.Ivory.copy(alpha = 0.75f))
            Row(verticalAlignment = Alignment.Bottom) {
                app.rise.clockin.ui.components.ClockText(formatMinutes(profile.wakeMinutes), RiseType.clockHuge, if (profile.alarmOn) Rise.Ivory else Rise.Ivory.copy(alpha = 0.4f))
                Spacer(Modifier.width(6.dp))
                Text(amPm(profile.wakeMinutes), style = RiseType.clockMid, color = Rise.Sun, modifier = Modifier.padding(bottom = 18.dp))
            }
            if (profile.alarmOn) {
                Text(
                    if (keptToday && open.isEmpty()) "Today is kept. Next alarm $whenText." else "Rings $whenText. ${profile.mission.title} to stop it.",
                    style = RiseType.body, color = Rise.Ivory.copy(alpha = 0.85f),
                )
            }
            if (streak > 0) {
                Spacer(Modifier.height(14.dp))
                StreakPill(streak)
            }
            coach?.let { c ->
                Spacer(Modifier.height(18.dp))
                CoachPanel(c, profile.mission, coachDay)
            }

            // Window open right now: the most important thing on the screen.
            if (open.isNotEmpty()) {
                Spacer(Modifier.height(20.dp))
                OpenWindowPanel(open, now) {
                    context.startActivity(Intent(context, AlarmActivity::class.java).putExtra(AlarmActivity.EXTRA_DIRECT, true))
                }
            }
            finishedUnclaimed.firstOrNull()?.let { v ->
                Spacer(Modifier.height(16.dp))
                SkyPanel(Modifier.fillMaxWidth()) {
                    Column {
                        Text("${v.pact.name} is over", style = RiseType.heading, color = Rise.Ivory)
                        Text("Your payout is ${skr(v.me!!.projectedPayout(v.pact))} SKR.", style = RiseType.body, color = Rise.Ivory.copy(alpha = 0.85f))
                        Spacer(Modifier.height(12.dp))
                        SunButton("Collect payout", { nav.push(Screen.Pact(v.pact.address)) })
                    }
                }
            }

            Spacer(Modifier.height(28.dp))
            when {
                !state.loadedOnce -> CardSkeleton()
                primary != null -> {
                    Box(Modifier.clickable(role = Role.Button) { nav.push(Screen.Pact(primary.pact.address)) }) {
                        TimeCard(primary, now, Modifier.fillMaxWidth())
                    }
                    if (state.pacts.size > 1) {
                        Spacer(Modifier.height(10.dp))
                        Text("You're in ${state.pacts.size} pacts. See them all in Pacts.", style = RiseType.small, color = Rise.Ivory.copy(alpha = 0.7f))
                    }
                }
                else -> NoPactYet(nav, state.discover.firstOrNull())
            }
            state.error?.let { Spacer(Modifier.height(14.dp)); Notice(it) }

            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GhostButton("Rehearse the alarm", { AlarmScheduler.rehearse(context) }, Modifier.weight(1f))
                if (primary != null) GhostButton("Invite", { nav.push(Screen.Pact(primary.pact.address, justStarted = true)) })
            }
            BottomBarSpace()
        }
    }
}

@Composable
fun StreakPill(streak: Int) {
    Row(
        Modifier.clip(RoundedCornerShape(20.dp)).background(Rise.Sun.copy(alpha = 0.16f)).padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("$streak", style = RiseType.bodyStrong, color = Rise.Sun)
        Spacer(Modifier.width(8.dp))
        Text(if (streak == 1) "morning in a row" else "mornings in a row", style = RiseType.small, color = Rise.Ivory)
    }
}

@Composable
private fun OpenWindowPanel(open: List<PactView>, now: Long, onClockIn: () -> Unit) {
    val first = open.first()
    val day = first.me!!.openDay(first.pact, now)!!
    val closes = first.me.windowCloses(first.pact, day)
    val stake = open.sumOf { it.pact.stakePerDay }
    val pulse = rememberInfiniteTransition(label = "pulse").animateFloat(0.55f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "a")
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(Rise.Sun).padding(20.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).alpha(pulse.value).clip(RoundedCornerShape(5.dp)).background(Rise.InkRed))
            Spacer(Modifier.width(8.dp))
            Text("Your wake window is open", style = RiseType.bodyStrong, color = Rise.Night)
        }
        Spacer(Modifier.height(6.dp))
        Text("Clock in before ${localTime(closes)} to keep your ${skr(stake)} SKR.", style = RiseType.body, color = Rise.Night)
        Spacer(Modifier.height(14.dp))
        Box(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(Rise.Night).clickable(role = Role.Button, onClick = onClockIn).padding(vertical = 17.dp),
            contentAlignment = Alignment.Center,
        ) { Text("Clock in now", style = RiseType.button, color = Rise.Sun) }
    }
}

@Composable
private fun NoPactYet(nav: Nav, suggestion: PactView?) {
    SkyPanel(Modifier.fillMaxWidth()) {
        Column {
            Text("Your alarm is set, but nothing's at stake yet.", style = RiseType.heading, color = Rise.Ivory)
            Spacer(Modifier.height(6.dp))
            Text("Start a pact with friends, or join one that's open. Every morning you clock in on time earns your stake back.", style = RiseType.body, color = Rise.Ivory.copy(alpha = 0.8f))
            Spacer(Modifier.height(16.dp))
            SunButton("Start a pact", { nav.push(Screen.NewPact) }, Modifier.fillMaxWidth())
            suggestion?.let { s ->
                Spacer(Modifier.height(10.dp))
                GhostButton("Join ${s.pact.name} (${s.pact.memberCount} in)", { nav.push(Screen.Join(s.pact.address)) }, Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
fun CardSkeleton() {
    val a = rememberInfiniteTransition(label = "sk").animateFloat(0.25f, 0.45f, infiniteRepeatable(tween(800), RepeatMode.Reverse), label = "a")
    Box(Modifier.fillMaxWidth().height(330.dp).alpha(a.value).clip(CardShape).background(Rise.Manila).semantics { contentDescription = "Loading your time card" })
}


/** Rise Coach: tomorrow's predicted risk, why, and the difficulty it chose. */
@Composable
fun CoachPanel(c: app.rise.clockin.ai.CoachReport, mission: app.rise.clockin.data.Mission? = null, day: String = "tomorrow") {
    val Day = day.replaceFirstChar { it.uppercase() }
    var open by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    val pct = (c.risk * 100).toInt()
    val tone = when (c.difficulty) {
        app.rise.clockin.ai.Difficulty.Tough -> Rise.Rose
        app.rise.clockin.ai.Difficulty.Gentle -> Rise.Moss
        else -> Rise.Sun
    }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(Rise.NightDeep.copy(alpha = 0.5f))
            .clickable(role = Role.Button) { open = !open }.padding(18.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Rise Coach", style = RiseType.bodyStrong, color = Rise.Sun)
            Spacer(Modifier.weight(1f))
            Box(Modifier.clip(RoundedCornerShape(12.dp)).background(tone.copy(alpha = 0.2f)).padding(horizontal = 10.dp, vertical = 4.dp)) {
                Text("$Day: ${c.difficulty.label}", style = RiseType.small, color = tone)
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text("$pct%", style = RiseType.amount.copy(fontSize = androidx.compose.ui.unit.TextUnit(34f, androidx.compose.ui.unit.TextUnitType.Sp)), color = Rise.Ivory)
            Spacer(Modifier.width(8.dp))
            Text("chance you oversleep $day", style = RiseType.small, color = Rise.Mist, modifier = Modifier.padding(bottom = 6.dp))
        }
        Spacer(Modifier.height(6.dp))
        Text(c.headline.replace("Tomorrow", Day).replace("tomorrow", day), style = RiseType.body, color = Rise.Ivory.copy(alpha = 0.9f))
        if (open) {
            Spacer(Modifier.height(10.dp))
            c.factors.forEach { f ->
                Text("${if (f.weight > 0) "Raises" else "Lowers"} risk: ${f.text.replace("Tomorrow", Day).replace("tomorrow", day)}", style = RiseType.small, color = Rise.Mist)
            }
            Spacer(Modifier.height(6.dp))
            val target = when (mission?.id) {
                1 -> "${c.difficulty.steps} steps"
                0 -> "${c.difficulty.lux} lux of daylight"
                3 -> "a ${(c.difficulty.visionConfidence * 100).toInt()}% morning-scene match"
                2 -> "your wake-spot code"
                else -> "${c.difficulty.steps} steps, ${c.difficulty.lux} lux or a ${(c.difficulty.visionConfidence * 100).toInt()}% vision match"
            }
            Text(
                "$Day's mission: $target. Sunrise starts ${c.difficulty.sunriseLead} min early.",
                style = RiseType.small, color = Rise.Ivory.copy(alpha = 0.8f),
            )
            Spacer(Modifier.height(6.dp))
            Text(
                if (c.personalMornings == 0) "Learning from the ${c.trainedOn} mornings on your pacts' time cards until it has mornings of yours. Nothing leaves the device."
                else "Trained on this phone from ${c.trainedOn} mornings on your pacts' time cards, ${c.personalMornings} of them yours. Nothing leaves the device.",
                style = RiseType.tiny, color = Rise.Mist,
            )
        } else {
            Spacer(Modifier.height(6.dp))
            Text("Tap to see why", style = RiseType.tiny, color = Rise.Mist)
        }
    }
}
