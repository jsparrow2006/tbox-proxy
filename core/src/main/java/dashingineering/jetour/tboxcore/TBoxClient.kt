package dashingineering.jetour.tboxcore

import android.content.Context
import android.content.Intent
import dashingineering.jetour.tboxcore.discovery.TcpDiscovery
import dashingineering.jetour.tboxcore.service.TBoxBridgeService
import dashingineering.jetour.tboxcore.tcp.TcpClient
import dashingineering.jetour.tboxcore.types.LogType
import dashingineering.jetour.tboxcore.types.TBoxClientCallback
import dashingineering.jetour.tboxcore.types.TBoxCommand
import dashingineering.jetour.tboxcore.util.ByteConverter
import dashingineering.jetour.tboxcore.util.ByteConverter.toLogString
import dashingineering.jetour.tboxcore.util.startForegroundServiceCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.net.InetAddress
import kotlin.collections.plus

/**
 * 🎯 Главная точка входа в библиотеку TBoxCore
 *
 * Принцип работы:
 * 1. При создании экземпляра проверяется наличие TCP-сервера на порту
 * 2. Если сервер есть → подключаемся как клиент
 * 3. Если нет → запускаем локальный сервис-сервер
 * 4. Все данные передаются как raw ByteArray
 *
 * @param context контекст приложения
 * @param localPort локальный UDP порт (по умолчанию 11048)
 * @param remotePort удалённый UDP порт (по умолчанию 50047)
 * @param remoteAddress IP-адрес UDP сервера (по умолчанию 192.168.225.1)
 * @param tcpPort TCP порт для межприложенного взаимодействия (по умолчанию 1104)
 * @param host хост для TCP подключения (по умолчанию 127.0.0.1)
 * @param callback коллбэки для получения данных и логов
 *
 * @sample
 * // Вариант 1: Параметры по умолчанию
 * val client = TBoxClient(
 *     context = appContext,
 *     callback = myCallback
 * )
 *
 * @sample
 * // Вариант 2: Кастомные параметры
 * val client = TBoxClient(
 *     context = appContext,
 *     localPort = 11048,
 *     remotePort = 50047,
 *     remoteAddress = "192.168.225.1",
 *     tcpPort = 1104,
 *     callback = myCallback
 * )
 */
class TBoxClient(
    private val context: Context,
    private val localPort: Int = DEFAULT_LOCAL_PORT,
    private val remotePort: Int = DEFAULT_REMOTE_PORT,
    private val remoteAddress: String = DEFAULT_REMOTE_ADDRESS,
    private val tcpPort: Int = DEFAULT_TCP_PORT,
    private val host: String = DEFAULT_HOST,
    private val callback: TBoxClientCallback
) {

    companion object {
        const val DEFAULT_LOCAL_PORT = 11048
        const val DEFAULT_REMOTE_PORT = 50047
        const val DEFAULT_REMOTE_ADDRESS = "192.168.225.1"
        const val DEFAULT_TCP_PORT = 1104
        const val DEFAULT_HOST = "127.0.0.1"
    }

    private var config: Config? = null
    private var tcpClient: TcpClient? = null  // Для подключения к серверу (в любом режиме)
    private var isServerMode = false
    private var scope: CoroutineScope? = null
    private var isInitialized = false

    // Очередь для последовательной отправки команд
    private lateinit var sendQueue: Channel<ByteArray>
    private var sendJob: Job? = null

    private data class Config(
        val localPort: Int,
        val remotePort: Int,
        val remoteAddress: InetAddress,
        val tcpPort: Int,
        val host: String
    )

    fun initialize() {
        if (isInitialized) {
            log(LogType.WARN, "TBoxClient", "Already initialized, ignoring duplicate init")
            return
        }

        val inetAddress = try {
            InetAddress.getByName(remoteAddress)
        } catch (e: Exception) {
            callback.onLogMessage(LogType.ERROR, "TBoxClient", "Invalid address: $remoteAddress, using default")
            InetAddress.getByName(DEFAULT_REMOTE_ADDRESS)
        }

        this.config = Config(localPort, remotePort, inetAddress, tcpPort, host)
        this.scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
        this.isInitialized = true
        
        // Сбрасываем и создаём новую очередь при новой инициализации
        sendJob?.cancel()
        if (::sendQueue.isInitialized) {
            sendQueue.close()
        }
        sendJob = null
        sendQueue = Channel(Channel.UNLIMITED)

        log(LogType.INFO, "TBoxClient", "Initialized with UDP:$localPort → $remoteAddress:$remotePort, TCP:$tcpPort")

        scope?.launch {
            discoverAndConnect()
        }
    }

    private suspend fun discoverAndConnect() {
        val cfg = config ?: return

        val serverExists = TcpDiscovery.isServerAvailable(
            host = cfg.host,
            port = cfg.tcpPort,
            timeoutMs = 300
        )

        if (serverExists) {
            log(LogType.INFO, "TBoxClient", "Server found on port ${cfg.tcpPort}, connecting as client")
            connectAsClient(cfg)
        } else {
            log(LogType.INFO, "TBoxClient", "No server found on port ${cfg.tcpPort}, starting local bridge")
            startAsServer(cfg)
        }
    }

    private suspend fun connectAsClient(cfg: Config) {
        tcpClient = TcpClient(
            host = cfg.host,
            port = cfg.tcpPort,
            callback = object : TBoxClientCallback {
                override fun onDataReceived(data: ByteArray) {
                    // Данные от сервера (UDP → TCP → мы)
                    callback.onDataReceived(data)
                }

                override fun onLogMessage(type: LogType, tag: String, message: String) {
                    callback.onLogMessage(type, "TcpClient.$tag", message)
                }

                override fun onConnectionChanged(connected: Boolean) {
                    callback.onConnectionChanged(connected)
                }
            }
        )

        val connected = tcpClient?.connect() == true
        if (!connected) {
            log(LogType.WARN, "TBoxClient", "Failed to connect to server, trying to start local server")
            startAsServer(cfg)
        } else {
            // Запускаем обработчик очереди отправки
            startSendProcessor()
        }
    }

    private fun startAsServer(cfg: Config) {
        val intent = Intent(context, TBoxBridgeService::class.java).apply {
            action = TBoxBridgeService.ACTION_START
            putExtra(TBoxBridgeService.EXTRA_LOCAL_PORT, cfg.localPort)
            putExtra(TBoxBridgeService.EXTRA_REMOTE_PORT, cfg.remotePort)
            putExtra(TBoxBridgeService.EXTRA_REMOTE_ADDRESS, cfg.remoteAddress.hostAddress)
            putExtra(TBoxBridgeService.EXTRA_TCP_PORT, cfg.tcpPort)
        }

        context.startForegroundServiceCompat(intent)
        isServerMode = true

        scope?.launch {
            delay(500)

            tcpClient = TcpClient(
                host = cfg.host,
                port = cfg.tcpPort,
                callback = object : TBoxClientCallback {
                    override fun onDataReceived(data: ByteArray) {
                        callback.onDataReceived(data)
                    }

                    override fun onLogMessage(type: LogType, tag: String, message: String) {
                        callback.onLogMessage(type, "TcpClient.$tag", message)
                    }

                    override fun onConnectionChanged(connected: Boolean) {
                        callback.onConnectionChanged(connected)
                    }
                }
            )

            val connected = tcpClient?.connect() == true
            if (connected) {
                log(LogType.INFO, "TBoxClient", "Connected to local server (loopback)")
                callback.onConnectionChanged(true)
                // Запускаем обработчик очереди отправки
                startSendProcessor()
            } else {
                log(LogType.ERROR, "TBoxClient", "Failed to connect to local server")
            }
        }
    }

    /**
     * Запускает обработчик очереди отправки.
     * Обрабатывает команды последовательно в порядке добавления.
     */
    private fun startSendProcessor() {
        sendJob?.cancel()
        sendJob = scope?.launch(Dispatchers.IO) {
            for (data in sendQueue) {
                val client = tcpClient
                if (client != null && client.isConnected) {
                    val sent = client.send(data)
                    withContext(Dispatchers.Main) {
                        if (sent) {
                            log(LogType.DEBUG, "TBoxClient", "→ Sent via TCP: ${data.toLogString()}")
                        } else {
                            log(LogType.ERROR, "TBoxClient", "Failed to send data")
                        }
                    }
                } else {
                    log(LogType.ERROR, "TBoxClient", "Not connected, cannot send")
                }
            }
        }
        log(LogType.INFO, "TBoxClient", "Send processor started")
    }

    /**
     * Отправляет raw данные в TBox через TCP мост.
     * 
     * Метод **асинхронный** — не блокирует вызывающий поток.
     * Можно безопасно вызывать из UI-потока.
     * 
     * Команды отправляются **последовательно** в порядке вызова.
     * 
     * @param data данные для отправки (команда с заголовком и checksum)
     * 
     * @sample
     * // Вызов из UI-потока — безопасно
     * button.setOnClickListener {
     *     tboxClient.sendRawMessage(command)
     * }
     * 
     * @sample
     * // Серийная отправка — команды уйдут по порядку
     * tboxClient.sendCommand(cmd1)
     * tboxClient.sendCommand(cmd2)
     * tboxClient.sendCommand(cmd3)
     */
    fun sendRawMessage(data: ByteArray) {
        val client = tcpClient
        if (client != null && client.isConnected) {
            // Добавляем в очередь для последовательной отправки
            if (!sendQueue.trySend(data).isSuccess) {
                log(LogType.ERROR, "TBoxClient", "Failed to queue data for sending")
            }
        } else {
            log(LogType.ERROR, "TBoxClient", "Not connected, cannot send")
        }
    }

    /**
     * Отправляет команду в TBox.
     * 
     * Метод асинхронный — не блокирует вызывающий поток.
     * 
     * @param tid идентификатор транзакции
     * @param sid идентификатор системы
     * @param cmd код команды
     * @param data данные команды
     * 
     * @sample
     * // Отправка команды получения CAN-фреймов
     * tboxClient.sendCommand(0x01, 0x10, 0x15, byteArrayOf(0x01, 0x02))
     */
    fun sendCommand(tid: Byte, sid: Byte, cmd: Byte, data: ByteArray) {
        val fullData = ByteConverter.fillHeader(data.size, tid, sid, cmd) + data
        val checksum = ByteConverter.xorSum(fullData)
        sendRawMessage(fullData + checksum)
    }

    /**
     * Отправляет команду в TBox.
     * 
     * Метод асинхронный — не блокирует вызывающий поток.
     * 
     * @param command объект команды
     * 
     * @sample
     * // Создание и отправка команды
     * val command = TBoxCommand(
     *     tid = TBoxConstants.CRT_CODE,
     *     sid = TBoxConstants.GATE_CODE,
     *     cmd = 0x15,
     *     data = byteArrayOf(0x01, 0x02)
     * )
     * tboxClient.sendCommand(command)
     */
    fun sendCommand(command: TBoxCommand) {
        log(LogType.INFO, "TBoxClient", "${command.textMessage} Sending command: tid: ${command.tid} sid: ${command.sid} cmd: ${command.cmd} data: ${command.data.toLogString()} ")
        sendCommand(command.tid, command.sid, command.cmd, command.data)
    }

    fun isConnected(): Boolean {
        return tcpClient?.isConnected == true
    }

    fun getMode(): String = if (isServerMode) "SERVER" else "CLIENT"

    fun destroy() {
        if (!isInitialized) return

        log(LogType.INFO, "TBoxClient", "Destroying...")

        // Отменяем обработчик очереди
        sendJob?.cancel()
        sendJob = null
        if (::sendQueue.isInitialized) {
            sendQueue.close()
        }

        scope?.cancel()
        tcpClient?.disconnect()
        tcpClient = null

        if (isServerMode) {
            try {
                context.stopService(Intent(context, TBoxBridgeService::class.java))
            } catch (e: Exception) {
                log(LogType.WARN, "TBoxClient", "Failed to stop service: ${e.message}")
            }
        }

        scope = null
        config = null
        isServerMode = false
        isInitialized = false

        log(LogType.INFO, "TBoxClient", "Destroyed complete")
    }

    private fun log(type: LogType, tag: String, message: String) {
        callback.onLogMessage(type, tag, message)
    }
}