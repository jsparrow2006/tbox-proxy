package dashingineering.jetour.tboxcore.tcp

import dashingineering.jetour.tboxcore.types.LogType
import dashingineering.jetour.tboxcore.types.TBoxHostInfo
import dashingineering.jetour.tboxcore.types.TBoxStatus
import dashingineering.jetour.tboxcore.types.TBoxStatusType
import dashingineering.jetour.tboxcore.types.TBoxWireProtocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.io.BufferedOutputStream
import java.io.Closeable
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

internal typealias BridgeLogger = (type: LogType, tag: String, message: String) -> Unit

/**
 * Локальный TCP-сервер моста.
 *
 * - [legacyPort] (1104) — клиенты 1.x/2.x. Формат по умолчанию — 1.x; клиент 2.1.x
 *   распознаётся по первому присланному кадру.
 * - [v3Port] (1105) — клиенты 3.x: рукопожатие HELLO → HOST_INFO, данные и статусы.
 *
 * Каждый клиент пишется своей корутиной из своей очереди: медленный клиент не тормозит
 * остальных, а кадры в сокете не перемешиваются.
 */
internal class TcpServer(
    private val legacyPort: Int,
    private val v3Port: Int,
    private val hostInfo: () -> TBoxHostInfo,
    private val onClientData: suspend (ByteArray) -> Unit,
    private val log: BridgeLogger
) {
    private val clients = CopyOnWriteArrayList<ClientConnection>()
    private val servers = CopyOnWriteArrayList<ServerSocket>()
    private val running = AtomicBoolean(false)
    private var scope: CoroutineScope? = null

    /** `false`, если не удалось занять [legacyPort]. Порт v3 необязателен. */
    fun start(): Boolean {
        if (!running.compareAndSet(false, true)) return true
        val serverScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        scope = serverScope

        val legacy = bind(legacyPort)
        if (legacy == null) {
            running.set(false)
            serverScope.cancel()
            scope = null
            return false
        }
        serverScope.launch { acceptLoop(legacy, Endpoint.LEGACY) }

        if (v3Port > 0) {
            bind(v3Port)?.let { v3 -> serverScope.launch { acceptLoop(v3, Endpoint.V3) } }
        }
        return true
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        servers.forEach { runCatching { it.close() } }
        servers.clear()
        clients.forEach { it.close() }
        clients.clear()
        scope?.cancel()
        scope = null
        log(LogType.INFO, TAG, "Stopped")
    }

    val clientCount: Int get() = clients.size

    fun broadcastData(data: ByteArray) {
        if (data.isEmpty()) return
        val frames = LazyFrames { protocol ->
            when (protocol) {
                TBoxWireProtocol.LEGACY_V1 -> FrameCodec.encodeLegacy(data)
                TBoxWireProtocol.TYPED_V2, TBoxWireProtocol.V3 -> FrameCodec.encode(data, FrameCodec.TYPE_DATA)
            }
        }
        clients.forEach { client -> client.protocolForData()?.let { client.enqueue(frames[it]) } }
    }

    fun broadcastStatus(status: TBoxStatus) {
        val frame by lazy { FrameCodec.encode(status.toByteArray(), FrameCodec.TYPE_STATUS) }
        clients.forEach { client -> if (client.acceptsStatus(status.type)) client.enqueue(frame) }
    }

    fun broadcastHostInfo() {
        val frame = FrameCodec.encode(WireProtocol.encodeHostInfo(hostInfo()), FrameCodec.TYPE_HOST_INFO)
        clients.forEach { client -> if (client.isV3Ready) client.enqueue(frame) }
    }

    private fun bind(port: Int): ServerSocket? = try {
        ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress(InetAddress.getByName(LOOPBACK), port), BACKLOG)
        }.also {
            servers += it
            log(LogType.INFO, TAG, "Listening on $LOOPBACK:$port")
        }
    } catch (e: Exception) {
        log(LogType.ERROR, TAG, "Bind $port failed: ${e.javaClass.simpleName}: ${e.message}")
        null
    }

    private fun acceptLoop(server: ServerSocket, endpoint: Endpoint) {
        while (running.get() && !server.isClosed) {
            val socket = try {
                server.accept()
            } catch (e: Exception) {
                if (running.get()) log(LogType.ERROR, TAG, "Accept error on ${server.localPort}: ${e.message}")
                continue
            }
            socket.tcpNoDelay = true
            val client = ClientConnection(socket, endpoint)
            clients += client
            log(LogType.INFO, TAG, "Client connected (${endpoint.name.lowercase()}:${socket.port}), total=${clients.size}")
            scope?.launch { client.writeLoop() }
            scope?.launch {
                client.readLoop()
                clients.remove(client)
                log(LogType.INFO, TAG, "Client disconnected (${endpoint.name.lowercase()}:${socket.port}), total=${clients.size}")
            }
        }
    }

    private enum class Endpoint { LEGACY, V3 }

    private class LazyFrames(private val encode: (TBoxWireProtocol) -> ByteArray) {
        private val cache = HashMap<TBoxWireProtocol, ByteArray>(2)
        operator fun get(protocol: TBoxWireProtocol): ByteArray = cache.getOrPut(protocol) { encode(protocol) }
    }

    private inner class ClientConnection(
        private val socket: Socket,
        private val endpoint: Endpoint
    ) : Closeable {
        private val outbox = Channel<ByteArray>(OUTBOX_CAPACITY, BufferOverflow.DROP_OLDEST)
        private val closed = AtomicBoolean(false)

        @Volatile
        private var protocol: TBoxWireProtocol? =
            if (endpoint == Endpoint.LEGACY) TBoxWireProtocol.LEGACY_V1 else null

        @Volatile
        private var protocolDetected = endpoint == Endpoint.V3

        val isV3Ready: Boolean get() = protocol == TBoxWireProtocol.V3

        /** Протокол для кадров данных; `null` — клиенту v3 ещё рано (нет HELLO). */
        fun protocolForData(): TBoxWireProtocol? = protocol

        fun acceptsStatus(type: TBoxStatusType): Boolean = when (protocol) {
            TBoxWireProtocol.V3 -> true
            TBoxWireProtocol.TYPED_V2 -> type.ordinal <= TBoxStatusType.LAST_V2_STATUS.ordinal
            TBoxWireProtocol.LEGACY_V1, null -> false
        }

        fun enqueue(frame: ByteArray) {
            if (!closed.get()) outbox.trySend(frame)
        }

        suspend fun writeLoop() {
            try {
                val output = BufferedOutputStream(socket.getOutputStream())
                for (frame in outbox) {
                    output.write(frame)
                    while (true) output.write(outbox.tryReceive().getOrNull() ?: break)
                    output.flush()
                }
            } catch (e: Exception) {
                if (!closed.get()) log(LogType.WARN, TAG, "Write to client failed: ${e.message}")
            } finally {
                close()
            }
        }

        suspend fun readLoop() {
            val reader = FrameReader()
            val chunk = ByteArray(READ_CHUNK)
            try {
                val input = socket.getInputStream()
                while (!closed.get()) {
                    val read = input.read(chunk)
                    if (read < 0) break
                    if (read == 0) continue
                    for (body in reader.append(chunk, read)) handleBody(body)
                }
            } catch (e: FrameReader.FrameException) {
                log(LogType.ERROR, TAG, "Protocol error from client: ${e.message}")
            } catch (e: Exception) {
                if (!closed.get()) log(LogType.WARN, TAG, "Read from client failed: ${e.message}")
            } finally {
                close()
            }
        }

        private suspend fun handleBody(body: ByteArray) {
            when (endpoint) {
                Endpoint.LEGACY -> handleLegacy(body)
                Endpoint.V3 -> handleV3(body)
            }
        }

        private suspend fun handleLegacy(body: ByteArray) {
            if (!protocolDetected) {
                protocol = WireProtocol.detectLegacyClientFormat(body)
                protocolDetected = true
                log(LogType.INFO, TAG, "Legacy client:${socket.port} uses $protocol")
            }
            val data = when (protocol) {
                TBoxWireProtocol.TYPED_V2 -> if (body[0] == FrameCodec.TYPE_DATA) body.copyOfRange(1, body.size) else null
                else -> body
            }
            if (data != null && data.isNotEmpty()) onClientData(data)
        }

        private suspend fun handleV3(body: ByteArray) {
            val type = body[0]
            val payload = body.copyOfRange(1, body.size)
            if (protocol == null) {
                val hello = if (type == FrameCodec.TYPE_HELLO) WireProtocol.decodeHello(payload) else null
                if (hello == null) {
                    log(LogType.WARN, TAG, "v3 client:${socket.port} did not start with HELLO, closing")
                    close()
                    return
                }
                log(LogType.INFO, TAG, "v3 client:${socket.port} ${hello.clientPackage} lib=${hello.libraryVersion} proto=${hello.protocolVersion}")
                enqueueHostInfo()
                protocol = TBoxWireProtocol.V3
                return
            }
            if (type == FrameCodec.TYPE_DATA && payload.isNotEmpty()) onClientData(payload)
        }

        private fun enqueueHostInfo() {
            enqueue(FrameCodec.encode(WireProtocol.encodeHostInfo(hostInfo()), FrameCodec.TYPE_HOST_INFO))
        }

        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            outbox.close()
            runCatching { socket.close() }
        }
    }

    private companion object {
        const val TAG = "TcpServer"
        const val LOOPBACK = "127.0.0.1"
        const val BACKLOG = 50
        const val READ_CHUNK = 16 * 1024
        const val OUTBOX_CAPACITY = 1024
    }
}
