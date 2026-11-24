package com.example.sparklog.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import android.content.Context
import kotlinx.coroutines.flow.Flow

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

    @Query("SELECT * FROM materials WHERE deleted = 0 ORDER BY date DESC, updatedAt DESC LIMIT 300")
    fun observeRecent(): Flow<List<Material>>

    @Query("SELECT * FROM materials WHERE updatedAt > :since")
    suspend fun changedSince(since: Long): List<Material>

    @Query("SELECT * FROM materials WHERE id = :id")
    suspend fun get(id: String): Material?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: Material)
}

@Database(entities = [Customer::class, WorkEntry::class, Trip::class, Material::class], version = 1, exportSchema = false)
abstract class AppDb : RoomDatabase() {
    abstract fun customers(): CustomerDao
    abstract fun work(): WorkDao
    abstract fun trips(): TripDao
    abstract fun materials(): MaterialDao

    companion object {
        @Volatile private var instance: AppDb? = null
        fun get(context: Context): AppDb = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, AppDb::class.java, "sparklog.db")
                .build().also { instance = it }
        }
    }
}
