package com.lsd.wififrankenstein.ui.wifimap

import com.lsd.wififrankenstein.util.PerformanceManager
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors

object MapOperationExecutor {
    private var databasePool =
        Executors.newFixedThreadPool(PerformanceManager.getDatabaseThreadCount())
    val databaseDispatcher: CoroutineDispatcher by lazy {
        databasePool.asCoroutineDispatcher()
    }

    private var clusteringPool =
        Executors.newFixedThreadPool(PerformanceManager.getClusteringThreadCount())
    val clusteringDispatcher: CoroutineDispatcher by lazy {
        clusteringPool.asCoroutineDispatcher()
    }

    private var ioPool = Executors.newFixedThreadPool(PerformanceManager.getIOThreadCount())
    val ioDispatcher: CoroutineDispatcher by lazy {
        ioPool.asCoroutineDispatcher()
    }

    val uiUpdateDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate

    fun shutdown() {
        databasePool.shutdown()
        clusteringPool.shutdown()
        ioPool.shutdown()
    }
}
