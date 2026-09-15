package com.charifmahmoudi.chatgptremote

import android.Manifest
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.awaitCancellation
import kotlinx.serialization.json.JsonElement
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TunnelServiceIntegrationTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @get:Rule
    val notificationPermission: GrantPermissionRule =
        GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)

    @Before
    fun prepareConfiguredService() {
        context.stopService(Intent(context, TunnelService::class.java))
        clearConfiguration()
        SecureConfig(context).apply {
            saveTunnel(TEST_TUNNEL_ID, "synthetic-runtime-key")
            markPaired()
            saveAdbEndpoint("127.0.0.1", 5555)
        }
    }

    @After
    fun cleanUp() {
        context.stopService(Intent(context, TunnelService::class.java))
        awaitPhase(ServicePhase.STOPPED)
        TunnelServiceDependencies.resetForTests()
        clearConfiguration()
    }

    @Test
    fun healthyAdbAndConnectedTunnelPublishRunning() {
        installFakes(tunnelRunner = { connected -> ConnectedTunnelRunner(connected) })

        startService()

        awaitPhase(ServicePhase.RUNNING)
        assertEquals(
            "Secure tunnel connected · local ADB verified · MCP ready",
            ServiceState.current.message,
        )
    }

    @Test
    fun runtimeAdbFailureRemovesRunningState() {
        val transport = AtomicReference<FakeAdbTransport>()
        installFakes(
            onTransportCreated = { transport.set(it) },
            tunnelRunner = { connected -> ConnectedTunnelRunner(connected) },
        )
        startService()
        awaitPhase(ServicePhase.RUNNING)

        transport.get().publishHealth(false, "unreachable")

        awaitPhase(ServicePhase.NEED_ADB_PORT)
        assertTrue(ServiceState.current.message.contains("Local ADB is unreachable"))
    }

    @Test
    fun tunnelAuthorizationFailureRequestsNewCredentials() {
        installFakes(tunnelRunner = { AuthorizationFailureTunnelRunner })

        startService()

        awaitPhase(ServicePhase.NEED_TUNNEL)
        assertTrue(ServiceState.current.message.contains("authorization failed"))
    }

    private fun installFakes(
        onTransportCreated: (FakeAdbTransport) -> Unit = {},
        tunnelRunner: (() -> Unit) -> TunnelRunner,
    ) {
        TunnelServiceDependencies.installForTests(
            adbFactory = { _, _, _, health ->
                FakeAdbTransport(health).also(onTransportCreated)
            },
            tunnelFactory = { _, _, _, _, connected, _, _ -> tunnelRunner(connected) },
        )
    }

    private fun startService() {
        ContextCompat.startForegroundService(context, Intent(context, TunnelService::class.java))
    }

    private fun awaitPhase(expected: ServicePhase) {
        val deadline = System.nanoTime() + PHASE_TIMEOUT_NANOS
        while (ServiceState.current.phase != expected && System.nanoTime() < deadline) {
            Thread.sleep(50)
        }
        assertEquals("Service did not reach $expected", expected, ServiceState.current.phase)
    }

    private fun clearConfiguration() {
        context.getSharedPreferences("secure_config", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private class FakeAdbTransport(
        private val health: (Boolean, String) -> Unit,
    ) : AdbTransport {
        override suspend fun probe() {
            health(true, "probe")
        }

        fun publishHealth(healthy: Boolean, category: String) {
            health(healthy, category)
        }

        override suspend fun jsonRpc(
            payload: JsonElement,
            headers: Map<String, List<String>>,
        ) = McpResult(204, null, emptyMap())

        override suspend fun terminate(headers: Map<String, List<String>>) =
            McpResult(204, null, emptyMap())
    }

    private class ConnectedTunnelRunner(
        private val connected: () -> Unit,
    ) : TunnelRunner {
        override suspend fun run() {
            connected()
            awaitCancellation()
        }

        override fun stop() = Unit
    }

    private object AuthorizationFailureTunnelRunner : TunnelRunner {
        override suspend fun run(): Unit = throw SecurityException("synthetic authorization failure")
        override fun stop() = Unit
    }

    private companion object {
        const val TEST_TUNNEL_ID = "tunnel_0123456789abcdef0123456789abcdef"
        const val PHASE_TIMEOUT_NANOS = 20_000_000_000L
    }
}
