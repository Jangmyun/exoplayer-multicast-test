package com.example.exoplayermulticasttest

import java.io.IOException
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.MulticastSocket

class MulticastController(private val host: String, private val port: Int) {
    private lateinit var socket: MulticastSocket

    fun start() {
        try {
            val multicastGroup = InetAddress.getByName(host)
            socket = MulticastSocket(port).apply {
                reuseAddress = true
                joinGroup(multicastGroup)
                soTimeout = 10000
            }
        } catch (e: Exception) {
            throw IOException("failed multicast socket initializing : ${e.message}")
        }
    }

    fun stop() {
        if (::socket.isInitialized && !socket.isClosed) {
            socket.leaveGroup(InetAddress.getByName(host))
            socket.close()
        }
    }

    fun fetchData(buffer: ByteArray): Int {
        val packet = DatagramPacket(buffer, buffer.size)
        socket.receive(packet)
        return packet.length
    }
}