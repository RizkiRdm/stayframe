package com.example.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.domain.controller.ScreenTimeoutController
import com.example.domain.model.ScreenTimeoutMode

/**
 * Receives scheduled AlarmManager expiry broadcasts for timed Keep-Screen-Awake
 * modes so the original system screen-off timeout is restored even if UI or service stopped.
 */
class ScreenTimeoutExpiryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ScreenTimeoutController.ACTION_RESTORE_SCREEN_TIMEOUT) return
        ScreenTimeoutController(context.applicationContext).restoreTimeout()
        StayFrameServiceBridge.setScreenTimeoutMode(ScreenTimeoutMode.OFF)
    }
}
