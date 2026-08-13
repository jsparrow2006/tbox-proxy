package dashingineering.jetour.tboxcore.discovery

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

object TcpDiscovery {
    suspend fun isServerAvailable(
        host: String = "127.0.0.1",
        port: Int = 1104,
        timeoutMs: Int = 300,
        retries: Int = 3,
        retryDelayMs: Long = 500
    ): Boolean = withContext(Dispatchers.IO) {
        repeat(retries) { attempt ->
            try {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(host, port), timeoutMs)
                    socket.soTimeout = timeoutMs
                    return@withContext true
                }
            } catch (_: Exception) {
                if (attempt < retries - 1) {
                    delay(retryDelayMs)
                }
            }
        }
        false
    }
}
