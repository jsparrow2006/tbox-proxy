package dashingineering.jetour.tboxcore.client

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Координатор на основе Broadcast для межприложенного взаимодействия TBox
 *
 * Протокол:
 * 1. PING — поиск активного хоста
 * 2. PONG — ответ хоста (содержит packageName)
 * 3. COMMAND — команда на отправку данных
 * 4. DATA — данные от TBox
 * 5. LOG — лог сообщение
 * 6. HOST_CONNECTED — хост подключён
 * 7. HOST_DISCONNECTED — хост отключён
 */
class TboxBroadcastCoordinator(
    private val context: Context,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Main + SupervisorJob()),
    private val commandCallback: ((ByteArray) -> Boolean)? = null
) {
    companion object {
        private const val ACTION_PING = "dashingineering.jetour.tboxcore.PING"
        private const val ACTION_PONG = "dashingineering.jetour.tboxcore.PONG"
        private const val ACTION_COMMAND = "dashingineering.jetour.tboxcore.COMMAND"
        private const val ACTION_DATA = "dashingineering.jetour.tboxcore.DATA"
        private const val ACTION_LOG = "dashingineering.jetour.tboxcore.LOG"
        private const val ACTION_HOST_CONNECTED = "dashingineering.jetour.tboxcore.HOST_CONNECTED"
        private const val ACTION_HOST_DISCONNECTED = "dashingineering.jetour.tboxcore.HOST_DISCONNECTED"
        
        private const val EXTRA_PACKAGE = "package"
        private const val EXTRA_DATA = "data"
        private const val EXTRA_LEVEL = "level"
        private const val EXTRA_TAG = "tag"
        private const val EXTRA_MESSAGE = "message"
        
        private const val PING_TIMEOUT_MS = 500L
    }

    private var isHost = false
    private val mutex = Mutex()
    private val listeners = CopyOnWriteArrayList<CoordinatorListener>()
    private var receiver: BroadcastReceiver? = null
    private var isRegistered = false
    
    /**
     * Проверка, является ли текущее приложение хостом
     */
    fun isHost(): Boolean = isHost

    /**
     * Интерфейс слушателя событий координатора
     */
    interface CoordinatorListener {
        fun onDataReceived(data: ByteArray)
        fun onLog(level: String, tag: String, message: String)
        fun onHostConnected()
        fun onHostDisconnected()
        fun onHostFound(packageName: String)
        fun onNoHostFound()
    }

    /**
     * Инициализация координатора
     * @param becomeHostOnInit если true — становимся хостом сразу
     */
    fun init(becomeHostOnInit: Boolean = false) {
        registerReceiver()
        if (becomeHostOnInit) {
            becomeHost()
        } else {
            // Отправляем PING для поиска существующего хоста
            sendPing()
        }
    }

    /**
     * Отправка команды хосту
     */
    fun sendCommand(data: ByteArray) {
        val intent = Intent(ACTION_COMMAND).apply {
            putExtra(EXTRA_PACKAGE, context.packageName)
            putExtra(EXTRA_DATA, data)
        }
        context.sendBroadcast(intent)
    }

    /**
     * Стать хостом
     */
    fun becomeHost() {
        scope.launch {
            mutex.withLock {
                if (isHost) return@withLock
                isHost = true
            }
            Log.d("TBoxCoordinator", "Ставим хостом: ${context.packageName}")
            // Уведомляем слушателей
            notifyListeners { listener -> listener.onHostConnected() }
            // Отправляем лог
            sendLog("INFO", "COORDINATOR", "Стали хостом: ${context.packageName}")
        }
    }

    /**
     * Остановить хост
     */
    fun stopHost() {
        scope.launch {
            mutex.withLock {
                if (!isHost) return@withLock
                isHost = false
            }
            Log.d("TBoxCoordinator", "Останавливаем хост: ${context.packageName}")
            notifyListeners { listener -> listener.onHostDisconnected() }
            sendLog("INFO", "COORDINATOR", "Хост остановлен: ${context.packageName}")
        }
    }

    /**
     * Отправка данных от TBox всем слушателям
     */
    fun broadcastDataReceived(data: ByteArray) {
        val intent = Intent(ACTION_DATA).apply {
            putExtra(EXTRA_PACKAGE, context.packageName)
            putExtra(EXTRA_DATA, data)
        }
        context.sendBroadcast(intent)
        // Также уведомляем локальных слушателей
        scope.launch {
            notifyListeners { listener -> listener.onDataReceived(data) }
        }
    }

    /**
     * Отправка лога всем слушателям
     */
    fun broadcastLog(level: String, tag: String, message: String) {
        sendLog(level, tag, message)
    }

    private fun sendLog(level: String, tag: String, message: String) {
        val intent = Intent(ACTION_LOG).apply {
            putExtra(EXTRA_PACKAGE, context.packageName)
            putExtra(EXTRA_LEVEL, level)
            putExtra(EXTRA_TAG, tag)
            putExtra(EXTRA_MESSAGE, message)
        }
        context.sendBroadcast(intent)
        // Также уведомляем локальных слушателей
        scope.launch {
            notifyListeners { listener -> listener.onLog(level, tag, message) }
        }
    }

    /**
     * Поиск хоста через PING
     */
    private fun sendPing() {
        Log.d("TBoxCoordinator", "Отправляем PING для поиска хоста")
        val intent = Intent(ACTION_PING).apply {
            putExtra(EXTRA_PACKAGE, context.packageName)
        }
        context.sendBroadcast(intent)
        
        // Ждём ответ PONG в течение timeout
        scope.launch {
            delay(PING_TIMEOUT_MS)
            mutex.withLock {
                if (!isHost) {
                    Log.d("TBoxCoordinator", "Хост не найден, становимся хостом")
                    notifyListeners { listener -> listener.onNoHostFound() }
                }
            }
        }
    }

    /**
     * Ответ на PING
     */
    private fun sendPong(requestingPackage: String) {
        Log.d("TBoxCoordinator", "Отправляем PONG в ответ на PING от $requestingPackage")
        val intent = Intent(ACTION_PONG).apply {
            putExtra(EXTRA_PACKAGE, context.packageName)
        }
        context.sendBroadcast(intent)
    }

    /**
     * Добавление слушателя
     */
    fun addListener(listener: CoordinatorListener) {
        listeners.add(listener)
    }

    /**
     * Удаление слушателя
     */
    fun removeListener(listener: CoordinatorListener) {
        listeners.remove(listener)
    }

    /**
     * Очистка ресурсов
     */
    fun destroy() {
        if (isRegistered) {
            try {
                context.unregisterReceiver(receiver)
            } catch (e: Exception) {
                // Already unregistered
            }
            isRegistered = false
        }
        listeners.clear()
    }

    private fun registerReceiver() {
        val filter = IntentFilter().apply {
            addAction(ACTION_PING)
            addAction(ACTION_PONG)
            addAction(ACTION_COMMAND)
            addAction(ACTION_DATA)
            addAction(ACTION_LOG)
            addAction(ACTION_HOST_CONNECTED)
            addAction(ACTION_HOST_DISCONNECTED)
        }
        
        receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                intent ?: return
                handleBroadcast(intent)
            }
        }
        
        context.registerReceiver(receiver, filter)
        isRegistered = true
        Log.d("TBoxCoordinator", "Receiver зарегистрирован для ${context.packageName}")
    }

    private fun handleBroadcast(intent: Intent) {
        val action = intent.action ?: return
        val sourcePackage = intent.getStringExtra(EXTRA_PACKAGE) ?: return
        
        // Игнорируем PING/PONG от себя, но принимаем DATA/LOG от всех (включая себя)
        val isSelf = sourcePackage == context.packageName
        if (isSelf && (action == ACTION_PING || action == ACTION_PONG)) return
        
        when (action) {
            ACTION_PING -> {
                // Кто-то ищет хост — если мы хост, отвечаем PONG
                scope.launch {
                    mutex.withLock {
                        if (isHost) {
                            sendPong(sourcePackage)
                        }
                    }
                }
            }
            ACTION_PONG -> {
                // Нашли хост!
                Log.d("TBoxCoordinator", "Получили PONG от хоста: $sourcePackage")
                scope.launch {
                    mutex.withLock {
                        if (!isHost) {
                            notifyListeners { listener -> listener.onHostFound(sourcePackage) }
                        }
                    }
                }
            }
            ACTION_COMMAND -> {
                // Команда от клиента — только хост обрабатывает
                scope.launch {
                    mutex.withLock {
                        if (isHost) {
                            val data = intent.getByteArrayExtra(EXTRA_DATA)
                            if (data != null) {
                                Log.d("TBoxCoordinator", "Получена команда от $sourcePackage, размер ${data.size} байт")
                                // Вызываем callback для отправки в UDP
                                commandCallback?.invoke(data)
                            }
                        }
                    }
                }
            }
            ACTION_DATA -> {
                // Данные от хоста
                val data = intent.getByteArrayExtra(EXTRA_DATA)
                if (data != null) {
                    scope.launch {
                        notifyListeners { listener -> listener.onDataReceived(data) }
                    }
                }
            }
            ACTION_LOG -> {
                val level = intent.getStringExtra(EXTRA_LEVEL) ?: "INFO"
                val tag = intent.getStringExtra(EXTRA_TAG) ?: "TBOX"
                val message = intent.getStringExtra(EXTRA_MESSAGE) ?: ""
                scope.launch {
                    notifyListeners { listener -> listener.onLog(level, tag, message) }
                }
            }
            ACTION_HOST_CONNECTED -> {
                scope.launch {
                    notifyListeners { listener -> listener.onHostConnected() }
                }
            }
            ACTION_HOST_DISCONNECTED -> {
                scope.launch {
                    notifyListeners { listener -> listener.onHostDisconnected() }
                }
            }
        }
    }

    private suspend fun notifyListeners(block: suspend (CoordinatorListener) -> Unit) {
        listeners.toList().forEach { listener ->
            try {
                block(listener)
            } catch (e: Exception) {
                Log.e("TBoxCoordinator", "Error notifying listener", e)
            }
        }
    }
}

// Extension для получения ByteArray из Bundle
private fun Intent.getByteArrayExtra(key: String): ByteArray? {
    return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
        getByteArrayExtra(key)
    } else {
        @Suppress("DEPRECATION")
        getByteArrayExtra(key)
    }
}
