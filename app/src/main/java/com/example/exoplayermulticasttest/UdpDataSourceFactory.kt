package com.example.exoplayermulticasttest

import androidx.media3.datasource.DataSource

@androidx.media3.common.util.UnstableApi
class UdpDataSourceFactory(private val uri: String): DataSource.Factory {
    override fun createDataSource(): DataSource {
        return UdpDataSource(uri)
    }
}