package com.tremendoushand.repeatodo.data

import androidx.room.withTransaction
import com.tremendoushand.repeatodo.domain.Snapshot
import com.tremendoushand.repeatodo.domain.completionId
import com.tremendoushand.repeatodo.domain.mergeSnapshots
import com.tremendoushand.repeatodo.domain.purgeTombstones
import kotlinx.coroutines.flow.Flow
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Single entry point for reading and changing tasks, categories and completions. */
class TaskRepository(private val db: AppDatabase) {
    /** Called after any change, so reminders and the calendar can follow. Set by [com.tremendoushand.repeatodo.AppContainer]. */
    var onChanged: suspend () -> Unit = {}

    val categories: Flow<List<CategoryEntity>> = db.categories().observe()
    val tasks: Flow<List<TaskEntity>> = db.tasks().observe()
    val doneCompletions: Flow<List<CompletionEntity>> = db.completions().observeDone()

    private fun now() = System.currentTimeMillis()

    suspend fun task(id: String): TaskEntity? = db.tasks().get(id)
    suspend fun activeTasks(): List<TaskEntity> = db.tasks().active()
    suspend fun allCompletions(): List<CompletionEntity> = db.completions().all()
    suspend fun completion(taskId: String, key: Long): CompletionEntity? = db.completions().get(completionId(taskId, key))

    suspend fun saveTask(task: TaskEntity): TaskEntity {
        val t = now()
        val saved = task.copy(
            id = task.id.ifBlank { UUID.randomUUID().toString() },
            createdAt = if (task.createdAt == 0L) t else task.createdAt,
            updatedAt = t,
        )
        db.tasks().upsert(listOf(saved))
        onChanged()
        return saved
    }

    suspend fun deleteTask(id: String) {
        val task = db.tasks().get(id) ?: return
        db.tasks().upsert(listOf(task.copy(deleted = true, updatedAt = now())))
        onChanged()
    }

    suspend fun setArchived(id: String, archived: Boolean) {
        val task = db.tasks().get(id) ?: return
        db.tasks().upsert(listOf(task.copy(archived = archived, updatedAt = now())))
        onChanged()
    }

    /**
     * One tap on a task: adds one to the occurrence's count, or clears it when it was already done.
     * Returns true when the occurrence is done afterwards.
     */
    suspend fun tap(taskId: String, key: Long): Boolean = changeCount(taskId, key) { count, target ->
        if (count >= target) 0 else count + 1
    }

    /** Marks the occurrence done in one go (used by the notification action). */
    suspend fun complete(taskId: String, key: Long): Boolean = changeCount(taskId, key) { _, target -> target }

    suspend fun clear(taskId: String, key: Long) {
        changeCount(taskId, key) { _, _ -> 0 }
    }

    private suspend fun changeCount(taskId: String, key: Long, next: (count: Int, target: Int) -> Int): Boolean {
        val done = db.withTransaction {
            val task = db.tasks().get(taskId) ?: return@withTransaction false
            val target = task.targetCount.coerceAtLeast(1)
            val id = completionId(taskId, key)
            val current = db.completions().get(id)?.count ?: 0
            val count = next(current, target).coerceIn(0, target)
            db.completions().upsert(listOf(CompletionEntity(id, taskId, key, count, now())))

            // Tasks with a total count finish once enough occurrences are done, and come back if undone.
            val total = task.totalCount
            if (total != null && total > 0) {
                val finished = db.completions().doneCount(taskId) >= total
                if (finished != task.archived) {
                    db.tasks().upsert(listOf(task.copy(archived = finished, updatedAt = now())))
                }
            }
            count >= target
        }
        onChanged()
        return done
    }

    suspend fun saveCategory(category: CategoryEntity): CategoryEntity {
        val saved = category.copy(id = category.id.ifBlank { UUID.randomUUID().toString() }, updatedAt = now())
        db.categories().upsert(listOf(saved))
        return saved
    }

    /** Deletes a category; its tasks stay and become uncategorized. */
    suspend fun deleteCategory(id: String) {
        db.withTransaction {
            val category = db.categories().get(id) ?: return@withTransaction
            val t = now()
            db.categories().upsert(listOf(category.copy(deleted = true, updatedAt = t)))
            db.tasks().upsert(db.tasks().inCategory(id).map { it.copy(categoryId = null, updatedAt = t) })
        }
        onChanged()
    }

    suspend fun seedDefaultCategories() {
        if (db.categories().all().isNotEmpty()) return
        val t = now()
        val defaults = listOf(
            "건강" to 0xFF43A047, "집안일" to 0xFFFB8C00, "업무" to 0xFF1E88E5, "자기계발" to 0xFF8E24AA,
        )
        db.categories().upsert(
            defaults.mapIndexed { i, (name, color) ->
                CategoryEntity(UUID.randomUUID().toString(), name, color, i, t)
            },
        )
    }

    suspend fun snapshot(): Snapshot = Snapshot(
        exportedAt = now(),
        categories = db.categories().all().map { it.toDto() },
        tasks = db.tasks().all().map { it.toDto() },
        completions = db.completions().all().map { it.toDto() },
    )

    /**
     * Merges [remote] into the local database (newest record wins) and returns the merged
     * snapshot, ready to be uploaded or saved.
     */
    suspend fun mergeIn(remote: Snapshot?): Snapshot {
        val merged = db.withTransaction {
            val local = snapshot()
            val cutoff = now() - TimeUnit.DAYS.toMillis(90)
            val merged = (if (remote == null) local else mergeSnapshots(local, remote, now())).purgeTombstones(cutoff)

            val localCategories = local.categories.associateBy { it.id }
            val localTasks = local.tasks.associateBy { it.id }
            val localCompletions = local.completions.associateBy { it.id }
            db.categories().upsert(merged.categories.filter { localCategories[it.id] != it }.map { it.toEntity() })
            db.tasks().upsert(merged.tasks.filter { localTasks[it.id] != it }.map { it.toEntity() })
            db.completions().upsert(merged.completions.filter { localCompletions[it.id] != it }.map { it.toEntity() })

            // Rows purged as old tombstones.
            val keptCategories = merged.categories.map { it.id }.toSet()
            val keptTasks = merged.tasks.map { it.id }.toSet()
            db.categories().deleteRows(local.categories.map { it.id }.filterNot { it in keptCategories })
            val goneTasks = local.tasks.map { it.id }.filterNot { it in keptTasks }
            db.completions().deleteForTasks(goneTasks)
            db.tasks().deleteRows(goneTasks)
            merged
        }
        onChanged()
        return merged
    }
}
