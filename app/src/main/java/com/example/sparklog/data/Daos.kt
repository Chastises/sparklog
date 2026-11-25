package com.example.sparklog.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import android.content.Context
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

/** v7 -> v8: rates fixed per job. */
val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        for (c in listOf("hourlyRate", "ratePer10Km", "calloutFee"))
            db.execSQL("ALTER TABLE `jobs` ADD COLUMN `$c` REAL NOT NULL DEFAULT -1")
    }
}

/** v6 -> v7: hourly rate and call-out fee flag on work entries. */
val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `work_entries` ADD COLUMN `hourlyRate` REAL NOT NULL DEFAULT -1")
        db.execSQL("ALTER TABLE `work_entries` ADD COLUMN `callout` INTEGER NOT NULL DEFAULT 0")
    }
}

/** v5 -> v6: E-number on materials. */
val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `materials` ADD COLUMN `eNumber` TEXT NOT NULL DEFAULT ''")
    }
}

/** v4 -> v5: per-line material markup (-1 = use the job's markup). */
val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `materials` ADD COLUMN `markupPercent` REAL NOT NULL DEFAULT -1")
    }
}

/** v3 -> v4: per-job material markup (-1 = use the default from Settings). */
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `jobs` ADD COLUMN `markupPercent` REAL NOT NULL DEFAULT -1")
    }
}

/** v2 -> v3: to-do list. */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `todos` (`id` TEXT NOT NULL, `title` TEXT NOT NULL, `notes` TEXT NOT NULL, " +
                "`jobId` TEXT NOT NULL, `dueAt` INTEGER NOT NULL, `done` INTEGER NOT NULL, " +
                "`updatedAt` INTEGER NOT NULL, `deleted` INTEGER NOT NULL, PRIMARY KEY(`id`))"
        )
    }
}

/** v1 -> v2: jobs table, jobId on entries, invoice fields on materials. Keeps all existing data. */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `jobs` (`id` TEXT NOT NULL, `number` INTEGER NOT NULL, " +
                "`customerId` TEXT NOT NULL, `title` TEXT NOT NULL, `finished` INTEGER NOT NULL, " +
                "`updatedAt` INTEGER NOT NULL, `deleted` INTEGER NOT NULL, PRIMARY KEY(`id`))"
        )
        db.execSQL("ALTER TABLE `work_entries` ADD COLUMN `jobId` TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE `trips` ADD COLUMN `jobId` TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE `materials` ADD COLUMN `jobId` TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE `materials` ADD COLUMN `unitPrice` REAL NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE `materials` ADD COLUMN `supplier` TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE `materials` ADD COLUMN `invoiceRef` TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE `materials` ADD COLUMN `source` TEXT NOT NULL DEFAULT 'manual'")
    }
}

@Dao
interface TodoDao {
    @Query("SELECT * FROM todos WHERE deleted = 0")
    fun observeAll(): Flow<List<Todo>>

    @Query("SELECT * FROM todos WHERE updatedAt > :since")
    suspend fun changedSince(since: Long): List<Todo>

    @Query("SELECT * FROM todos WHERE id = :id")
    suspend fun get(id: String): Todo?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: Todo)
}

@Dao
interface JobDao {
    @Query("SELECT * FROM jobs WHERE deleted = 0 ORDER BY number DESC")
    fun observeAll(): Flow<List<Job>>

    @Query("SELECT MAX(number) FROM jobs")
    suspend fun maxNumber(): Int?

    @Query("SELECT * FROM jobs WHERE updatedAt > :since")
    suspend fun changedSince(since: Long): List<Job>

    @Query("SELECT * FROM jobs WHERE id = :id")
    suspend fun get(id: String): Job?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: Job)
}

@Dao
interface CustomerDao {
    @Query("SELECT * FROM customers WHERE deleted = 0 ORDER BY name COLLATE NOCASE")
    fun observeAll(): Flow<List<Customer>>

    @Query("SELECT * FROM customers WHERE updatedAt > :since")
    suspend fun changedSince(since: Long): List<Customer>

    @Query("SELECT * FROM customers WHERE id = :id")
    suspend fun get(id: String): Customer?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: Customer)
}

@Dao
interface WorkDao {
    @Query("SELECT * FROM work_entries WHERE deleted = 0")
    fun observeAll(): Flow<List<WorkEntry>>

    @Query("SELECT * FROM work_entries WHERE deleted = 0 AND date = :date ORDER BY updatedAt")
    fun observeDay(date: String): Flow<List<WorkEntry>>

    @Query("SELECT * FROM work_entries WHERE deleted = 0 AND date BETWEEN :from AND :to")
    fun observeRange(from: String, to: String): Flow<List<WorkEntry>>

    @Query("SELECT COUNT(*) FROM work_entries WHERE deleted = 0 AND date = :date")
    suspend fun countForDate(date: String): Int

    @Query("SELECT * FROM work_entries WHERE updatedAt > :since")
    suspend fun changedSince(since: Long): List<WorkEntry>

    @Query("SELECT * FROM work_entries WHERE id = :id")
    suspend fun get(id: String): WorkEntry?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: WorkEntry)
}

@Dao
interface TripDao {
    @Query("SELECT * FROM trips WHERE deleted = 0 AND date = :date ORDER BY updatedAt")
    fun observeDay(date: String): Flow<List<Trip>>

    @Query("SELECT * FROM trips WHERE deleted = 0 AND date BETWEEN :from AND :to")
    fun observeRange(from: String, to: String): Flow<List<Trip>>

    @Query("SELECT * FROM trips WHERE deleted = 0")
    fun observeAll(): Flow<List<Trip>>

    @Query("SELECT * FROM trips WHERE deleted = 0 ORDER BY date DESC, updatedAt DESC LIMIT 200")
    fun observeRecent(): Flow<List<Trip>>

    @Query("SELECT * FROM trips WHERE updatedAt > :since")
    suspend fun changedSince(since: Long): List<Trip>

    @Query("SELECT * FROM trips WHERE id = :id")
    suspend fun get(id: String): Trip?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: Trip)
}

@Dao
interface MaterialDao {
    @Query("SELECT * FROM materials WHERE deleted = 0 AND date = :date ORDER BY updatedAt")
    fun observeDay(date: String): Flow<List<Material>>

    @Query("SELECT * FROM materials WHERE deleted = 0 AND date BETWEEN :from AND :to")
    fun observeRange(from: String, to: String): Flow<List<Material>>


    @Query("SELECT * FROM materials WHERE deleted = 0")
    fun observeAll(): Flow<List<Material>>

    @Query("SELECT * FROM materials WHERE updatedAt > :since")
    suspend fun changedSince(since: Long): List<Material>

    @Query("SELECT * FROM materials WHERE id = :id")
    suspend fun get(id: String): Material?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: Material)
}

@Database(
    entities = [Customer::class, Job::class, WorkEntry::class, Trip::class, Material::class, Todo::class],
    version = 8,
    exportSchema = false,
)
abstract class AppDb : RoomDatabase() {
    abstract fun todos(): TodoDao
    abstract fun jobs(): JobDao
    abstract fun customers(): CustomerDao
    abstract fun work(): WorkDao
    abstract fun trips(): TripDao
    abstract fun materials(): MaterialDao

    companion object {
        @Volatile private var instance: AppDb? = null
        fun get(context: Context): AppDb = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, AppDb::class.java, "sparklog.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8)
                .build().also { instance = it }
        }
    }
}
