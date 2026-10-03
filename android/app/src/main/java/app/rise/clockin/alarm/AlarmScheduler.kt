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
        set(context, wakeAt - p.sunriseLead * 60_000L, wakeAt, rehearsal = false)
    }

    /** A practice run: a short sunrise, then the real ring and mission. */
    fun rehearse(context: Context, inSeconds: Int = 4, dawnSeconds: Int = 10) {
        val now = System.currentTimeMillis()
        val fire = now + inSeconds * 1000L
        set(context, fire, fire + dawnSeconds * 1000L, rehearsal = true, requestCode = REQ + 1)
    }

    private fun set(context: Context, fireAt: Long, wakeAt: Long, rehearsal: Boolean, requestCode: Int = REQ) {
        val am = context.getSystemService(AlarmManager::class.java)
        val intent = Intent(context, AlarmReceiver::class.java)
            .putExtra(EXTRA_WAKE_AT, wakeAt)
            .putExtra(EXTRA_REHEARSAL, rehearsal)
        val op = PendingIntent.getBroadcast(context, requestCode, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
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
        val op = PendingIntent.getBroadcast(context, REQ, Intent(context, AlarmReceiver::class.java), PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)
        op?.let { am.cancel(it) }
    }
}
