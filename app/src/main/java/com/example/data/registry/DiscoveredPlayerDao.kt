package com.example.data.registry

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface DiscoveredPlayerDao {

    @Query("SELECT * FROM discovered_players ORDER BY confidenceScore DESC, displayName ASC")
    fun observeAllPlayers(): Flow<List<DiscoveredPlayerEntity>>

    @Query("SELECT * FROM discovered_players ORDER BY confidenceScore DESC, displayName ASC")
    suspend fun getAllPlayers(): List<DiscoveredPlayerEntity>

    @Query("SELECT * FROM discovered_players WHERE packageName = :packageName LIMIT 1")
    suspend fun getPlayerByPackage(packageName: String): DiscoveredPlayerEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPlayer(player: DiscoveredPlayerEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPlayers(players: List<DiscoveredPlayerEntity>)

    @Query("UPDATE discovered_players SET userSyncedIntervalSec = :intervalSec WHERE packageName = :packageName")
    suspend fun updateUserSyncedInterval(packageName: String, intervalSec: Int?)

    @Query("UPDATE discovered_players SET isEnabledForMonitoring = :enabled WHERE packageName = :packageName")
    suspend fun updateMonitoringEnabled(packageName: String, enabled: Boolean)

    @Query("DELETE FROM discovered_players WHERE verificationStatus = :unavailableStatus AND lastVerifiedAtMs < :cutoffMs")
    suspend fun pruneStaleUnavailable(unavailableStatus: String, cutoffMs: Long)
}
