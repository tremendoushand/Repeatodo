package com.tremendoushand.repeatodo.notify

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.content.edit
import androidx.core.net.toUri
import com.tremendoushand.repeatodo.data.SettingsRepository
import com.tremendoushand.repeatodo.data.TaskRepository
import com.tremendoushand.repeatodo.domain.nextAttentionDate
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Keeps one alarm per task with its own reminder time, plus one alarm per grouped reminder time.
 * Everything is recomputed from scratch on each change; there are only ever a handful of alarms.
 */
class ReminderScheduler(
    private val context: Context,
    private val tasks: TaskRepository,
    private val settings: SettingsRepository,
) {
    private val alarmManager = context.getSystemService(AlarmManager::class.java)
    private val prefs = context.getSharedPreferences("alarms", Context.MODE_PRIVATE)
    private val mutex = Mutex()

    fun canScheduleExact(): Boolean = Build.VERSION.SDK_INT < 31 || alarmManager.canScheduleExactAlarms()

    suspend fun rescheduleAll() = mutex.withLock {
        val now = LocalDateTime.now()
        val wanted = mutableMapOf<String, Long>()

        for (task in tasks.activeTasks()) {
            val minutes = task.reminderMinutes ?: continue
            val at = nextTrigger(now) { from -> task.rule.nextAttentionDate(from)?.atStartOfDay()?.plusMinutes(minutes.toLong()) }
            if (at != null) wanted[taskUri(task.id)] = at
        }
        for (minutes in settings.current().digestTimes) {
            val at = nextTrigger(now) { from -> from.atStartOfDay().plusMinutes(minutes.toLong()) }
            if (at != null) wanted[digestUri(minutes)] = at
        }

        val previous = prefs.getStringSet(KEY_SCHEDULED, emptySet()).orEmpty()
        for (uri in previous - wanted.keys) alarmManager.cancel(pendingIntent(uri))
        for ((uri, at) in wanted) set(uri, at)
        prefs.edit { putStringSet(KEY_SCHEDULED, wanted.keys) }
    }

    private inline fun nextTrigger(now: LocalDateTime, candidate: (LocalDate) -> LocalDateTime?): Long? {
        var day = now.toLocalDate()
        repeat(2) {
            val at = candidate(day) ?: return null
            if (at.isAfter(now)) return at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            day = at.toLocalDate().plusDays(1)
        }
        return null
    }

    private fun set(uri: String, at: Long) {
        val pi = pendingIntent(uri)
        try {
            if (canScheduleExact()) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            } else {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            }
        } catch (e: SecurityException) {
            // Exact alarms were revoked between the check and the call; fall back to inexact.
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    private fun pendingIntent(uri: String): PendingIntent = PendingIntent.getBroadcast(
        context, 0,
        Intent(context, ReminderReceiver::class.java).setData(uri.toUri()),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    companion object {
        private const val KEY_SCHEDULED = "scheduled"
        fun taskUri(taskId: String) = "repeatodo://task/$taskId"
        fun digestUri(minutes: Int) = "repeatodo://digest/$minutes"

        fun parse(uri: Uri?): Pair<String, String>? {
            val segments = uri?.pathSegments ?: return null
            val host = uri.host ?: return null
            return host to (segments.firstOrNull() ?: return null)
        }
    }
}
