package com.example.exoplayermulticasttest

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.MulticastSocket

class MainActivity : ComponentActivity() {

    private lateinit var player: ExoPlayer
    private lateinit var multicastLock: WifiManager.MulticastLock

    private val packetStats = mutableStateOf(PacketStats())


    companion object {
        private const val TAG = "MainActivity"
        private const val STREAM_URI = "udp://@224.1.1.1:1234"
    }

    data class PacketStats(
        val total: Long = 0,
        val lost: Long = 0,
        val lossRate: Double = 0.0
    )

    @androidx.media3.common.util.UnstableApi
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setupMulticastLock()
        setupExoPlayer()
        startLossMonitor() // 패킷 로스 모니터 시작

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
                // 패킷 통계 표시
                Text(
                    text = "Total: ${packetStats.value.total}, " +
                            "Lost: ${packetStats.value.lost}, " +
                            "Loss: ${"%.4f".format(packetStats.value.lossRate)}%",
                    modifier = Modifier.padding(16.dp)
                )
            }
        }
    }

    private fun startLossMonitor() {
        CoroutineScope(Dispatchers.IO).launch {
            // ExoPlayer와 충돌을 피하기 위해 다른 포트를 사용하거나,
            // ExoPlayer가 사용하는 포트와 동일한 포트를 사용하되 reuseAddress를 true로 설정합니다.
            // 여기서는 동일 포트를 사용한다고 가정합니다.
            val socket = MulticastSocket(1234).apply {
                reuseAddress = true // 다른 소켓과 포트를 공유할 수 있게 함
            }
            val group = InetAddress.getByName("224.1.1.1")
            socket.joinGroup(group)

            // 각 PID별로 마지막 CC(Continuity Counter)를 저장하는 맵
            val pidCcMap = mutableMapOf<Int, Int>()
            var totalPkt = 0L
            var lostPkt = 0L
            var remain = ByteArray(0)

            val buf = ByteArray(4096) // UDP 패킷을 받기 위한 버퍼
            val udpPacket = DatagramPacket(buf, buf.size)

            try {
                while (true) {
                    socket.receive(udpPacket)
                    // 수신한 데이터와 이전에 남은 데이터를 합침
                    val data = remain + udpPacket.data.copyOf(udpPacket.length)
                    var offset = 0

                    // 188바이트 TS 패킷 단위로 처리
                    while (offset + 188 <= data.size) {
                        // Sync Byte(0x47)를 찾아 패킷의 시작점을 맞춤
                        if (data[offset] != 0x47.toByte()) {
                            var nextSync = -1
                            for (i in offset + 1 until data.size) {
                                if (data[i] == 0x47.toByte()) {
                                    nextSync = i
                                    break
                                }
                            }
                            if (nextSync == -1) break // Sync Byte 없음
                            offset = nextSync
                            continue
                        }

                        // TS 패킷 (188 바이트) 추출
                        val tsPacket = data.copyOfRange(offset, offset + 188)
                        val pid =
                            ((tsPacket[1].toInt() and 0x1F) shl 8) or (tsPacket[2].toInt() and 0xFF)
                        val cc = tsPacket[3].toInt() and 0x0F

                        // Null Packet (PID 8191)은 통계에서 제외
                        if (pid == 8191) {
                            offset += 188
                            continue
                        }

                        totalPkt++ // 전체 패킷 수 증가

                        // 이 PID가 처음 발견된 경우, 현재 CC를 기록하고 다음으로 넘어감
                        if (!pidCcMap.containsKey(pid)) {
                            pidCcMap[pid] = cc
                        } else {
                            val lastCc = pidCcMap[pid]!!
                            // 예상되는 다음 CC 값 (15 다음은 0)
                            val expectedCc = (lastCc + 1) % 16

                            if (cc != expectedCc) {
                                // CC가 예상과 다르면 패킷 손실 발생
                                val lostCount = (cc - expectedCc + 16) % 16
                                lostPkt += lostCount
                                Log.w(
                                    TAG,
                                    "Packet Loss Detected! PID: $pid, LastCC: $lastCc, CurrentCC: $cc, Lost: $lostCount"
                                )
                            }
                            // 현재 CC를 마지막 CC로 업데이트
                            pidCcMap[pid] = cc
                        }

                        offset += 188
                    }
                    // 처리하고 남은 데이터를 다음 루프를 위해 저장
                    remain = if (offset < data.size) data.copyOfRange(
                        offset,
                        data.size
                    ) else ByteArray(0)

                    // UI 업데이트 (메인 스레드에서)
                    withContext(Dispatchers.Main) {
                        val lossRate = if (totalPkt > 0) 100.0 * lostPkt / totalPkt else 0.0
                        packetStats.value = PacketStats(totalPkt, lostPkt, lossRate)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in Loss Monitor", e)
            } finally {
                socket.leaveGroup(group)
                socket.close()
            }
        }
    }

    private fun checkNetworkEnvironment() {
        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val wifiInfo = wifiManager.connectionInfo

        Log.i(TAG, "=== Network Environment ===")
        Log.i(TAG, "WiFi connected: ${wifiManager.isWifiEnabled}")
        Log.i(TAG, "WiFi SSID: ${wifiInfo.ssid}")
        Log.i(TAG, "WiFi IP: ${wifiInfo.ipAddress}")
        Log.i(TAG, "WiFi Signal: ${wifiInfo.rssi} dBm")
    }

    private fun setupMulticastLock() {
        Log.i(TAG, "=== Setting up MulticastLock ===")

        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        multicastLock = wifiManager.createMulticastLock("udpMulticastLock").apply {
            setReferenceCounted(true)
            acquire()
        }

        Log.i(TAG, "MulticastLock acquired: ${multicastLock.isHeld}")
    }

    private fun testMulticastReception() {
        Log.i(TAG, "=== Testing Multicast Reception ===")

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val socket = MulticastSocket(1234)
                socket.reuseAddress = true
                socket.soTimeout = 5000 // 5초 타임아웃

                val group = InetAddress.getByName("224.1.1.1")
                socket.joinGroup(group)

                Log.i(TAG, "Multicast socket created, waiting for packets...")

                val buffer = ByteArray(1316)
                val packet = DatagramPacket(buffer, buffer.size)

                socket.receive(packet)
                Log.i(TAG, "SUCCESS: Received ${packet.length} bytes from ${packet.address}")

                socket.leaveGroup(group)
                socket.close()

            } catch (e: Exception) {
                Log.e(TAG, "Multicast test FAILED: ${e.message}")
                Log.e(TAG, "Possible causes:")
                Log.e(TAG, "1. No stream being sent to 224.1.1.1:1234")
                Log.e(TAG, "2. Router doesn't support multicast")
                Log.e(TAG, "3. Network firewall blocking packets")
            }
        }
    }


    @androidx.media3.common.util.UnstableApi
    private fun setupExoPlayer() {
        Log.i(TAG, "=== Setting up ExoPlayer (Final Version with Custom Buffer) ===")

        try {
            // 1. 버퍼링 정책을 관대하게 설정
            // 최소 30초, 최대 60초 분량의 데이터를 미리 확보하도록 설정하여 버퍼링 문제를 해결
            val loadControl = androidx.media3.exoplayer.DefaultLoadControl.Builder()
//                .setBufferDurationsMs(
//                    30_000, // Min buffer (ms)
//                    60_000, // Max buffer (ms)
//                    1_500,  // Buffer for playback to start (ms)
//                    2_000   // Buffer for playback after rebuffer (ms)
//                )
                .build()
            Log.d(TAG, "Custom DefaultLoadControl created")

            // 2. ExoPlayer 내장 UDP 소스를 사용하도록 MediaItem 생성 (URI에서 '@' 제거)
            // 2. ExoPlayer 내장 UDP 소스를 사용하도록 MediaItem 생성 (URI에서 '@' 제거)
            val mediaItem = MediaItem.fromUri("udp://224.1.1.1:1234")
            Log.d(
                TAG,
                "MediaItem created for internal UDP source: ${mediaItem.localConfiguration?.uri}"
            )

            // 3. ExoPlayer 빌드 시, 위에서 만든 커스텀 버퍼링 정책을 적용
            player = ExoPlayer.Builder(this)
                .setLoadControl(loadControl) // ✅ 커스텀 버퍼 설정 적용
                .build()
                .apply {
                    addListener(createPlayerListener())
                    setMediaItem(mediaItem)
                    Log.i(TAG, "ExoPlayer prepared, starting playback...")
                    prepare()
                    playWhenReady = true
                }

            Log.i(TAG, "✅ ExoPlayer setup completed successfully (Final Version)")

        } catch (e: Exception) {
            Log.e(TAG, "❌ ExoPlayer setup failed (Final Version)", e)
        }
    }

    private fun createPlayerListener() = object : Player.Listener {
        override fun onPlayerError(error: PlaybackException) {
            Log.e(TAG, "=== PLAYBACK ERROR ===")
            Log.e(TAG, "Error code: ${error.errorCode}")
            Log.e(TAG, "Error message: ${error.message}")
            Log.e(TAG, "Cause: ${error.cause}")

            when (error.errorCode) {
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED -> {
                    Log.e(TAG, "DIAGNOSIS: Network connection failed")
                    Log.e(TAG, "- Check if ffmpeg is running and sending to 224.1.1.1:1234")
                    Log.e(TAG, "- Verify router supports multicast")
                }

                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> {
                    Log.e(TAG, "DIAGNOSIS: Network timeout")
                    Log.e(TAG, "- Stream might be too slow or intermittent")
                }

                PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED -> {
                    Log.e(TAG, "DIAGNOSIS: Container parsing error")
                    Log.e(TAG, "- Check MPEG-TS stream format")
                }

                else -> {
                    Log.e(TAG, "DIAGNOSIS: Other error - ${error.errorCode}")
                }
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            val stateString = when (playbackState) {
                Player.STATE_IDLE -> "IDLE - Player created"
                Player.STATE_BUFFERING -> "BUFFERING - Loading stream data..."
                Player.STATE_READY -> "READY - ✅ Video playing successfully!"
                Player.STATE_ENDED -> "ENDED - Stream finished"
                else -> "UNKNOWN - $playbackState"
            }
            Log.i(TAG, "=== PLAYBACK STATE: $stateString ===")
        }

        override fun onIsLoadingChanged(isLoading: Boolean) {
            Log.d(TAG, "Loading state: $isLoading")
        }

        override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
            Log.i(TAG, "🎥 Video resolution detected: ${videoSize.width}x${videoSize.height}")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "=== App Destroyed ===")

        player.release()

        if (multicastLock.isHeld) {
            multicastLock.release()
            Log.d(TAG, "MulticastLock released")
        }
    }
}
