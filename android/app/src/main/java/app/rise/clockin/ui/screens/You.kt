package app.rise.clockin.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.rise.clockin.LocalSender
import app.rise.clockin.alarm.AlarmScheduler
import app.rise.clockin.data.Mission
import app.rise.clockin.data.Store
import app.rise.clockin.solana.Config
import app.rise.clockin.solana.RiseProgram
import app.rise.clockin.solana.WalletKind
import app.rise.clockin.ui.BottomBarSpace
import app.rise.clockin.ui.Nav
import app.rise.clockin.ui.Perms
import app.rise.clockin.ui.Screen
import app.rise.clockin.ui.rememberResumeTick
import app.rise.clockin.ui.components.Avatar
import app.rise.clockin.ui.components.GhostButton
import app.rise.clockin.ui.components.Notice
import app.rise.clockin.ui.components.SkyPanel
import app.rise.clockin.ui.components.SunButton
import app.rise.clockin.ui.components.SunDial
import app.rise.clockin.ui.components.TextAction
import app.rise.clockin.ui.components.shortAddress
import app.rise.clockin.ui.components.skr
import app.rise.clockin.ui.sky.SkyBackground
import app.rise.clockin.ui.sky.dawnForTime
import app.rise.clockin.ui.theme.Rise
import app.rise.clockin.ui.theme.RiseType
import kotlinx.coroutines.launch

@Composable
fun YouTab(nav: Nav) {
    val state by Store.state.collectAsState()
    val profile by Store.profile.collectAsState()
    val context = LocalContext.current
    val sender = LocalSender.current
    val scope = rememberCoroutineScope()
    val now = rememberNow(30_000)
    val tick = rememberResumeTick()
    var busy by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    var editWake by remember { mutableStateOf(false) }
    var wakeDraft by remember(profile.wakeMinutes) { mutableStateOf(profile.wakeMinutes) }
    val needs = remember(profile.mission) { Perms.needsFor(profile.mission) }
    val missing = remember(tick, needs) { needs.filter { !Perms.granted(context, it) } }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    // While a pact is running, its wake window is fixed on chain; the alarm follows it.
    val locking = state.pacts.firstOrNull { !it.pact.isOver(now) && it.me != null }
    val totalKept = state.pacts.sumOf { it.me?.hits ?: 0 }
    val best = state.pacts.maxOfOrNull { it.me?.bestStreak ?: 0 } ?: 0

    fun run(label: String, block: suspend () -> Unit) {
        busy = label; message = null
        scope.launch {
            runCatching { block() }.onFailure { message = RiseProgram.explain(it) to false }
            busy = null
        }
    }

    SkyBackground(dawnForTime(), horizon = 1.3f, sunX = 0.15f) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).windowInsetsPadding(WindowInsets.statusBars).padding(horizontal = 20.dp),
        ) {
            Spacer(Modifier.height(18.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Avatar(profile.avatar, 64.dp)
                Spacer(Modifier.width(14.dp))
                Column {
                    Text(profile.name.ifBlank { "You" }, style = RiseType.title, color = Rise.Ivory)
                    if (profile.seekerMint != null) SeekerBadge()
                    else Text("$totalKept mornings kept, best streak $best", style = RiseType.small, color = Rise.Mist)
                }
            }

            // Wallet
            Spacer(Modifier.height(24.dp))
            SkyPanel(Modifier.fillMaxWidth()) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(state.walletLabel ?: "Wallet", style = RiseType.bodyStrong, color = Rise.Ivory)
                            Text(shortAddress(state.address ?: ""), style = RiseType.small, color = Rise.Mist)
                        }
                        TextAction("Copy", {
                            context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Wallet address", state.address))
                            message = "Address copied." to true
                        })
                    }
                    Spacer(Modifier.height(14.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                        Column { Text(skr(state.skr), style = RiseType.amount, color = Rise.Sun); Text("test SKR", style = RiseType.tiny, color = Rise.Mist) }
                        Column { Text("%.3f".format(state.sol / 1e9), style = RiseType.amount, color = Rise.Ivory); Text("devnet SOL", style = RiseType.tiny, color = Rise.Mist) }
                    }
                    Spacer(Modifier.height(14.dp))
                    val dripWait = Store.nextDripInMinutes()
                    if (dripWait > 0 && busy != "drip") {
                        GhostButton("Next 500 test SKR in $dripWait min", {}, Modifier.fillMaxWidth(), enabled = false)
                    } else {
                        SunButton("Get 500 test SKR", busy = busy == "drip", busyText = "Sending test SKR", onClick = {
                            run("drip") { Store.getTestTokens(sender); message = "500 test SKR are in your wallet." to true }
                        }, modifier = Modifier.fillMaxWidth())
                    }
                    if (state.kind == WalletKind.Phone && profile.seekerMint == null) {
                        Spacer(Modifier.height(10.dp))
                        GhostButton(if (busy == "seeker") "Checking your Seeker…" else "Verify my Seeker", {
                            val s = sender ?: return@GhostButton
                            run("seeker") {
                                val mint = Store.verifySeeker(s)
                                message = if (mint != null) "Seeker verified. Your Genesis Token is on your profile." to true
                                else "This wallet doesn't hold a Seeker Genesis Token. Use the wallet on your Seeker's Seed Vault." to false
                            }
                        }, Modifier.fillMaxWidth(), enabled = busy == null)
                    }
                }
            }
            message?.let { (m, ok) -> Spacer(Modifier.height(10.dp)); Notice(m, tone = if (ok) Rise.Moss else Rise.Rose) }

            // Alarm
            Spacer(Modifier.height(26.dp))
            Text("Alarm", style = RiseType.heading, color = Rise.Ivory)
            Spacer(Modifier.height(10.dp))
            SettingRow("Alarm on", "Rings every morning at your wake time.") {
                Switch(profile.alarmOn, { on -> Store.updateProfile { it.copy(alarmOn = on) } }, colors = SwitchDefaults.colors(checkedTrackColor = Rise.Sun, checkedThumbColor = Rise.Night))
            }
            SettingRow("Wake time", if (locking != null) "Locked by ${locking.pact.name} until it ends." else "Drag the sun to change it.") {
                if (locking == null) TextAction(if (editWake) "Done" else "Change", {
                    if (editWake) Store.updateProfile { it.copy(wakeMinutes = wakeDraft) }
                    editWake = !editWake
                })
            }
            if (editWake && locking == null) SunDial(wakeDraft, { wakeDraft = it }, Modifier.fillMaxWidth())
            SettingRow("Sunrise light", "The screen starts glowing ${profile.sunriseLead} min before the alarm, like a sunrise lamp. Rise Coach starts it earlier on risky mornings.") {
                Chips(listOf(0, 5, 10, 15), profile.sunriseLead, { "$it" }) { v -> Store.updateProfile { it.copy(sunriseLead = v) } }
            }
            SettingRow("Speak the stakes", "Reads out who's up and what's on the line.") {
                Switch(profile.voice, { on -> Store.updateProfile { it.copy(voice = on) } }, colors = SwitchDefaults.colors(checkedTrackColor = Rise.Sun, checkedThumbColor = Rise.Night))
            }
            Spacer(Modifier.height(10.dp))
            Text("Mission", style = RiseType.bodyStrong, color = Rise.Ivory)
            Spacer(Modifier.height(8.dp))
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Mission.entries.forEach { m -> MissionOption(m, m == profile.mission) { Store.updateProfile { it.copy(mission = m) } } }
            }
            if (profile.mission == Mission.Spot) TextAction("Show my wake spot code", { nav.push(Screen.WakeSpot) })
            if (missing.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                Text("Rise can't fully wake you yet", style = RiseType.bodyStrong, color = Rise.Rose)
                Spacer(Modifier.height(8.dp))
                missing.forEach { need ->
                    PermissionRow(need, false) {
                        val rt = Perms.runtime(need)
                        if (rt != null) permLauncher.launch(rt) else runCatching { context.startActivity(Perms.settingsIntent(context, need)) }
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }
            Spacer(Modifier.height(14.dp))
            GhostButton("Rehearse the alarm now", { AlarmScheduler.rehearse(context) }, Modifier.fillMaxWidth())

            // About
            Spacer(Modifier.height(26.dp))
            Text("How Rise works", style = RiseType.heading, color = Rise.Ivory)
            Spacer(Modifier.height(6.dp))
            Text(
                "Every pact morning is a Solana transaction. The Rise program checks the network clock: you count only inside your window, from 30 minutes before your wake time until the grace period ends. Missed mornings fill the pot, and the pot is split by mornings kept when the pact ends. No one, including us, can move the SKR in a pact.",
                style = RiseType.small, color = Rise.Ivory.copy(alpha = 0.78f),
            )
            TextAction("Rise program on Solana Explorer", { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Config.explorerAddress(Config.PROGRAM.toBase58())))) })
            Spacer(Modifier.height(10.dp))
            var confirmSignOut by remember { mutableStateOf(false) }
            GhostButton("Sign out", { confirmSignOut = true }, Modifier.fillMaxWidth())
            if (confirmSignOut) {
                androidx.compose.material3.AlertDialog(
                    onDismissRequest = { confirmSignOut = false },
                    containerColor = Rise.NightDeep,
                    title = { Text("Sign out of Rise?", style = RiseType.heading, color = Rise.Ivory) },
                    text = {
                        Text(
                            if (state.kind == WalletKind.Practice)
                                "This erases the practice wallet on this phone. Any SKR it has locked in pacts can't be collected without it."
                            else "Your wallet keeps your keys. Rise only forgets this phone's profile and alarm.",
                            style = RiseType.body, color = Rise.Ivory.copy(alpha = 0.85f),
                        )
                    },
                    confirmButton = { TextAction(if (state.kind == WalletKind.Practice) "Erase and sign out" else "Sign out", { confirmSignOut = false; Store.signOut() }, color = Rise.Rose) },
                    dismissButton = { TextAction("Keep it", { confirmSignOut = false }, color = Rise.Ivory) },
                )
            }
            BottomBarSpace()
        }
    }
}

@Composable
private fun SettingRow(title: String, line: String, trailing: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = RiseType.bodyStrong, color = Rise.Ivory)
                Text(line, style = RiseType.small, color = Rise.Mist)
            }
            if (title != "Sunrise light") trailing()
        }
        if (title == "Sunrise light") { Spacer(Modifier.height(10.dp)); trailing() }
    }
}

@Composable
fun SeekerBadge() {
    Row(
        Modifier.padding(top = 4.dp).clip(RoundedCornerShape(12.dp)).background(Rise.Moss.copy(alpha = 0.18f)).padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).clip(RoundedCornerShape(4.dp)).background(Rise.Moss))
        Spacer(Modifier.width(6.dp))
        Text("Verified Seeker", style = RiseType.small, color = Rise.Moss)
    }
}
