package dashingineering.jetour.tboxcore.tcp

import dashingineering.jetour.tboxcore.types.LogType
import dashingineering.jetour.tboxcore.types.TBoxHostInfo
import dashingineering.jetour.tboxcore.types.TBoxStatus
import dashingineering.jetour.tboxcore.types.TBoxWireProtocol
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Клиентское соединение с хостом любой версии.
 *
 * - [connectV3] — порт v3, рукопожатие HELLO → HOST_INFO.
 * - [connectLegacy] — порт 1104 хоста 1.x/2.x. Формат хоста определяется по первому кадру от него;
 *   если хост молчит [legacyDetectTimeoutMs], считается 1.x.
 */
internal class BridgeConnection private constructor(
    private val log: BridgeLogger,
    private val listener: Listener
) {
    interface Listener {
        fun onData(data: ByteArray)
        fun onStatus(status: TBoxStatus)
        fun onHostInfo(info: TBoxHostInfo)
        fun onClosed(reason: String)
    }

    private lateinit var tcp: TcpClient
    private val protocolDeferred = CompletableDeferred<TBoxWireProtocol>()
    private val hostInfoDeferred = CompletableDeferred<TBoxHostInfo>()
    private var legacyDetectTimeoutMs = 0L

    @Volatile
    private var protocol: TBoxWireProtocol? = null

    @Volatile
    var hostInfo: TBoxHostInfo? = null
        private set

    val isConnected: Boolean get() = tcp.isConnected

    private fun createTcp(host: String, port: Int) {
        tcp = TcpClient(host, port, log, ::onBody) { reason -> listener.onClosed(reason) }
    }

    private fun onBody(body: ByteArray) {
        when (protocol ?: detectLegacy(body)) {
            TBoxWireProtocol.LEGACY_V1 -> listener.onData(body)
            TBoxWireProtocol.TYPED_V2, TBoxWireProtocol.V3 -> onTypedBody(body)
        }
    }

    private fun detectLegacy(body: ByteArray): TBoxWireProtocol {
        val detected = WireProtocol.detectLegacyHostFormat(body) ?: TBoxWireProtocol.LEGACY_V1
        setLegacyProtocol(detected, "first frame")
        return protocol ?: detected
    }

    private fun setLegacyProtocol(detected: TBoxWireProtocol, reason: String) {
        synchronized(this) {
            if (protocol != null) return
            protocol = detected
        }
        protocolDeferred.complete(detected)
        val info = TBoxHostInfo(protocol = detected)
        hostInfo = info
        log(LogType.INFO, TAG, "Legacy host protocol $detected ($reason)")
        listener.onHostInfo(info)
    }

    private fun onTypedBody(body: ByteArray) {
        val payload = body.copyOfRange(1, body.size)
        when (body[0]) {
            FrameCodec.TYPE_DATA -> listener.onData(payload)
            FrameCodec.TYPE_STATUS -> TBoxStatus.fromByteArray(payload)?.let(listener::onStatus)
                ?: log(LogType.DEBUG, TAG, "Unknown status frame")
            FrameCodec.TYPE_HOST_INFO -> WireProtocol.decodeHostInfo(payload)?.let { info ->
                hostInfo = info
                hostInfoDeferred.complete(info)
                listener.onHostInfo(info)
            }
            else -> log(LogType.DEBUG, TAG, "Unknown frame type ${body[0]}")
        }
    }

    /** Ждёт определения формата старого хоста не дольше заданного таймаута. */
    private suspend fun awaitProtocol(): TBoxWireProtocol {
        protocol?.let { return it }
        withTimeoutOrNull(legacyDetectTimeoutMs) { protocolDeferred.await() }
        setLegacyProtocol(TBoxWireProtocol.LEGACY_V1, "host silent ${legacyDetectTimeoutMs}ms, default")
        return protocol ?: TBoxWireProtocol.LEGACY_V1
    }

    suspend fun send(data: ByteArray): Boolean {
        val frame = when (awaitProtocol()) {
            TBoxWireProtocol.LEGACY_V1 -> FrameCodec.encodeLegacy(data)
            TBoxWireProtocol.TYPED_V2, TBoxWireProtocol.V3 -> FrameCodec.encode(data, FrameCodec.TYPE_DATA)
        }
        return tcp.send(frame)
    }

    fun close() = tcp.disconnect()

    companion object {
        private const val TAG = "BridgeConnection"

        suspend fun connectV3(
            host: String,
            port: Int,
            clientPackage: String,
            log: BridgeLogger,
            listener: Listener,
            connectTimeoutMs: Int = 500,
            handshakeTimeoutMs: Long = 1_500
        ): BridgeConnection? {
            val connection = BridgeConnection(log, listener)
            connection.createTcp(host, port)
            connection.protocol = TBoxWireProtocol.V3
            if (!connection.tcp.connect(connectTimeoutMs)) return null
            val hello = FrameCodec.encode(WireProtocol.encodeHello(clientPackage), FrameCodec.TYPE_HELLO)
            if (!connection.tcp.send(hello)) {
                connection.close()
                return null
            }
            val info = withTimeoutOrNull(handshakeTimeoutMs) { connection.hostInfoDeferred.await() }
            if (info == null) {
                log(LogType.WARN, TAG, "No HOST_INFO from $host:$port within ${handshakeTimeoutMs}ms")
                connection.close()
                return null
            }
            return connection
        }

        suspend fun connectLegacy(
            host: String,
            port: Int,
            log: BridgeLogger,
            listener: Listener,
            connectTimeoutMs: Int = 500,
            detectTimeoutMs: Long = 1_000
        ): BridgeConnection? {
            val connection = BridgeConnection(log, listener)
            connection.legacyDetectTimeoutMs = detectTimeoutMs
            connection.createTcp(host, port)
            return if (connection.tcp.connect(connectTimeoutMs)) connection else null
        }
    }
}
