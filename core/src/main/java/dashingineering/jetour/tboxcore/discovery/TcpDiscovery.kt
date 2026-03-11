package dashingineering.jetour.tboxcore.discovery

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

object TcpDiscovery {
    suspend fun isServerAvailable(
        host: String = "127.0.0.1",
        port: Int = 1104,
        timeoutMs: Int = 300
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), timeoutMs)
                socket.soTimeout = timeoutMs
                true
            }
        } catch (_: Exception) {
            false
        }
    }
}