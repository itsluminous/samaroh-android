package com.itsluminous.samaroh.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.itsluminous.samaroh.core.database.entity.SyncConflictEntity
import com.itsluminous.samaroh.core.database.entity.SyncCursorEntity
import kotlinx.coroutines.flow.Flow
import java.time.Instant

/*
 * DAOs for the local-only sync bookkeeping tables — ADDITIVE W1-E change
 * (docs/decisions.md ADR-007).
 */

@Dao
interface SyncCursorDao {
    @Query("SELECT last_pulled_at FROM sync_cursors WHERE business_id = :businessId AND table_name = :tableName")
    suspend fun cursor(
        businessId: String,
        tableName: String,
    ): Instant?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(cursor: SyncCursorEntity)
}

@Dao
interface SyncConflictDao {
    @Insert
    suspend fun insert(conflict: SyncConflictEntity): Long

    @Query("SELECT * FROM sync_conflicts ORDER BY occurred_at DESC, id DESC")
    fun conflictLog(): Flow<List<SyncConflictEntity>>

    /** Drives the in-app conflict banner state: banner shows while this is > 0. */
    @Query("SELECT COUNT(*) FROM sync_conflicts WHERE acknowledged = 0")
    fun unacknowledgedCount(): Flow<Int>

    @Query("UPDATE sync_conflicts SET acknowledged = 1 WHERE id = :id")
    suspend fun acknowledge(id: Long)
}
