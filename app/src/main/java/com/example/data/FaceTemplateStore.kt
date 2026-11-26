package com.example.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import com.example.domain.biometrics.FaceDescriptorExtractor
import com.example.service.StayFrameServiceBridge
import java.nio.ByteBuffer
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlin.math.sqrt

/**
 * On-device face template encryption and storage backed by Android Keystore.
 * Stores only normalized feature vectors; never stores raw camera frames or images.
 */
class FaceTemplateStore internal constructor(
    private val context: Context,
    private val keyStoreDelegate: KeyStoreDelegate = AndroidKeyStoreDelegate
) {

    constructor(context: Context) : this(context, AndroidKeyStoreDelegate)

    internal interface KeyStoreDelegate {
        fun ensureKey(alias: String)
        fun getKey(alias: String): SecretKey?
        fun deleteKey(alias: String)
    }

    private object AndroidKeyStoreDelegate : KeyStoreDelegate {
        override fun ensureKey(alias: String) {
            try {
                val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
                if (!keyStore.containsAlias(alias)) {
                    val keyGen = KeyGenerator.getInstance(
                        android.security.keystore.KeyProperties.KEY_ALGORITHM_AES,
                        ANDROID_KEYSTORE
                    )
                    val keyGenParameterSpec = android.security.keystore.KeyGenParameterSpec.Builder(
                        alias,
                        android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or android.security.keystore.KeyProperties.PURPOSE_DECRYPT
                    )
                        .setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256)
                        .build()

                    keyGen.init(keyGenParameterSpec)
                    keyGen.generateKey()
                }
            } catch (e: Exception) {
                StayFrameServiceBridge.recordRecoverableException(
                    "keystore_ensure_failed",
                    "Failed to initialize Keystore key for $alias",
                    e
                )
            }
        }

        override fun getKey(alias: String): SecretKey? {
            return try {
                val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
                (keyStore.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.secretKey
            } catch (e: Exception) {
                StayFrameServiceBridge.recordRecoverableException(
                    "keystore_load_failed",
                    "Failed to retrieve Keystore key for $alias",
                    e
                )
                null
            }
        }

        override fun deleteKey(alias: String) {
            try {
                val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
                if (keyStore.containsAlias(alias)) {
                    keyStore.deleteEntry(alias)
                }
            } catch (e: Exception) {
                StayFrameServiceBridge.recordRecoverableException(
                    "keystore_delete_failed",
                    "Failed to delete Keystore key for $alias",
                    e
                )
            }
        }
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    companion object {
        private const val PREFS_NAME = "stayframe_biometrics"
        private const val KEY_ENCRYPTED_TEMPLATE = "encrypted_face_template"
        private const val KEY_IV = "face_template_iv"
        private const val KEY_ENROLLED_TIMESTAMP = "enrolled_timestamp"
        private const val KEY_ENROLLED_USER_NAME = "enrolled_user_name"
        private const val KEY_ALIAS = "StayFrameKeyStoreAlias"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_LENGTH = 128

        /** Canonical cosine similarity threshold for identity match */
        const val MATCH_THRESHOLD = FaceDescriptorExtractor.RECOMMENDED_MATCH_THRESHOLD
    }

    init {
        ensureKeyStoreKey()
    }

    private fun ensureKeyStoreKey() {
        keyStoreDelegate.ensureKey(KEY_ALIAS)
    }

    private fun getSecretKey(): SecretKey? {
        return keyStoreDelegate.getKey(KEY_ALIAS)
    }

    /**
     * Encrypt and store the enrolled face feature vector.
     * Re-ensures Android Keystore alias exists before encryption so delete -> re-enroll succeeds cleanly.
     */
    fun saveTemplate(vector: FloatArray, userName: String = "Primary Viewer"): Boolean {
        return try {
            ensureKeyStoreKey()

            val byteBuffer = ByteBuffer.allocate(vector.size * 4)
            for (f in vector) {
                byteBuffer.putFloat(f)
            }
            val rawBytes = byteBuffer.array()

            val secretKey = getSecretKey() ?: return false
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey)
            val iv = cipher.iv
            val encryptedBytes = cipher.doFinal(rawBytes)

            prefs.edit()
                .putString(KEY_ENCRYPTED_TEMPLATE, Base64.encodeToString(encryptedBytes, Base64.NO_WRAP))
                .putString(KEY_IV, Base64.encodeToString(iv, Base64.NO_WRAP))
                .putLong(KEY_ENROLLED_TIMESTAMP, System.currentTimeMillis())
                .putString(KEY_ENROLLED_USER_NAME, userName)
                .apply()
            true
        } catch (e: Exception) {
            StayFrameServiceBridge.recordRecoverableException(
                "template_encrypt_failed",
                "Failed to encrypt and save enrolled facial template",
                e
            )
            false
        }
    }

    /**
     * Decrypt and load the enrolled face feature vector.
     * Fails closed if IV, ciphertext, or SecretKey is missing/corrupt.
     */
    fun getTemplate(): FloatArray? {
        val encryptedBase64 = prefs.getString(KEY_ENCRYPTED_TEMPLATE, null) ?: return null
        val ivBase64 = prefs.getString(KEY_IV, null) ?: return null
        return try {
            val encryptedBytes = Base64.decode(encryptedBase64, Base64.NO_WRAP)
            val iv = Base64.decode(ivBase64, Base64.NO_WRAP)
            val secretKey = getSecretKey() ?: return null

            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_LENGTH, iv))
            val rawBytes = cipher.doFinal(encryptedBytes)

            val buffer = ByteBuffer.wrap(rawBytes)
            val floatCount = rawBytes.size / 4
            val result = FloatArray(floatCount)
            for (i in 0 until floatCount) {
                result[i] = buffer.float
            }
            result
        } catch (e: Exception) {
            StayFrameServiceBridge.recordRecoverableException(
                "template_decrypt_failed",
                "Failed to decrypt enrolled facial template",
                e
            )
            null
        }
    }

    fun isEnrolled(): Boolean {
        return prefs.contains(KEY_ENCRYPTED_TEMPLATE)
    }

    fun getEnrolledTimestamp(): Long {
        return prefs.getLong(KEY_ENROLLED_TIMESTAMP, 0L)
    }

    fun getEnrolledUserName(): String {
        return prefs.getString(KEY_ENROLLED_USER_NAME, "Primary Viewer") ?: "Primary Viewer"
    }

    /**
     * Delete enrollment from preferences and delete cryptographic key from Android Keystore.
     */
    fun deleteTemplate() {
        prefs.edit()
            .remove(KEY_ENCRYPTED_TEMPLATE)
            .remove(KEY_IV)
            .remove(KEY_ENROLLED_TIMESTAMP)
            .remove(KEY_ENROLLED_USER_NAME)
            .apply()

        keyStoreDelegate.deleteKey(KEY_ALIAS)
    }

    /**
     * Calculate cosine similarity between two feature vectors
     */
    fun computeCosineSimilarity(v1: FloatArray, v2: FloatArray): Float {
        if (v1.isEmpty() || v2.isEmpty() || v1.size != v2.size) return 0f
        var dot = 0f
        var normA = 0f
        var normB = 0f
        for (i in v1.indices) {
            dot += v1[i] * v2[i]
            normA += v1[i] * v1[i]
            normB += v2[i] * v2[i]
        }
        val denom = sqrt(normA.toDouble()) * sqrt(normB.toDouble())
        return if (denom > 0) (dot / denom).toFloat() else 0f
    }
}
