package com.example.exoplayermulticasttest

import android.net.Uri
import android.util.Log
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.core.net.toUri
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.SocketTimeoutException

@androidx.media3.common.util.UnstableApi
class UdpDataSource(
    private val uri: String,
    private val bufferSize: Int = 200_000
) : DataSource {

    companion object {
        private const val TAG = "UdpDataSource"
    }

    private var socket: DatagramSocket? = null
    private var packet: DatagramPacket? = null
    private var opened = false
    private var packetCount = 0

    override fun open(dataSpec: DataSpec): Long {
        Log.d(TAG, "=== Opening UDP DataSource ===")
        Log.d(TAG, "URI: $uri")
        Log.d(TAG, "Buffer size: $bufferSize")

        if (opened) {
            Log.w(TAG, "DataSource already opened")
            return C.LENGTH_UNSET.toLong()
        }

        val uri = java.net.URI(uri)
        val host = uri.host
        val port = uri.port

        Log.d(TAG, "Parsed - Host: $host, Port: $port")

        try {
            socket = if (host.startsWith("224.") || host.startsWith("239.")) {
                Log.d(TAG, "Creating MulticastSocket for multicast address")
                MulticastSocket(null).apply {
                    reuseAddress = true
                    soTimeout = 5000 // 5초 타임아웃
                    bind(InetSocketAddress(port))

                    val group = InetAddress.getByName(host)
                    // ✅ 수정된 부분: joinGroup(InetSocketAddress, NetworkInterface) 대신 joinGroup(InetAddress) 사용
                    joinGroup(group)

                    Log.i(TAG, "✅ Successfully joined multicast group $host:$port")
                }
            } else {
                Log.d(TAG, "Creating DatagramSocket for unicast address")
                DatagramSocket(port).apply {
                    reuseAddress = true
                    soTimeout = 5000
                }
            }

            val buf = ByteArray(bufferSize)
            packet = DatagramPacket(buf, buf.size)
            opened = true

            Log.i(TAG, "✅ UDP DataSource opened successfully")
            return C.LENGTH_UNSET.toLong()

        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to open UDP DataSource", e)
            throw IOException("Failed to open UDP connection: ${e.message}", e)
        }
    }

    override fun read(buffer: ByteArray, offset: Int, readLength: Int): Int {
        if (!opened) {
            Log.e(TAG, "❌ Read called on unopened DataSource")
            throw IOException("DataSource not opened")
        }

        val sock = socket ?: throw IOException("Socket is null")
        val pack = packet ?: throw IOException("Packet is null")

        return try {
            Log.v(TAG, "Waiting for UDP packet... (timeout: ${sock.soTimeout}ms)")

            sock.receive(pack)
            packetCount++

            val bytesReceived = pack.length.coerceAtMost(readLength)
            System.arraycopy(pack.data, 0, buffer, offset, bytesReceived)

            if (packetCount % 100 == 1) { // 매 100번째 패킷마다 로그
                Log.d(TAG, "📦 Packet #$packetCount: ${bytesReceived} bytes from ${pack.address}")
            }

            bytesReceived

        } catch (e: SocketTimeoutException) {
            Log.w(TAG, "⏰ UDP receive timeout (no data for ${sock.soTimeout}ms)")
            throw IOException("UDP receive timeout - check if stream is active", e)
        } catch (e: IOException) {
            Log.e(TAG, "❌ UDP receive error: ${e.message}")
            throw IOException("UDP receive failed: ${e.message}", e)
        }
    }

    override fun getUri(): Uri? = uri.toUri()

    override fun close() {
        if (!opened) return

        Log.d(TAG, "=== Closing UDP DataSource ===")
        Log.d(TAG, "Total packets received: $packetCount")

        opened = false
        socket?.let { sock ->
            try {
                val uri = java.net.URI(uri)
                val host = uri.host

                if (host.startsWith("224.") || host.startsWith("239.")) {
                    val multicastGroup = InetAddress.getByName(host)
                    (sock as? MulticastSocket)?.leaveGroup(multicastGroup)
                    Log.d(TAG, "Left multicast group: $host")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error during cleanup", e)
            } finally {
                sock.close()
            }
        }

        socket = null
        packet = null
        packetCount = 0

        Log.d(TAG, "✅ UDP DataSource closed")
    }

    override fun addTransferListener(transferListener: TransferListener) {
        // 기본 구현
    }
}
