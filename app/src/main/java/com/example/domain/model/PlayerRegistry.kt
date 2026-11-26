package com.example.domain.model

import android.media.session.PlaybackState
import android.os.SystemClock

enum class PlayerVerificationStatus(val label: String) {
    CANDIDATE("Detected"),
    VERIFIED("Verified"),
    ACTIVE_NOW("Active"),
    UNAVAILABLE("Unavailable"),
    USER_DISABLED("Disabled")
}

enum class DiscoverySource(val label: String) {
    BUILT_IN_PROFILE("Verified Profile"),
    INTENT_SCAN("System Video Scan"),
    MEDIA_SESSION_LEARNED("Live MediaSession")
}

/**
 * Player-specific adapter metadata for capability interpretation and adaptive seek defaults.
 */
data class SupportedPlayer(
    val packageName: String,
    val displayName: String,
    val category: String,
    val supportsSeek: Boolean = true,
    val defaultSeekIntervalSeconds: Int? = null,
    val notes: String = "Compatible with Android MediaSession transport controls"
)

object PlayerRegistry {
    val SUPPORTED_PLAYERS: List<SupportedPlayer> = listOf(
        SupportedPlayer(
            packageName = "com.google.android.youtube",
            displayName = "YouTube",
            category = "Streaming Video",
            supportsSeek = true,
            defaultSeekIntervalSeconds = 10,
            notes = "Default 10s double-tap profile (configurable 5s–60s per user setting)"
        ),
        SupportedPlayer(
            packageName = "org.videolan.vlc",
            displayName = "VLC for Android",
            category = "Local / Network Video",
            supportsSeek = true,
            defaultSeekIntervalSeconds = 10,
            notes = "Frame-accurate MediaSession seekTo support"
        ),
        SupportedPlayer(
            packageName = "com.mxtech.videoplayer.ad",
            displayName = "MX Player",
            category = "Local Video Player",
            supportsSeek = true,
            defaultSeekIntervalSeconds = 10,
            notes = "Reliable MediaSession playback and seek control"
        ),
        SupportedPlayer(
            packageName = "com.mxtech.videoplayer.pro",
            displayName = "MX Player Pro",
            category = "Local Video Player",
            supportsSeek = true,
            defaultSeekIntervalSeconds = 10,
            notes = "Reliable MediaSession playback and seek control"
        ),
        SupportedPlayer(
            packageName = "com.android.chrome",
            displayName = "Google Chrome",
            category = "Web Media",
            supportsSeek = true,
            defaultSeekIntervalSeconds = 5,
            notes = "HTML5 web video media notification session"
        ),
        SupportedPlayer(
            packageName = "org.schabi.newpipe",
            displayName = "NewPipe",
            category = "Video Client",
            supportsSeek = true,
            defaultSeekIntervalSeconds = 10,
            notes = "Standard Android MediaSession client"
        ),
        SupportedPlayer(
            packageName = "is.xyz.mpv",
            displayName = "mpv-android",
            category = "Local Video Player",
            supportsSeek = true,
            defaultSeekIntervalSeconds = 5,
            notes = "Direct local playback MediaSession"
        )
    )

    private val builtInPackageSet: Set<String> = SUPPORTED_PLAYERS.map { it.packageName }.toSet()

    @Volatile
    private var dynamicSupportedPackages: Set<String> = emptySet()

    fun updateDynamicSupportedPackages(packages: Set<String>) {
        dynamicSupportedPackages = packages
    }

    fun isSupported(packageName: String?): Boolean {
        if (packageName.isNullOrBlank()) return false
        return builtInPackageSet.contains(packageName) || dynamicSupportedPackages.contains(packageName)
    }

    fun findByPackage(packageName: String?): SupportedPlayer? {
        if (packageName.isNullOrBlank()) return null
        return SUPPORTED_PLAYERS.find { it.packageName == packageName }
    }
}

/**
 * Pure helper for position extrapolation using:
 * position + (elapsedRealtime - lastPositionUpdateTime) * playbackSpeed
 * when state is STATE_PLAYING.
 */
object MediaPositionEstimator {
    fun estimatePosition(
        state: Int?,
        basePosition: Long,
        lastPositionUpdateTime: Long,
        playbackSpeed: Float,
        currentElapsedRealtime: Long = SystemClock.elapsedRealtime()
    ): Long {
        if (state == PlaybackState.STATE_PLAYING && lastPositionUpdateTime > 0L) {
            val elapsed = currentElapsedRealtime - lastPositionUpdateTime
            if (elapsed > 0) {
                val speed = if (playbackSpeed > 0f) playbackSpeed else 1.0f
                return (basePosition + (elapsed * speed).toLong()).coerceAtLeast(0L)
            }
        }
        return basePosition.coerceAtLeast(0L)
    }
}

/**
 * Snapshot of current active media session including observable capabilities & adaptive sync metadata
 */
data class ActiveMediaSessionInfo(
    val packageName: String? = null,
    val playerDisplayName: String = "No Active Player",
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val playbackPositionMs: Long = 0L,
    val isSupported: Boolean = false,
    val canSeek: Boolean = true,
    val effectiveIntervalSeconds: Int? = null,
    val syncSource: SyncSource = SyncSource.APP_GLOBAL_FALLBACK,
    val capabilities: SessionCapabilities = SessionCapabilities()
)
