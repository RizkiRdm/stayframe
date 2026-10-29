package com.example.service

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.example.domain.model.ActiveMediaSessionInfo
import com.example.domain.model.PlayerRegistry

/**
 * Android NotificationListenerService observing active media sessions
 * and executing play, pause, and seekTo transport commands.
 */
class StayFrameNotificationListenerService : NotificationListenerService() {

    private var sessionManager: MediaSessionManager? = null
    private var componentName: ComponentName? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var isBound = false

    private val sessionCallback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) {
            refreshActiveSessions()
        }
    }

    private var activeControllers: List<MediaController> = emptyList()
    private val registeredControllers = mutableMapOf<MediaSession.Token, MediaController>()

    companion object {
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
        StayFrameServiceBridge.listenerServiceInstance = this
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        isBound = true
        StayFrameServiceBridge.listenerServiceInstance = this
        refreshActiveSessions()
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        isBound = false
        StayFrameServiceBridge.listenerServiceInstance = null
        for (controller in registeredControllers.values) {
            try {
                controller.unregisterCallback(sessionCallback)
            } catch (_: Exception) {}
        }
        registeredControllers.clear()
    }

    override fun onDestroy() {
        super.onDestroy()
        isBound = false
        if (StayFrameServiceBridge.listenerServiceInstance == this) {
            StayFrameServiceBridge.listenerServiceInstance = null
        }
        for (controller in registeredControllers.values) {
            try {
                controller.unregisterCallback(sessionCallback)
            } catch (_: Exception) {}
        }
        registeredControllers.clear()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        refreshActiveSessions()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        refreshActiveSessions()
    }

    /**
     * Inspect active media sessions across the system
     */
    fun refreshActiveSessions() {
        try {
            val comp = componentName ?: return
            val mgr = sessionManager ?: return

            val controllers = mgr.getActiveSessions(comp)
            activeControllers = controllers

            // Register callback once per controller and clean up stale ones
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

            // Find first playing supported session, or any active supported session
            var bestSession: ActiveMediaSessionInfo? = null

            for (controller in controllers) {
                val pkg = controller.packageName
                val isSupported = PlayerRegistry.isSupported(pkg)
                val playbackState = controller.playbackState
                val isPlaying = playbackState?.state == PlaybackState.STATE_PLAYING
                val basePosition = playbackState?.position ?: 0L
                val lastUpdateTime = playbackState?.lastPositionUpdateTime ?: 0L
                val playbackSpeed = playbackState?.playbackSpeed ?: 1.0f
                val position = com.example.domain.model.MediaPositionEstimator.estimatePosition(
                    state = playbackState?.state,
                    basePosition = basePosition,
                    lastPositionUpdateTime = lastUpdateTime,
                    playbackSpeed = playbackSpeed
                )
                val player = PlayerRegistry.findByPackage(pkg)

                val sessionInfo = ActiveMediaSessionInfo(
                    packageName = pkg,
                    playerDisplayName = player?.displayName ?: (pkg ?: "Unknown"),
                    isPlaying = isPlaying,
                    playbackPositionMs = position,
                    isSupported = isSupported,
                    canSeek = player?.supportsSeek ?: true
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

    /**
     * Send Pause command to target package
     */
    fun sendPauseCommand(targetPackage: String) {
        val controller = activeControllers.find { it.packageName == targetPackage }
            ?: sessionManager?.getActiveSessions(componentName ?: return)?.find { it.packageName == targetPackage }

        controller?.transportControls?.pause()
    }

    /**
     * Send Rewind / SeekTo command to target package
     */
    fun sendRewindCommand(targetPackage: String, targetPositionMs: Long) {
        val controller = activeControllers.find { it.packageName == targetPackage }
            ?: sessionManager?.getActiveSessions(componentName ?: return)?.find { it.packageName == targetPackage }

        controller?.transportControls?.seekTo(targetPositionMs)
    }

    /**
     * Send Play command to target package
     */
    fun sendPlayCommand(targetPackage: String) {
        val controller = activeControllers.find { it.packageName == targetPackage }
            ?: sessionManager?.getActiveSessions(componentName ?: return)?.find { it.packageName == targetPackage }

        controller?.transportControls?.play()
    }
}
