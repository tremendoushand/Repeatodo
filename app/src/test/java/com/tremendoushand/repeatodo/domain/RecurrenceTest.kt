package com.tremendoushand.repeatodo.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek.FRIDAY
import java.time.DayOfWeek.MONDAY
import java.time.DayOfWeek.WEDNESDAY
import java.time.LocalDate

class RecurrenceTest {
    private val start = LocalDate.of(2026, 10, 1) // Thursday

    @Test
    fun daily_isDueEveryDayFromStart() {
        val rule = RepeatRule(RepeatType.DAILY, startDate = start)
        assertFalse(rule.isDueOn(start.minusDays(1)))
        assertTrue(rule.isDueOn(start))
        assertEquals(31, rule.occurrencesBetween(start, LocalDate.of(2026, 10, 31)).size)
    }

    @Test
    fun weekly_specificDays() {
        val rule = RepeatRule(RepeatType.WEEKLY, weekDays = WeekDays.of(MONDAY, WEDNESDAY, FRIDAY), startDate = start)
        val occ = rule.occurrencesBetween(LocalDate.of(2026, 10, 5), LocalDate.of(2026, 10, 11))
        assertEquals(listOf(5, 7, 9), occ.map { it.start.dayOfMonth })
        assertEquals(LocalDate.of(2026, 10, 2), rule.nextAttentionDate(start))
    }

    @Test
    fun weekly_flexibleUsesMondayWeeks() {
        val rule = RepeatRule(RepeatType.WEEKLY, weekDays = 0, startDate = start)
        assertTrue(rule.isFlexible)
        val occ = rule.occurrenceOn(LocalDate.of(2026, 10, 8))!!
        assertEquals(LocalDate.of(2026, 10, 5), occ.start)
        assertEquals(LocalDate.of(2026, 10, 11), occ.end)
        // The week the task starts in still counts.
        assertEquals(LocalDate.of(2026, 9, 28), rule.occurrenceOn(start)!!.start)
        assertEquals(5, rule.occurrencesBetween(start, LocalDate.of(2026, 10, 31)).size)
    }

    @Test
    fun monthly_dayClampsToShortMonths() {
        val rule = RepeatRule(RepeatType.MONTHLY, monthDay = 31, startDate = LocalDate.of(2026, 1, 1))
        assertTrue(rule.isDueOn(LocalDate.of(2026, 2, 28)))
        assertTrue(rule.isDueOn(LocalDate.of(2026, 4, 30)))
        assertTrue(rule.isDueOn(LocalDate.of(2026, 5, 31)))
        assertFalse(rule.isDueOn(LocalDate.of(2026, 5, 30)))
    }

    @Test
    fun monthly_lastDayAndFlexible() {
        val last = RepeatRule(RepeatType.MONTHLY, monthDay = LAST_DAY, startDate = start)
        assertEquals(LocalDate.of(2026, 10, 31), last.nextAttentionDate(start))
        val flex = RepeatRule(RepeatType.MONTHLY, monthDay = 0, startDate = start)
        val occ = flex.occurrenceOn(LocalDate.of(2026, 11, 15))!!
        assertEquals(LocalDate.of(2026, 11, 1), occ.start)
        assertEquals(LocalDate.of(2026, 11, 30), occ.end)
    }

    @Test
    fun previousOccurrence_stopsAtStart() {
        val rule = RepeatRule(RepeatType.DAILY, startDate = start)
        assertNull(rule.previousOccurrence(rule.occurrenceOn(start)!!))
    }

    @Test
    fun streak_countsBackAndIgnoresPendingToday() {
        val rule = RepeatRule(RepeatType.DAILY, startDate = start)
        val today = LocalDate.of(2026, 10, 10)
        val done = setOf(7L, 8L, 9L).map { LocalDate.of(2026, 10, it.toInt()).toEpochDay() }.toSet()
        assertEquals(3, rule.streak({ it in done }, today))
        assertEquals(4, rule.streak({ it in done + today.toEpochDay() }, today))
        assertEquals(0, rule.streak({ false }, today))
    }

    @Test
    fun streak_notDueTodayStartsFromLastOccurrence() {
        val rule = RepeatRule(RepeatType.WEEKLY, weekDays = WeekDays.of(MONDAY), startDate = start)
        val done = setOf(LocalDate.of(2026, 10, 5).toEpochDay(), LocalDate.of(2026, 10, 12).toEpochDay())
        assertEquals(2, rule.streak({ it in done }, LocalDate.of(2026, 10, 14)))
    }

    @Test
    fun rate_countsTodayButNotUpcoming() {
        val rule = RepeatRule(RepeatType.DAILY, startDate = start)
        val today = LocalDate.of(2026, 10, 5)
        val done = setOf(LocalDate.of(2026, 10, 1).toEpochDay(), LocalDate.of(2026, 10, 2).toEpochDay())
        val rate = rule.rateBetween(start, LocalDate.of(2026, 10, 31), today) { it in done }
        // 1..4 are closed (2 done, 2 missed), 5 is today and not done yet, the rest upcoming.
        assertEquals(Rate(2, 5), rate)
    }

    @Test
    fun rate_mixedSingleAndMultiCountTasksToday() {
        // A single task done today and an 8-times-a-day task done 3 times: the day is 1 of 2, not 100%.
        val today = LocalDate.of(2026, 10, 5)
        val single = RepeatRule(RepeatType.DAILY, startDate = today)
        val multi = RepeatRule(RepeatType.DAILY, startDate = today)
        val counts = mapOf("single" to 1, "multi" to 3)
        val targets = mapOf("single" to 1, "multi" to 8)
        val total = listOf("single" to single, "multi" to multi).fold(Rate.ZERO) { acc, (id, rule) ->
            acc + rule.rateBetween(today, today.plusDays(6), today) { (counts[id] ?: 0) >= targets.getValue(id) }
        }
        assertEquals(Rate(1, 2), total)
    }

    @Test
    fun rate_flexibleCountsOnlyWhenDoneOrOver() {
        val rule = RepeatRule(RepeatType.WEEKLY, weekDays = 0, startDate = start)
        val today = LocalDate.of(2026, 10, 7)
        assertEquals(Rate(0, 0), rule.rateBetween(LocalDate.of(2026, 10, 5), LocalDate.of(2026, 10, 11), today) { false })
        assertEquals(Rate(1, 1), rule.rateBetween(LocalDate.of(2026, 10, 5), LocalDate.of(2026, 10, 11), today) { true })
    }

    @Test
    fun merge_latestWinsAndPurgeDropsOldTombstones() {
        val a = Snapshot(tasks = listOf(task("t1", "old", 10), task("t2", "keep", 10)))
        val b = Snapshot(tasks = listOf(task("t1", "new", 20), task("t3", "gone", 5, deleted = true)),
            completions = listOf(CompletionDto(completionId("t3", 1), "t3", 1, 1, 5)))
        val merged = mergeSnapshots(a, b, now = 100)
        assertEquals("new", merged.tasks.first { it.id == "t1" }.title)
        assertEquals(3, merged.tasks.size)
        val purged = merged.purgeTombstones(olderThan = 6)
        assertEquals(setOf("t1", "t2"), purged.tasks.map { it.id }.toSet())
        assertTrue(purged.completions.isEmpty())
        // Round trip through JSON.
        assertEquals(purged, SnapshotJson.decodeFromString(Snapshot.serializer(), SnapshotJson.encodeToString(Snapshot.serializer(), purged)))
    }

    private fun task(id: String, title: String, updatedAt: Long, deleted: Boolean = false) =
        TaskDto(id = id, title = title, repeatType = "DAILY", startDate = 0, updatedAt = updatedAt, deleted = deleted)
}
