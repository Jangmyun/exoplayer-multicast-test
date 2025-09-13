package com.example.exoplayermulticasttest

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.MulticastSocket
import androidx.core.net.toUri
import androidx.media3.datasource.TransferListener

@androidx.media3.common.util.UnstableApi
class UdpDataSource(
    private val uri: String,
    private val bufferSize: Int = 1316
) : DataSource {
    private var socket: DatagramSocket? = null
    private var packet: DatagramPacket? = null
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        if (opened) return 0

        val uri = java.net.URI(uri)
        val host = uri.host
        val port = uri.port

        socket = if (uri.host.startsWith("224.") || uri.host.startsWith("239.")) {
            MulticastSocket(null).apply {
                reuseAddress = true
                bind(InetSocketAddress(port))
                joinGroup(InetSocketAddress(host, port), null)
            }
        } else {
            DatagramSocket(port).apply {
                reuseAddress = true
            }
        }

        val buf = ByteArray(bufferSize)
        packet = DatagramPacket(buf, buf.size)

        opened = true
        return C.LENGTH_UNSET.toLong()
    }

    override fun read(buffer: ByteArray, offset: Int, readLength: Int): Int {
        if (!opened) throw IOException("DataSource not opened")

        val sock = socket ?: throw IOException("Socket is null")
        val pack = packet ?: throw IOException("Packet is null")

        sock.receive(pack)
        val length = pack.length.coerceAtMost(readLength)
        System.arraycopy(pack.data, 0, buffer, offset, length)
        return length
    }

    override fun getUri(): Uri? = uri.toUri()

    override fun close() {
        socket?.close()
        socket = null
        opened = false
    }

    override fun addTransferListener(transferListener: TransferListener) {
        // No operation
    }
}