package com.example.domain.model

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Privacy-safe diagnostic event for local inspection (PRD Section 15)
 * Strictly does not contain image data, biometric vectors, notification text, or video titles.
 */
data class DiagnosticEvent(
    val id: String = System.currentTimeMillis().toString() + "-" + (1000..9999).random(),
    val timestamp: Long = System.currentTimeMillis(),
    val state: PresenceState,
    val eventName: String,
    val details: String,
    val rewindOffsetMs: Long? = null
) {
    val formattedTime: String
        get() {
            val sdf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
            return sdf.format(Date(timestamp))
        }
}
