package app.rise.clockin.ui.screens

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import app.rise.clockin.LocalSender
import app.rise.clockin.data.Mission
import app.rise.clockin.data.Store
import app.rise.clockin.solana.RiseProgram
import app.rise.clockin.solana.WalletKind
import app.rise.clockin.ui.Need
import app.rise.clockin.ui.Perms
import app.rise.clockin.ui.rememberResumeTick
import app.rise.clockin.ui.components.Avatar
import app.rise.clockin.ui.components.AvatarColors
import app.rise.clockin.ui.components.GhostButton
import app.rise.clockin.ui.components.Notice
import app.rise.clockin.ui.components.SunButton
import app.rise.clockin.ui.components.SunDial
import app.rise.clockin.ui.components.TextAction
import app.rise.clockin.ui.components.skr
import app.rise.clockin.ui.sky.SkyBackground
import app.rise.clockin.ui.theme.Rise
import app.rise.clockin.ui.theme.RiseType
import kotlinx.coroutines.launch

private enum class Step { Welcome, Name, Wake, Mission, Ready }

@Composable
fun OnboardingScreen(invite: String?) {
    val state by Store.state.collectAsState()
    val profile by Store.profile.collectAsState()
    var step by rememberSaveable { mutableStateOf(if (state.address == null) Step.Welcome else Step.Name) }
    // The page-load moment: the sun comes up once, behind the first screen.
    val dawn = remember { Animatable(0.02f) }
    LaunchedEffect(Unit) { dawn.animateTo(0.62f, tween(2600, easing = FastOutSlowInEasing)) }
    val stepDawn = when (step) { Step.Welcome -> dawn.value; Step.Name -> 0.6f; Step.Wake -> 0.63f; Step.Mission -> 0.66f; Step.Ready -> 0.7f }

    BackHandler(enabled = step != Step.Welcome && step != Step.Name) { step = Step.entries[step.ordinal - 1] }

    SkyBackground(stepDawn, sunX = 0.5f, horizon = if (step == Step.Welcome) 0.80f else 0.94f) {
        AnimatedContent(step, transitionSpec = { (slideInHorizontally(tween(320)) { it / 4 } + fadeIn(tween(260))) togetherWith fadeOut(tween(160)) }, label = "step") { s ->
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).imePadding().padding(horizontal = 24.dp)) {
                when (s) {
                    Step.Welcome -> Welcome(invite != null) { step = Step.Name }
                    Step.Name -> NameStep(profile.name, profile.avatar) { step = Step.Wake }
                    Step.Wake -> WakeStep(profile.wakeMinutes) { step = Step.Mission }
                    Step.Mission -> MissionStep(profile.mission) { step = Step.Ready }
                    Step.Ready -> ReadyStep(invite)
                }
            }
        }
    }
}

@Composable
private fun ColumnScope.Welcome(hasInvite: Boolean, next: () -> Unit) {
    val sender = LocalSender.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    Spacer(Modifier.height(36.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(14.dp).clip(RoundedCornerShape(7.dp)).background(Rise.Sun))
        Spacer(Modifier.width(10.dp))
        Text("Rise", style = RiseType.heading, color = Rise.Ivory)
    }
    Spacer(Modifier.weight(0.6f))
    Text("The alarm your friends are betting on.", style = RiseType.display, color = Rise.Ivory)
    Spacer(Modifier.height(16.dp))
    Text(
        "Wake up and clock in on Solana before your window closes. Sleep in, and your SKR is split among the friends who made it.",
        style = RiseType.body, color = Rise.Ivory.copy(alpha = 0.82f),
    )
    if (hasInvite) {
        Spacer(Modifier.height(14.dp))
        Notice("A friend invited you to their pact. Set up Rise and you'll land on it.", tone = Rise.Sun)
    }
    Spacer(Modifier.weight(1f))
    error?.let { Notice(it); Spacer(Modifier.height(12.dp)) }
    SunButton(
        "Connect wallet", busy = busy, busyText = "Opening your wallet",
        onClick = {
            val s = sender ?: return@SunButton
            busy = true; error = null
            scope.launch {
                runCatching { Store.connectPhoneWallet(s) }.onSuccess { next() }.onFailure { error = it.message }
                busy = false
            }
        },
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(10.dp))
    GhostButton("Try it with a practice wallet", { Store.usePracticeWallet(); next() }, Modifier.fillMaxWidth())
    Spacer(Modifier.height(10.dp))
    Text(
        "Connect uses Seed Vault on Seeker, or Phantom and Solflare. Rise runs on Solana devnet with test SKR, so nothing here costs real money.",
        style = RiseType.tiny, color = Rise.Mist, modifier = Modifier.padding(bottom = 12.dp),
    )
}

@Composable
private fun StepHeader(index: Int, title: String, line: String) {
    Spacer(Modifier.height(28.dp))
    Text("Step $index of 4", style = RiseType.small, color = Rise.Sun)
    Spacer(Modifier.height(8.dp))
    Text(title, style = RiseType.title, color = Rise.Ivory)
    Spacer(Modifier.height(8.dp))
    Text(line, style = RiseType.body, color = Rise.Ivory.copy(alpha = 0.8f))
}

@Composable
private fun ColumnScope.NameStep(initialName: String, initialAvatar: Int, next: () -> Unit) {
    var name by rememberSaveable { mutableStateOf(initialName) }
    var avatar by rememberSaveable { mutableIntStateOf(initialAvatar) }
    val focus = LocalFocusManager.current
    val valid = name.trim().length in 1..20
    StepHeader(1, "What should your friends call you?", "Your name and sign go on the pact's time card, next to every morning you clock in.")
    Spacer(Modifier.height(24.dp))
    BasicTextField(
        value = name,
        onValueChange = { name = it.take(20) },
        textStyle = RiseType.title.copy(color = Rise.Ivory),
        cursorBrush = SolidColor(Rise.Sun),
        singleLine = true,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }),
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Your name" },
        decorationBox = { inner ->
            Column {
                Box { if (name.isEmpty()) Text("Your first name", style = RiseType.title, color = Rise.Ivory.copy(alpha = 0.3f)); inner() }
                Spacer(Modifier.height(8.dp))
                Box(Modifier.fillMaxWidth().height(2.dp).background(if (valid) Rise.Sun else Rise.Ivory.copy(alpha = 0.3f)))
            }
        },
    )
    Spacer(Modifier.height(28.dp))
    Text("Pick your sign", style = RiseType.bodyStrong, color = Rise.Ivory)
    Spacer(Modifier.height(12.dp))
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        AvatarColors.indices.chunked(4).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                row.forEach { i ->
                    Box(
                        Modifier.size(62.dp).clip(RoundedCornerShape(31.dp))
                            .semantics { selected = avatar == i }
                            .clickable(role = Role.RadioButton) { avatar = i }
                            .then(if (avatar == i) Modifier.border(3.dp, Rise.Sun, RoundedCornerShape(31.dp)) else Modifier)
                            .padding(5.dp),
                    ) { Avatar(i, 52.dp) }
                }
            }
        }
    }
    Spacer(Modifier.weight(1f))
    SunButton("Continue", {
        Store.updateProfile { it.copy(name = name.trim(), avatar = avatar) }
        next()
    }, Modifier.fillMaxWidth().padding(bottom = 16.dp), enabled = valid)
}

@Composable
private fun ColumnScope.WakeStep(initial: Int, next: () -> Unit) {
    var minutes by rememberSaveable { mutableIntStateOf(initial) }
    Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
        StepHeader(2, "When do you want to be up?", "Drag the sun to your wake time. Your window opens 30 minutes before it and closes 10 minutes after.")
        Spacer(Modifier.height(12.dp))
        SunDial(minutes, { minutes = it }, Modifier.fillMaxWidth())
    }
    SunButton("Set my wake time", {
        Store.updateProfile { it.copy(wakeMinutes = minutes) }
        next()
    }, Modifier.fillMaxWidth().padding(vertical = 16.dp))
}

@Composable
private fun ColumnScope.MissionStep(initial: Mission, next: () -> Unit) {
    var mission by rememberSaveable { mutableStateOf(initial) }
    Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
        StepHeader(3, "How will you prove you're up?", "The alarm only stops when you finish a mission. Pick the one that gets you out of bed.")
        Spacer(Modifier.height(20.dp))
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Mission.entries.forEach { m -> MissionOption(m, m == mission) { mission = m } }
        }
    }
    SunButton("Continue with ${mission.title}", {
        Store.updateProfile { it.copy(mission = mission) }
        next()
    }, Modifier.fillMaxWidth().padding(vertical = 16.dp))
}

@Composable
fun MissionOption(m: Mission, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))
            .background(if (selected) Rise.Sun.copy(alpha = 0.16f) else Rise.NightDeep.copy(alpha = 0.4f))
            .border(if (selected) 2.dp else 1.dp, if (selected) Rise.Sun else Rise.Ivory.copy(alpha = 0.12f), RoundedCornerShape(20.dp))
            .semantics { this.selected = selected }
            .clickable(role = Role.RadioButton, onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MissionGlyph(m, Modifier.size(52.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(m.title, style = RiseType.heading, color = Rise.Ivory)
            Spacer(Modifier.height(2.dp))
            Text(m.line, style = RiseType.small, color = Rise.Ivory.copy(alpha = 0.72f))
        }
    }
}

@Composable
private fun ColumnScope.ReadyStep(invite: String?) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val state by Store.state.collectAsState()
    val profile by Store.profile.collectAsState()
    val sender = LocalSender.current
    val scope = rememberCoroutineScope()
    val tick = rememberResumeTick()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val needs = remember(profile.mission) { Perms.needsFor(profile.mission) }
    val granted = remember(tick, needs) { needs.associateWith { Perms.granted(context, it) } }
    var lastAsked by remember { mutableStateOf<Need?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { lastAsked = null }
    LaunchedEffect(Unit) { Store.refresh() }

    Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
        StepHeader(4, "Let Rise ring, then grab some test SKR.", "Android needs your OK to wake you. Each pact morning is backed by SKR, so you'll need a few to join.")
        Spacer(Modifier.height(20.dp))
        needs.forEach { need ->
            PermissionRow(need, granted[need] == true) {
                val rt = Perms.runtime(need)
                if (rt != null) { lastAsked = need; launcher.launch(rt) } else runCatching { context.startActivity(Perms.settingsIntent(context, need)) }
            }
            Spacer(Modifier.height(10.dp))
        }
        Spacer(Modifier.height(10.dp))
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Rise.NightDeep.copy(alpha = 0.45f)).padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("${skr(state.skr)} SKR", style = RiseType.amount, color = if (state.skr > 0) Rise.Sun else Rise.Ivory)
                Text(if (state.kind == WalletKind.Practice) "In your practice wallet" else "In ${state.walletLabel ?: "your wallet"}", style = RiseType.tiny, color = Rise.Mist)
            }
            if (state.skr == 0L) {
                SunButton("Get 500 test SKR", busy = busy, busyText = "Sending", onClick = {
                    busy = true; error = null
                    scope.launch {
                        runCatching { Store.getTestTokens(sender) }.onFailure { error = RiseProgram.explain(it) }
                        busy = false
                    }
                })
            } else {
                Text("Ready", style = RiseType.bodyStrong, color = Rise.Moss)
            }
        }
        error?.let { Spacer(Modifier.height(10.dp)); Notice(it) }
    }
    val allGranted = granted.values.all { it }
    if (!allGranted) Text("You can finish without these, but the alarm may not wake you.", style = RiseType.tiny, color = Rise.Mist, modifier = Modifier.padding(top = 8.dp))
    SunButton(if (invite != null) "Open my invite" else "Start rising", {
        Store.updateProfile { it.copy(onboarded = true) }
    }, Modifier.fillMaxWidth().padding(vertical = 16.dp))
}

@Composable
fun PermissionRow(need: Need, granted: Boolean, onGrant: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Rise.NightDeep.copy(alpha = 0.4f)).padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(10.dp).clip(RoundedCornerShape(5.dp)).background(if (granted) Rise.Moss else Rise.Rose))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(need.title, style = RiseType.bodyStrong, color = Rise.Ivory)
            Text(need.why, style = RiseType.tiny, color = Rise.Mist)
        }
        if (granted) Text("Allowed", style = RiseType.small, color = Rise.Moss, modifier = Modifier.padding(end = 10.dp))
        else TextAction("Allow", onGrant)
    }
}

fun Context.ignore() = Unit
