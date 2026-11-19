package com.example

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.registry.DiscoveredPlayerEntity
import com.example.data.registry.PlayerDiscoveryScanner
import com.example.data.registry.PlayerRegistryRepository
import com.example.data.registry.StayFrameDatabase
import com.example.domain.model.PlayerVerificationStatus
import com.example.domain.model.SessionCapabilities
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowPackageManager

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlayerDiscoveryScannerTest {

    private lateinit var context: Context
    private lateinit var db: StayFrameDatabase
    private lateinit var repository: PlayerRegistryRepository
    private lateinit var shadowPm: ShadowPackageManager

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        shadowPm = Shadows.shadowOf(context.packageManager)
        db = Room.inMemoryDatabaseBuilder(context, StayFrameDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = PlayerRegistryRepository(
            context = context,
            dao = db.discoveredPlayerDao(),
            scanner = PlayerDiscoveryScanner(context)
        )
    }

    @After
    fun teardown() {
        db.close()
    }

    private fun installFakePlayer(pkg: String, label: String, registerVideoIntent: Boolean = true) {
        val appInfo = ApplicationInfo().apply {
            packageName = pkg
            name = label
            enabled = true
        }
        val pkgInfo = PackageInfo().apply {
            packageName = pkg
            applicationInfo = appInfo
        }
        shadowPm.installPackage(pkgInfo)

        if (registerVideoIntent) {
            val comp = android.content.ComponentName(pkg, "$pkg.PlayerActivity")
            val activityInfo = ActivityInfo().apply {
                packageName = pkg
                name = comp.className
                applicationInfo = appInfo
            }
            shadowPm.addOrUpdateActivity(activityInfo)
            val filter = IntentFilter(Intent.ACTION_VIEW).apply {
                addDataScheme("content")
                addDataType("video/mp4")
            }
            shadowPm.addIntentFilterForActivity(comp, filter)
        }
    }

    @Test
    fun testDiscoveryMarksInstalledPlayerVerifiedAndRemovedPlayerUnavailable() = runBlocking {
        installFakePlayer("org.videolan.vlc", "VLC")
        // Seed stale package in Room that is NOT installed
        db.discoveredPlayerDao().upsertPlayer(
            DiscoveredPlayerEntity(
                packageName = "com.deleted.player",
                displayName = "Deleted Player",
                category = "Local",
                verificationStatus = PlayerVerificationStatus.VERIFIED.name,
                confidenceScore = 80
            )
        )

        val scanned = repository.rescanAndValidateRegistry(force = true)
        val vlc = scanned.find { it.packageName == "org.videolan.vlc" }
        val deleted = scanned.find { it.packageName == "com.deleted.player" }

        assertNotNull(vlc)
        assertEquals(PlayerVerificationStatus.VERIFIED.name, vlc!!.verificationStatus)
        assertTrue(vlc.confidenceScore >= 50)

        assertNotNull(deleted)
        assertEquals(PlayerVerificationStatus.UNAVAILABLE.name, deleted!!.verificationStatus)
        assertEquals(0, deleted.confidenceScore)

        // Uninstall lifecycle event
        shadowPm.removePackage("org.videolan.vlc")
        repository.onPackageLifecycleEvent("org.videolan.vlc", isRemoved = true)
        val afterRemove = repository.getPlayer("org.videolan.vlc")
        assertEquals(PlayerVerificationStatus.UNAVAILABLE.name, afterRemove!!.verificationStatus)
    }

    @Test
    fun testLiveMediaSessionObservation_LearnsUnknownPlayerDynamicallyWithoutDuplicates() = runBlocking {
        val pkg = "tv.custom.videoapp"
        repository.recordLiveSessionObservation(
            packageName = pkg,
            capabilities = SessionCapabilities(supportsPause = true, supportsSeekTo = true, durationMs = 90_000L)
        )
        // Call twice to verify no duplicates
        repository.recordLiveSessionObservation(
            packageName = pkg,
            capabilities = SessionCapabilities(supportsPause = true, supportsSeekTo = true, durationMs = 90_000L)
        )

        val all = repository.getAllPlayersSnapshot().filter { it.packageName == pkg }
        assertEquals(1, all.size)
        assertEquals(PlayerVerificationStatus.VERIFIED.name, all[0].verificationStatus)
    }
}
