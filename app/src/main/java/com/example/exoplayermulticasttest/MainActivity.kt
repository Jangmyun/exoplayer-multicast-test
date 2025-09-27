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
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.upstream.DefaultAllocator
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.*
import java.io.OutputStreamWriter
import java.net.*
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : ComponentActivity() {

    private var player: ExoPlayer? = null // Nullable로 변경하여 비활성화 상태 표현
    private lateinit var multicastLock: WifiManager.MulticastLock

    private val analysisStats = mutableStateOf(PacketAnalysis())
    private val isMonitoring = mutableStateOf(false)

    private var analysisJob: Job? = null
    private var csvWriter: OutputStreamWriter? = null

    companion object {
        private const val TAG = "MainActivity"
        private const val MULTICAST_URI = "udp://224.1.1.1:1234"
        // 유니캐스트 모니터링은 포트만 중요하므로 IP는 0.0.0.0 사용
        private const val UNICAST_MONITOR_URI = "udp://0.0.0.0:1234"
        private const val MCAST_PORT = 1234
    }

    // 현재 스트림 모드를 관리하는 상태 변수
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
        // 앱 시작 시 기본값인 멀티캐스트로 플레이어 설정
        setupExoPlayer()

        enableEdgeToEdge()
        setContent {
            Column(modifier = Modifier.fillMaxSize()) {
                // 스트림 모드에 따라 PlayerView 또는 안내 문구 표시
                Box(modifier = Modifier.weight(1f).fillMaxWidth().background(Color.Black)) {
                    // player가 null이 아닐 때만 PlayerView를 렌더링
                    if (player != null && streamUri == MULTICAST_URI) {
                        AndroidView(
                            factory = { context ->
                                PlayerView(context).apply {
                                    this.player = this@MainActivity.player
                                    useController = true
                                }
                            },
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Text(
                            "Player is disabled in Unicast monitoring mode.",
                            color = Color.White,
                            modifier = Modifier.align(Alignment.Center)
                        )
                    }
                }

                // --- UI Controls ---
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    Button(onClick = { switchStream(MULTICAST_URI) }) { Text("Multicast Mode") }
                    Button(onClick = { switchStream(UNICAST_MONITOR_URI) }) { Text("Unicast Mode") }
                }
                Text(
                    text = "Current Mode: ${if (streamUri == MULTICAST_URI) "Multicast" else "Unicast"}",
                    modifier = Modifier.padding(horizontal = 16.dp),
                    fontWeight = FontWeight.Bold
                )

                // 모니터링 정보 UI
                Column(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
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
                        Button(onClick = { startAnalysis() }, enabled = !isMonitoring.value) { Text("Start Monitoring") }
                        Button(onClick = { stopAnalysis() }, enabled = isMonitoring.value) { Text("Stop Monitoring") }
                    }
                }
            }
        }
    }

    @androidx.media3.common.util.UnstableApi
    private fun switchStream(newUri: String) {
        if (isMonitoring.value) {
            stopAnalysis()
        }

        streamUri = newUri
        player?.release() // Null-safe release
        player = null // 플레이어 비활성화

        if (newUri == MULTICAST_URI) {
            setupExoPlayer()
            Toast.makeText(this, "Switched to Multicast. Player is active.", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Switched to Unicast. Player is disabled.", Toast.LENGTH_SHORT).show()
        }
    }

    @androidx.media3.common.util.UnstableApi
    private fun setupExoPlayer() {
        try {
            val loadControl = DefaultLoadControl.Builder()
                .setAllocator(DefaultAllocator(true, 65536, 2 * 1024 * 1024)).build()

            val mediaItem = MediaItem.fromUri(streamUri)
            player = ExoPlayer.Builder(this)
                .setLoadControl(loadControl)
                .build()
                .apply {
                    addListener(createPlayerListener())
                    setMediaItem(mediaItem)
                    prepare()
                    playWhenReady = true
                }
        } catch (e: Exception) {
            Log.e(TAG, "ExoPlayer setup failed", e)
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

    private suspend fun runPacketAnalysis(scope: CoroutineScope) {
        val uri = Uri.parse(streamUri)
        val host = uri.host ?: "0.0.0.0" // 기본값을 0.0.0.0으로 변경
        val port = if (uri.port > 0) uri.port else MCAST_PORT
        val address = InetAddress.getByName(host)

        val socket: DatagramSocket? = try {
            if (address.isMulticastAddress) {
                MulticastSocket(port).apply {
                    reuseAddress = true
                    joinGroup(address)
                }
            } else { // Unicast
                DatagramSocket(port).apply { reuseAddress = true }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create socket on port $port.", e)
            withContext(Dispatchers.Main) {
                Toast.makeText(this@MainActivity, "Error: Failed to open port $port. Is player active?", Toast.LENGTH_LONG).show()
            }
            null
        }

        if (socket == null) {
            withContext(Dispatchers.Main) { stopAnalysis() }
            return
        }

        val pidCcMap = mutableMapOf<Int, Int>()
        var totalPkt = 0L
        var lostPkt = 0L
        var remain = ByteArray(0)
        var bytesInInterval = 0L
        var lastUpdateTime = System.currentTimeMillis()

        val buf = ByteArray(4096)
        val udpPacket = DatagramPacket(buf, buf.size)

        try {
            while (scope.isActive) {
                socket.receive(udpPacket)
                val receivedBytes = udpPacket.length
                bytesInInterval += receivedBytes

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
                    if (pid == 0x1FFF) {
                        offset += 188; continue
                    }
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
                    val throughputMbps = if (intervalSeconds > 0) {
                        (bytesInInterval * 8) / (intervalSeconds * 1_000_000)
                    } else {
                        0.0
                    }
                    val lossRate = if (totalPkt > 0) 100.0 * lostPkt / totalPkt else 0.0

                    withContext(Dispatchers.Main) {
                        analysisStats.value = PacketAnalysis(totalPkt, lostPkt, lossRate, throughputMbps)
                    }
                    writeCsvLog(System.currentTimeMillis(), totalPkt, lostPkt, lossRate, throughputMbps)

                    bytesInInterval = 0L
                    lastUpdateTime = now
                }
            }
        } catch (e: Exception) {
            if (scope.isActive) Log.e(TAG, "Error during packet analysis", e)
        } finally {
            try {
                if (socket is MulticastSocket && address.isMulticastAddress) {
                    socket.leaveGroup(address)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error leaving multicast group", e)
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
            Log.i(TAG, "=== PLAYBACK STATE: $stateString ====")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopAnalysis()
        player?.release()
        if (multicastLock.isHeld) {
            multicastLock.release()
        }
    }
}