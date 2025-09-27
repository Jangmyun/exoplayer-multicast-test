package com.example.exoplayermulticasttest

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.*
import java.io.OutputStreamWriter
import java.net.*
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : ComponentActivity() {

    private lateinit var player: ExoPlayer
    private lateinit var multicastLock: WifiManager.MulticastLock

    private val analysisStats = mutableStateOf(PacketAnalysis())
    private val isMonitoring = mutableStateOf(false)

    private var analysisJob: Job? = null
    private var csvWriter: OutputStreamWriter? = null

    companion object {
        private const val TAG = "MainActivity"
        private const val MULTICAST_URI = "udp://224.1.1.1:1234"
        private const val UNICAST_URI = "udp://192.168.0.159:1234"
    }

    private var streamUri by mutableStateOf(MULTICAST_URI)

    data class PacketAnalysis(
        val totalPackets: Long = 0,
        val lostPackets: Long = 0,
        val lossRate: Double = 0.0,
        val throughputMbps: Double = 0.0
    )

    @androidx.media3.common.util.UnstableApi
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setupMulticastLock()
        setupExoPlayer()

        enableEdgeToEdge()
        setContent {
            Column(modifier = Modifier.fillMaxSize()) {
                AndroidView(
                    factory = { context ->
                        PlayerView(context).apply {
                            this.player = this@MainActivity.player
                            useController = true
                        }
                    },
                    modifier = Modifier.weight(1f)
                )

                // 토글 버튼 UI
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    Button(onClick = { switchStream(MULTICAST_URI) }) {
                        Text("Multicast")
                    }
                    Button(onClick = { switchStream(UNICAST_URI) }) {
                        Text("Unicast")
                    }
                }

                Text(
                    text = "Current Stream: $streamUri",
                    modifier = Modifier.padding(16.dp),
                    fontWeight = FontWeight.Bold
                )

                // 모니터링 정보 + 제어 버튼
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        "Monitoring Status: ${if (isMonitoring.value) "Running" else "Stopped"}",
                        color = if (isMonitoring.value) Color.Green else Color.Red,
                        fontWeight = FontWeight.Bold
                    )
                    Text(text = "Total TS Packets: ${analysisStats.value.totalPackets}")
                    Text(text = "Lost TS Packets: ${analysisStats.value.lostPackets}")
                    Text(text = "Loss Rate: ${"%.4f".format(analysisStats.value.lossRate)} %")
                    Text(text = "Throughput: ${"%.2f".format(analysisStats.value.throughputMbps)} Mbps")

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        Button(
                            onClick = { startAnalysis() },
                            enabled = !isMonitoring.value
                        ) { Text("Start Monitoring") }
                        Button(
                            onClick = { stopAnalysis() },
                            enabled = isMonitoring.value
                        ) { Text("Stop Monitoring") }
                    }
                }
            }
        }
    }

    private fun startAnalysis() {
        if (isMonitoring.value) return
        isMonitoring.value = true
        Toast.makeText(this, "Starting analysis...", Toast.LENGTH_SHORT).show()

        analysisStats.value = PacketAnalysis()
        startCsvLogging()

        analysisJob = CoroutineScope(Dispatchers.IO).launch {
            runPacketAnalysis(this)
        }
    }

    private fun stopAnalysis() {
        if (!isMonitoring.value) return
        isMonitoring.value = false
        Toast.makeText(this, "Stopping analysis...", Toast.LENGTH_SHORT).show()

        analysisJob?.cancel()
        analysisJob = null

        stopCsvLogging()
    }

    private fun isMulticastAddress(address: InetAddress): Boolean {
        return address.isMulticastAddress
    }

    private suspend fun runPacketAnalysis(scope: CoroutineScope) {
        val uri = Uri.parse(streamUri)
        val host = uri.host ?: "224.1.1.1"
        val port = if (uri.port > 0) uri.port else 1234
        val address = InetAddress.getByName(host)

        val socket: DatagramSocket = if (isMulticastAddress(address)) {
            MulticastSocket(port).apply {
                reuseAddress = true
                joinGroup(address)
            }
        } else {
            DatagramSocket(port).apply { reuseAddress = true }
        }

        val pidCcMap = mutableMapOf<Int, Int>()
        var totalPkt = 0L
        var lostPkt = 0L
        var remain = ByteArray(0)
        var bytesThisSecond = 0L
        var lastUpdateTime = System.currentTimeMillis()

        val buf = ByteArray(4096)
        val udpPacket = DatagramPacket(buf, buf.size)

        try {
            while (scope.isActive) {
                socket.receive(udpPacket)
                val receivedBytes = udpPacket.length
                bytesThisSecond += receivedBytes

                val data = remain + udpPacket.data.copyOf(receivedBytes)
                var offset = 0

                while (offset + 188 <= data.size) {
                    if (data[offset] != 0x47.toByte()) {
                        var nextSync = -1
                        for (i in offset + 1 until data.size) {
                            if (data[i] == 0x47.toByte()) {
                                nextSync = i; break
                            }
                        }
                        if (nextSync == -1) break
                        offset = nextSync
                        continue
                    }

                    val tsPacket = data.copyOfRange(offset, offset + 188)
                    val pid = ((tsPacket[1].toInt() and 0x1F) shl 8) or (tsPacket[2].toInt() and 0xFF)
                    if (pid == 0x1FFF) { offset += 188; continue }
                    totalPkt++
                    val cc = tsPacket[3].toInt() and 0x0F

                    if (pidCcMap.containsKey(pid)) {
                        val lastCc = pidCcMap[pid]!!
                        val expectedCc = (lastCc + 1) % 16
                        if (cc != expectedCc) {
                            lostPkt += (cc - expectedCc + 16) % 16
                        }
                    }
                    pidCcMap[pid] = cc
                    offset += 188
                }
                remain = if (offset < data.size) data.copyOfRange(offset, data.size) else ByteArray(0)

                val now = System.currentTimeMillis()
                if (now - lastUpdateTime >= 100) {
                    val intervalSeconds = (now - lastUpdateTime) / 1000.0
                    val throughputMbps = (bytesThisSecond * 8) / (intervalSeconds * 1_000_000)
                    val lossRate = if (totalPkt > 0) 100.0 * lostPkt / totalPkt else 0.0

                    withContext(Dispatchers.Main) {
                        analysisStats.value = PacketAnalysis(totalPkt, lostPkt, lossRate, throughputMbps)
                    }
                    writeCsvLog(System.currentTimeMillis(), totalPkt, lostPkt, lossRate, throughputMbps)

                    bytesThisSecond = 0L
                    lastUpdateTime = now
                }
            }
        } catch (e: Exception) {
            if (scope.isActive) Log.e(TAG, "Error during packet analysis", e)
        } finally {
            if (socket is MulticastSocket) {
                socket.leaveGroup(address)
            }
            socket.close()
            withContext(Dispatchers.Main) {
                if (isMonitoring.value) {
                    isMonitoring.value = false
                    Toast.makeText(this@MainActivity, "Analysis stopped unexpectedly.", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun startCsvLogging() {
        val contentResolver = applicationContext.contentResolver
        val contentValues = ContentValues().apply {
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            put(MediaStore.MediaColumns.DISPLAY_NAME, "udp_stats_$timestamp.csv")
            put(MediaStore.MediaColumns.MIME_TYPE, "text/csv")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        }
        try {
            val uri = contentResolver.insert(MediaStore.Files.getContentUri("external"), contentValues)
            if (uri == null) {
                Toast.makeText(this, "Error: Could not create file.", Toast.LENGTH_SHORT).show()
                return
            }
            csvWriter = OutputStreamWriter(contentResolver.openOutputStream(uri))
            csvWriter?.append("Timestamp,TotalPackets,LostPackets,LossRate(%),Throughput(Mbps)\n")
            csvWriter?.flush()
            val fileName = contentValues.getAsString(MediaStore.MediaColumns.DISPLAY_NAME)
            Toast.makeText(this, "CSV logging started: $fileName", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start CSV logging with MediaStore", e)
            csvWriter = null
        }
    }

    private fun writeCsvLog(time: Long, total: Long, lost: Long, lossRate: Double, throughput: Double) {
        csvWriter?.let {
            try {
                val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(Date(time))
                val line = "$timestamp,$total,$lost,${"%.4f".format(lossRate)},${"%.2f".format(throughput)}\n"
                it.append(line)
                it.flush()
            } catch (e: Exception) { Log.e(TAG, "Failed to write to CSV file", e) }
        }
    }

    private fun stopCsvLogging() {
        try {
            csvWriter?.flush()
            csvWriter?.close()
            csvWriter = null
            Toast.makeText(this, "CSV logging stopped.", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) { Log.e(TAG, "Failed to close CSV writer", e) }
    }

    private fun setupMulticastLock() {
        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        multicastLock = wifiManager.createMulticastLock("udpMulticastLock").apply {
            setReferenceCounted(true)
            acquire()
        }
    }

    @androidx.media3.common.util.UnstableApi
    private fun setupExoPlayer() {
        try {
            val mediaItem = MediaItem.fromUri(streamUri)
            player = ExoPlayer.Builder(this).build().apply {
                addListener(createPlayerListener())
                setMediaItem(mediaItem)
                prepare()
                playWhenReady = true
            }
        } catch (e: Exception) {
            Log.e(TAG, "ExoPlayer setup failed", e)
        }
    }

    @androidx.media3.common.util.UnstableApi
    private fun switchStream(newUri: String) {
        streamUri = newUri
        player.release()
        setupExoPlayer()
        Toast.makeText(this, "Switched to $newUri", Toast.LENGTH_SHORT).show()
    }

    private fun createPlayerListener() = object : Player.Listener {
        override fun onPlayerError(error: PlaybackException) {
            Log.e(TAG, "=== PLAYBACK ERROR: ${error.message}", error)
        }
        override fun onPlaybackStateChanged(playbackState: Int) {
            val stateString = when (playbackState) {
                Player.STATE_IDLE -> "IDLE"
                Player.STATE_BUFFERING -> "BUFFERING"
                Player.STATE_READY -> "READY"
                Player.STATE_ENDED -> "ENDED"
                else -> "UNKNOWN"
            }
            Log.i(TAG, "=== PLAYBACK STATE: $stateString ===")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopAnalysis()
        player.release()
        if (multicastLock.isHeld) {
            multicastLock.release()
        }
    }
}
