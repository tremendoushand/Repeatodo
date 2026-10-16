package com.tremendoushand.repeatodo.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Dao
interface CategoryDao {
    @Query("SELECT * FROM categories WHERE deleted = 0 ORDER BY sortOrder, name")
    fun observe(): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM categories")
    suspend fun all(): List<CategoryEntity>

    @Query("SELECT * FROM categories WHERE id = :id")
    suspend fun get(id: String): CategoryEntity?

    @Upsert
    suspend fun upsert(items: List<CategoryEntity>)

    @Query("DELETE FROM categories WHERE id IN (:ids)")
    suspend fun deleteRows(ids: List<String>)
}

@Dao
interface TaskDao {
    /** Tasks that are not deleted, including finished (archived) ones. */
    @Query("SELECT * FROM tasks WHERE deleted = 0 ORDER BY sortOrder, createdAt")
    fun observe(): Flow<List<TaskEntity>>

    @Query("SELECT * FROM tasks WHERE deleted = 0 AND archived = 0 ORDER BY sortOrder, createdAt")
    suspend fun active(): List<TaskEntity>

    @Query("SELECT * FROM tasks WHERE deleted = 0")
    suspend fun notDeleted(): List<TaskEntity>

    @Query("SELECT * FROM tasks")
    suspend fun all(): List<TaskEntity>

    @Query("SELECT * FROM tasks WHERE id = :id")
    suspend fun get(id: String): TaskEntity?

    @Query("SELECT * FROM tasks WHERE categoryId = :categoryId AND deleted = 0")
    suspend fun inCategory(categoryId: String): List<TaskEntity>

    @Upsert
    suspend fun upsert(items: List<TaskEntity>)

    @Query("DELETE FROM tasks WHERE id IN (:ids)")
    suspend fun deleteRows(ids: List<String>)
}

@Dao
interface CompletionDao {
    @Query("SELECT * FROM completions WHERE count > 0")
    fun observeDone(): Flow<List<CompletionEntity>>

    @Query("SELECT * FROM completions")
    suspend fun all(): List<CompletionEntity>

    @Query("SELECT * FROM completions WHERE id = :id")
    suspend fun get(id: String): CompletionEntity?

    @Query(
        "SELECT COUNT(*) FROM completions c JOIN tasks t ON t.id = c.taskId " +
            "WHERE c.taskId = :taskId AND c.count >= t.targetCount",
    )
    suspend fun doneCount(taskId: String): Int

    @Query("SELECT * FROM completions WHERE taskId = :taskId AND occurrenceKey BETWEEN :fromKey AND :toKey")
    suspend fun between(taskId: String, fromKey: Long, toKey: Long): List<CompletionEntity>

    @Upsert
    suspend fun upsert(items: List<CompletionEntity>)

    @Query("DELETE FROM completions WHERE taskId IN (:taskIds)")
    suspend fun deleteForTasks(taskIds: List<String>)
}

@Dao
interface CalendarLinkDao {
    @Query("SELECT * FROM calendar_links")
    suspend fun all(): List<CalendarLinkEntity>

    @Upsert
    suspend fun upsert(link: CalendarLinkEntity)

    @Query("DELETE FROM calendar_links WHERE taskId = :taskId")
    suspend fun delete(taskId: String)

    @Query("DELETE FROM calendar_links")
    suspend fun clear()
}

@Database(
    entities = [CategoryEntity::class, TaskEntity::class, CompletionEntity::class],
    version = 2,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun categories(): CategoryDao
    abstract fun tasks(): TaskDao
    abstract fun completions(): CompletionDao

    companion object {
        /** 1 → 2: "any time" tasks can ask for several days per week/month. */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tasks ADD COLUMN periodTimes INTEGER NOT NULL DEFAULT 1")
            }
        }

        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "repeatodo.db")
                .addMigrations(MIGRATION_1_2)
                .build()
    }
}

/**
 * Data that only makes sense on this device (calendar event ids). Kept in its own file so it is
 * left out of Android backup and device transfer.
 */
@Database(entities = [CalendarLinkEntity::class], version = 1, exportSchema = true)
abstract class DeviceDatabase : RoomDatabase() {
    abstract fun calendarLinks(): CalendarLinkDao

    companion object {
        const val NAME = "device.db"
        fun create(context: Context): DeviceDatabase =
            Room.databaseBuilder(context, DeviceDatabase::class.java, NAME).build()
    }
}
