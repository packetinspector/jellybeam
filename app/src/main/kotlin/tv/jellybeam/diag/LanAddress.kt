package tv.jellybeam.diag

import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.Collections

/** docs/21 §4: the TV's LAN-routed IPv4, shown in the report URL. The UDP "connect" sends no
 * packet -- it only asks the OS which local address would route to an unreachable address.
 */
object LanAddress {
    fun find(): String? = routedAddress() ?: interfaceScanAddress()

    private fun routedAddress(): String? = try {
        DatagramSocket().use { socket ->
            socket.connect(InetAddress.getByName("10.255.255.255"), 1)
            val address = socket.localAddress
            if (address is Inet4Address && !address.isLoopbackAddress && !address.isAnyLocalAddress) {
                address.hostAddress
            } else {
                null
            }
        }
    } catch (_: Exception) {
        null
    }

    private fun interfaceScanAddress(): String? = try {
        Collections.list(NetworkInterface.getNetworkInterfaces())
            .asSequence()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { Collections.list(it.inetAddresses).asSequence() }
            .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }
            ?.hostAddress
    } catch (_: Exception) {
        null
    }
}
