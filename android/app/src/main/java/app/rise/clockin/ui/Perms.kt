package app.rise.clockin.ui

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.rise.clockin.data.Mission

/** What Rise needs from Android to ring reliably, each with a way to grant it. */
enum class Need(val title: String, val why: String) {
    Notifications("Notifications", "So the alarm can appear on your lock screen."),
    ExactAlarms("Alarms and reminders", "So Rise rings at your exact wake time."),
    FullScreen("Full-screen alarm", "So the alarm takes over the screen like a clock app."),
    Motion("Physical activity", "So the Walk it off mission can count your steps."),
    Camera("Camera", "So the camera missions can see your wake spot or the morning."),
}

object Perms {
    fun granted(context: Context, need: Need): Boolean = when (need) {
        Need.Notifications -> Build.VERSION.SDK_INT < 33 || has(context, Manifest.permission.POST_NOTIFICATIONS)
        Need.ExactAlarms -> Build.VERSION.SDK_INT < 31 || context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
        Need.FullScreen -> Build.VERSION.SDK_INT < 34 || context.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()
        Need.Motion -> Build.VERSION.SDK_INT < 29 || has(context, Manifest.permission.ACTIVITY_RECOGNITION)
        Need.Camera -> has(context, Manifest.permission.CAMERA)
    }

    /** Runtime permission string, or null when the need is granted from a settings page. */
    fun runtime(need: Need): String? = when (need) {
        Need.Notifications -> if (Build.VERSION.SDK_INT >= 33) Manifest.permission.POST_NOTIFICATIONS else null
        Need.Motion -> if (Build.VERSION.SDK_INT >= 29) Manifest.permission.ACTIVITY_RECOGNITION else null
        Need.Camera -> Manifest.permission.CAMERA
        else -> null
    }

    fun settingsIntent(context: Context, need: Need): Intent = when (need) {
        Need.ExactAlarms -> Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}"))
        Need.FullScreen -> if (Build.VERSION.SDK_INT >= 34) Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:${context.packageName}"))
        else Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
        else -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
    }

    fun needsFor(mission: Mission): List<Need> = buildList {
        add(Need.Notifications); add(Need.ExactAlarms)
        if (Build.VERSION.SDK_INT >= 34) add(Need.FullScreen)
        if (mission == Mission.Walk && Build.VERSION.SDK_INT >= 29) add(Need.Motion)
        if (mission == Mission.Spot || mission == Mission.Photo) add(Need.Camera)
    }

    private fun has(context: Context, p: String) = ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED
}

/** Re-reads permission state whenever the app comes back to the foreground. */
@Composable
fun rememberResumeTick(): Int {
    val owner = LocalLifecycleOwner.current
    var tick by remember { mutableIntStateOf(0) }
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) tick++ }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    return tick
}

@Composable
fun rememberContext(): Context = LocalContext.current
