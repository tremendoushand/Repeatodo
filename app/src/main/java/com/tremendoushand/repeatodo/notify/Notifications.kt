package com.tremendoushand.repeatodo.notify

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import com.tremendoushand.repeatodo.MainActivity
import com.tremendoushand.repeatodo.R
import com.tremendoushand.repeatodo.data.TaskEntity

object Notifications {
    const val CHANNEL_TASK = "task_reminders"
    const val CHANNEL_DIGEST = "digest"
    private const val DIGEST_ID = 1

    fun createChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannels(
            listOf(
                NotificationChannel(CHANNEL_TASK, "할 일 알림", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "알림 시간을 정한 할 일을 그 시간에 알려줍니다."
                },
                NotificationChannel(CHANNEL_DIGEST, "모아서 알림", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "알림 시간을 정하지 않은 할 일을 정해진 시간에 모아서 알려줍니다."
                },
            ),
        )
    }

    fun canNotify(context: Context): Boolean =
        (Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED) &&
            NotificationManagerCompat.from(context).areNotificationsEnabled()

    private fun openAppIntent(context: Context): PendingIntent = PendingIntent.getActivity(
        context, 0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun taskNotificationId(taskId: String) = taskId.hashCode() or 0x10000

    @SuppressLint("MissingPermission")
    fun showTask(context: Context, task: TaskEntity, occurrenceKey: Long, count: Int, subtitle: String?) {
        if (!canNotify(context)) return
        val complete = PendingIntent.getBroadcast(
            context, 0,
            Intent(context, CompleteActionReceiver::class.java)
                .setData("repeatodo://complete/${task.id}/$occurrenceKey".toUri()),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val text = buildList {
            if (subtitle != null) add(subtitle)
            if (task.targetCount > 1) add("$count/${task.targetCount}회")
            if (task.note.isNotBlank()) add(task.note)
        }.joinToString(" · ")
        val notification = NotificationCompat.Builder(context, CHANNEL_TASK)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(task.title)
            .setContentText(text.ifBlank { "할 일을 할 시간이에요." })
            .setContentIntent(openAppIntent(context))
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .addAction(0, if (task.targetCount > 1) "모두 완료" else "완료", complete)
            .build()
        NotificationManagerCompat.from(context).notify(taskNotificationId(task.id), notification)
    }

    @SuppressLint("MissingPermission")
    fun showDigest(context: Context, titles: List<String>) {
        if (!canNotify(context) || titles.isEmpty()) return
        val style = NotificationCompat.InboxStyle()
        titles.take(7).forEach { style.addLine("• $it") }
        if (titles.size > 7) style.setSummaryText("외 ${titles.size - 7}개")
        val notification = NotificationCompat.Builder(context, CHANNEL_DIGEST)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("아직 남은 할 일 ${titles.size}개")
            .setContentText(titles.joinToString(", "))
            .setStyle(style)
            .setContentIntent(openAppIntent(context))
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(DIGEST_ID, notification)
    }

    fun cancelTask(context: Context, taskId: String) {
        NotificationManagerCompat.from(context).cancel(taskNotificationId(taskId))
    }
}
