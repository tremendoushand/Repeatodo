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
 * Completion rate of a rule over [from]..[to]. Occurrences still open or upcoming
 * only count once they are done, so an unfinished day never drags the rate down early.
 */
fun RepeatRule.rateBetween(
    from: LocalDate,
    to: LocalDate,
    today: LocalDate,
    isDone: (Long) -> Boolean,
): Rate {
    var done = 0
    var counted = 0
    for (occ in occurrencesBetween(from, to)) {
        when (slotState(occ, isDone(occ.key), today)) {
            SlotState.DONE -> { done++; counted++ }
            SlotState.MISSED -> counted++
            else -> Unit
        }
    }
    return Rate(done, counted)
}
