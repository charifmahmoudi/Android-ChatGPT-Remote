package com.charifmahmoudi.chatgptremote

/**
 * Narrow construction boundary for the service's two external systems.
 *
 * Production always starts with the real implementations. Debug instrumentation tests may install
 * deterministic substitutes; release builds reject that operation.
 */
internal object TunnelServiceDependencies {
    private val productionAdbFactory: AdbFactory = { host, port, diagnostic, health ->
        AdbMcpTransport(host, port, diagnostic, health)
    }
    private val productionTunnelFactory: TunnelFactory =
        { baseUrl, tunnelId, apiKey, transport, connected, lost, diagnostic ->
            TunnelClient(
                baseUrl = baseUrl,
                tunnelId = tunnelId,
                apiKey = apiKey,
                transport = transport,
                onConnected = connected,
                onConnectionLost = lost,
                onDiagnostic = diagnostic,
            )
        }

    @Volatile
    private var adbFactory: AdbFactory = productionAdbFactory

    @Volatile
    private var tunnelFactory: TunnelFactory = productionTunnelFactory

    fun createAdbTransport(
        host: String,
        port: Int,
        onDiagnostic: (String) -> Unit,
        onHealthChanged: (Boolean, String) -> Unit,
    ): AdbTransport = adbFactory(host, port, onDiagnostic, onHealthChanged)

    fun createTunnelRunner(
        baseUrl: String,
        tunnelId: String,
        apiKey: String,
        transport: McpTransport,
        onConnected: () -> Unit,
        onConnectionLost: () -> Unit,
        onDiagnostic: (String) -> Unit,
    ): TunnelRunner = tunnelFactory(
        baseUrl,
        tunnelId,
        apiKey,
        transport,
        onConnected,
        onConnectionLost,
        onDiagnostic,
    )

    fun installForTests(adbFactory: AdbFactory, tunnelFactory: TunnelFactory) {
        check(BuildConfig.DEBUG) { "Test dependencies are unavailable in release builds" }
        this.adbFactory = adbFactory
        this.tunnelFactory = tunnelFactory
    }

    fun resetForTests() {
        check(BuildConfig.DEBUG) { "Test dependencies are unavailable in release builds" }
        adbFactory = productionAdbFactory
        tunnelFactory = productionTunnelFactory
    }
}

internal typealias AdbFactory = (
    host: String,
    port: Int,
    diagnostic: (String) -> Unit,
    health: (Boolean, String) -> Unit,
) -> AdbTransport

internal typealias TunnelFactory = (
    baseUrl: String,
    tunnelId: String,
    apiKey: String,
    transport: McpTransport,
    connected: () -> Unit,
    lost: () -> Unit,
    diagnostic: (String) -> Unit,
) -> TunnelRunner
