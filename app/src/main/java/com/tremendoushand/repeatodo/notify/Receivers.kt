package com.tremendoushand.repeatodo.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.tremendoushand.repeatodo.AppContainer
import com.tremendoushand.repeatodo.RepeatodoApp
import com.tremendoushand.repeatodo.domain.RepeatType
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
                    val occ = task.rule.occurrenceOn(today)
                    if (occ != null) {
                        val count = c.tasks.completion(task.id, occ.key)?.count ?: 0
                        if (count < task.targetCount) {
                            val subtitle = if (occ.flexible) {
                                if (task.repeatType == RepeatType.WEEKLY) "이번 주 안에" else "이번 달 안에"
                            } else null
                            Notifications.showTask(context, task, occ.key, count, subtitle)
                        }
                    }
                }
            }
            "digest" -> {
                val pending = c.tasks.activeTasks()
                    .filter { it.reminderMinutes == null }
                    .filter { task ->
                        val occ = task.rule.occurrenceOn(today) ?: return@filter false
                        (c.tasks.completion(task.id, occ.key)?.count ?: 0) < task.targetCount
                    }
                Notifications.showDigest(context, pending.map { it.title })
            }
        }
        c.reminders.rescheduleAll()
    }
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
