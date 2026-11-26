package com.example.data.registry

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.example.domain.model.DiscoverySource
import com.example.domain.model.PlayerRegistry
import com.example.domain.model.PlayerVerificationStatus
import com.example.service.StayFrameServiceBridge
import com.example.domain.model.SessionCapabilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class PlayerRegistryRepository(
    private val context: Context,
    private val dao: DiscoveredPlayerDao = StayFrameDatabase.getInstance(context).discoveredPlayerDao(),
    private val scanner: PlayerDiscoveryScanner = PlayerDiscoveryScanner(context)
) {
    companion object {
        private const val STALE_UNAVAILABLE_TTL_MS = 30L * 24L * 60L * 60L * 1000L // 30 days
        private const val RESCAN_COOLDOWN_MS = 5_000L
    }

    private val scanMutex = Mutex()

    @Volatile
    private var lastScanTimestampMs: Long = 0L

    val playersFlow: Flow<List<DiscoveredPlayerEntity>> = dao.observeAllPlayers()

    suspend fun getAllPlayersSnapshot(): List<DiscoveredPlayerEntity> = withContext(Dispatchers.IO) {
        dao.getAllPlayers()
    }

    suspend fun getPlayer(packageName: String): DiscoveredPlayerEntity? = withContext(Dispatchers.IO) {
        dao.getPlayerByPackage(packageName)
    }

    /**
     * Perform full discovery & stale-entry revalidation.
     * Throttled unless force == true to protect battery and avoid redundant scans.
     */
    suspend fun rescanAndValidateRegistry(force: Boolean = false): List<DiscoveredPlayerEntity> =
        withContext(Dispatchers.IO) {
            scanMutex.withLock {
                val now = System.currentTimeMillis()
                if (!force && (now - lastScanTimestampMs) < RESCAN_COOLDOWN_MS) {
                    return@withLock dao.getAllPlayers()
                }
                lastScanTimestampMs = now

                val existingMap = dao.getAllPlayers().associateBy { it.packageName }
                val scanned = scanner.scanInstalledPlayers(existingMap, now)
                if (scanned.isNotEmpty()) {
                    dao.upsertPlayers(scanned)
                }

                dao.pruneStaleUnavailable(
                    unavailableStatus = PlayerVerificationStatus.UNAVAILABLE.name,
                    cutoffMs = now - STALE_UNAVAILABLE_TTL_MS
                )

                val updated = dao.getAllPlayers()
                syncDynamicRegistryAllowlist(updated)
                updated
            }
        }

    /**
     * Incrementally handle single-package install, update, or removal events.
     */
    suspend fun onPackageLifecycleEvent(packageName: String, isRemoved: Boolean) = withContext(Dispatchers.IO) {
        scanMutex.withLock {
            val existing = dao.getPlayerByPackage(packageName)
            val now = System.currentTimeMillis()
            if (isRemoved) {
                if (existing != null) {
                    dao.upsertPlayer(
                        existing.copy(
                            verificationStatus = PlayerVerificationStatus.UNAVAILABLE.name,
                            confidenceScore = 0,
                            lastVerifiedAtMs = now
                        )
                    )
                }
            } else {
                val updatedEntry = scanner.evaluateSinglePackage(packageName, existing, now)
                if (updatedEntry != null) {
                    dao.upsertPlayer(updatedEntry)
                }
            }
            syncDynamicRegistryAllowlist(dao.getAllPlayers())
        }
    }

    /**
     * Learn or update capabilities from an active live MediaController session.
     * Ensures newly discovered video apps emitting MediaSession events can be verified dynamically.
     */
    suspend fun recordLiveSessionObservation(
        packageName: String,
        capabilities: SessionCapabilities
    ) = withContext(Dispatchers.IO) {
        if (packageName == context.packageName) return@withContext
        scanMutex.withLock {
            val existing = dao.getPlayerByPackage(packageName)
            val builtIn = PlayerRegistry.findByPackage(packageName)
            val now = System.currentTimeMillis()

            val label = builtIn?.displayName
                ?: existing?.displayName
                ?: resolveAppLabel(packageName)
                ?: packageName

            val updated = if (existing != null) {
                val newScore = maxOf(existing.confidenceScore, if (builtIn != null) 90 else 75)
                existing.copy(
                    displayName = label,
                    supportsSeekTo = capabilities.supportsSeekTo,
                    verificationStatus = if (existing.isEnabledForMonitoring) {
                        PlayerVerificationStatus.VERIFIED.name
                    } else {
                        PlayerVerificationStatus.USER_DISABLED.name
                    },
                    confidenceScore = newScore,
                    lastVerifiedAtMs = now
                )
            } else {
                DiscoveredPlayerEntity(
                    packageName = packageName,
                    displayName = label,
                    category = builtIn?.category ?: "Observed Media Player",
                    discoverySource = if (builtIn != null) {
                        DiscoverySource.BUILT_IN_PROFILE.name
                    } else {
                        DiscoverySource.MEDIA_SESSION_LEARNED.name
                    },
                    verificationStatus = PlayerVerificationStatus.VERIFIED.name,
                    confidenceScore = if (builtIn != null) 90 else 75,
                    supportsSeekTo = capabilities.supportsSeekTo,
                    profileDefaultIntervalSec = builtIn?.defaultSeekIntervalSeconds,
                    notes = builtIn?.notes ?: "Learned dynamically from live MediaSession",
                    lastVerifiedAtMs = now
                )
            }

            dao.upsertPlayer(updated)
            syncDynamicRegistryAllowlist(dao.getAllPlayers())
        }
    }

    suspend fun setUserSyncedInterval(packageName: String, intervalSec: Int?) = withContext(Dispatchers.IO) {
        dao.updateUserSyncedInterval(packageName, intervalSec)
    }

    suspend fun setPlayerMonitoringEnabled(packageName: String, enabled: Boolean) = withContext(Dispatchers.IO) {
        val existing = dao.getPlayerByPackage(packageName) ?: return@withContext
        val newStatus = if (!enabled) {
            PlayerVerificationStatus.USER_DISABLED.name
        } else if (existing.confidenceScore >= PlayerDiscoveryScanner.VERIFIED_CONFIDENCE_THRESHOLD) {
            PlayerVerificationStatus.VERIFIED.name
        } else {
            PlayerVerificationStatus.CANDIDATE.name
        }
        dao.upsertPlayer(existing.copy(isEnabledForMonitoring = enabled, verificationStatus = newStatus))
        syncDynamicRegistryAllowlist(dao.getAllPlayers())
    }

    private fun syncDynamicRegistryAllowlist(entries: List<DiscoveredPlayerEntity>) {
        val activeVerifiedPackages = entries
            .filter {
                it.isEnabledForMonitoring &&
                    (it.statusEnum() == PlayerVerificationStatus.VERIFIED ||
                        it.statusEnum() == PlayerVerificationStatus.ACTIVE_NOW)
            }
            .map { it.packageName }
            .toSet()
        PlayerRegistry.updateDynamicSupportedPackages(activeVerifiedPackages)
    }

    private fun resolveAppLabel(packageName: String): String? {
        return try {
            val pm = context.packageManager
            val appInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getApplicationInfo(packageName, 0)
            }
            pm.getApplicationLabel(appInfo).toString()
        } catch (e: Exception) {
            StayFrameServiceBridge.recordRecoverableException(
                "player_label_lookup_failed",
                "Failed to resolve application label for $packageName",
                e
            )
            null
        }
    }
}
