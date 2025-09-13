package com.example.exoplayermulticasttest

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView
import com.example.exoplayermulticasttest.ui.theme.ExoPlayerMulticastTestTheme

class MainActivity : ComponentActivity() {
    private lateinit var player: ExoPlayer

    @androidx.media3.common.util.UnstableApi
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val streamUri = "udp://@224.1.1.1:1234"

        val mediaSource = ProgressiveMediaSource.Factory(UdpDataSourceFactory(streamUri))
            .createMediaSource(MediaItem.fromUri(streamUri))

        player = ExoPlayer.Builder(this).build().apply {
            setMediaSource(mediaSource)
            prepare()
            play()
        }

        enableEdgeToEdge()
        setContent {
            AndroidView(
                factory = { context ->
                    PlayerView(context).apply {
                        this.player = player
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
        }
    }

    override fun onStop() {
        super.onStop()
        player.release()
    }
}