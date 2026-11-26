package com.example.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.data.StayFramePreferencesRepository
import com.example.data.registry.DiscoveredPlayerEntity
import com.example.data.registry.PlayerRegistryRepository
import com.example.domain.model.ActiveMediaSessionInfo
import com.example.domain.model.AdaptiveIntervalResolver
import com.example.domain.model.DiagnosticEvent
import com.example.domain.model.MediaPositionEstimator
import com.example.domain.model.PlayerCapabilityInspector
import com.example.domain.model.PlayerRegistry
import com.example.domain.model.PlayerVerificationStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Android NotificationListenerService observing active media sessions, resolving
 * adaptive player seek intervals, learning live players dynamically, and coordinating
 * persistent guard recovery after OS process termination.
 */
class StayFrameNotificationListenerService : NotificationListenerService() {

    private var sessionManager: MediaSessionManager? = null
    private var componentName: ComponentName? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private lateinit var registryRepository: PlayerRegistryRepository
    private lateinit var preferencesRepository: StayFramePreferencesRepository
    private var packageChangeReceiver: PackageChangeReceiver? = null
    private var isReceiverRegistered = false

    @Volatile
    private var cachedRegistry: Map<String, DiscoveredPlayerEntity> = emptyMap()

    private val sessionCallback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) {
            refreshActiveSessions()
        }

        override fun onMetadataChanged(metadata: MediaMetadata?) {
            refreshActiveSessions()
        }
    }

    private var activeControllers: List<MediaController> = emptyList()
    private val registeredControllers = mutableMapOf<MediaSession.Token, MediaController>()

    companion object {
        private const val RECOVERY_CHANNEL_ID = "stayframe_recovery_channel"
        private const val RECOVERY_NOTIFICATION_ID = 1002

        fun isNotificationListenerEnabled(context: Context): Boolean {
            val enabledListeners = Settings.Secure.getString(
                context.contentResolver,
                "enabled_notification_listeners"
            ) ?: return false
            val myComponent = ComponentName(context, StayFrameNotificationListenerService::class.java).flattenToString()
            return enabledListeners.contains(myComponent)
        }

        fun createNotificationListenerSettingsIntent(): Intent {
            return Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        sessionManager = getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
        componentName = ComponentName(this, StayFrameNotificationListenerService::class.java)
        registryRepository = PlayerRegistryRepository(applicationContext)
        preferencesRepository = StayFramePreferencesRepository(applicationContext)
        StayFrameServiceBridge.listenerServiceInstance = this

        serviceScope.launch {
            registryRepository.playersFlow.collect { list ->
                cachedRegistry = list.associateBy { it.packageName }
                mainHandler.post { refreshActiveSessions() }
            }
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        StayFrameServiceBridge.listenerServiceInstance = this
        StayFrameServiceBridge.reportListenerReconnected()

        registerPackageObserver()
        PlayerRescanWorker.schedulePeriodicRescan(applicationContext)

        serviceScope.launch {
            registryRepository.rescanAndValidateRegistry(force = false)
            attemptArmedSessionRecovery()
        }

        refreshActiveSessions()
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        StayFrameServiceBridge.listenerServiceInstance = null
        unregisterPackageObserver()
        clearCallbacks()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val comp = componentName ?: ComponentName(this, StayFrameNotificationListenerService::class.java)
            try {
                requestRebind(comp)
            } catch (e: Exception) {
                StayFrameServiceBridge.recordRecoverableException(
                    "listener_rebind_failed",
                    "Failed to request NotificationListener rebind",
                    e
                )
            }
        }
        if (StayFrameServiceBridge.isMonitoring.value) {
            StayFrameServiceBridge.reportListenerDisconnectedError(
                "Notification Listener disconnected; attempting rebind. Check Notification Access if playback control fails."
            )
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (StayFrameServiceBridge.listenerServiceInstance == this) {
            StayFrameServiceBridge.listenerServiceInstance = null
        }
        unregisterPackageObserver()
        clearCallbacks()
        serviceScope.cancel()
    }

    private fun clearCallbacks() {
        for (controller in registeredControllers.values) {
            try {
                controller.unregisterCallback(sessionCallback)
            } catch (e: Exception) {
                StayFrameServiceBridge.recordRecoverableException(
                    "media_callback_unregister_failed",
                    "Failed to unregister stale MediaController callback",
                    e
                )
            }
        }
        registeredControllers.clear()
    }

    private fun registerPackageObserver() {
        if (isReceiverRegistered) return
        val receiver = PackageChangeReceiver(
            repository = registryRepository,
            scope = serviceScope,
            onRegistryUpdated = {
                mainHandler.post { refreshActiveSessions() }
            }
        )
        packageChangeReceiver = receiver
        ContextCompat.registerReceiver(
            applicationContext,
            receiver,
            PackageChangeReceiver.createIntentFilter(),
            ContextCompat.RECEIVER_EXPORTED
        )
        isReceiverRegistered = true
    }

    private fun unregisterPackageObserver() {
        if (!isReceiverRegistered) return
        try {
            packageChangeReceiver?.let { applicationContext.unregisterReceiver(it) }
        } catch (e: Exception) {
            StayFrameServiceBridge.recordRecoverableException(
                "package_receiver_unregister_failed",
                "Failed to unregister PackageChangeReceiver",
                e
            )
        }
        packageChangeReceiver = null
        isReceiverRegistered = false
    }

    internal suspend fun attemptArmedSessionRecovery() {
        val prefs = preferencesRepository.getSnapshot()
        StayFrameServiceBridge.engine.setAbsenceGracePeriod(prefs.gracePeriodSeconds)
        StayFrameServiceBridge.engine.setDetectionMode(prefs.detectionMode)

        if (!prefs.isMonitoringArmed || StayFrameServiceBridge.isMonitoring.value) return

        val hasCamera = ContextCompat.checkSelfPermission(
            applicationContext,
            android.Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasCamera) {
            StayFrameServiceBridge.engine.reportError("Camera permission missing during recovery.")
            return
        }

        // T7: Never launch a camera FGS automatically from background; post tap-to-resume notification instead.
        StayFrameServiceBridge.addDiagnosticEvent(
            DiagnosticEvent(
                state = StayFrameServiceBridge.presenceState.value,
                eventName = "recovery_tap_to_resume_posted",
                details = "Armed monitoring session interrupted; posted tap-to-resume notification for user launch."
            )
        )
        postRecoveryNotification()
    }

    internal fun postRecoveryNotification() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                RECOVERY_CHANNEL_ID,
                "StayFrame Recovery",
                NotificationManager.IMPORTANCE_DEFAULT
            )
            manager.createNotificationChannel(channel)
        }

        val resumeIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_SINGLE_TOP or
                Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_RESUME_MONITORING, true)
        }
        val pending = PendingIntent.getActivity(
            this,
            2,
            resumeIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, RECOVERY_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("StayFrame Guard Interrupted")
            .setContentText("Tap to resume camera presence monitoring.")
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()

        manager.notify(RECOVERY_NOTIFICATION_ID, notification)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        refreshActiveSessions()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        refreshActiveSessions()
    }

    fun refreshActiveSessions() {
        try {
            val comp = componentName ?: return
            val mgr = sessionManager ?: return

            val controllers = mgr.getActiveSessions(comp)
            activeControllers = controllers

            // Register callback once per controller token and clean up stale ones
            val currentTokens = controllers.map { it.sessionToken }.toSet()
            val iterator = registeredControllers.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                if (entry.key !in currentTokens) {
                    try {
                        entry.value.unregisterCallback(sessionCallback)
                    } catch (e: Exception) {
                        StayFrameServiceBridge.recordRecoverableException(
                            "media_callback_unregister_failed",
                            "Failed to unregister inactive MediaController callback",
                            e
                        )
                    }
                    iterator.remove()
                }
            }

            for (controller in controllers) {
                val token = controller.sessionToken
                if (!registeredControllers.containsKey(token)) {
                    try {
                        controller.registerCallback(sessionCallback, mainHandler)
                        registeredControllers[token] = controller
                    } catch (e: Exception) {
                        StayFrameServiceBridge.recordRecoverableException(
                            "media_callback_register_failed",
                            "Failed to register MediaController callback for ${controller.packageName}",
                            e
                        )
                    }
                }
            }

            val globalGraceSec = StayFrameServiceBridge.engine.getAbsenceGracePeriodSeconds()
            var bestSession: ActiveMediaSessionInfo? = null

            for (controller in controllers) {
                val pkg = controller.packageName ?: continue
                val registryEntry = cachedRegistry[pkg]
                val builtIn = PlayerRegistry.findByPackage(pkg)
                val playbackState = controller.playbackState
                val capabilities = PlayerCapabilityInspector.inspect(playbackState, controller.metadata)

                val isExplicitlyDisabled = registryEntry?.isEnabledForMonitoring == false ||
                    registryEntry?.statusEnum() == PlayerVerificationStatus.UNAVAILABLE

                val isSupported = !isExplicitlyDisabled && (
                    PlayerRegistry.isSupported(pkg) ||
                        registryEntry?.statusEnum() == PlayerVerificationStatus.VERIFIED
                    )

                // Dynamically learn active video/media sessions when not yet in Room registry
                if (!isExplicitlyDisabled &&
                    playbackState?.state == PlaybackState.STATE_PLAYING &&
                    registryEntry == null &&
                    (capabilities.supportsPause || capabilities.supportsSeekTo)
                ) {
                    serviceScope.launch {
                        registryRepository.recordLiveSessionObservation(pkg, capabilities)
                    }
                }

                val timing = AdaptiveIntervalResolver.resolve(
                    globalAppGraceSec = globalGraceSec,
                    capabilities = capabilities,
                    userSyncedIntervalSec = registryEntry?.userSyncedIntervalSec,
                    observedStepIntervalSec = registryEntry?.observedStepIntervalSec,
                    profileDefaultIntervalSec = registryEntry?.profileDefaultIntervalSec ?: builtIn?.defaultSeekIntervalSeconds,
                    adapterRequiresSeekTo = builtIn?.supportsSeek ?: true
                )

                val pbState = playbackState?.state
                val isPlaying = pbState == PlaybackState.STATE_PLAYING
                val isBuffering = pbState == PlaybackState.STATE_BUFFERING ||
                    pbState == PlaybackState.STATE_CONNECTING
                val basePosition = playbackState?.position ?: 0L
                val lastUpdateTime = playbackState?.lastPositionUpdateTime ?: 0L
                val position = MediaPositionEstimator.estimatePosition(
                    state = pbState,
                    basePosition = basePosition,
                    lastPositionUpdateTime = lastUpdateTime,
                    playbackSpeed = capabilities.playbackSpeed
                )

                val sessionInfo = ActiveMediaSessionInfo(
                    packageName = pkg,
                    playerDisplayName = registryEntry?.displayName ?: builtIn?.displayName ?: pkg,
                    isPlaying = isPlaying,
                    isBuffering = isBuffering,
                    playbackPositionMs = position,
                    isSupported = isSupported,
                    canSeek = timing.canSeek,
                    effectiveIntervalSeconds = timing.effectiveGracePeriodSeconds,
                    syncSource = timing.syncSource,
                    capabilities = capabilities
                )

                if (isSupported && isPlaying) {
                    bestSession = sessionInfo
                    break
                } else if (isSupported && isBuffering && (bestSession == null || (!bestSession.isPlaying && !bestSession.isBuffering))) {
                    bestSession = sessionInfo
                } else if (isSupported && bestSession == null) {
                    bestSession = sessionInfo
                }
            }

            val finalSession = bestSession ?: ActiveMediaSessionInfo(
                packageName = null,
                playerDisplayName = "No Active Player",
                isPlaying = false,
                isBuffering = false,
                playbackPositionMs = 0L,
                isSupported = false
            )

            mainHandler.post {
                StayFrameServiceBridge.updateActiveSession(finalSession)
            }
        } catch (e: SecurityException) {
            StayFrameServiceBridge.recordRecoverableException(
                "media_session_security_error",
                "Notification Listener access missing while querying active sessions",
                e
            )
        } catch (e: Exception) {
            StayFrameServiceBridge.recordRecoverableException(
                "media_session_refresh_failed",
                "Failed to refresh active media sessions",
                e
            )
        }
    }

    private fun resolveController(targetPackage: String): MediaController? {
        return activeControllers.find { it.packageName == targetPackage }
            ?: run {
                val comp = componentName ?: return null
                try {
                    sessionManager?.getActiveSessions(comp)?.find { it.packageName == targetPackage }
                } catch (e: Exception) {
                    StayFrameServiceBridge.recordRecoverableException(
                        "media_controller_resolve_failed",
                        "Failed to resolve MediaController for $targetPackage",
                        e
                    )
                    null
                }
            }
    }

    fun sendPauseCommand(targetPackage: String): Boolean {
        val controller = resolveController(targetPackage) ?: return false
        return try {
            controller.transportControls.pause()
            true
        } catch (e: Exception) {
            StayFrameServiceBridge.recordRecoverableException(
                "media_pause_command_failed",
                "Transport pause command failed for $targetPackage",
                e
            )
            false
        }
    }

    fun sendRewindCommand(targetPackage: String, targetPositionMs: Long): Boolean {
        val controller = resolveController(targetPackage) ?: return false
        return try {
            controller.transportControls.seekTo(targetPositionMs)
            true
        } catch (e: Exception) {
            StayFrameServiceBridge.recordRecoverableException(
                "media_rewind_command_failed",
                "Transport seekTo($targetPositionMs) failed for $targetPackage",
                e
            )
            false
        }
    }

    fun sendPlayCommand(targetPackage: String): Boolean {
        val controller = resolveController(targetPackage) ?: return false
        return try {
            controller.transportControls.play()
            true
        } catch (e: Exception) {
            StayFrameServiceBridge.recordRecoverableException(
                "media_play_command_failed",
                "Transport play command failed for $targetPackage",
                e
            )
            false
        }
    }
}
