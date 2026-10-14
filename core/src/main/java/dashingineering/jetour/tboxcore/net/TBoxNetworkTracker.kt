package dashingineering.jetour.tboxcore.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import dashingineering.jetour.tboxcore.tcp.BridgeLogger
import dashingineering.jetour.tboxcore.types.LogType
import java.net.Inet4Address
import java.net.InetAddress

/**
 * Находит сеть Android, через которую доступен TBox: у неё есть IPv4-адрес в одной подсети
 * с [remoteAddress] (на ГУ Jetour это `usb0`, тип `Rndis`, `192.168.225.0/24`).
 *
 * Зачем: сокет без привязки идёт по сети по умолчанию. На ГУ, где при включённом Wi-Fi
 * правила маршрутизации отдают всё в `wlan0` (Android 10 / Adayo), пакеты на `192.168.225.1`
 * уходят на роутер. `Network.bindSocket` метит сокет сетью TBox и не требует root.
 * netId сети меняется при пересоздании интерфейса, поэтому сеть отслеживается колбэком.
 */
internal class TBoxNetworkTracker(
    context: Context,
    private val remoteAddress: InetAddress,
    private val log: BridgeLogger,
    private val onChanged: (network: Network?, interfaceName: String?) -> Unit
) {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val candidates = LinkedHashMap<Network, String?>()
    private var current: Network? = null
    private var started = false

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
            update(network, linkProperties)
        }

        override fun onLost(network: Network) {
            synchronized(candidates) { candidates.remove(network) }
            reselect()
        }
    }

    fun start() {
        if (started || connectivity == null) return
        started = true
        val request = NetworkRequest.Builder()
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        try {
            connectivity.registerNetworkCallback(request, callback)
        } catch (e: Exception) {
            log(LogType.ERROR, TAG, "registerNetworkCallback failed: ${e.javaClass.simpleName}: ${e.message}")
        }
        @Suppress("DEPRECATION")
        connectivity.allNetworks.forEach { network ->
            connectivity.getLinkProperties(network)?.let { update(network, it) }
        }
        if (synchronized(candidates) { current == null }) {
            log(LogType.INFO, TAG, "No network with ${remoteAddress.hostAddress} subnet yet")
        }
    }

    fun stop() {
        if (!started) return
        started = false
        runCatching { connectivity?.unregisterNetworkCallback(callback) }
        synchronized(candidates) {
            candidates.clear()
            current = null
        }
    }

    private fun update(network: Network, linkProperties: LinkProperties) {
        val matches = linkProperties.linkAddresses.any { link ->
            sameSubnet(link.address, remoteAddress, link.prefixLength)
        }
        synchronized(candidates) {
            if (matches) candidates[network] = linkProperties.interfaceName else candidates.remove(network)
        }
        reselect()
    }

    private fun reselect() {
        val (network, iface) = synchronized(candidates) {
            val selected = current?.takeIf { it in candidates } ?: candidates.keys.lastOrNull()
            if (selected == current) return
            current = selected
            selected to selected?.let { candidates[it] }
        }
        log(LogType.INFO, TAG, "TBox network: ${iface ?: "none"} ($network)")
        onChanged(network, iface)
    }

    private companion object {
        const val TAG = "TBoxNetwork"

        fun sameSubnet(address: InetAddress, target: InetAddress, prefixLength: Int): Boolean {
            if (address !is Inet4Address || target !is Inet4Address) return false
            if (prefixLength !in 1..32) return false
            val a = address.address.fold(0) { acc, b -> (acc shl 8) or (b.toInt() and 0xFF) }
            val t = target.address.fold(0) { acc, b -> (acc shl 8) or (b.toInt() and 0xFF) }
            val mask = if (prefixLength == 32) -1 else (-1 shl (32 - prefixLength))
            return a and mask == t and mask
        }
    }
}
