package com.example.domain.model

import android.media.MediaMetadata
import android.media.session.PlaybackState

/**
 * Distinguishes what StayFrame technically observes from Android MediaSession vs.
 * profile defaults or user-confirmed player settings.
 */
data class SessionCapabilities(
    val supportsPlay: Boolean = true,
    val supportsPause: Boolean = true,
    val supportsSeekTo: Boolean = true,
    val supportsRewind: Boolean = false,
    val isLiveStream: Boolean = false,
    val durationMs: Long = -1L,
    val playbackSpeed: Float = 1.0f
)

enum class SyncSource(val label: String) {
    PLAYER_USER_SYNCED("Synced to Player Setting"),
    PLAYER_OBSERVED_STEP("Observed Seek Step"),
    PLAYER_PROFILE_DEFAULT("Player Profile Default"),
    APP_GLOBAL_FALLBACK("App Global Fallback")
}

data class EffectiveTimingPolicy(
    val effectiveGracePeriodSeconds: Int,
    val effectiveRewindOffsetMs: Long,
    val canSeek: Boolean,
    val syncSource: SyncSource
)

object PlayerCapabilityInspector {

    fun inspect(playbackState: PlaybackState?, metadata: MediaMetadata?): SessionCapabilities {
        val actions = playbackState?.actions ?: 0L
        val duration = metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: -1L
        val hasExplicitActions = actions != 0L

        val supportsPlay = !hasExplicitActions || (actions and PlaybackState.ACTION_PLAY) != 0L ||
            (actions and PlaybackState.ACTION_PLAY_PAUSE) != 0L
        val supportsPause = !hasExplicitActions || (actions and PlaybackState.ACTION_PAUSE) != 0L ||
            (actions and PlaybackState.ACTION_PLAY_PAUSE) != 0L
        val supportsSeekTo = !hasExplicitActions || (actions and PlaybackState.ACTION_SEEK_TO) != 0L
        val supportsRewind = (actions and PlaybackState.ACTION_REWIND) != 0L
        val speed = playbackState?.playbackSpeed?.takeIf { it > 0f } ?: 1.0f

        // Live streams typically expose duration == 0 or < 0 with an active playing state
        val isLive = duration == 0L && playbackState?.state == PlaybackState.STATE_PLAYING

        return SessionCapabilities(
            supportsPlay = supportsPlay,
            supportsPause = supportsPause,
            supportsSeekTo = supportsSeekTo,
            supportsRewind = supportsRewind,
            isLiveStream = isLive,
            durationMs = duration,
            playbackSpeed = speed
        )
    }
}

object AdaptiveIntervalResolver {

    const val MIN_INTERVAL_SECONDS = 3
    const val MAX_INTERVAL_SECONDS = 30

    /**
     * Resolves effective absence-grace and rewind timing for the active player.
     * Precedence:
     * 1. Explicit per-player user-synced interval (matches e.g. YouTube 5s/10s double-tap)
     * 2. Observed seek step telemetry (when reliably captured)
     * 3. Player adapter profile default (when player enforces/defaults a known interval)
     * 4. Global app fallback grace period
     */
    fun resolve(
        globalAppGraceSec: Int,
        capabilities: SessionCapabilities,
        userSyncedIntervalSec: Int? = null,
        observedStepIntervalSec: Int? = null,
        profileDefaultIntervalSec: Int? = null,
        adapterRequiresSeekTo: Boolean = true
    ): EffectiveTimingPolicy {
        val safeGlobal = globalAppGraceSec.coerceIn(MIN_INTERVAL_SECONDS, MAX_INTERVAL_SECONDS)

        val (rawSeconds, source) = when {
            userSyncedIntervalSec != null ->
                userSyncedIntervalSec to SyncSource.PLAYER_USER_SYNCED
            observedStepIntervalSec != null ->
                observedStepIntervalSec to SyncSource.PLAYER_OBSERVED_STEP
            profileDefaultIntervalSec != null ->
                profileDefaultIntervalSec to SyncSource.PLAYER_PROFILE_DEFAULT
            else ->
                safeGlobal to SyncSource.APP_GLOBAL_FALLBACK
        }

        val effectiveSeconds = rawSeconds.coerceIn(MIN_INTERVAL_SECONDS, MAX_INTERVAL_SECONDS)
        val canSeek = adapterRequiresSeekTo && capabilities.supportsSeekTo && !capabilities.isLiveStream
        val rewindMs = if (canSeek) effectiveSeconds * 1000L else 0L

        return EffectiveTimingPolicy(
            effectiveGracePeriodSeconds = effectiveSeconds,
            effectiveRewindOffsetMs = rewindMs,
            canSeek = canSeek,
            syncSource = source
        )
    }
}
