package com.example.exoplayermulticasttest

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.MulticastSocket

class MainActivity : ComponentActivity() {

    private lateinit var player: ExoPlayer
    private lateinit var multicastLock: WifiManager.MulticastLock

    companion object {
        private const val TAG = "MainActivity"
        private const val STREAM_URI = "udp://@224.1.1.1:1234"
    }

    @androidx.media3.common.util.UnstableApi
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Log.i(TAG, "=== App Started ===")

        try {
            // 네트워크 환경 체크
            checkNetworkEnvironment()

            // MulticastLock 설정
            setupMulticastLock()

            // 멀티캐스트 수신 테스트
            testMulticastReception()

            // ExoPlayer 설정
            setupExoPlayer()
        } catch (e: Exception) {
            Log.e(TAG, "Setup failed", e)
        }

        enableEdgeToEdge()
        setContent {
            AndroidView(
                factory = { context ->
                    PlayerView(context).apply {
                        this.player = this@MainActivity.player
                        useController = true
                        controllerAutoShow = false
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
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
