package com.charifmahmoudi.chatgptremote

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SecureConfigInstrumentedTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    @After
    fun clearConfiguration() {
        context.getSharedPreferences("secure_config", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun configurationRoundTripsThroughEncryptedPreferences() {
        val config = SecureConfig(context)

        config.saveTunnel(TEST_TUNNEL_ID, "synthetic-runtime-key")
        config.saveAdbEndpoint("127.0.0.1", 5555)
        config.markPaired()

        assertEquals(
            AppConfig(TEST_TUNNEL_ID, "synthetic-runtime-key", "127.0.0.1", 5555),
            SecureConfig(context).load(),
        )
        assertTrue(SecureConfig(context).isPaired())
    }

    @Test
    fun encryptedFileDoesNotContainPlaintextCredentials() {
        val runtimeKey = "synthetic-runtime-key-that-must-not-be-plaintext"
        SecureConfig(context).saveTunnel(TEST_TUNNEL_ID, runtimeKey)

        val encryptedFile = context.filesDir.parentFile
            ?.resolve("shared_prefs/secure_config.xml")
            ?.readText()
            .orEmpty()

        assertTrue(encryptedFile.isNotBlank())
        assertFalse(encryptedFile.contains(TEST_TUNNEL_ID))
        assertFalse(encryptedFile.contains(runtimeKey))
    }

    private companion object {
        const val TEST_TUNNEL_ID = "tunnel_0123456789abcdef0123456789abcdef"
    }
}
