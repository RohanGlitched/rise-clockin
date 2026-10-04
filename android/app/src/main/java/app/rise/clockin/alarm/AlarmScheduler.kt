package app.rise.clockin.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import app.rise.clockin.MainActivity
import app.rise.clockin.data.Profile
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * Schedules the next morning. The alarm fires `sunriseLead` minutes before the wake
 * time so the screen can glow like a sunrise lamp, then rings at the wake time.
 */
object AlarmScheduler {
    const val EXTRA_WAKE_AT = "wakeAt"
    const val EXTRA_REHEARSAL = "rehearsal"
    private const val REQ = 4207

    /** Epoch millis of the next wake time for this profile. */
    fun nextWake(p: Profile, now: LocalDateTime = LocalDateTime.now()): Long {
        val t = LocalTime.of(p.wakeMinutes / 60, p.wakeMinutes % 60)
        var at = LocalDateTime.of(LocalDate.now(), t)
        if (!at.minusMinutes(p.sunriseLead.toLong()).isAfter(now)) at = at.plusDays(1)
        return at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }

    fun canScheduleExact(context: Context): Boolean {
        val am = context.getSystemService(AlarmManager::class.java)
        return Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
    }

    fun schedule(context: Context, p: Profile) {
        cancel(context)
        if (!p.alarmOn || !p.onboarded) return
        val wakeAt = nextWake(p)
        // Rise Coach can ask for a longer sunrise when tomorrow looks risky.
        val lead = maxOf(p.sunriseLead, app.rise.clockin.ai.currentDifficulty().sunriseLead.takeIf { app.rise.clockin.ai.WakeCoach.current != null } ?: 0)
        set(context, wakeAt - lead * 60_000L, wakeAt, rehearsal = false)
    }

    /**
     * "Rehearse the alarm". When one of your pact windows is already open this is the real
     * thing: it rings with the live stakes and the clock-in goes on chain. Otherwise it's a
     * practice run with nothing at stake.
     */
    fun rehearse(context: Context) {
        val live = app.rise.clockin.data.Store.openForClockIn().isNotEmpty()
        val now = System.currentTimeMillis()
        val fire = now + (if (live) 20_000L else 4_000L)
        val dawn = if (live) 25_000L else 10_000L
        set(context, fire, fire + dawn, rehearsal = !live, requestCode = REQ + 1)
    }

    private fun set(context: Context, fireAt: Long, wakeAt: Long, rehearsal: Boolean, requestCode: Int = REQ) {
        val am = context.getSystemService(AlarmManager::class.java)
        val op = operation(context, requestCode, PendingIntent.FLAG_UPDATE_CURRENT, wakeAt, rehearsal)!!
        val show = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        // The trigger is the start of the sunrise; the service rings at wakeAt.
        if (canScheduleExact(context)) {
            am.setAlarmClock(AlarmManager.AlarmClockInfo(fireAt, show), op)
        } else {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, fireAt, op)
        }
    }

    fun cancel(context: Context) {
        val am = context.getSystemService(AlarmManager::class.java)
        operation(context, REQ, PendingIntent.FLAG_NO_CREATE)?.let { am.cancel(it) }
    }

    /**
     * The alarm starts the alarm service directly rather than going through a broadcast:
     * on slow or busy phones the broadcast queue can hold an alarm back for minutes.
     */
    private fun operation(context: Context, requestCode: Int, flags: Int, wakeAt: Long? = null, rehearsal: Boolean = false): PendingIntent? {
        val intent = Intent(context, AlarmService::class.java)
        if (wakeAt != null) intent.putExtra(EXTRA_WAKE_AT, wakeAt).putExtra(EXTRA_REHEARSAL, rehearsal)
        return if (Build.VERSION.SDK_INT >= 26) PendingIntent.getForegroundService(context, requestCode, intent, flags or PendingIntent.FLAG_IMMUTABLE)
        else PendingIntent.getService(context, requestCode, intent, flags or PendingIntent.FLAG_IMMUTABLE)
    }
}
