package com.lsd.wififrankenstein.ui.wifimap

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors

class MapOperationExecutor {
    companion object {
        val databaseDispatcher: CoroutineDispatcher = Executors.newFixedThreadPool(2)
            .asCoroutineDispatcher()

        val clusteringDispatcher: CoroutineDispatcher = Executors.newFixedThreadPool(1)
            .asCoroutineDispatcher()

        val uiUpdateDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate
    }
}