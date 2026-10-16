package com.tremendoushand.repeatodo.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.tremendoushand.repeatodo.RepeatodoApp
import com.tremendoushand.repeatodo.data.SettingsRepository
import com.tremendoushand.repeatodo.data.TaskRepository
import com.tremendoushand.repeatodo.domain.Snapshot
import com.tremendoushand.repeatodo.domain.SnapshotJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Two-way sync of the whole database with one file in the user's Google Drive app folder. */
class SyncManager(
    private val context: Context,
    private val tasks: TaskRepository,
    private val settings: SettingsRepository,
) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    private val mutex = Mutex()

    /** Downloads, merges and uploads. Throws on failure; the error is also saved for the settings screen. */
    suspend fun syncNow() = mutex.withLock {
        try {
            val token = DriveAuth.tokenSilently(context)
                ?: throw DriveAuthException("구글 계정 연결이 필요해요. 설정에서 다시 연결해 주세요.")
            withContext(Dispatchers.IO) {
                val drive = DriveClient(token, http)
                val fileId = drive.findFileId()
                // If the Drive copy exists but can't be read, stop rather than overwrite it with local data.
                val remote = fileId?.let { id ->
                    val text = drive.download(id)
                    try {
                        SnapshotJson.decodeFromString(Snapshot.serializer(), text)
                    } catch (e: Exception) {
                        throw IOException("드라이브의 동기화 파일을 읽을 수 없어 동기화를 멈췄어요. 앱을 최신 버전으로 업데이트해 주세요.", e)
                    }
                }
                val merged = tasks.mergeIn(remote)
                val content = SnapshotJson.encodeToString(Snapshot.serializer(), merged)
                if (fileId == null) drive.create(content) else drive.update(fileId, content)
            }
            settings.setSyncResult(System.currentTimeMillis(), null)
        } catch (e: Exception) {
            settings.setSyncResult(null, e.message ?: e.javaClass.simpleName)
            throw e
        }
    }

    /** Applies the chosen interval and network rule to the background job. */
    suspend fun applySchedule() {
        val s = settings.current()
        val wm = WorkManager.getInstance(context)
        if (!s.driveConnected || s.syncIntervalHours <= 0) {
            wm.cancelUniqueWork(PERIODIC)
            return
        }
        val request = PeriodicWorkRequestBuilder<SyncWorker>(s.syncIntervalHours.toLong(), TimeUnit.HOURS)
            .setConstraints(constraints(s.syncWifiOnly))
            .build()
        wm.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    /** Runs a sync in the background as soon as any network is available. */
    fun requestOnce() {
        val request = OneTimeWorkRequestBuilder<SyncWorker>().setConstraints(constraints(wifiOnly = false)).build()
        WorkManager.getInstance(context).enqueueUniqueWork(ONCE, ExistingWorkPolicy.REPLACE, request)
    }

    private fun constraints(wifiOnly: Boolean) = Constraints.Builder()
        .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
        .build()

    companion object {
        private const val PERIODIC = "drive_sync_periodic"
        private const val ONCE = "drive_sync_once"
    }
}

class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as RepeatodoApp).container
        if (!container.settings.current().driveConnected) return Result.success()
        return try {
            container.sync.syncNow()
            Result.success()
        } catch (e: IOException) {
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        } catch (e: Exception) {
            Result.failure()
        }
    }
}
