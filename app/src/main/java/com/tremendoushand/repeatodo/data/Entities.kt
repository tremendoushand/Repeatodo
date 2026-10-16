package com.tremendoushand.repeatodo.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.tremendoushand.repeatodo.domain.CategoryDto
import com.tremendoushand.repeatodo.domain.CompletionDto
import com.tremendoushand.repeatodo.domain.Goal
import com.tremendoushand.repeatodo.domain.LAST_DAY
import com.tremendoushand.repeatodo.domain.RepeatRule
import com.tremendoushand.repeatodo.domain.RepeatType
import com.tremendoushand.repeatodo.domain.TaskDto
import com.tremendoushand.repeatodo.domain.WeekDays
import java.time.DayOfWeek
import java.time.LocalDate

@Entity(tableName = "categories")
data class CategoryEntity(
    @PrimaryKey val id: String,
    val name: String,
    val color: Long,
    val sortOrder: Int = 0,
    val updatedAt: Long,
    val deleted: Boolean = false,
)

@Entity(tableName = "tasks", indices = [Index("categoryId")])
data class TaskEntity(
    @PrimaryKey val id: String,
    val title: String,
    val note: String = "",
    val categoryId: String? = null,
    val repeatType: RepeatType = RepeatType.DAILY,
    val weekDays: Int = 0,
    val monthDay: Int = 0,
    /** Minutes after midnight, or null to use the grouped reminder times. */
    val reminderMinutes: Int? = null,
    /** How many times it must be done on a day (e.g. 8 glasses of water a day). */
    val targetCount: Int = 1,
    /** For "any time this week/month" rules: on how many different days (e.g. 2 days a week). */
    @ColumnInfo(defaultValue = "1")
    val periodTimes: Int = 1,
    /** Finish the task after this many completed occurrences, or null to repeat forever. */
    val totalCount: Int? = null,
    val startDate: Long,
    val showInCalendar: Boolean = false,
    val archived: Boolean = false,
    val sortOrder: Int = 0,
    val createdAt: Long,
    val updatedAt: Long,
    val deleted: Boolean = false,
) {
    /** The repeat rule, with weeks starting on [weekStart] (the user's setting). */
    fun rule(weekStart: DayOfWeek): RepeatRule =
        RepeatRule(repeatType, weekDays, monthDay, LocalDate.ofEpochDay(startDate), weekStart)

    /** "Any time this week/month" rather than on set days. */
    val isFlexible: Boolean get() = rule(DayOfWeek.MONDAY).isFlexible

    val goal: Goal
        get() = Goal(perDay = targetCount.coerceAtLeast(1), daysPerPeriod = if (isFlexible) periodTimes.coerceAtLeast(1) else 1)
}

@Entity(tableName = "completions", indices = [Index("taskId")])
data class CompletionEntity(
    @PrimaryKey val id: String,
    val taskId: String,
    val occurrenceKey: Long,
    val count: Int,
    val updatedAt: Long,
)

/** Which device calendar event mirrors a task. Device-only, never synced. */
@Entity(tableName = "calendar_links")
data class CalendarLinkEntity(
    @PrimaryKey val taskId: String,
    val eventId: Long,
    val calendarId: Long,
)

// Year 1900..2200, far inside what java.time accepts.
private val MIN_EPOCH_DAY = LocalDate.of(1900, 1, 1).toEpochDay()
private val MAX_EPOCH_DAY = LocalDate.of(2200, 1, 1).toEpochDay()

fun CategoryEntity.toDto() = CategoryDto(id, name, color, sortOrder, updatedAt, deleted)
fun CategoryDto.toEntity() = CategoryEntity(id, name.take(100), color, sortOrder, updatedAt, deleted)

fun TaskEntity.toDto() = TaskDto(
    id, title, note, categoryId, repeatType.name, weekDays, monthDay, reminderMinutes,
    targetCount, totalCount, startDate, showInCalendar, archived, sortOrder, createdAt, updatedAt, deleted,
    periodTimes = periodTimes,
)

/** Values from a backup or Drive file are clamped to sane ranges so a damaged file can't crash the app. */
fun TaskDto.toEntity() = TaskEntity(
    id = id, title = title.take(500), note = note.take(5000), categoryId = categoryId,
    repeatType = runCatching { RepeatType.valueOf(repeatType) }.getOrDefault(RepeatType.DAILY),
    weekDays = weekDays and WeekDays.ALL, monthDay = monthDay.coerceIn(0, LAST_DAY),
    reminderMinutes = reminderMinutes?.coerceIn(0, 24 * 60 - 1),
    targetCount = targetCount.coerceIn(1, 99), periodTimes = periodTimes.coerceIn(1, 31),
    totalCount = totalCount?.coerceIn(1, 9999),
    startDate = startDate.coerceIn(MIN_EPOCH_DAY, MAX_EPOCH_DAY),
    showInCalendar = showInCalendar, archived = archived, sortOrder = sortOrder,
    createdAt = createdAt, updatedAt = updatedAt, deleted = deleted,
)

fun CompletionEntity.toDto() = CompletionDto(id, taskId, occurrenceKey, count, updatedAt)
fun CompletionDto.toEntity() = CompletionEntity(id, taskId, occurrenceKey, count.coerceIn(0, 99), updatedAt)
