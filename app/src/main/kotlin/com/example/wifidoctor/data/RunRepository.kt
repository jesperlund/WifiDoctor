package com.example.wifidoctor.data

import com.example.wifidoctor.core.history.RunRecord
import com.example.wifidoctor.data.db.RunDao
import com.example.wifidoctor.data.db.toEntity
import com.example.wifidoctor.data.db.toRecord
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** History of diagnostic runs. */
interface RunRepository {
    fun observeRuns(): Flow<List<RunRecord>>
    fun observeRoomRuns(): Flow<List<RunRecord>>
    suspend fun save(record: RunRecord): Long
    suspend fun get(id: Long): RunRecord?
    suspend fun all(): List<RunRecord>
    suspend fun deleteRoom(room: String)
    suspend fun clear()
}

@Singleton
class RoomRunRepository @Inject constructor(private val dao: RunDao) : RunRepository {
    override fun observeRuns(): Flow<List<RunRecord>> = dao.observeAll().map { list -> list.map { it.toRecord() } }
    override fun observeRoomRuns(): Flow<List<RunRecord>> = dao.observeRoomRuns().map { list -> list.map { it.toRecord() } }
    override suspend fun save(record: RunRecord): Long = dao.insert(record.copy(id = 0).toEntity())
    override suspend fun get(id: Long): RunRecord? = dao.byId(id)?.toRecord()
    override suspend fun all(): List<RunRecord> = dao.all().map { it.toRecord() }
    override suspend fun deleteRoom(room: String) = dao.deleteRoom(room.trim())
    override suspend fun clear() = dao.clear()
}
