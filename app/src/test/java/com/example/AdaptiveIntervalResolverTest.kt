package com.example

import com.example.domain.model.AdaptiveIntervalResolver
import com.example.domain.model.SessionCapabilities
import com.example.domain.model.SyncSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveIntervalResolverTest {

    @Test
    fun testPlayerIntervalOverridesLargerAppGlobalSetting() {
        // App configured 10s, player supports/synced at 5s -> uses 5s
        val policy = AdaptiveIntervalResolver.resolve(
            globalAppGraceSec = 10,
            capabilities = SessionCapabilities(supportsSeekTo = true, durationMs = 120_000L),
            userSyncedIntervalSec = 5
        )
        assertEquals(5, policy.effectiveGracePeriodSeconds)
        assertEquals(5000L, policy.effectiveRewindOffsetMs)
        assertEquals(SyncSource.PLAYER_USER_SYNCED, policy.syncSource)
        assertTrue(policy.canSeek)
    }

    @Test
    fun testYouTubeSyncedIntervalTenVsFiveSeconds() {
        val yt10 = AdaptiveIntervalResolver.resolve(
            globalAppGraceSec = 5,
            capabilities = SessionCapabilities(supportsSeekTo = true, durationMs = 300_000L),
            userSyncedIntervalSec = 10,
            profileDefaultIntervalSec = 10
        )
        assertEquals(10, yt10.effectiveGracePeriodSeconds)
        assertEquals(10_000L, yt10.effectiveRewindOffsetMs)

        val yt5 = AdaptiveIntervalResolver.resolve(
            globalAppGraceSec = 10,
            capabilities = SessionCapabilities(supportsSeekTo = true, durationMs = 300_000L),
            userSyncedIntervalSec = 5,
            profileDefaultIntervalSec = 10
        )
        assertEquals(5, yt5.effectiveGracePeriodSeconds)
        assertEquals(5_000L, yt5.effectiveRewindOffsetMs)
    }

    @Test
    fun testUnknownPlayerFallsBackToAppGlobalSetting() {
        val fallback = AdaptiveIntervalResolver.resolve(
            globalAppGraceSec = 7,
            capabilities = SessionCapabilities(supportsSeekTo = true, durationMs = 60_000L),
            userSyncedIntervalSec = null,
            observedStepIntervalSec = null,
            profileDefaultIntervalSec = null
        )
        assertEquals(7, fallback.effectiveGracePeriodSeconds)
        assertEquals(7000L, fallback.effectiveRewindOffsetMs)
        assertEquals(SyncSource.APP_GLOBAL_FALLBACK, fallback.syncSource)
    }

    @Test
    fun testUnseekableOrLiveStreamDisablesRewindSafely() {
        val livePolicy = AdaptiveIntervalResolver.resolve(
            globalAppGraceSec = 5,
            capabilities = SessionCapabilities(supportsSeekTo = true, isLiveStream = true, durationMs = 0L),
            profileDefaultIntervalSec = 10
        )
        assertEquals(10, livePolicy.effectiveGracePeriodSeconds)
        assertFalse(livePolicy.canSeek)
        assertEquals(0L, livePolicy.effectiveRewindOffsetMs)

        val noSeekActionPolicy = AdaptiveIntervalResolver.resolve(
            globalAppGraceSec = 5,
            capabilities = SessionCapabilities(supportsSeekTo = false, durationMs = 60_000L)
        )
        assertFalse(noSeekActionPolicy.canSeek)
        assertEquals(0L, noSeekActionPolicy.effectiveRewindOffsetMs)
    }
}
