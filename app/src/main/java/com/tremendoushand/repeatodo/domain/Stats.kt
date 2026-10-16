package com.tremendoushand.repeatodo.domain

import java.time.LocalDate

enum class SlotState { DONE, MISSED, PENDING, UPCOMING }

fun slotState(occ: Occurrence, done: Boolean, today: LocalDate): SlotState = when {
    done -> SlotState.DONE
    occ.end.isBefore(today) -> SlotState.MISSED
    occ.start.isAfter(today) -> SlotState.UPCOMING
    else -> SlotState.PENDING
}

data class Rate(val done: Int, val counted: Int) {
    /** 0..1, or null when nothing was countable yet. */
    val ratio: Float? get() = if (counted == 0) null else done.toFloat() / counted

    operator fun plus(other: Rate) = Rate(done + other.done, counted + other.counted)

    companion object {
        val ZERO = Rate(0, 0)
    }
}

/**
 * Completion rate of a rule over [from]..[to].
 *
 * A dated occurrence counts from its day on, so today's unfinished tasks show as not done yet.
 * An "any time this week/month" occurrence only counts once it's done or its period has ended,
 * so it doesn't drag the rate down while there is still time.
 */
fun RepeatRule.rateBetween(
    from: LocalDate,
    to: LocalDate,
    today: LocalDate,
    isDone: (Occurrence) -> Boolean,
): Rate {
    var done = 0
    var counted = 0
    for (occ in occurrencesBetween(from, to)) {
        when (slotState(occ, isDone(occ), today)) {
            SlotState.DONE -> { done++; counted++ }
            SlotState.MISSED -> counted++
            SlotState.PENDING -> if (!occ.flexible) counted++
            SlotState.UPCOMING -> Unit
        }
    }
    return Rate(done, counted)
}
