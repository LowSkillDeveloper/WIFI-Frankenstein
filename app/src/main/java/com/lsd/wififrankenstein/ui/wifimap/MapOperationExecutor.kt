package com.lsd.wififrankenstein.ui.wifimap

import com.lsd.wififrankenstein.util.PerformanceManager
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors

object MapOperationExecutor {
    val databaseDispatcher: CoroutineDispatcher by lazy {
        Executors.newFixedThreadPool(PerformanceManager.getDatabaseThreadCount())
            .asCoroutineDispatcher()
    }

    val clusteringDispatcher: CoroutineDispatcher by lazy {
        Executors.newFixedThreadPool(PerformanceManager.getClusteringThreadCount())
            .asCoroutineDispatcher()
    }

    val ioDispatcher: CoroutineDispatcher by lazy {
        Executors.newFixedThreadPool(PerformanceManager.getIOThreadCount())
            .asCoroutineDispatcher()
    }

    val uiUpdateDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate
}