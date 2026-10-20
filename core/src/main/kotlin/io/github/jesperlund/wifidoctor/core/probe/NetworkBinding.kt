package io.github.jesperlund.wifidoctor.core.probe

import java.net.DatagramSocket
import java.net.InetAddress
import java.net.URL
import java.net.URLConnection
import javax.net.SocketFactory

/**
 * Lets the platform force probe traffic onto a specific network (the Wi-Fi network on Android),
 * so a phone that also has mobile data never measures the wrong link.
 */
interface NetworkBinding {
    fun socketFactory(): SocketFactory
    fun bind(socket: DatagramSocket)
    fun openConnection(url: URL): URLConnection
    fun lookup(host: String): Array<InetAddress>

    /** Uses whatever the default network is. */
    object Default : NetworkBinding {
        override fun socketFactory(): SocketFactory = SocketFactory.getDefault()
        override fun bind(socket: DatagramSocket) = Unit
        override fun openConnection(url: URL): URLConnection = url.openConnection()
        override fun lookup(host: String): Array<InetAddress> = InetAddress.getAllByName(host)
    }
}
