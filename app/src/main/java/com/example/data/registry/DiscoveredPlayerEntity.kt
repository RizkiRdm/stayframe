package com.example.data.registry

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.example.domain.model.DiscoverySource
import com.example.domain.model.PlayerVerificationStatus

@Entity(tableName = "discovered_players")
data class DiscoveredPlayerEntity(
    @PrimaryKey val packageName: String,
    val displayName: String,
    val category: String,
    val versionCode: Long = 0L,
    val discoverySource: String = DiscoverySource.INTENT_SCAN.name,
    val verificationStatus: String = PlayerVerificationStatus.CANDIDATE.name,
    val confidenceScore: Int = 0,
    val supportsSeekTo: Boolean = true,
    val profileDefaultIntervalSec: Int? = null,
    val userSyncedIntervalSec: Int? = null,
    val observedStepIntervalSec: Int? = null,
    val isEnabledForMonitoring: Boolean = true,
    val notes: String = "",
    val lastVerifiedAtMs: Long = System.currentTimeMillis()
) {
    fun statusEnum(): PlayerVerificationStatus = try {
        PlayerVerificationStatus.valueOf(verificationStatus)
    } catch (e: Exception) {
        com.example.service.StayFrameServiceBridge.recordRecoverableException(
            "player_status_parse_failed",
            "Unknown PlayerVerificationStatus '$verificationStatus' for $packageName",
            e
        )
        PlayerVerificationStatus.CANDIDATE
    }

    fun sourceEnum(): DiscoverySource = try {
        DiscoverySource.valueOf(discoverySource)
    } catch (e: Exception) {
        com.example.service.StayFrameServiceBridge.recordRecoverableException(
            "player_source_parse_failed",
            "Unknown DiscoverySource '$discoverySource' for $packageName",
            e
        )
        DiscoverySource.INTENT_SCAN
    }

    val effectiveSyncedIntervalSec: Int?
        get() = userSyncedIntervalSec ?: observedStepIntervalSec ?: profileDefaultIntervalSec
}
