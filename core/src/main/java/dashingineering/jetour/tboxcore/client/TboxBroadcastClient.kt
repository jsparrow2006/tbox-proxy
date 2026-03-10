package dashingineering.jetour.tboxcore.client

import android.content.Context
import android.content.Intent
import android.util.Log
import dashingineering.jetour.tboxcore.service.TboxBroadcastHostService
import dashingineering.jetour.tboxcore.util.buildTboxPacket
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Клиент для работы с TBox через Broadcast Coordinator
 * 
 * Автоматически находит существующий хост в системе или создаёт новый
 */
class TboxBroadcastClient(
    private val context: Context,
    private val defaultIp: String = "192.168.225.1",
    private val defaultPort: Int = 50047,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
) {
    private var coordinator: TboxBroadcastCoordinator? = null
    private val mutex = Mutex()
    private var isInitialized = false
    
    /**
     * Слушатель событий
     */
    var onEvent: (Event) -> Unit = {}
    
    sealed class Event {
        object HostConnected : Event()
        object HostDisconnected : Event()
        data class DataReceived(val data: ByteArray) : Event()
        data class LogMessage(val level: String, val tag: String, val message: String) : Event()
        data class HostFound(val packageName: String) : Event()
        object NoHostFound : Event()
    }

    /**
     * Подключение к TBox
     * 
     * 1. Отправляет PING для поиска существующего хоста
     * 2. Если хост найден — подключается к нему
     * 3. Если хост не найден — запускает свой сервис
     */
    fun connect() {
        scope.launch {
            mutex.withLock {
                if (isInitialized) {
                    Log.d("TBoxBroadcastClient", "Уже инициализирован")
                    return@withLock
                }
                isInitialized = true
            }
            
            Log.d("TBoxBroadcastClient", "Подключение...")
            
            // Создаём координатор
            coordinator = TboxBroadcastCoordinator(
                context = context,
                scope = scope,
                commandCallback = null // Клиент не отправляет команды напрямую
            )
            
            // Регистрируем слушателя
            coordinator?.addListener(object : TboxBroadcastCoordinator.CoordinatorListener {
                override fun onDataReceived(data: ByteArray) {
                    onEvent(Event.DataReceived(data))
                }

                override fun onLog(level: String, tag: String, message: String) {
                    onEvent(Event.LogMessage(level, tag, message))
                }

                override fun onHostConnected() {
                    onEvent(Event.HostConnected)
                }

                override fun onHostDisconnected() {
                    onEvent(Event.HostDisconnected)
                }

                override fun onHostFound(packageName: String) {
                    Log.d("TBoxBroadcastClient", "Хост найден: $packageName")
                    onEvent(Event.HostFound(packageName))
                }

                override fun onNoHostFound() {
                    Log.d("TBoxBroadcastClient", "Хост не найден, запускаем свой сервис")
                    onEvent(Event.NoHostFound)
                    startOwnHost()
                }
            })
            
            // Инициализируем координатор (отправит PING)
            coordinator?.init(becomeHostOnInit = false)
        }
    }

    /**
     * Отправка команды в TBox
     */
    fun sendCommand(data: ByteArray) {
        coordinator?.sendCommand(data)
    }

    /**
     * Отправка команды с построением пакета TBox
     * @param tid Transaction ID
     * @param sid Service ID
     * @param cmd Command byte
     * @param data Payload
     */
    fun sendCommand(tid: Byte, sid: Byte, cmd: Byte, data: ByteArray = byteArrayOf()) {
        val packet = buildTboxPacket(tid, sid, cmd, data)
        sendCommand(packet)
    }

    /**
     * Отключение
     */
    fun disconnect() {
        scope.launch {
            mutex.withLock {
                coordinator?.destroy()
                coordinator = null
                isInitialized = false
            }
        }
    }

    /**
     * Проверка, является ли это приложение хостом
     */
    fun isHost(): Boolean = coordinator?.isHost() ?: false

    private fun startOwnHost() {
        val intent = Intent(context, TboxBroadcastHostService::class.java).apply {
            action = TboxBroadcastHostService.ACTION_START
            putExtra(TboxBroadcastHostService.EXTRA_IP, defaultIp)
            putExtra(TboxBroadcastHostService.EXTRA_PORT, defaultPort)
        }
        context.startForegroundService(intent)
        
        // После запуска сервиса координатор получит PONG от себя самого
        // и станет хостом
        scope.launch {
            delay(500)
            coordinator?.becomeHost()
        }
    }
}
