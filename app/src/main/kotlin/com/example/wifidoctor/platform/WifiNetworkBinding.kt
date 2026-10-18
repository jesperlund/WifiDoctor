package com.example.wifidoctor.platform

import android.content.Context
import android.net.Uri
import com.example.wifidoctor.core.probe.NetworkBinding
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.URL
import java.net.URLConnection
import javax.inject.Inject
import javax.inject.Singleton
import javax.net.SocketFactory

/**
 * Sends probe traffic over the Wi-Fi network even if Android routes the default traffic over
 * mobile data (e.g. because Wi-Fi failed its internet check). Falls back to the default network
 * when Wi-Fi is not available.
 */
@Singleton
class WifiNetworkBinding @Inject constructor(private val tracker: WifiNetworkTracker) : NetworkBinding {
    private val network get() = tracker.state.value.network

    override fun socketFactory(): SocketFactory = network?.socketFactory ?: NetworkBinding.Default.socketFactory()

    override fun bind(socket: DatagramSocket) {
        network?.bindSocket(socket)
    }

    override fun openConnection(url: URL): URLConnection = network?.openConnection(url) ?: url.openConnection()

    override fun lookup(host: String): Array<InetAddress> = network?.getAllByName(host) ?: InetAddress.getAllByName(host)
}

/** Writes exported text to a user-chosen document (Storage Access Framework URI). */
interface DocumentWriter {
    suspend fun write(uri: String, content: String)
}

@Singleton
class ContentResolverWriter @Inject constructor(@ApplicationContext private val context: Context) : DocumentWriter {
    override suspend fun write(uri: String, content: String) = withContext(Dispatchers.IO) {
        val stream = context.contentResolver.openOutputStream(Uri.parse(uri), "wt")
            ?: error("Could not open the selected file")
        stream.bufferedWriter().use { it.write(content) }
    }
}
