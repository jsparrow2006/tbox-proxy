package dashengineering.jetour.TboxCore.client

import dashengineering.jetour.TboxCore.command.TboxCommand
import dashengineering.jetour.TboxCore.models.*
import dashengineering.jetour.TboxCore.parser.TboxResponseParser
import dashengineering.jetour.TboxCore.protocol.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

class TboxUdpClient(
    private val ip: String,
    private val port: Int = 50047,
    private val coroutineScope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
    private val onEvent: suspend (TboxEvent) -> Unit,
    private val onError: suspend (String) -> Unit = {},
    private val onLog: suspend (String) -> Unit = {}
) {
    private var socket: DatagramSocket? = null
    private var address: InetAddress? = null
    private val mutex = Mutex()
    private var listenJob: Job? = null
    private var isRunning = false
    private val currentTime: Long get() = System.currentTimeMillis()

    sealed class TboxEvent {
        data class TboxConnected(val connected: Boolean) : TboxEvent()
        data class NetState(val state: dashengineering.jetour.TboxCore.models.NetState, val values: NetValues) : TboxEvent()
        data class ApnState(val index: Int, val state: APNState) : TboxEvent()
        data class LocValues(val values: dashengineering.jetour.TboxCore.models.LocValues) : TboxEvent()
        data class VoltagesState(val state: dashengineering.jetour.TboxCore.models.VoltagesState) : TboxEvent()
        data class HdmData(val data: dashengineering.jetour.TboxCore.models.HdmData) : TboxEvent()
        data class CanFrame(val canId: String,  val data: ByteArray) : TboxEvent()
        data class AtResponse(val response: String) : TboxEvent()
        data class Version(val module: String, val version: String) : TboxEvent()
        data class CommandAck(val command: TboxCommand, val success: Boolean) : TboxEvent()
        data class LogMessage(val level: String, val tag: String, val message: String) : TboxEvent()
    }

    suspend fun start() {
        if (isRunning) return
        isRunning = true
        socket = DatagramSocket().apply { soTimeout = 1000 }
        address = InetAddress.getByName(ip)
        startListener()
        onEvent(TboxEvent.TboxConnected(true))
        onLog("INFO: TBox connected")
    }

    suspend fun stop() {
        if (!isRunning) return
        isRunning = false
        listenJob?.cancel()
        listenJob = null
        socket?.close()
        socket = null
        address = null
        onEvent(TboxEvent.TboxConnected(false))
        onLog("WARN: TBox disconnected")
    }

    fun isRunning(): Boolean = isRunning

    private fun startListener() {
        listenJob = coroutineScope.launch {
            val buffer = ByteArray(4096)
            val packet = DatagramPacket(buffer, buffer.size)
            while (isActive) {
                try {
                    socket?.receive(packet)
                    val data = packet.data.copyOf(packet.length)
                    handleResponse(data)
                } catch (e: Exception) {
                    // ignore timeouts
                }
                delay(100)
            }
        }
    }

    private suspend fun handleResponse(data: ByteArray) {
        if (!checkPacket(data)) {
            onError("Invalid packet: ${toHexString(data)}")
            return
        }
        val length = extractDataLength(data)
        if (!checkLength(data, length)) {
            onError("Invalid length: expected $length, got ${data.size - 14}")
            return
        }
        val payload = extractData(data, length)
        if (payload.isEmpty()) {
            onError("Checksum error: ${toHexString(data)}")
            return
        }

        val tid = data[9]
        val cmd = data[12]

        when (tid) {
            0x25.toByte() -> handleMDC(cmd, payload) // MDC
            0x23.toByte() -> handleCRT(cmd, payload) // CRT
            0x29.toByte() -> handleLOC(cmd, payload) // LOC
            0x2F.toByte() -> handleAPP(cmd, payload) // APP
            0x2D.toByte() -> handleSWD(cmd, payload) // SWD
            else -> onError("Unknown TID: ${tid.toInt()}")
        }
    }

    private suspend fun handleMDC(cmd: Byte,  payload: ByteArray) {
        when (cmd) {
            0x87.toByte() -> {
                TboxResponseParser.parseMDCNetState(payload, currentTime)?.let { (state, values) ->
                    onEvent(TboxEvent.NetState(state, values))
                }
            }
            0x91.toByte() -> {
                TboxResponseParser.parseMDCAPNState(payload, currentTime)?.let { (index, state) ->
                    onEvent(TboxEvent.ApnState(index, state))
                }
            }
            0x8E.toByte() -> {
                TboxResponseParser.parseATResponse(payload)?.let { resp ->
                    onEvent(TboxEvent.AtResponse(resp))
                }
            }
            0x81.toByte() -> {
                TboxResponseParser.parseVersion(payload)?.let { ver ->
                    onEvent(TboxEvent.Version("MDC", ver))
                }
            }
        }
    }

    private suspend fun handleCRT(cmd: Byte,  payload: ByteArray) {
        when (cmd) {
            0x90.toByte() -> {
                TboxResponseParser.parseCRTPowVol(payload, currentTime)?.let { state ->
                    onEvent(TboxEvent.VoltagesState(state))
                }
            }
            0x94.toByte() -> {
                TboxResponseParser.parseCRTHdmData(payload)?.let { data ->
                    onEvent(TboxEvent.HdmData(data))
                }
            }
            0x95.toByte() -> {
                TboxResponseParser.parseCRTCanFrame(payload, currentTime).forEach { event ->
                    onEvent(TboxEvent.CanFrame(event.canId, event.data))
                }
            }
            0x81.toByte() -> {
                TboxResponseParser.parseVersion(payload)?.let { ver ->
                    onEvent(TboxEvent.Version("CRT", ver))
                }
            }
        }
    }

    private suspend fun handleLOC(cmd: Byte,  payload: ByteArray) {
        when (cmd) {
            0x85.toByte() -> {
                TboxResponseParser.parseLOCValues(payload, currentTime)?.let { values ->
                    onEvent(TboxEvent.LocValues(values))
                }
            }
            0x81.toByte() -> {
                TboxResponseParser.parseVersion(payload)?.let { ver ->
                    onEvent(TboxEvent.Version("LOC", ver))
                }
            }
        }
    }

    private suspend fun handleAPP(cmd: Byte,  payload: ByteArray) {
        when (cmd) {
            0x81.toByte() -> {
                TboxResponseParser.parseVersion(payload)?.let { ver ->
                    onEvent(TboxEvent.Version("APP", ver))
                }
            }
        }
    }

    private suspend fun handleSWD(cmd: Byte,  payload: ByteArray) {
        when (cmd) {
            0x81.toByte() -> {
                TboxResponseParser.parseVersion(payload)?.let { ver ->
                    onEvent(TboxEvent.Version("SWD", ver))
                }
            }
        }
    }

    suspend fun sendCommand(command: TboxCommand): Boolean {
        if (!isRunning || socket == null || address == null) return false
        val packet = encodeCommand(command) ?: return false
        val header = fillHeader(packet.data.size, packet.tid, packet.sid, packet.cmd)
        val packetData = header + packet.data + xorSum(header + packet.data)
        return try {
            mutex.withLock {
                socket!!.send(DatagramPacket(packetData, packetData.size, address, port))
            }
            true
        } catch (e: Exception) {
            onError("Send failed: $e")
            false
        }
    }

    private fun encodeCommand(command: TboxCommand): CommandPacket? {
        val SELF_CODE = 0x50.toByte()
        return when (command) {
            is TboxCommand.SendAT -> {
                val cmdData = (command.command + "\r\n").toByteArray()
                val size = cmdData.size + 10
                val prefix = byteArrayOf(
                    (size shr 8).toByte(),
                    (size and 0xFF).toByte(),
                    -1, 0, 0, 0, 0, 0, 0, 0
                )
                CommandPacket(0x25, SELF_CODE, 0x0E, prefix + cmdData)
            }

            is TboxCommand.RebootTbox -> {
                CommandPacket(0x23, SELF_CODE, 0x2B, byteArrayOf(0x02))
            }

            is TboxCommand.SubscribeLocation -> {
                val timeout = if (command.enabled) command.intervalSec.toByte() else 0
                val data = if (command.enabled) {
                    byteArrayOf(0x02, timeout, 0x00)
                } else {
                    byteArrayOf(0x00, 0x00, 0x00)
                }
                CommandPacket(0x29, SELF_CODE, 0x05, data)
            }

            is TboxCommand.GetCanFrame -> {
                CommandPacket(0x23, SELF_CODE, 0x15, byteArrayOf(0x01, 0x02))
            }

            is TboxCommand.GetPowerVoltages -> {
                CommandPacket(0x23, SELF_CODE, 0x10, byteArrayOf(0x01, 0x01))
            }

            is TboxCommand.GetHdmData -> {
                CommandPacket(0x23, SELF_CODE, 0x14, byteArrayOf(0x03, 0x00))
            }

            is TboxCommand.GetVersions -> {
                // Вернём null — клиент должен отправлять команды по отдельности
                null
            }

            else -> null
        }
    }
}