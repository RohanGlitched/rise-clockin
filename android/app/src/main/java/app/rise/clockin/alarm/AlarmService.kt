package app.rise.clockin.alarm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.app.NotificationCompat
import app.rise.clockin.AlarmActivity
import app.rise.clockin.R
import app.rise.clockin.data.Store
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

enum class AlarmPhase { Idle, Sunrise, Ringing, Mission, Done }

data class AlarmState(
    val phase: AlarmPhase = AlarmPhase.Idle,
    val wakeAt: Long = 0,
    val rehearsal: Boolean = false,
    val startedAt: Long = 0,
)

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val svc = Intent(context, AlarmService::class.java).putExtras(intent)
        if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(svc) else context.startService(svc)
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Store.init(context)
        AlarmScheduler.schedule(context, Store.profile.value)
    }
}

/**
 * Runs the morning: a silent sunrise until the wake time, then an alarm that climbs in
 * volume, vibrates, and speaks the stakes out loud until the mission is done.
 */
class AlarmService : Service() {
    companion object {
        const val CHANNEL = "rise.alarm"
        const val QUIET = "rise.alarm.quiet"
        const val ACTION_SHOWN = "shown"
        const val ACTION_MISSION = "mission"
        const val ACTION_STOP = "stop"
        private const val NOTE_ID = 6

        private val _state = MutableStateFlow(AlarmState())
        val state: StateFlow<AlarmState> = _state.asStateFlow()

        fun enterMission(context: Context) = context.startService(Intent(context, AlarmService::class.java).setAction(ACTION_MISSION))
        /** The alarm screen is up: swap the heads-up banner for a quiet ongoing notification. */
        fun shown(context: Context) { if (_state.value.phase != AlarmPhase.Idle) context.startService(Intent(context, AlarmService::class.java).setAction(ACTION_SHOWN)) }
        fun stop(context: Context) = context.startService(Intent(context, AlarmService::class.java).setAction(ACTION_STOP))

        fun ensureChannel(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL) == null) {
                val ch = NotificationChannel(CHANNEL, "Wake-up alarm", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Rings at your wake time"
                    setSound(null, null)
                    enableVibration(false)
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                }
                nm.createNotificationChannel(ch)
            }
            if (nm.getNotificationChannel(QUIET) == null) {
                nm.createNotificationChannel(NotificationChannel(QUIET, "Alarm in progress", NotificationManager.IMPORTANCE_LOW).apply { setSound(null, null) })
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var player: MediaPlayer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var ringJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var focus: AudioFocusRequest? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { finish(); return START_NOT_STICKY }
            ACTION_SHOWN -> { getSystemService(NotificationManager::class.java).notify(NOTE_ID, note(QUIET, fullScreen = false)); return START_NOT_STICKY }
            ACTION_MISSION -> { quiet(); _state.value = _state.value.copy(phase = AlarmPhase.Mission); return START_NOT_STICKY }
        }
        Store.init(this)
        val wakeAt = intent?.getLongExtra(AlarmScheduler.EXTRA_WAKE_AT, System.currentTimeMillis()) ?: System.currentTimeMillis()
        val rehearsal = intent?.getBooleanExtra(AlarmScheduler.EXTRA_REHEARSAL, false) ?: false
        ensureChannel(this)
        startInForeground()
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "rise:alarm").apply { acquire(30 * 60_000L) }
        _state.value = AlarmState(AlarmPhase.Sunrise, wakeAt, rehearsal, System.currentTimeMillis())
        tts = TextToSpeech(this) { ok ->
            ttsReady = ok == TextToSpeech.SUCCESS
            if (ttsReady) tts?.language = Locale.getDefault()
        }
        // Refresh pacts so the spoken line knows who is already up.
        scope.launch(Dispatchers.IO) { runCatching { Store.refresh() } }
        ringJob = scope.launch {
            if (!rehearsal && alreadyKeptToday()) { finish(); return@launch }
            val wait = wakeAt - System.currentTimeMillis()
            if (wait > 0) delay(wait)
            if (_state.value.phase == AlarmPhase.Sunrise) ring()
            // Give up after 25 minutes so the phone doesn't ring all morning.
            delay(25 * 60_000L)
            if (_state.value.phase == AlarmPhase.Ringing) finish()
        }
        // Next morning is scheduled as soon as this one starts.
        if (!rehearsal) AlarmScheduler.schedule(this, Store.profile.value.let { it })
        return START_NOT_STICKY
    }

    private fun note(channel: String, fullScreen: Boolean): Notification {
        val full = PendingIntent.getActivity(
            this, 1, Intent(this, AlarmActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, channel)
            .setSmallIcon(R.drawable.ic_sun)
            .setContentTitle("Rise and clock in")
            .setContentText("Your wake window is open.")
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(if (fullScreen) NotificationCompat.PRIORITY_MAX else NotificationCompat.PRIORITY_LOW)
            .apply { if (fullScreen) setFullScreenIntent(full, true) }
            .setContentIntent(full)
            .setOngoing(true)
            .setSilent(!fullScreen)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
    }

    /** True when every pact morning due today is already clocked in (e.g. you woke before the alarm). */
    private suspend fun alreadyKeptToday(): Boolean {
        runCatching { Store.refresh() }
        val now = System.currentTimeMillis() / 1000
        val active = Store.state.value.pacts.filter { v -> v.me != null && !v.pact.isOver(now) }
        if (active.isEmpty()) return false
        return active.all { v ->
            val me = v.me!!
            val today = (me.firstDay until v.pact.days).firstOrNull { d -> now <= me.windowCloses(v.pact, d) && now >= me.windowOpens(v.pact, d) - 3600 }
            today == null || me.isIn(today)
        }
    }

    private fun startInForeground() {
        val note = note(CHANNEL, fullScreen = true)
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTE_ID, note, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else startForeground(NOTE_ID, note)
        // Some launchers ignore full-screen intents while the screen is on; open directly too.
        runCatching { startActivity(Intent(this, AlarmActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    private fun ring() {
        _state.value = _state.value.copy(phase = AlarmPhase.Ringing)
        val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
        val am = getSystemService(AudioManager::class.java)
        focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT).setAudioAttributes(attrs).build().also { am.requestAudioFocus(it) }
        player = MediaPlayer().apply {
            setAudioAttributes(attrs)
            val afd = resources.openRawResourceFd(R.raw.rise_alarm)
            setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
            afd.close()
            isLooping = true
            setVolume(0.25f, 0.25f)
            prepare()
            start()
        }
        vibrate()
        scope.launch {
            // Climb from a gentle chime to full volume over a minute.
            var v = 0.25f
            while (_state.value.phase == AlarmPhase.Ringing && v < 1f) {
                delay(3000); v = (v + 0.0375f).coerceAtMost(1f)
                player?.setVolume(v, v)
            }
        }
        scope.launch {
            delay(2500)
            while (_state.value.phase == AlarmPhase.Ringing) {
                speak(stakesLine())
                delay(40_000)
            }
        }
    }

    private fun vibrate() {
        val v = if (Build.VERSION.SDK_INT >= 31) getSystemService(VibratorManager::class.java).defaultVibrator
        else @Suppress("DEPRECATION") getSystemService(Vibrator::class.java)
        v.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 500, 400, 500, 1600), 0))
    }

    private fun stopVibrate() {
        val v = if (Build.VERSION.SDK_INT >= 31) getSystemService(VibratorManager::class.java).defaultVibrator
        else @Suppress("DEPRECATION") getSystemService(Vibrator::class.java)
        v.cancel()
    }

    /** "Good morning Rohan. It's 6:30. Aman and Priya are already up. 10 SKR is on the line." */
    private fun stakesLine(): String {
        val p = Store.profile.value
        val now = System.currentTimeMillis() / 1000
        val time = Instant.now().atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("h:mm"))
        val name = p.name.ifBlank { "there" }
        if (_state.value.rehearsal) return "Good morning, $name. It's $time. This is a rehearsal. Tomorrow, your stake is on the line."
        val open = Store.openForClockIn(now)
        if (open.isEmpty()) return "Good morning, $name. It's $time. Time to rise."
        val stake = open.sumOf { it.pact.stakePerDay } / 1_000_000
        val up = open.flatMap { v ->
            val day = v.pact.dayIndex(now)
            v.members.filter { it.owner != v.me?.owner && it.isIn(day) }.map { it.name }
        }.distinct()
        val friends = when (up.size) {
            0 -> "Nobody is up yet. Be first."
            1 -> "${up[0]} is already up."
            2 -> "${up[0]} and ${up[1]} are already up."
            else -> "${up[0]}, ${up[1]} and ${up.size - 2} more are already up."
        }
        return "Good morning, $name. It's $time. $friends $stake SKR is on the line."
    }

    private fun speak(line: String) {
        val t = tts ?: return
        if (!ttsReady || !Store.profile.value.voice) return
        player?.setVolume(0.12f, 0.12f)
        t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {}
            override fun onDone(id: String?) { scope.launch { if (_state.value.phase == AlarmPhase.Ringing) player?.setVolume(1f, 1f) } }
            @Deprecated("Deprecated in Java") override fun onError(id: String?) {}
        })
        val params = android.os.Bundle().apply { putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_ALARM) }
        t.speak(line, TextToSpeech.QUEUE_FLUSH, params, "stakes")
    }

    /** Silences the alarm while the mission runs; the screen stays up. */
    private fun quiet() {
        ringJob?.cancel()
        player?.run { runCatching { stop() }; release() }
        player = null
        tts?.stop()
        stopVibrate()
    }

    private fun finish() {
        quiet()
        _state.value = AlarmState(AlarmPhase.Idle)
        focus?.let { getSystemService(AudioManager::class.java).abandonAudioFocusRequest(it) }
        wakeLock?.let { if (it.isHeld) it.release() }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        quiet()
        tts?.shutdown()
        scope.cancel()
        super.onDestroy()
    }
}
