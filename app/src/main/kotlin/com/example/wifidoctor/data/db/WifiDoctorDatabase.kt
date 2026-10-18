package com.example.wifidoctor.data.db

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.Query
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

@Dao
interface RunDao {
    @Insert
    suspend fun insert(run: RunEntity): Long

    @Query("SELECT * FROM runs ORDER BY timestampMs ASC")
    fun observeAll(): Flow<List<RunEntity>>

    @Query("SELECT * FROM runs WHERE roomLabel IS NOT NULL ORDER BY timestampMs ASC")
    fun observeRoomRuns(): Flow<List<RunEntity>>

    @Query("SELECT * FROM runs ORDER BY timestampMs ASC")
    suspend fun all(): List<RunEntity>

    @Query("SELECT * FROM runs WHERE id = :id")
    suspend fun byId(id: Long): RunEntity?

    @Query("DELETE FROM runs WHERE roomLabel = :room COLLATE NOCASE")
    suspend fun deleteRoom(room: String)

    @Query("DELETE FROM runs")
    suspend fun clear()
}

@Database(entities = [RunEntity::class], version = 1, exportSchema = false)
abstract class WifiDoctorDatabase : RoomDatabase() {
    abstract fun runDao(): RunDao

    companion object {
        const val NAME = "wifidoctor.db"
    }
}
