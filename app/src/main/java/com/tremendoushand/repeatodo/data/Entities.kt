package com.tremendoushand.repeatodo.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.tremendoushand.repeatodo.domain.CategoryDto
import com.tremendoushand.repeatodo.domain.CompletionDto
import com.tremendoushand.repeatodo.domain.RepeatRule
import com.tremendoushand.repeatodo.domain.RepeatType
import com.tremendoushand.repeatodo.domain.TaskDto
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
    /** How many times one occurrence must be done (e.g. 8 glasses of water a day). */
    val targetCount: Int = 1,
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
    val rule: RepeatRule
        get() = RepeatRule(repeatType, weekDays, monthDay, LocalDate.ofEpochDay(startDate))
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

fun CategoryEntity.toDto() = CategoryDto(id, name, color, sortOrder, updatedAt, deleted)
fun CategoryDto.toEntity() = CategoryEntity(id, name, color, sortOrder, updatedAt, deleted)

fun TaskEntity.toDto() = TaskDto(
    id, title, note, categoryId, repeatType.name, weekDays, monthDay, reminderMinutes,
    targetCount, totalCount, startDate, showInCalendar, archived, sortOrder, createdAt, updatedAt, deleted,
)

fun TaskDto.toEntity() = TaskEntity(
    id = id, title = title, note = note, categoryId = categoryId,
    repeatType = runCatching { RepeatType.valueOf(repeatType) }.getOrDefault(RepeatType.DAILY),
    weekDays = weekDays, monthDay = monthDay, reminderMinutes = reminderMinutes,
    targetCount = targetCount, totalCount = totalCount, startDate = startDate,
    showInCalendar = showInCalendar, archived = archived, sortOrder = sortOrder,
    createdAt = createdAt, updatedAt = updatedAt, deleted = deleted,
)

fun CompletionEntity.toDto() = CompletionDto(id, taskId, occurrenceKey, count, updatedAt)
fun CompletionDto.toEntity() = CompletionEntity(id, taskId, occurrenceKey, count, updatedAt)
