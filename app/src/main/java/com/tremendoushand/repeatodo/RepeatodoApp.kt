package com.tremendoushand.repeatodo

import android.app.Application
import android.content.Context
import com.tremendoushand.repeatodo.calendar.CalendarSync
import com.tremendoushand.repeatodo.data.AppDatabase
import com.tremendoushand.repeatodo.data.SettingsRepository
import com.tremendoushand.repeatodo.data.TaskRepository
import com.tremendoushand.repeatodo.notify.Notifications
import com.tremendoushand.repeatodo.notify.ReminderScheduler
import com.tremendoushand.repeatodo.sync.SyncManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Creates and wires the app's long-lived objects. */
class AppContainer(context: Context) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val db = AppDatabase.create(context)
    val settings = SettingsRepository(context)
    val tasks = TaskRepository(db)
    val reminders = ReminderScheduler(context, tasks, settings)
    val calendar = CalendarSync(context, db, settings)
    val sync = SyncManager(context, tasks, settings)

    init {
        tasks.onChanged = {
            // Keep the UI snappy: follow-up work runs on its own.
            scope.launch { reminders.rescheduleAll() }
            scope.launch { runCatching { calendar.syncAll() } }
        }
    }
}

class RepeatodoApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        Notifications.createChannels(this)
        container.scope.launch {
            container.tasks.seedDefaultCategories()
            container.reminders.rescheduleAll()
            container.sync.applySchedule()
            runCatching { container.calendar.syncAll() }
        }
    }
}
