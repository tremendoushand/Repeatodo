package com.tremendoushand.repeatodo.domain

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

enum class RepeatType { DAILY, WEEKLY, MONTHLY }

/** Month day value meaning "the last day of the month". */
const val LAST_DAY = 32

/**
 * How a task repeats.
 *
 * - [weekDays]: bit 0 = Monday ... bit 6 = Sunday. 0 on a WEEKLY rule means "any day of the week, once".
 * - [monthDay]: 1..31 or [LAST_DAY]. 0 on a MONTHLY rule means "any day of the month, once".
 *   A day that a month doesn't have (e.g. 31 in April) falls on that month's last day.
 */
data class RepeatRule(
    val type: RepeatType,
    val weekDays: Int = 0,
    val monthDay: Int = 0,
    val startDate: LocalDate,
) {
    val isFlexible: Boolean
        get() = when (type) {
            RepeatType.DAILY -> false
            RepeatType.WEEKLY -> weekDays and WeekDays.ALL == 0
            RepeatType.MONTHLY -> monthDay == 0
        }
}

object WeekDays {
    const val ALL = 0x7F
    fun bit(day: DayOfWeek): Int = 1 shl (day.value - 1)
    fun contains(mask: Int, day: DayOfWeek): Boolean = mask and bit(day) != 0
    fun of(vararg days: DayOfWeek): Int = days.fold(0) { acc, d -> acc or bit(d) }
    fun toList(mask: Int, weekStart: DayOfWeek = DayOfWeek.MONDAY): List<DayOfWeek> =
        (0 until 7).map { weekStart.plus(it.toLong()) }.filter { contains(mask, it) }
}

/**
 * One slot of a task that can be completed.
 *
 * [key] identifies the slot: the epoch day of the due date, or of the period's first day
 * for flexible ("any time this week/month") rules. For flexible weekly rules the week is
 * always counted Monday to Sunday so that keys stay stable when the display week start changes.
 */
data class Occurrence(
    val key: Long,
    val start: LocalDate,
    val end: LocalDate,
    val flexible: Boolean,
) {
    fun contains(date: LocalDate) = !date.isBefore(start) && !date.isAfter(end)
}

fun weekStartOf(date: LocalDate, weekStart: DayOfWeek = DayOfWeek.MONDAY): LocalDate =
    date.with(TemporalAdjusters.previousOrSame(weekStart))

private fun resolvedMonthDay(monthDay: Int, date: LocalDate): Int =
    if (monthDay >= LAST_DAY) date.lengthOfMonth() else minOf(monthDay, date.lengthOfMonth())

/** Whether a non-flexible rule is due on [date]. Flexible rules are never "due" on a single day. */
fun RepeatRule.isDueOn(date: LocalDate): Boolean {
    if (date.isBefore(startDate) || isFlexible) return false
    return when (type) {
        RepeatType.DAILY -> true
        RepeatType.WEEKLY -> WeekDays.contains(weekDays, date.dayOfWeek)
        RepeatType.MONTHLY -> date.dayOfMonth == resolvedMonthDay(monthDay, date)
    }
}

private fun RepeatRule.flexiblePeriod(date: LocalDate): Occurrence {
    val (start, end) = when (type) {
        RepeatType.WEEKLY -> weekStartOf(date).let { it to it.plusDays(6) }
        else -> date.withDayOfMonth(1).let { it to it.withDayOfMonth(it.lengthOfMonth()) }
    }
    return Occurrence(start.toEpochDay(), start, end, flexible = true)
}

/** The occurrence that is open on [date], if any. */
fun RepeatRule.occurrenceOn(date: LocalDate): Occurrence? {
    if (isFlexible) {
        val p = flexiblePeriod(date)
        return if (p.end.isBefore(startDate)) null else p
    }
    return if (isDueOn(date)) Occurrence(date.toEpochDay(), date, date, flexible = false) else null
}

/** All occurrences that overlap [from]..[to] (inclusive), oldest first. */
fun RepeatRule.occurrencesBetween(from: LocalDate, to: LocalDate): List<Occurrence> {
    if (to.isBefore(from)) return emptyList()
    val result = mutableListOf<Occurrence>()
    if (isFlexible) {
        var d = from
        while (!d.isAfter(to)) {
            val p = flexiblePeriod(d)
            if (!p.end.isBefore(startDate)) result += p
            d = p.end.plusDays(1)
        }
    } else {
        var d = if (from.isBefore(startDate)) startDate else from
        while (!d.isAfter(to)) {
            if (isDueOn(d)) result += Occurrence(d.toEpochDay(), d, d, flexible = false)
            d = d.plusDays(1)
        }
    }
    return result
}

/**
 * The first date on or after [from] on which this rule wants attention:
 * its next due date, or every day of an open flexible period.
 */
fun RepeatRule.nextAttentionDate(from: LocalDate): LocalDate? {
    val start = if (from.isBefore(startDate)) startDate else from
    if (isFlexible) return start
    var d = start
    repeat(400) {
        if (isDueOn(d)) return d
        d = d.plusDays(1)
    }
    return null
}

/** The occurrence right before [occ]. */
fun RepeatRule.previousOccurrence(occ: Occurrence): Occurrence? {
    if (isFlexible) {
        val p = flexiblePeriod(occ.start.minusDays(1))
        return if (p.end.isBefore(startDate)) null else p
    }
    var d = occ.start.minusDays(1)
    while (!d.isBefore(startDate)) {
        if (isDueOn(d)) return Occurrence(d.toEpochDay(), d, d, flexible = false)
        d = d.minusDays(1)
    }
    return null
}

/**
 * Consecutive completed occurrences ending now. The occurrence open today doesn't
 * break the streak while it is still pending.
 */
fun RepeatRule.streak(isDone: (Long) -> Boolean, today: LocalDate): Int {
    var occ: Occurrence? = occurrenceOn(today) ?: run {
        // Not due today: start from the most recent past occurrence.
        val probe = Occurrence(today.toEpochDay(), today, today, false)
        previousOccurrence(probe)
    }
    var count = 0
    var first = true
    var guard = 0
    while (occ != null && guard++ < 1000) {
        if (isDone(occ.key)) {
            count++
        } else if (!(first && occ.contains(today))) {
            break
        }
        first = false
        occ = previousOccurrence(occ)
    }
    return count
}
