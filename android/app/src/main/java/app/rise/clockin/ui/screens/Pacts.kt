package app.rise.clockin.ui.screens

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import app.rise.clockin.LocalSender
import app.rise.clockin.data.PactView
import app.rise.clockin.data.Store
import app.rise.clockin.solana.Config
import app.rise.clockin.solana.RiseProgram
import app.rise.clockin.ui.BottomBarSpace
import app.rise.clockin.ui.Nav
import app.rise.clockin.ui.Screen
import app.rise.clockin.ui.card.TimeCard
import app.rise.clockin.ui.card.cardSubtitle
import app.rise.clockin.ui.card.localTime
import app.rise.clockin.ui.components.Avatar
import app.rise.clockin.ui.components.GhostButton
import app.rise.clockin.ui.components.LabeledValue
import app.rise.clockin.ui.components.Notice
import app.rise.clockin.ui.components.SkyPanel
import app.rise.clockin.ui.components.SunButton
import app.rise.clockin.ui.components.TextAction
import app.rise.clockin.ui.components.amPm
import app.rise.clockin.ui.components.formatMinutes
import app.rise.clockin.ui.components.skr
import app.rise.clockin.ui.sky.SkyBackground
import app.rise.clockin.ui.sky.dawnForTime
import app.rise.clockin.ui.theme.Rise
import app.rise.clockin.ui.theme.RiseType
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.launch

const val SITE = "https://rise-clockin.vercel.app"
fun inviteUrl(pact: String) = "$SITE/join/$pact"

// ------------------------------------------------------------------ shared chrome

@Composable
fun DetailScaffold(title: String, nav: Nav, content: @Composable ColumnScope.() -> Unit) {
    SkyBackground(dawnForTime(), horizon = 1.3f, sunX = 0.85f) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars)) {
            Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(48.dp).clip(RoundedCornerShape(24.dp)).clickable(role = Role.Button) { nav.pop() }.semantics { contentDescription = "Back" },
                    contentAlignment = Alignment.Center,
                ) {
                    androidx.compose.foundation.Canvas(Modifier.size(18.dp)) {
                        val w = size.width
                        val cap = androidx.compose.ui.graphics.StrokeCap.Round
                        drawLine(Rise.Ivory, androidx.compose.ui.geometry.Offset(w * 0.68f, w * 0.12f), androidx.compose.ui.geometry.Offset(w * 0.3f, w * 0.5f), strokeWidth = w * 0.13f, cap = cap)
                        drawLine(Rise.Ivory, androidx.compose.ui.geometry.Offset(w * 0.3f, w * 0.5f), androidx.compose.ui.geometry.Offset(w * 0.68f, w * 0.88f), strokeWidth = w * 0.13f, cap = cap)
                    }
                }
                Text(title, style = RiseType.heading, color = Rise.Ivory)
            }
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(horizontal = 20.dp),
                content = {
                    content()
                    Spacer(Modifier.height(24.dp))
                    Spacer(Modifier.windowInsetsPadding(WindowInsets.navigationBars))
                },
            )
        }
    }
}

// ------------------------------------------------------------------ Pacts tab

@Composable
fun PactsTab(nav: Nav) {
    val state by Store.state.collectAsState()
    val now = rememberNow(15_000)
    SkyBackground(dawnForTime(), horizon = 1.3f, sunX = 0.2f) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).windowInsetsPadding(WindowInsets.statusBars).padding(horizontal = 20.dp),
        ) {
            Spacer(Modifier.height(18.dp))
            Text("Pacts", style = RiseType.display, color = Rise.Ivory)
            Spacer(Modifier.height(6.dp))
            Text("Each pact is a time card you share. Keep your mornings, and the pot is yours to split.", style = RiseType.body, color = Rise.Ivory.copy(alpha = 0.8f))
            Spacer(Modifier.height(18.dp))
            SunButton("Start a pact", { nav.push(Screen.NewPact) }, Modifier.fillMaxWidth())
            Spacer(Modifier.height(26.dp))
            if (!state.loadedOnce) CardSkeleton()
            if (state.pacts.isNotEmpty()) {
                Text("Yours", style = RiseType.heading, color = Rise.Ivory)
                Spacer(Modifier.height(10.dp))
                state.pacts.forEach { v -> PactRow(v, now) { nav.push(Screen.Pact(v.pact.address)) }; Spacer(Modifier.height(10.dp)) }
                Spacer(Modifier.height(16.dp))
            } else if (state.loadedOnce) {
                Notice("You're not in a pact yet. Start one and send the invite, or join an open one below.", tone = Rise.Sun)
                Spacer(Modifier.height(20.dp))
            }
            if (state.discover.isNotEmpty()) {
                Text("Open to join", style = RiseType.heading, color = Rise.Ivory)
                Spacer(Modifier.height(4.dp))
                Text("Public pacts anyone can join until their last morning.", style = RiseType.small, color = Rise.Mist)
                Spacer(Modifier.height(10.dp))
                state.discover.forEach { v -> PactRow(v, now) { nav.push(Screen.Join(v.pact.address)) }; Spacer(Modifier.height(10.dp)) }
            }
            state.error?.let { Spacer(Modifier.height(8.dp)); Notice(it) }
            BottomBarSpace()
        }
    }
}

@Composable
fun PactRow(v: PactView, now: Long, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Rise.NightDeep.copy(alpha = 0.45f))
            .border(1.dp, Rise.Ivory.copy(alpha = 0.08f), RoundedCornerShape(20.dp))
            .clickable(role = Role.Button, onClick = onClick).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(v.pact.name, style = RiseType.heading, color = Rise.Ivory)
            Text(cardSubtitle(v.pact, now), style = RiseType.small, color = Rise.Mist)
            Spacer(Modifier.height(10.dp))
            Row {
                v.ranked.take(5).forEachIndexed { i, m -> Avatar(m.avatar, 28.dp, Modifier.offset(x = (-8 * i).dp), ring = Rise.NightDeep) }
                if (v.members.size > 5) Text("+${v.members.size - 5}", style = RiseType.small, color = Rise.Mist, modifier = Modifier.padding(start = 2.dp, top = 5.dp))
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(skr(v.pact.potSoFar(v.members, now)), style = RiseType.amount, color = Rise.Sun)
            Text("SKR in the pot", style = RiseType.tiny, color = Rise.Mist)
        }
    }
}

// ------------------------------------------------------------------ Pact detail

@Composable
fun PactScreen(address: String, justStarted: Boolean, nav: Nav) {
    val state by Store.state.collectAsState()
    val now = rememberNow(5_000)
    val context = LocalContext.current
    val sender = LocalSender.current
    val scope = rememberCoroutineScope()
    var loaded by remember { mutableStateOf<PactView?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var showInvite by remember { mutableStateOf(justStarted) }
    val view = state.pacts.firstOrNull { it.pact.address == address } ?: state.discover.firstOrNull { it.pact.address == address } ?: loaded
    LaunchedEffect(address) { if (view == null) loaded = runCatching { Store.loadPact(address) }.getOrNull() }

    DetailScaffold(view?.pact?.name ?: "Pact", nav) {
        if (view == null) { Spacer(Modifier.height(20.dp)); CardSkeleton(); return@DetailScaffold }
        val p = view.pact
        val me = view.me
        Spacer(Modifier.height(10.dp))
        TimeCard(view, now, Modifier.fillMaxWidth(), maxRows = 20)
        Spacer(Modifier.height(20.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            LabeledValue("SKR in the pot", skr(p.potSoFar(view.members, now)), valueColor = Rise.Sun)
            LabeledValue("a morning", skr(p.stakePerDay))
            LabeledValue("members", "${p.memberCount}")
        }
        if (me != null) {
            Spacer(Modifier.height(18.dp))
            SkyPanel(Modifier.fillMaxWidth()) {
                Column {
                    Text("Your card", style = RiseType.heading, color = Rise.Ivory)
                    Spacer(Modifier.height(6.dp))
                    val wake = localTime(me.target(p, maxOf(me.firstDay, 0)))
                    Text(
                        "You wake at $wake. ${me.hits} of ${p.days - me.firstDay} mornings kept so far, best streak ${me.bestStreak}. " +
                            "Each morning you keep earns ${skr(p.stakePerDay)} SKR back from your ${skr(me.deposit)}, plus your share of the pot when the pact ends.",
                        style = RiseType.body, color = Rise.Ivory.copy(alpha = 0.85f),
                    )
                    if (p.isOver(now)) {
                        Spacer(Modifier.height(14.dp))
                        if (me.claimed) Text("Payout collected.", style = RiseType.bodyStrong, color = Rise.Moss)
                        else SunButton("Collect ${skr(me.projectedPayout(p))} SKR", busy = busy, busyText = "Collecting", onClick = {
                            busy = true; error = null
                            scope.launch { runCatching { Store.claim(p, sender) }.onFailure { error = RiseProgram.explain(it) }; busy = false }
                        }, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        } else if (!p.isOver(now)) {
            Spacer(Modifier.height(18.dp))
            SunButton("Join this pact", { nav.replace(Screen.Join(address)) }, Modifier.fillMaxWidth())
        }
        error?.let { Spacer(Modifier.height(10.dp)); Notice(it) }

        Spacer(Modifier.height(18.dp))
        if (showInvite && !p.isOver(now)) InvitePanel(p.address, p.name)
        else if (!p.isOver(now)) GhostButton("Invite friends", { showInvite = true }, Modifier.fillMaxWidth())
        Spacer(Modifier.height(16.dp))
        Text("How the pot works", style = RiseType.bodyStrong, color = Rise.Ivory)
        Spacer(Modifier.height(4.dp))
        Text(
            "Each morning you clock in on time earns your ${skr(p.stakePerDay)} SKR back. A missed morning stays in the pot. When the pact ends, the pot is split by mornings kept, so the earliest risers take the most.",
            style = RiseType.small, color = Rise.Ivory.copy(alpha = 0.75f),
        )
        TextAction("View pact on Solana Explorer", { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Config.explorerAddress(p.address)))) })
    }
}

@Composable
fun InvitePanel(pact: String, name: String) {
    val context = LocalContext.current
    val url = inviteUrl(pact)
    val qr = remember(url) { qrBitmap(url, 640) }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(Rise.Manila).padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Bring your friends in", style = RiseType.heading, color = Rise.InkBlue)
        Spacer(Modifier.height(4.dp))
        Text("They scan this with their camera or open the link.", style = RiseType.small, color = Rise.InkBlue.copy(alpha = 0.7f))
        Spacer(Modifier.height(14.dp))
        Image(qr.asImageBitmap(), "Invite QR code for $name", Modifier.size(200.dp))
        Spacer(Modifier.height(14.dp))
        Box(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(Rise.InkBlue)
                .clickable(role = Role.Button) {
                    val send = Intent(Intent.ACTION_SEND).setType("text/plain")
                        .putExtra(Intent.EXTRA_TEXT, "Join my Rise pact \"$name\". We wake up on time or the sleepers pay: $url")
                    context.startActivity(Intent.createChooser(send, "Send invite"))
                }.padding(vertical = 16.dp),
            contentAlignment = Alignment.Center,
        ) { Text("Share invite link", style = RiseType.button, color = Rise.Manila) }
    }
}

fun qrBitmap(text: String, size: Int, dark: Int = 0xFF1F2747.toInt(), light: Int = 0xFFEDE3CC.toInt()): Bitmap {
    val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 1))
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    for (x in 0 until size) for (y in 0 until size) bmp.setPixel(x, y, if (m[x, y]) dark else light)
    return bmp
}

// ------------------------------------------------------------------ New pact

@Composable
fun NewPactScreen(nav: Nav) {
    val state by Store.state.collectAsState()
    val profile by Store.profile.collectAsState()
    val sender = LocalSender.current
    val scope = rememberCoroutineScope()
    var name by rememberSaveable { mutableStateOf("${profile.name.ifBlank { "Morning" }}'s early crew") }
    var stake by rememberSaveable { mutableIntStateOf(10) }
    var days by rememberSaveable { mutableIntStateOf(7) }
    var public by rememberSaveable { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val total = stake.toLong() * days * 1_000_000
    val enough = state.skr >= total
    val validName = name.trim().length in 1..32

    DetailScaffold("New pact", nav) {
        Spacer(Modifier.height(8.dp))
        Text("Name it", style = RiseType.bodyStrong, color = Rise.Ivory)
        Spacer(Modifier.height(8.dp))
        BasicTextField(
            value = name, onValueChange = { name = it.take(32) },
            textStyle = RiseType.title.copy(color = Rise.Ivory), cursorBrush = SolidColor(Rise.Sun), singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Pact name" },
            decorationBox = { inner -> Column { inner(); Spacer(Modifier.height(8.dp)); Box(Modifier.fillMaxWidth().height(2.dp).background(if (validName) Rise.Sun else Rise.Rose)) } },
        )
        Spacer(Modifier.height(24.dp))
        Text("Stake a morning", style = RiseType.bodyStrong, color = Rise.Ivory)
        Spacer(Modifier.height(10.dp))
        Chips(listOf(5, 10, 25, 50), stake, { "$it SKR" }) { stake = it }
        Spacer(Modifier.height(22.dp))
        Text("How many mornings", style = RiseType.bodyStrong, color = Rise.Ivory)
        Spacer(Modifier.height(10.dp))
        Chips(listOf(3, 7, 14, 30), days, { "$it" }) { days = it }
        Spacer(Modifier.height(22.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Open to anyone", style = RiseType.bodyStrong, color = Rise.Ivory)
                Text("Public pacts show up for every Rise user to join.", style = RiseType.small, color = Rise.Mist)
            }
            Switch(public, { public = it }, colors = SwitchDefaults.colors(checkedTrackColor = Rise.Sun, checkedThumbColor = Rise.Night))
        }
        Spacer(Modifier.height(22.dp))
        SkyPanel(Modifier.fillMaxWidth()) {
            Column {
                Text("You lock ${skr(total)} SKR", style = RiseType.heading, color = Rise.Ivory)
                Spacer(Modifier.height(6.dp))
                Text(
                    "Your wake time is ${formatMinutes(profile.wakeMinutes)} ${amPm(profile.wakeMinutes)} and it's locked for this pact. Clock in on time to earn each morning's $stake SKR back; every miss goes to the pot.",
                    style = RiseType.small, color = Rise.Ivory.copy(alpha = 0.8f),
                )
            }
        }
        if (!enough) { Spacer(Modifier.height(12.dp)); Notice("You have ${skr(state.skr)} SKR. Get test SKR from the You tab, or pick a smaller stake.") }
        error?.let { Spacer(Modifier.height(12.dp)); Notice(it) }
        Spacer(Modifier.height(18.dp))
        SunButton(
            "Start pact for ${skr(total)} SKR", busy = busy, busyText = "Starting your pact", enabled = enough && validName,
            onClick = {
                busy = true; error = null
                scope.launch {
                    runCatching { Store.startPact(name.trim(), stake, days, public, sender) }
                        .onSuccess { nav.replace(Screen.Pact(it, justStarted = true)) }
                        .onFailure { error = RiseProgram.explain(it) }
                    busy = false
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
fun <T> Chips(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { o ->
            val on = o == selected
            Box(
                Modifier.weight(1f).height(48.dp).clip(RoundedCornerShape(24.dp))
                    .background(if (on) Rise.Sun else Rise.NightDeep.copy(alpha = 0.4f))
                    .border(1.dp, if (on) Rise.Sun else Rise.Ivory.copy(alpha = 0.15f), RoundedCornerShape(24.dp))
                    .semantics { this.selected = on }
                    .clickable(role = Role.RadioButton) { onSelect(o) },
                contentAlignment = Alignment.Center,
            ) { Text(label(o), style = RiseType.bodyStrong, color = if (on) Rise.Night else Rise.Ivory) }
        }
    }
}

// ------------------------------------------------------------------ Join

@Composable
fun JoinScreen(address: String, nav: Nav) {
    val state by Store.state.collectAsState()
    val profile by Store.profile.collectAsState()
    val sender = LocalSender.current
    val scope = rememberCoroutineScope()
    val now = rememberNow(5_000)
    var view by remember { mutableStateOf<PactView?>(null) }
    var missing by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(address) {
        val v = runCatching { Store.loadPact(address) }.getOrNull()
        if (v == null) missing = true else view = v
    }
    LaunchedEffect(view) { if (view?.me != null) nav.replace(Screen.Pact(address)) }

    DetailScaffold("Join a pact", nav) {
        val v = view
        if (missing) { Spacer(Modifier.height(20.dp)); Notice("This invite doesn't point to a Rise pact. Ask your friend to send the link again."); return@DetailScaffold }
        if (v == null) { Spacer(Modifier.height(20.dp)); CardSkeleton(); return@DetailScaffold }
        val p = v.pact
        val stub = app.rise.clockin.solana.Member("", p.address, "", "", 0, Store.wakeOffsetFor(p), 0, 0, 0, 0, 0, -1, false, 0, ShortArray(64) { Config.NOT_IN })
        val firstDay = (0 until p.days).firstOrNull { d -> now < stub.windowOpens(p, d) - 60 }
        val deposit = firstDay?.let { p.stakePerDay * (p.days - it) } ?: 0
        Spacer(Modifier.height(8.dp))
        TimeCard(v, now, Modifier.fillMaxWidth())
        Spacer(Modifier.height(20.dp))
        when {
            p.memberCount >= p.maxMembers -> Notice("This pact is full.")
            firstDay == null -> Notice("This pact has no mornings left to join.")
            else -> {
                val firstMorning = localTime(stub.target(p, firstDay))
                SkyPanel(Modifier.fillMaxWidth()) {
                    Column {
                        Text("You'd lock ${skr(deposit)} SKR", style = RiseType.heading, color = Rise.Ivory)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "${p.days - firstDay} mornings at ${skr(p.stakePerDay)} SKR, starting ${if (firstDay == p.dayIndex(now)) "today" else "with the next one"} at $firstMorning. Your wake time is ${formatMinutes(profile.wakeMinutes)} ${amPm(profile.wakeMinutes)}; change it in You before joining if you need to.",
                            style = RiseType.small, color = Rise.Ivory.copy(alpha = 0.8f),
                        )
                    }
                }
                if (state.skr < deposit) { Spacer(Modifier.height(12.dp)); Notice("You have ${skr(state.skr)} SKR. Get test SKR from the You tab first.") }
                error?.let { Spacer(Modifier.height(12.dp)); Notice(it) }
                Spacer(Modifier.height(16.dp))
                SunButton(
                    "Join for ${skr(deposit)} SKR", busy = busy, busyText = "Joining", enabled = state.skr >= deposit,
                    onClick = {
                        busy = true; error = null
                        scope.launch {
                            runCatching { Store.join(p, sender) }
                                .onSuccess { nav.replace(Screen.Pact(address)) }
                                .onFailure { error = RiseProgram.explain(it) }
                            busy = false
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

// ------------------------------------------------------------------ Wake spot

@Composable
fun WakeSpotScreen(nav: Nav) {
    val profile by Store.profile.collectAsState()
    val context = LocalContext.current
    val qr = remember(profile.spotCode) { qrBitmap(profile.spotCode, 720) }
    DetailScaffold("Your wake spot", nav) {
        Spacer(Modifier.height(8.dp))
        Text("Put this code where you have to walk to.", style = RiseType.title, color = Rise.Ivory)
        Spacer(Modifier.height(8.dp))
        Text("Print it, or save it and show it on another screen. Stick it by the kettle or the bathroom mirror; the alarm stops when you scan it.", style = RiseType.body, color = Rise.Ivory.copy(alpha = 0.8f))
        Spacer(Modifier.height(20.dp))
        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(Rise.Manila).padding(24.dp), contentAlignment = Alignment.Center) {
            Image(qr.asImageBitmap(), "Your wake spot code", Modifier.size(260.dp))
        }
        Spacer(Modifier.height(16.dp))
        SunButton("Share the code", {
            val file = java.io.File(context.cacheDir, "rise-wake-spot.png")
            file.outputStream().use { qr.compress(Bitmap.CompressFormat.PNG, 100, it) }
            val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.files", file)
            val send = Intent(Intent.ACTION_SEND).setType("image/png").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            context.startActivity(Intent.createChooser(send, "Save or print your wake spot"))
        }, Modifier.fillMaxWidth())
        Spacer(Modifier.height(10.dp))
        GhostButton("Make a new code", { Store.newWakeSpot() }, Modifier.fillMaxWidth())
    }
}
