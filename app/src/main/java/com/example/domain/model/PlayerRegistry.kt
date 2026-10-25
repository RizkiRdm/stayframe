package com.example.domain.model

/**
 * Details of a supported video player in the compatibility registry
 */
data class SupportedPlayer(
    val packageName: String,
    val displayName: String,
    val category: String,
    val supportsSeek: Boolean = true,
    val notes: String = "Tested with Android MediaSession transport controls"
)

object PlayerRegistry {
    val SUPPORTED_PLAYERS: List<SupportedPlayer> = listOf(
        SupportedPlayer(
            packageName = "com.google.android.youtube",
            displayName = "YouTube",
            category = "Streaming Video",
            supportsSeek = true,
            notes = "Full support for play, pause, and seekTo rewind"
        ),
        SupportedPlayer(
            packageName = "org.videolan.vlc",
            displayName = "VLC for Android",
            category = "Local / Network Video",
            supportsSeek = true,
            notes = "Full support for pause and instant rewind"
        ),
        SupportedPlayer(
            packageName = "com.mxtech.videoplayer.ad",
            displayName = "MX Player",
            category = "Local Video Player",
            supportsSeek = true,
            notes = "Reliable media session support"
        ),
        SupportedPlayer(
            packageName = "com.mxtech.videoplayer.pro",
            displayName = "MX Player Pro",
            category = "Local Video Player",
            supportsSeek = true,
            notes = "Reliable media session support"
        ),
        SupportedPlayer(
            packageName = "com.android.chrome",
            displayName = "Google Chrome",
            category = "Web Media",
            supportsSeek = true,
            notes = "HTML5 web video media notifications"
        ),
        SupportedPlayer(
            packageName = "org.schabi.newpipe",
            displayName = "NewPipe",
            category = "Video Client",
            supportsSeek = true,
            notes = "Standard Android media session"
        )
    )

    private val supportedPackageSet: Set<String> = SUPPORTED_PLAYERS.map { it.packageName }.toSet()

    fun isSupported(packageName: String?): Boolean {
        if (packageName.isNullOrBlank()) return false
        return supportedPackageSet.contains(packageName)
    }

    fun findByPackage(packageName: String?): SupportedPlayer? {
        if (packageName.isNullOrBlank()) return null
        return SUPPORTED_PLAYERS.find { it.packageName == packageName }
    }
}

/**
 * Snapshot of current active media session
 */
data class ActiveMediaSessionInfo(
    val packageName: String? = null,
    val playerDisplayName: String = "No Active Player",
    val isPlaying: Boolean = false,
    val playbackPositionMs: Long = 0L,
    val isSupported: Boolean = false,
    val canSeek: Boolean = true
)
