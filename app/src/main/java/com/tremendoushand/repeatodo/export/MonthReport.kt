package com.tremendoushand.repeatodo.export

import com.tremendoushand.repeatodo.data.CategoryEntity
import com.tremendoushand.repeatodo.data.CompletionEntity
import com.tremendoushand.repeatodo.data.TaskEntity
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth

/** What was done in one month, ready to be written out. */
data class MonthReport(
    val month: YearMonth,
    val tasks: List<TaskLine>,
    /** First column of the calendar sheet. */
    val weekStart: DayOfWeek = DayOfWeek.MONDAY,
) {
    /** Entries for [date], in task order. */
    fun entriesOn(date: LocalDate): List<DayEntry> = tasks.mapNotNull { line ->
        line.counts[date.dayOfMonth]?.let { DayEntry(line.title, it, line.perDay) }
    }
}

data class DayEntry(val title: String, val count: Int, val perDay: Int)

data class TaskLine(
    val category: String,
    val title: String,
    val repeat: String,
    val perDay: Int,
    /** Day of month to times done that day; days with nothing done are left out. */
    val counts: Map<Int, Int>,
) {
    val total: Int get() = counts.values.sum()
    val days: Int get() = counts.size
}

/** Collects the tasks that were worked on in [month], in category then task order. */
fun buildMonthReport(
    month: YearMonth,
    tasks: List<TaskEntity>,
    categories: List<CategoryEntity>,
    completions: List<CompletionEntity>,
    weekStart: DayOfWeek,
    repeatLabel: (TaskEntity) -> String,
): MonthReport {
    val from = month.atDay(1).toEpochDay()
    val to = month.atEndOfMonth().toEpochDay()
    val byCategory = categories.associateBy { it.id }
    val counts = completions
        .filter { it.count > 0 && it.occurrenceKey in from..to }
        .groupBy { it.taskId }
    val lines = tasks
        .filter { counts.containsKey(it.id) }
        .sortedWith(compareBy({ byCategory[it.categoryId]?.sortOrder ?: Int.MAX_VALUE }, { it.sortOrder }, { it.createdAt }))
        .map { task ->
            TaskLine(
                category = byCategory[task.categoryId]?.name.orEmpty(),
                title = task.title,
                repeat = repeatLabel(task),
                perDay = task.targetCount.coerceAtLeast(1),
                counts = counts.getValue(task.id).associate { LocalDate.ofEpochDay(it.occurrenceKey).dayOfMonth to it.count },
            )
        }
    return MonthReport(month, lines, weekStart)
}
