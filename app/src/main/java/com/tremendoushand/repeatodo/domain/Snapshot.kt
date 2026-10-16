package com.tremendoushand.repeatodo.domain

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Everything that is backed up or synced. Device-only data (calendar links, settings) is left out. */
@Serializable
data class Snapshot(
    val version: Int = 1,
    val exportedAt: Long = 0,
    val categories: List<CategoryDto> = emptyList(),
    val tasks: List<TaskDto> = emptyList(),
    val completions: List<CompletionDto> = emptyList(),
)

@Serializable
data class CategoryDto(
    val id: String,
    val name: String,
    val color: Long,
    val sortOrder: Int = 0,
    val updatedAt: Long,
    val deleted: Boolean = false,
)

@Serializable
data class TaskDto(
    val id: String,
    val title: String,
    val note: String = "",
    val categoryId: String? = null,
    val repeatType: String,
    val weekDays: Int = 0,
    val monthDay: Int = 0,
    val reminderMinutes: Int? = null,
    val targetCount: Int = 1,
    val totalCount: Int? = null,
    val startDate: Long,
    val showInCalendar: Boolean = false,
    val archived: Boolean = false,
    val sortOrder: Int = 0,
    val createdAt: Long = 0,
    val updatedAt: Long,
    val deleted: Boolean = false,
    val periodTimes: Int = 1,
)

@Serializable
data class CompletionDto(
    val id: String,
    val taskId: String,
    val occurrenceKey: Long,
    val count: Int,
    val updatedAt: Long,
)

val SnapshotJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    prettyPrint = false
}

private fun <T> mergeById(a: List<T>, b: List<T>, id: (T) -> String, updatedAt: (T) -> Long): List<T> {
    val out = LinkedHashMap<String, T>()
    for (item in a) out[id(item)] = item
    for (item in b) {
        val current = out[id(item)]
        if (current == null || updatedAt(item) > updatedAt(current)) out[id(item)] = item
    }
    return out.values.toList()
}

/** Merges two snapshots record by record; the most recently updated version wins. */
fun mergeSnapshots(local: Snapshot, remote: Snapshot, now: Long): Snapshot = Snapshot(
    version = maxOf(local.version, remote.version),
    exportedAt = now,
    categories = mergeById(local.categories, remote.categories, { it.id }, { it.updatedAt }),
    tasks = mergeById(local.tasks, remote.tasks, { it.id }, { it.updatedAt }),
    completions = mergeById(local.completions, remote.completions, { it.id }, { it.updatedAt }),
)

/** Drops deletion markers older than [olderThan] along with completions of deleted tasks. */
fun Snapshot.purgeTombstones(olderThan: Long): Snapshot {
    val goneTasks = tasks.filter { it.deleted && it.updatedAt < olderThan }.map { it.id }.toSet()
    return copy(
        categories = categories.filterNot { it.deleted && it.updatedAt < olderThan },
        tasks = tasks.filterNot { it.id in goneTasks },
        completions = completions.filterNot { it.taskId in goneTasks },
    )
}

fun completionId(taskId: String, occurrenceKey: Long) = "$taskId:$occurrenceKey"
