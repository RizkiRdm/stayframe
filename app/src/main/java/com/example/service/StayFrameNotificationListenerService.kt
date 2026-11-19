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
            } catch (_: Exception) {}
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
        } catch (_: Exception) {}
        packageChangeReceiver = null
        isReceiverRegistered = false
    }

    private suspend fun attemptArmedSessionRecovery() {
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

        StayFrameServiceBridge.engine.beginRecovery()

        try {
            MonitoringForegroundService.start(applicationContext)
            StayFrameServiceBridge.addDiagnosticEvent(
                DiagnosticEvent(
                    state = StayFrameServiceBridge.presenceState.value,
                    eventName = "recovery_resumed",
                    details = "Monitoring service resumed after OS reconnection"
                )
            )
        } catch (e: Exception) {
            // Android 14+ prohibits launching camera foreground service from pure background
            StayFrameServiceBridge.addDiagnosticEvent(
                DiagnosticEvent(
                    state = StayFrameServiceBridge.presenceState.value,
                    eventName = "recovery_requires_foreground_tap",
                    details = "System blocked background camera start; tap recovery notification to resume."
                )
            )
            postRecoveryNotification()
        }
    }

    private fun postRecoveryNotification() {
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
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
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
            .setContentText("Tap to restore camera presence monitoring.")
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
                    } catch (_: Exception) {}
                    iterator.remove()
                }
            }

            for (controller in controllers) {
                val token = controller.sessionToken
                if (!registeredControllers.containsKey(token)) {
                    try {
                        controller.registerCallback(sessionCallback, mainHandler)
                        registeredControllers[token] = controller
                    } catch (_: Exception) {}
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

                val isPlaying = playbackState?.state == PlaybackState.STATE_PLAYING
                val basePosition = playbackState?.position ?: 0L
                val lastUpdateTime = playbackState?.lastPositionUpdateTime ?: 0L
                val position = MediaPositionEstimator.estimatePosition(
                    state = playbackState?.state,
                    basePosition = basePosition,
                    lastPositionUpdateTime = lastUpdateTime,
                    playbackSpeed = capabilities.playbackSpeed
                )

                val sessionInfo = ActiveMediaSessionInfo(
                    packageName = pkg,
                    playerDisplayName = registryEntry?.displayName ?: builtIn?.displayName ?: pkg,
                    isPlaying = isPlaying,
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
                } else if (isSupported && bestSession == null) {
                    bestSession = sessionInfo
                }
            }

            val finalSession = bestSession ?: ActiveMediaSessionInfo(
                packageName = null,
                playerDisplayName = "No Active Player",
                isPlaying = false,
                playbackPositionMs = 0L,
                isSupported = false
            )

            mainHandler.post {
                StayFrameServiceBridge.updateActiveSession(finalSession)
            }
        } catch (_: SecurityException) {
            // Permission might not be granted yet
        } catch (_: Exception) {}
    }

    fun sendPauseCommand(targetPackage: String) {
        val controller = activeControllers.find { it.packageName == targetPackage }
            ?: sessionManager?.getActiveSessions(componentName ?: return)?.find { it.packageName == targetPackage }

        controller?.transportControls?.pause()
    }

    fun sendRewindCommand(targetPackage: String, targetPositionMs: Long) {
        val controller = activeControllers.find { it.packageName == targetPackage }
            ?: sessionManager?.getActiveSessions(componentName ?: return)?.find { it.packageName == targetPackage }

        controller?.transportControls?.seekTo(targetPositionMs)
    }

    fun sendPlayCommand(targetPackage: String) {
        val controller = activeControllers.find { it.packageName == targetPackage }
            ?: sessionManager?.getActiveSessions(componentName ?: return)?.find { it.packageName == targetPackage }

        controller?.transportControls?.play()
    }
}
