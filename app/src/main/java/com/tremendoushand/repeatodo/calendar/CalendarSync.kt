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

/**
 * Mirrors tasks marked "캘린더에 표시" as repeating events in a calendar on the device
 * (for example the user's Google Calendar). Events are written by this app only; edits made
 * in the calendar app are overwritten on the next change.
 */
class CalendarSync(
    private val context: Context,
    private val db: AppDatabase,
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
            val calendarId = s.calendarId
            val links = db.calendarLinks().all().associateBy { it.taskId }
            val wanted = if (s.calendarEnabled && calendarId != null) {
                db.tasks().active().filter { it.showInCalendar }
            } else {
                emptyList()
            }
            val wantedIds = wanted.map { it.id }.toSet()

            for (link in links.values) {
                if (link.taskId !in wantedIds || link.calendarId != calendarId) {
                    deleteEvent(link.eventId)
                    db.calendarLinks().delete(link.taskId)
                }
            }
            if (calendarId == null) return@withContext
            for (task in wanted) {
                val values = eventValues(task, calendarId) ?: continue
                val link = links[task.id]?.takeIf { it.calendarId == calendarId }
                val updated = link != null && runCatching {
                    resolver.update(ContentUris.withAppendedId(Events.CONTENT_URI, link.eventId), values, null, null)
                }.getOrDefault(0) > 0
                if (!updated) {
                    val uri = runCatching { resolver.insert(Events.CONTENT_URI, values) }.getOrNull() ?: continue
                    db.calendarLinks().upsert(CalendarLinkEntity(task.id, ContentUris.parseId(uri), calendarId))
                }
            }
        }
    }

    /** Removes every event this app created. */
    suspend fun removeAll() = mutex.withLock {
        withContext(Dispatchers.IO) {
            if (hasPermission()) db.calendarLinks().all().forEach { deleteEvent(it.eventId) }
            db.calendarLinks().clear()
        }
    }

    private fun deleteEvent(eventId: Long) {
        runCatching { resolver.delete(ContentUris.withAppendedId(Events.CONTENT_URI, eventId), null, null) }
    }

    private fun eventValues(task: TaskEntity, calendarId: Long): ContentValues? {
        val rule = task.rule
        val flexible = rule.isFlexible
        val first: LocalDate = when {
            flexible && task.repeatType == RepeatType.WEEKLY -> weekStartOf(rule.startDate)
            flexible -> rule.startDate.withDayOfMonth(1)
            else -> rule.nextAttentionDate(rule.startDate) ?: return null
        }
        val rrule = when (task.repeatType) {
            RepeatType.DAILY -> "FREQ=DAILY"
            RepeatType.WEEKLY -> if (flexible) {
                "FREQ=WEEKLY;BYDAY=MO"
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
            task.repeatType == RepeatType.WEEKLY -> "[이번 주] "
            else -> "[이번 달] "
        }
        val minutes = task.reminderMinutes
        return ContentValues().apply {
            put(Events.CALENDAR_ID, calendarId)
            put(Events.TITLE, prefix + task.title)
            put(Events.DESCRIPTION, listOf(task.note, "Repeatodo에서 만든 일정").filter { it.isNotBlank() }.joinToString("\n"))
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
