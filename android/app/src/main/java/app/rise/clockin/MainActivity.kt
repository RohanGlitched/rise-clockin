package app.rise.clockin

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.staticCompositionLocalOf
import app.rise.clockin.alarm.AlarmService
import app.rise.clockin.data.Store
import app.rise.clockin.ui.RiseApp
import app.rise.clockin.ui.theme.RiseTheme
import com.solana.mobilewalletadapter.clientlib.ActivityResultSender

/** The Mobile Wallet Adapter sender for the current activity. */
val LocalSender = staticCompositionLocalOf<ActivityResultSender?> { null }

class MainActivity : ComponentActivity() {
    private lateinit var sender: ActivityResultSender
    /** Pact address from an invite link (rise://join/<address> or https://…/join/<address>). */
    private val inviteLink = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(0), navigationBarStyle = SystemBarStyle.dark(0))
        super.onCreate(savedInstanceState)
        Store.init(this)
        app.rise.clockin.ui.card.Clock24.on = android.text.format.DateFormat.is24HourFormat(this)
        AlarmService.ensureChannel(this)
        sender = ActivityResultSender(this)
        inviteLink.value = parseInvite(intent)
        setContent {
            RiseTheme {
                CompositionLocalProvider(LocalSender provides sender) {
                    RiseApp(invite = inviteLink.value, onInviteHandled = { inviteLink.value = null })
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        parseInvite(intent)?.let { inviteLink.value = it }
    }

    private fun parseInvite(intent: Intent?): String? {
        val uri = intent?.data ?: return null
        val segs = uri.pathSegments
        return when {
            uri.scheme == "rise" && uri.host == "join" -> segs.firstOrNull()
            segs.size >= 2 && segs[0] == "join" -> segs[1]
            else -> null
        }?.takeIf { it.length in 32..44 }
    }
}
