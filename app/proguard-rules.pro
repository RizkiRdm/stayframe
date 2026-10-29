# Proguard rules for StayFrame

# ML Kit Face Detection
-keep class com.google.mlkit.vision.face.** { *; }
-keep class com.google.android.gms.vision.** { *; }

# Domain and Data Models
-keep class com.example.domain.** { *; }
-keep class com.example.data.** { *; }

# CameraX
-keep class androidx.camera.** { *; }

# Media & Notification
-keep class android.media.session.** { *; }

# Coroutines & Serialization
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
