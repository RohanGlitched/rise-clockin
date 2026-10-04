package app.rise.clockin.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.rise.clockin.alarm.AlarmScheduler
import app.rise.clockin.data.Store
import app.rise.clockin.ui.screens.JoinScreen
import app.rise.clockin.ui.screens.NewPactScreen
import app.rise.clockin.ui.screens.OnboardingScreen
import app.rise.clockin.ui.screens.PactScreen
import app.rise.clockin.ui.screens.PactsTab
import app.rise.clockin.ui.screens.TodayTab
import app.rise.clockin.ui.screens.WakeSpotScreen
import app.rise.clockin.ui.screens.YouTab
import app.rise.clockin.ui.theme.Rise
import app.rise.clockin.ui.theme.RiseType
import kotlinx.coroutines.delay

sealed interface Screen {
    data object Home : Screen
    data class Pact(val address: String, val justStarted: Boolean = false, val joinedSkr: Long = 0, val joinedSig: String? = null) : Screen
    data object NewPact : Screen
    data class Join(val address: String) : Screen
    data object WakeSpot : Screen
}

enum class Tab(val label: String) { Today("Today"), Pacts("Pacts"), You("You") }

/** Navigation for the main app: three tabs plus a simple back stack of detail screens. */
class Nav {
    val stack = mutableStateListOf<Screen>(Screen.Home)
    val tab = androidx.compose.runtime.mutableStateOf(Tab.Today)
    val top get() = stack.last()
    fun push(s: Screen) { stack.add(s) }
    fun pop() { if (stack.size > 1) stack.removeAt(stack.lastIndex) }
    fun replace(s: Screen) { if (stack.size > 1) stack[stack.lastIndex] = s else stack.add(s) }
    fun home(tab: Tab? = null) { while (stack.size > 1) stack.removeAt(stack.lastIndex); tab?.let { this.tab.value = it } }
}

@Composable
fun RiseApp(invite: String?, onInviteHandled: () -> Unit) {
    val profile by Store.profile.collectAsState()
    val state by Store.state.collectAsState()
    val context = rememberContext()
    val nav = remember { Nav() }

    // Keep the next morning scheduled whenever the profile changes.
    LaunchedEffect(profile) { AlarmScheduler.schedule(context, profile) }
    // Refresh on open and every 45 s while the app is visible.
    LaunchedEffect(state.address) {
        while (state.address != null) { Store.refresh(); delay(45_000) }
    }
    LaunchedEffect(invite, profile.onboarded) {
        if (invite != null && profile.onboarded) { nav.home(); nav.push(Screen.Join(invite)); onInviteHandled() }
    }

    if (!profile.onboarded || state.address == null) {
        OnboardingScreen(invite)
        return
    }

    BackHandler(enabled = nav.stack.size > 1) { nav.pop() }
    BackHandler(enabled = nav.stack.size == 1 && nav.tab.value != Tab.Today) { nav.tab.value = Tab.Today }

    AnimatedContent(
        targetState = nav.top,
        transitionSpec = {
            if (targetState is Screen.Home) (fadeIn(tween(220)) togetherWith slideOutHorizontally(tween(260)) { it / 3 } + fadeOut(tween(200)))
            else (slideInHorizontally(tween(280)) { it / 3 } + fadeIn(tween(220)) togetherWith fadeOut(tween(180)))
        },
        label = "nav",
    ) { screen ->
        when (screen) {
            Screen.Home -> HomeShell(nav)
            is Screen.Pact -> PactScreen(screen.address, screen.justStarted, nav, screen.joinedSkr, screen.joinedSig)
            Screen.NewPact -> NewPactScreen(nav)
            is Screen.Join -> JoinScreen(screen.address, nav)
            Screen.WakeSpot -> WakeSpotScreen(nav)
        }
    }
}

@Composable
private fun HomeShell(nav: Nav) {
    val tab by nav.tab
    Box(Modifier.fillMaxSize().background(Rise.Night)) {
        AnimatedContent(tab, transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(160)) }, label = "tab") { t ->
            when (t) {
                Tab.Today -> TodayTab(nav)
                Tab.Pacts -> PactsTab(nav)
                Tab.You -> YouTab(nav)
            }
        }
        // Content scrolls under the status bar; a soft night scrim keeps the clock and icons readable.
        Box(
            Modifier.fillMaxWidth().windowInsetsTopHeight(WindowInsets.statusBars).align(Alignment.TopCenter)
                .background(Brush.verticalGradient(listOf(Rise.NightDeep.copy(alpha = 0.85f), Rise.NightDeep.copy(alpha = 0f)))),
        )
        BottomBar(tab, { nav.tab.value = it }, Modifier.align(Alignment.BottomCenter))
    }
}

@Composable
private fun BottomBar(tab: Tab, onSelect: (Tab) -> Unit, modifier: Modifier) {
    Box(
        modifier.fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Rise.NightDeep.copy(alpha = 0.92f), Rise.NightDeep)))
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(top = 18.dp, bottom = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier.clip(RoundedCornerShape(30.dp)).background(Rise.Ivory.copy(alpha = 0.08f)).padding(5.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Tab.entries.forEach { t ->
                val on = t == tab
                Box(
                    Modifier.clip(RoundedCornerShape(25.dp))
                        .background(if (on) Rise.Sun else Color.Transparent)
                        .semantics { selected = on }
                        .clickable(role = Role.Tab) { onSelect(t) }
                        .padding(horizontal = 24.dp, vertical = 13.dp),
                ) { Text(t.label, style = RiseType.bodyStrong, color = if (on) Rise.Night else Rise.Ivory.copy(alpha = 0.8f)) }
            }
        }
    }
}

/** Space reserved at the bottom of scrolling tabs so content clears the tab bar. */
@Composable
fun BottomBarSpace() = Spacer(Modifier.height(110.dp).windowInsetsPadding(WindowInsets.navigationBars))

@Composable
fun ScreenColumn(content: @Composable () -> Unit) = Column(Modifier.fillMaxSize()) { content() }

@Composable
fun SmallSun(modifier: Modifier = Modifier) = Box(modifier.size(10.dp).clip(RoundedCornerShape(5.dp)).background(Rise.Sun))
