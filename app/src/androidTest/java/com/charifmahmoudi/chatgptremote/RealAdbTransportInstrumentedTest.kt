package com.charifmahmoudi.chatgptremote

import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.flyfishxu.kadb.cert.KadbCert
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RealAdbTransportInstrumentedTest {
    @Test
    fun authenticatedTransportExecutesShellCommandAgainstEmulatorAdbd() = runBlocking {
        val encodedPrivateKey = InstrumentationRegistry.getArguments()
            .getString(PRIVATE_KEY_ARGUMENT)
        assumeTrue(
            "Real ADB smoke test requires the CI-provided emulator identity",
            !encodedPrivateKey.isNullOrBlank(),
        )
        val privateKey = Base64.decode(requireNotNull(encodedPrivateKey), Base64.DEFAULT)
        KadbCert.importPrivateKey(privateKey)

        val healthEvents = mutableListOf<Pair<Boolean, String>>()
        val transport = AdbMcpTransport(
            host = "127.0.0.1",
            port = ADB_PORT,
            onHealthChanged = { healthy, category -> healthEvents += healthy to category },
        )

        transport.probe()
        val response = transport.jsonRpc(
            buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", 1)
                put("method", "tools/call")
                putJsonObject("params") {
                    put("name", "adb_shell")
                    putJsonObject("arguments") {
                        put(
                            "command",
                            "printf '${SMOKE_MARKER}\\n'; getprop ro.build.version.sdk",
                        )
                    }
                }
            },
            emptyMap(),
        )

        assertEquals(200, response.code)
        val result = requireNotNull(response.body).jsonObject["result"]!!.jsonObject
        assertFalse(result["isError"]!!.jsonPrimitive.content.toBoolean())
        val output = result["content"]!!.jsonArray.single().jsonObject["text"]!!
            .jsonPrimitive.content
        assertTrue(output.contains("exit_code=0"))
        assertTrue(output.contains(SMOKE_MARKER))
        assertTrue(output.lineSequence().any { it == EXPECTED_API_LEVEL })
        assertTrue(healthEvents.contains(true to "probe"))
        assertTrue(healthEvents.contains(true to "tool"))
    }

    private companion object {
        const val PRIVATE_KEY_ARGUMENT = "ciAdbPrivateKey"
        const val ADB_PORT = 5555
        const val SMOKE_MARKER = "ci-real-adb-smoke"
        const val EXPECTED_API_LEVEL = "35"
    }
}
