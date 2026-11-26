package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.FaceTemplateStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FaceTemplateStoreTest {

    private lateinit var context: Context
    private lateinit var store: FaceTemplateStore

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        val prefs = context.getSharedPreferences("stayframe_biometrics", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        store = FaceTemplateStore(context)
    }

    @Test
    fun testScenario9_DeleteTemplate_ClearsPreferencesAndResetsState() {
        val prefs = context.getSharedPreferences("stayframe_biometrics", Context.MODE_PRIVATE)
        prefs.edit()
            .putString("encrypted_face_template", "dGVzdF9lbmNyeXB0ZWRfZGF0YQ==")
            .putString("face_template_iv", "dGVzdF9pdg==")
            .putLong("enrolled_timestamp", 1700000000000L)
            .putString("enrolled_user_name", "Primary Viewer")
            .commit()

        assertTrue(store.isEnrolled())
        assertEquals("Primary Viewer", store.getEnrolledUserName())
        assertEquals(1700000000000L, store.getEnrolledTimestamp())

        store.deleteTemplate()

        assertFalse(store.isEnrolled())
        assertNull(store.getTemplate())
        assertEquals(0L, store.getEnrolledTimestamp())
    }

    @Test
    fun testScenario9_CosineSimilarity_Calculations() {
        val v1 = floatArrayOf(1f, 0f, 0f)
        val v2 = floatArrayOf(1f, 0f, 0f)
        val v3 = floatArrayOf(0f, 1f, 0f)

        assertEquals(1.0f, store.computeCosineSimilarity(v1, v2), 0.001f)
        assertEquals(0.0f, store.computeCosineSimilarity(v1, v3), 0.001f)
        assertEquals(0.0f, store.computeCosineSimilarity(floatArrayOf(), floatArrayOf()), 0.001f)
        assertEquals(0.0f, store.computeCosineSimilarity(floatArrayOf(1f), floatArrayOf(1f, 2f)), 0.001f)
    }

    @Test
    fun testT6_DeleteThenReEnroll_CallsEnsureKeyStoreKeyAndRoundTrips() {
        var ensureCalls = 0
        var deleteCalls = 0
        val keys = mutableMapOf<String, javax.crypto.SecretKey>()
        val delegate = object : FaceTemplateStore.KeyStoreDelegate {
            override fun ensureKey(alias: String) {
                ensureCalls++
                if (!keys.containsKey(alias)) {
                    val keyGen = javax.crypto.KeyGenerator.getInstance("AES").apply { init(256) }
                    keys[alias] = keyGen.generateKey()
                }
            }

            override fun getKey(alias: String): javax.crypto.SecretKey? = keys[alias]

            override fun deleteKey(alias: String) {
                deleteCalls++
                keys.remove(alias)
            }
        }

        val secureStore = FaceTemplateStore(context, delegate)
        assertEquals(1, ensureCalls)

        val firstVector = floatArrayOf(0.15f, 0.45f, 0.85f)
        assertTrue(secureStore.saveTemplate(firstVector, "Viewer One"))
        assertEquals(2, ensureCalls)
        assertTrue(secureStore.isEnrolled())
        org.junit.Assert.assertArrayEquals(firstVector, secureStore.getTemplate(), 0.0001f)

        secureStore.deleteTemplate()
        assertEquals(1, deleteCalls)
        assertFalse(secureStore.isEnrolled())
        assertNull(secureStore.getTemplate())

        val secondVector = floatArrayOf(0.75f, 0.25f, 0.60f)
        assertTrue(secureStore.saveTemplate(secondVector, "Viewer Two"))
        assertEquals(3, ensureCalls)
        assertTrue(secureStore.isEnrolled())
        assertEquals("Viewer Two", secureStore.getEnrolledUserName())
        org.junit.Assert.assertArrayEquals(secondVector, secureStore.getTemplate(), 0.0001f)
    }
}
