package com.example.data.registry

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.net.Uri
import android.os.Build
import com.example.domain.model.DiscoverySource
import com.example.domain.model.PlayerRegistry
import com.example.domain.model.PlayerVerificationStatus

/**
 * Multi-signal video player discovery scanner compliant with Android 11+ <queries>.
 * Combines intent capability probing, MediaBrowser/MediaButton signals, built-in
 * adapter profiles, and live MediaSession observations rather than app-name guesses.
 */
class PlayerDiscoveryScanner(private val context: Context) {

    companion object {
        const val VERIFIED_CONFIDENCE_THRESHOLD = 50
        const val CANDIDATE_CONFIDENCE_THRESHOLD = 25
    }

    private val pm: PackageManager = context.packageManager

    fun scanInstalledPlayers(
        existingEntries: Map<String, DiscoveredPlayerEntity>,
        nowMs: Long = System.currentTimeMillis()
    ): List<DiscoveredPlayerEntity> {
        val videoIntentPackages = queryIntentPackages(
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(Uri.parse("content://stayframe/sample.mp4"), "video/*")
            }
        )
        val mediaBrowserPackages = queryServicePackages("android.media.browse.MediaBrowserService")
        val mediaButtonPackages = queryBroadcastPackages(Intent.ACTION_MEDIA_BUTTON)

        val candidatePackages = linkedSetOf<String>()
        candidatePackages.addAll(PlayerRegistry.SUPPORTED_PLAYERS.map { it.packageName })
        candidatePackages.addAll(videoIntentPackages)
        candidatePackages.addAll(mediaBrowserPackages)
        candidatePackages.addAll(existingEntries.keys)
        // MediaButton receivers alone can include audio-only apps, so only include if paired with video or existing entry
        candidatePackages.remove(context.packageName)

        val results = mutableListOf<DiscoveredPlayerEntity>()

        for (pkg in candidatePackages) {
            val previous = existingEntries[pkg]
            val builtIn = PlayerRegistry.findByPackage(pkg)
            val pkgInfo = getInstalledPackageInfo(pkg)
            val isEnabled = pkgInfo?.applicationInfo?.enabled == true

            if (pkgInfo == null || !isEnabled) {
                // Keep unavailable record only if previously tracked or built-in, marking it UNAVAILABLE
                if (previous != null) {
                    results.add(
                        previous.copy(
                            verificationStatus = PlayerVerificationStatus.UNAVAILABLE.name,
                            confidenceScore = 0,
                            lastVerifiedAtMs = if (previous.statusEnum() == PlayerVerificationStatus.UNAVAILABLE) {
                                previous.lastVerifiedAtMs
                            } else {
                                nowMs
                            }
                        )
                    )
                }
                continue
            }

            val handlesVideoIntent = pkg in videoIntentPackages
            val hasMediaService = pkg in mediaBrowserPackages || pkg in mediaButtonPackages
            val learnedFromSession = previous?.sourceEnum() == DiscoverySource.MEDIA_SESSION_LEARNED

            var score = 0
            if (builtIn != null) score += 50
            if (handlesVideoIntent) score += 30
            if (hasMediaService) score += 25
            if (learnedFromSession) score += 40
            score = score.coerceIn(0, 100)

            // Reject false positives (e.g. generic audio apps unless learned via video session or video intent)
            if (score < CANDIDATE_CONFIDENCE_THRESHOLD && previous == null) {
                continue
            }

            val status = when {
                previous?.isEnabledForMonitoring == false -> PlayerVerificationStatus.USER_DISABLED
                score >= VERIFIED_CONFIDENCE_THRESHOLD -> PlayerVerificationStatus.VERIFIED
                else -> PlayerVerificationStatus.CANDIDATE
            }

            val source = when {
                builtIn != null -> DiscoverySource.BUILT_IN_PROFILE
                learnedFromSession -> DiscoverySource.MEDIA_SESSION_LEARNED
                else -> DiscoverySource.INTENT_SCAN
            }

            val resolvedName = builtIn?.displayName
                ?: pkgInfo.applicationInfo?.loadLabel(pm)?.toString()?.takeIf { it.isNotBlank() }
                ?: previous?.displayName
                ?: pkg

            val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pkgInfo.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                pkgInfo.versionCode.toLong()
            }

            results.add(
                DiscoveredPlayerEntity(
                    packageName = pkg,
                    displayName = resolvedName,
                    category = builtIn?.category ?: if (handlesVideoIntent) "Video Player" else "Media App",
                    versionCode = versionCode,
                    discoverySource = source.name,
                    verificationStatus = status.name,
                    confidenceScore = score,
                    supportsSeekTo = previous?.supportsSeekTo ?: builtIn?.supportsSeek ?: true,
                    profileDefaultIntervalSec = builtIn?.defaultSeekIntervalSeconds ?: previous?.profileDefaultIntervalSec,
                    userSyncedIntervalSec = previous?.userSyncedIntervalSec,
                    observedStepIntervalSec = previous?.observedStepIntervalSec,
                    isEnabledForMonitoring = previous?.isEnabledForMonitoring ?: true,
                    notes = builtIn?.notes ?: buildCapabilityNotes(handlesVideoIntent, hasMediaService),
                    lastVerifiedAtMs = nowMs
                )
            )
        }

        return results
    }

    fun evaluateSinglePackage(
        packageName: String,
        existing: DiscoveredPlayerEntity?,
        nowMs: Long = System.currentTimeMillis()
    ): DiscoveredPlayerEntity? {
        if (packageName == context.packageName) return null
        val map = if (existing != null) mapOf(packageName to existing) else emptyMap()
        return scanInstalledPlayers(map, nowMs).find { it.packageName == packageName }
    }

    private fun buildCapabilityNotes(handlesVideo: Boolean, hasMediaService: Boolean): String {
        val parts = mutableListOf<String>()
        if (handlesVideo) parts.add("Handles video/* intents")
        if (hasMediaService) parts.add("Media session / controls")
        return if (parts.isEmpty()) "Discovered via system query" else parts.joinToString(" • ")
    }

    private fun getInstalledPackageInfo(packageName: String): PackageInfo? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(packageName, 0)
            }
        } catch (_: PackageManager.NameNotFoundException) {
            null
        } catch (_: Exception) {
            null
        }
    }

    private fun queryIntentPackages(intent: Intent): Set<String> {
        return try {
            val resolved: List<ResolveInfo> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong()))
            } else {
                @Suppress("DEPRECATION")
                pm.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
            }
            resolved.mapNotNull { it.activityInfo?.packageName }.toSet()
        } catch (_: Exception) {
            emptySet()
        }
    }

    private fun queryServicePackages(action: String): Set<String> {
        return try {
            val intent = Intent(action)
            val resolved: List<ResolveInfo> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.queryIntentServices(intent, PackageManager.ResolveInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.queryIntentServices(intent, 0)
            }
            resolved.mapNotNull { it.serviceInfo?.packageName }.toSet()
        } catch (_: Exception) {
            emptySet()
        }
    }

    private fun queryBroadcastPackages(action: String): Set<String> {
        return try {
            val intent = Intent(action)
            val resolved: List<ResolveInfo> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.queryBroadcastReceivers(intent, PackageManager.ResolveInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.queryBroadcastReceivers(intent, 0)
            }
            resolved.mapNotNull { it.activityInfo?.packageName }.toSet()
        } catch (_: Exception) {
            emptySet()
        }
    }
}
