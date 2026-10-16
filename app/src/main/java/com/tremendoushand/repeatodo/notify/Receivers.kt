package com.tremendoushand.repeatodo.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.tremendoushand.repeatodo.AppContainer
import com.tremendoushand.repeatodo.RepeatodoApp
import com.tremendoushand.repeatodo.domain.RepeatType
import com.tremendoushand.repeatodo.data.TaskEntity
import com.tremendoushand.repeatodo.domain.doneDays
import com.tremendoushand.repeatodo.domain.isDone
import com.tremendoushand.repeatodo.domain.occurrenceOn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.time.LocalDate

private val receiverScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

/** Runs [block] off the main thread while keeping the broadcast alive. */
private fun BroadcastReceiver.runAsync(context: Context, block: suspend (AppContainer) -> Unit) {
    val pending = goAsync()
    val container = (context.applicationContext as RepeatodoApp).container
    receiverScope.launch {
        try {
            block(container)
        } finally {
            pending.finish()
        }
    }
}

/** Fires for a task's own reminder time and for each grouped reminder time. */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = runAsync(context) { c ->
        val (kind, value) = ReminderScheduler.parse(intent.data) ?: return@runAsync
        val today = LocalDate.now()
        when (kind) {
            "task" -> {
                val task = c.tasks.task(value)
                if (task != null && !task.deleted && !task.archived && task.reminderMinutes != null) {
                    val left = remaining(c, task, today)
                    if (left != null) Notifications.showTask(context, task, today.toEpochDay(), left.todayCount, left.subtitle)
                }
            }
            "digest" -> {
                val pending = c.tasks.activeTasks()
                    .filter { it.reminderMinutes == null }
                    .filter { remaining(c, it, today) != null }
                Notifications.showDigest(context, pending.map { it.title })
            }
        }
        c.reminders.rescheduleAll()
    }
}

private class Remaining(val todayCount: Int, val subtitle: String?)

/** What's still left of [task] today, or null when there is nothing to remind about. */
private suspend fun remaining(c: AppContainer, task: TaskEntity, today: LocalDate): Remaining? {
    val rule = task.rule
    val occ = rule.occurrenceOn(today) ?: return null
    val counts = c.tasks.counts(task.id, occ.start.toEpochDay(), occ.end.toEpochDay())
    val todayCount = counts[today.toEpochDay()] ?: 0
    val goal = task.goal
    // Nothing left when today's part is done, or the whole week/month already is.
    if (todayCount >= goal.perDay || rule.isDone(occ, { counts[it] ?: 0 }, goal)) return null
    val subtitle = if (occ.flexible) {
        val period = if (task.repeatType == RepeatType.WEEKLY) "이번 주" else "이번 달"
        "$period ${rule.doneDays(occ, { counts[it] ?: 0 }, goal)}/${goal.daysPerPeriod}일"
    } else null
    return Remaining(todayCount, subtitle)
}

/** The "완료" button on a task notification. */
class CompleteActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = runAsync(context) { c ->
        val segments = intent.data?.pathSegments ?: return@runAsync
        val taskId = segments.getOrNull(0) ?: return@runAsync
        val key = segments.getOrNull(1)?.toLongOrNull() ?: return@runAsync
        c.tasks.complete(taskId, key)
        Notifications.cancelTask(context, taskId)
    }
}

/** Alarms are cleared on reboot and can drift after time changes, so set them again. */
class RescheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = runAsync(context) { c ->
        c.reminders.rescheduleAll()
    }
}
