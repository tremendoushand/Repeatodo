package com.tremendoushand.repeatodo.calendar

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import androidx.core.content.ContextCompat
import com.tremendoushand.repeatodo.data.AppDatabase
import com.tremendoushand.repeatodo.data.CalendarLinkEntity
import com.tremendoushand.repeatodo.data.DeviceDatabase
import com.tremendoushand.repeatodo.data.SettingsRepository
import com.tremendoushand.repeatodo.data.TaskEntity
import com.tremendoushand.repeatodo.domain.LAST_DAY
import com.tremendoushand.repeatodo.domain.RepeatType
import com.tremendoushand.repeatodo.domain.WeekDays
import com.tremendoushand.repeatodo.domain.nextAttentionDate
import com.tremendoushand.repeatodo.domain.weekStartOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

data class DeviceCalendar(val id: Long, val name: String, val account: String, val color: Int)

/** Written into every event this app creates, so leftovers can be found again. */
private const val MARKER = "Repeatodo에서 만든 일정"

/**
 * Mirrors tasks marked "캘린더에 표시" as repeating events in a calendar on the device
 * (for example the user's Google Calendar). Events are written by this app only; edits made
 * in the calendar app are overwritten on the next change.
 */
class CalendarSync(
    private val context: Context,
    private val db: AppDatabase,
    private val deviceDb: DeviceDatabase,
    private val settings: SettingsRepository,
) {
    private val resolver get() = context.contentResolver
    private val mutex = Mutex()

    fun hasPermission(): Boolean = listOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR).all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    suspend fun calendars(): List<DeviceCalendar> = withContext(Dispatchers.IO) {
        if (!hasPermission()) return@withContext emptyList()
        val projection = arrayOf(
            Calendars._ID, Calendars.CALENDAR_DISPLAY_NAME, Calendars.ACCOUNT_NAME, Calendars.CALENDAR_COLOR,
        )
        val result = mutableListOf<DeviceCalendar>()
        resolver.query(
            Calendars.CONTENT_URI, projection,
            "${Calendars.CALENDAR_ACCESS_LEVEL} >= ? AND ${Calendars.VISIBLE} = 1",
            arrayOf(Calendars.CAL_ACCESS_CONTRIBUTOR.toString()), null,
        )?.use { c ->
            while (c.moveToNext()) {
                result += DeviceCalendar(c.getLong(0), c.getString(1) ?: "", c.getString(2) ?: "", c.getInt(3))
            }
        }
        result
    }

    /** Brings the device calendar in line with the current tasks. Safe to call often. */
    suspend fun syncAll() = mutex.withLock {
        withContext(Dispatchers.IO) {
            if (!hasPermission()) return@withContext
            val s = settings.current()
            // Calendar ids are per device (settings may come from a backup), so only use ones that exist here.
            val available = calendars().map { it.id }.toSet()
            val default = s.calendarId?.takeIf { it in available }
            fun target(task: TaskEntity): Long? =
                task.categoryId?.let { s.categoryCalendars[it] }?.takeIf { it in available } ?: default

            val links = deviceDb.calendarLinks().all().associateBy { it.taskId }
            val wanted: Map<String, Pair<TaskEntity, Long>> = if (s.calendarEnabled) {
                db.tasks().active().filter { it.showInCalendar }
                    .mapNotNull { task -> target(task)?.let { task.id to (task to it) } }
                    .toMap()
            } else {
                emptyMap()
            }

            // Drop events for tasks no longer shown, or that moved to another calendar.
            for (link in links.values) {
                if (wanted[link.taskId]?.second != link.calendarId) {
                    deleteEvent(link.eventId)
                    deviceDb.calendarLinks().delete(link.taskId)
                }
            }
            for ((task, calendarId) in wanted.values) {
                val values = eventValues(task, calendarId, s.weekStart) ?: continue
                val link = links[task.id]?.takeIf { it.calendarId == calendarId }
                val updated = link != null && runCatching {
                    resolver.update(ContentUris.withAppendedId(Events.CONTENT_URI, link.eventId), values, null, null)
                }.getOrDefault(0) > 0
                if (!updated) {
                    val uri = runCatching { resolver.insert(Events.CONTENT_URI, values) }.getOrNull() ?: continue
                    deviceDb.calendarLinks().upsert(CalendarLinkEntity(task.id, ContentUris.parseId(uri), calendarId))
                }
            }
        }
    }

    /**
     * Removes every event this app created: the ones it knows about, and any left behind by an
     * earlier install (found by the note it writes in each event). Returns how many were removed.
     */
    suspend fun removeAll(): Int = mutex.withLock {
        withContext(Dispatchers.IO) {
            val ids = mutableSetOf<Long>()
            if (hasPermission()) {
                deviceDb.calendarLinks().all().mapTo(ids) { it.eventId }
                runCatching {
                    resolver.query(
                        Events.CONTENT_URI, arrayOf(Events._ID),
                        "${Events.DESCRIPTION} LIKE ? AND ${Events.DELETED} = 0", arrayOf("%$MARKER"), null,
                    )?.use { c -> while (c.moveToNext()) ids += c.getLong(0) }
                }
            }
            val removed = ids.count { deleteEvent(it) }
            deviceDb.calendarLinks().clear()
            removed
        }
    }

    private fun deleteEvent(eventId: Long): Boolean =
        runCatching { resolver.delete(ContentUris.withAppendedId(Events.CONTENT_URI, eventId), null, null) > 0 }.getOrDefault(false)

    private fun eventValues(task: TaskEntity, calendarId: Long, weekStart: DayOfWeek): ContentValues? {
        val rule = task.rule(weekStart)
        val flexible = rule.isFlexible
        val first: LocalDate = when {
            flexible && task.repeatType == RepeatType.WEEKLY -> weekStartOf(rule.startDate, weekStart)
            flexible -> rule.startDate.withDayOfMonth(1)
            else -> rule.nextAttentionDate(rule.startDate) ?: return null
        }
        val rrule = when (task.repeatType) {
            RepeatType.DAILY -> "FREQ=DAILY"
            RepeatType.WEEKLY -> if (flexible) {
                "FREQ=WEEKLY;BYDAY=" + byDay(weekStart)
            } else {
                "FREQ=WEEKLY;BYDAY=" + WeekDays.toList(task.weekDays).joinToString(",") { byDay(it) }
            }
            RepeatType.MONTHLY -> when {
                flexible -> "FREQ=MONTHLY;BYMONTHDAY=1"
                task.monthDay >= LAST_DAY -> "FREQ=MONTHLY;BYMONTHDAY=-1"
                // Fall back to the last day in months that are too short, like the app does.
                task.monthDay > 28 -> "FREQ=MONTHLY;BYMONTHDAY=" + (28..task.monthDay).joinToString(",") + ";BYSETPOS=-1"
                else -> "FREQ=MONTHLY;BYMONTHDAY=${task.monthDay}"
            }
        }
        val prefix = when {
            !flexible -> ""
            task.repeatType == RepeatType.WEEKLY -> if (task.periodTimes > 1) "[이번 주 ${task.periodTimes}일] " else "[이번 주] "
            else -> if (task.periodTimes > 1) "[이번 달 ${task.periodTimes}일] " else "[이번 달] "
        }
        val minutes = task.reminderMinutes
        return ContentValues().apply {
            put(Events.CALENDAR_ID, calendarId)
            put(Events.TITLE, prefix + task.title)
            put(Events.DESCRIPTION, listOf(task.note, MARKER).filter { it.isNotBlank() }.joinToString("\n"))
            put(Events.RRULE, rrule)
            put(Events.HAS_ALARM, 0)
            if (minutes == null || flexible) {
                put(Events.ALL_DAY, 1)
                put(Events.EVENT_TIMEZONE, "UTC")
                put(Events.DTSTART, first.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
                put(Events.DURATION, if (flexible && task.repeatType == RepeatType.WEEKLY) "P7D" else "P1D")
            } else {
                val zone = ZoneId.systemDefault()
                put(Events.ALL_DAY, 0)
                put(Events.EVENT_TIMEZONE, zone.id)
                put(Events.DTSTART, first.atStartOfDay(zone).plusMinutes(minutes.toLong()).toInstant().toEpochMilli())
                put(Events.DURATION, "PT30M")
            }
        }
    }

    private fun byDay(day: DayOfWeek) = when (day) {
        DayOfWeek.MONDAY -> "MO"
        DayOfWeek.TUESDAY -> "TU"
        DayOfWeek.WEDNESDAY -> "WE"
        DayOfWeek.THURSDAY -> "TH"
        DayOfWeek.FRIDAY -> "FR"
        DayOfWeek.SATURDAY -> "SA"
        DayOfWeek.SUNDAY -> "SU"
    }
}
