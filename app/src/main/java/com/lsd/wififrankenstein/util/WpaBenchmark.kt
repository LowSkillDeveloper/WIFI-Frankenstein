package com.lsd.wififrankenstein.util

import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import kotlin.system.measureTimeMillis

data class BenchmarkResult(
    val name: String,
    val attempts: Int,
    val elapsedMs: Long,
    val speed: Double
) {
    val speedFormatted: String
        get() = if (speed >= 1000) "%.1f kH/s".format(speed / 1000) else "%.0f H/s".format(
            speed
        )
    val elapsedFormatted: String get() = if (elapsedMs >= 1000) "%.2fs".format(elapsedMs / 1000.0) else "${elapsedMs}ms"
}

data class BenchmarkProgress(
    val stage: String,
    val subProgress: String = "",
    val percent: Int = -1
)

class WpaBenchmark {

    companion object {
        private const val TAG = "WpaBenchmark"
        private const val TARGET_MS_PER_TEST = 4000L
        private const val MULTI_TARGET_MS = 3000L
        private const val MIN_ITER = 5
        private const val MAX_ITER = 50000
        private const val CALIBRATION_COUNT = 5
        private val TEST_SSID = "TestNetwork"
        private val TEST_PASSWORD = "TheQuickBrownFoxJumpsOverTheLazyDog123!"
        private val TEST_BSSID = "aa:bb:cc:dd:ee:ff"
        private val TEST_STA_MAC = "11:22:33:44:55:66"
        private val TEST_ANONCE = ByteArray(32).apply {
            for (i in 0 until 32) this[i] = (i * 7 + 0xab).toByte()
        }
        private val TEST_EAPOL_HEX: String by lazy {
            val eapol = ByteArray(256).apply {
                this[0] = 1; this[1] = 3; this[2] = 0; this[3] = 0xF4.toByte()
                this[4] = 2; this[5] = 0x08.toByte(); this[6] = 0x02.toByte()
                this[7] = 0; this[8] = 0xCC.toByte()
                for (i in 0 until 8) this[9 + i] = 0
                TEST_ANONCE.copyInto(this, 17)
                for (i in 49 until 65) this[i] = 0
                for (i in 65 until 73) this[i] = 0
                for (i in 73 until 81) this[i] = 0
                for (i in 81 until 97) this[i] = 0
                this[97] = 0; this[98] = 0
            }
            WpaCrypto.bytesToHex(eapol)
        }

        private val TEST_PMKID_HASH = HandshakeHash(
            type = HandshakeType.PMKID,
            pmkidOrMic = "00000000000000000000000000000000",
            macAp = TEST_BSSID, macSta = TEST_STA_MAC,
            essid = TEST_SSID,
            essidHex = WpaCrypto.bytesToHex(TEST_SSID.toByteArray())
        )

        private val TEST_EAPOL_HASH = HandshakeHash(
            type = HandshakeType.EAPOL,
            pmkidOrMic = "00000000000000000000000000000000",
            macAp = TEST_BSSID, macSta = TEST_STA_MAC,
            essid = TEST_SSID,
            essidHex = WpaCrypto.bytesToHex(TEST_SSID.toByteArray()),
            anonce = WpaCrypto.bytesToHex(TEST_ANONCE),
            eapol = TEST_EAPOL_HEX, messagePair = 2, keyver = 2
        )
    }

    data class Report(
        val deviceName: String,
        val results: List<BenchmarkResult>,
        val estimatedDaily: String
    )

    suspend fun runAll(onProgress: (BenchmarkProgress) -> Unit = {}): Report =
        withContext(Dispatchers.Default) {
            val cpuCount = Runtime.getRuntime().availableProcessors()
            val deviceStr = "${Build.MODEL} (API ${Build.VERSION.SDK_INT})"
            Log.i(TAG, "===========================================================")
            Log.i(TAG, "  WPA BENCHMARK — $deviceStr")
            Log.i(TAG, "  CPU: $cpuCount cores, JVM: ${System.getProperty("java.vm.name")}")
            Log.i(TAG, "  Max heap: ${Runtime.getRuntime().maxMemory() / 1048576}MB")
            val backendInfo = WpaCrackSelector.info.replace("\n", " | ")
            Log.i(TAG, "  Backend: $backendInfo")
            Log.i(TAG, "===========================================================")
            onProgress(BenchmarkProgress("Calibrating speed", "measuring PBKDF2..."))
            val usecPerPbkdf2 = calibrate()
            val estimatedSpeed = 1_000_000.0 / usecPerPbkdf2

            val results = mutableListOf<BenchmarkResult>()
            val targetIter =
                ((TARGET_MS_PER_TEST * 1000) / usecPerPbkdf2).toInt().coerceIn(MIN_ITER, MAX_ITER)
            Log.i(
                TAG,
                "Adaptive iteration count: $targetIter (target ${TARGET_MS_PER_TEST}ms/test)"
            )


            if (NativeCracker.isAvailable) {
                onProgress(BenchmarkProgress("C PBKDF2 raw", "native baseline..."))
                val cNanos = NativeCracker.benchmarkPbkdf2(10)
                val cPerAttempt = cNanos / 10
                val cSpeed = 1_000_000_000.0 / cPerAttempt
                Log.i(
                    TAG,
                    "C PBKDF2 raw: 10 attempts in ${cNanos / 1000000}ms = ${"%.1f".format(cSpeed)} pw/s"
                )
                results.add(
                    BenchmarkResult(
                        "C PBKDF2 (raw)",
                        10,
                        (cNanos / 1000000).coerceAtLeast(1L),
                        cSpeed
                    )
                )
            }


            results.add(
                runBatchTest(
                    "PMKID (PBKDF2+HMAC) native", targetIter, "pmkid-native",
                    TEST_PMKID_HASH, onProgress
                )
            )

            results.add(
                runTest(
                    "PMKID (Kotlin fallback)", (targetIter / 10).coerceIn(5, 100), "pmkid-kotlin",
                    { WpaCracker.tryPasswordAny(TEST_PASSWORD, TEST_PMKID_HASH) }, onProgress
                )
            )
            results.add(
                runBatchTest(
                    "EAPOL keyver 1 (HMAC-MD5)", targetIter / 2, "kv1",
                    TEST_EAPOL_HASH.copy(keyver = 1), onProgress
                )
            )
            results.add(
                runBatchTest(
                    "EAPOL keyver 2 (HMAC-SHA1)", targetIter / 2, "kv2",
                    TEST_EAPOL_HASH, onProgress
                )
            )
            results.add(
                runBatchTest(
                    "EAPOL keyver 3 (AES-CMAC)", targetIter / 2, "kv3",
                    TEST_EAPOL_HASH.copy(keyver = 3), onProgress
                )
            )

            onProgress(BenchmarkProgress("Multi-thread test", "parallel × $cpuCount cores"))
            val multiResults = mutableListOf<BenchmarkResult>()
            val macApHex = TEST_EAPOL_HASH.macAp.replace(":", "").lowercase()
            val macStaHex = TEST_EAPOL_HASH.macSta.replace(":", "").lowercase()
            val anonceHex = (TEST_EAPOL_HASH.anonce ?: "").lowercase()
            val eapolHex = (TEST_EAPOL_HASH.eapol ?: "").lowercase()
            val micHex = TEST_EAPOL_HASH.pmkidOrMic.lowercase()
            val kv = TEST_EAPOL_HASH.keyver ?: 2
            for (threads in listOf(1, 2, 4, cpuCount).distinct()) {
                if (threads > cpuCount) continue
                val label = "Multi-Thread ($threads/$cpuCount cores)"
                Log.i(TAG, "--- $label (sustained ${MULTI_TARGET_MS}ms) ---")
                onProgress(BenchmarkProgress(label, "running..."))
                val deadline = System.currentTimeMillis() + MULTI_TARGET_MS
                var total = 0
                val elapsed = measureTimeMillis {
                    total = withContext(Dispatchers.Default) {
                        (0 until threads).map { tId ->
                            async {
                                var done = 0
                                var seq = 0
                                while (System.currentTimeMillis() < deadline) {
                                    val batch = ArrayList<String>(NativeCracker.BATCH_SIZE)
                                    repeat(NativeCracker.BATCH_SIZE) {
                                        batch.add(TEST_PASSWORD + "_${tId}_$seq")
                                    }
                                    seq++
                                    if (NativeCracker.isAvailable) {
                                        try {
                                            NativeCracker.crackBatchHex(
                                                batch.toTypedArray(), TEST_EAPOL_HASH.essid,
                                                macApHex, macStaHex, anonceHex, eapolHex, micHex,
                                                kv, 2
                                            )
                                        } catch (e: Exception) {
                                            Log.e(
                                                TAG,
                                                "Multi-thread native failed, falling back",
                                                e
                                            )
                                            for (pw in batch) {
                                                WpaCracker.tryPasswordAny(pw, TEST_EAPOL_HASH)
                                            }
                                        }
                                    } else {
                                        for (pw in batch) {
                                            WpaCracker.tryPasswordAny(pw, TEST_EAPOL_HASH)
                                        }
                                    }
                                    done += NativeCracker.BATCH_SIZE
                                }
                                Log.d(TAG, "  Thread $tId done: $done attempts")
                                done
                            }
                        }.awaitAll().sum()
                    }
                }
                val speed = total.toDouble() / elapsed * 1000
                Log.i(TAG, "  -> $total in ${elapsed}ms = $speed%.1f pw/s".format(speed))
                multiResults.add(BenchmarkResult(label, total, elapsed, speed))
            }
            results.addAll(multiResults)

            val bestOverall = results.filter { it.speed > 0 }.maxOfOrNull { it.speed } ?: 1.0
            val daily = bestOverall * 86400
            val dailyStr = formatDaily(daily)

            Log.i(TAG, "===========================================================")
            for (r in results) Log.i(TAG, "  %-35s %10s".format(r.name, r.speedFormatted))
            Log.i(TAG, "  Best multi-threaded: ${formatSpeed(bestOverall)} → $dailyStr")
            Log.i(TAG, "===========================================================")

            Report(deviceStr, results, dailyStr)
        }

    private fun calibrate(): Long {
        Log.d(
            TAG,
            "Calibration: ${CALIBRATION_COUNT}x batch(${NativeCracker.BATCH_SIZE}) PMKID via best backend..."
        )
        val ms = measureTimeMillis {
            repeat(CALIBRATION_COUNT) {
                WpaCrackSelector.crackBatch(
                    List(NativeCracker.BATCH_SIZE) { TEST_PASSWORD }, TEST_PMKID_HASH
                )
            }
        }
        val totalAttempts = CALIBRATION_COUNT * NativeCracker.BATCH_SIZE
        val perAttemptUs = (ms * 1000 / totalAttempts).coerceAtLeast(1)
        Log.d(TAG, "Calibration: ${totalAttempts} attempts in ${ms}ms = ${perAttemptUs}us/attempt")
        return perAttemptUs
    }

    private fun runTest(
        name: String,
        count: Int,
        tag: String,
        block: () -> Unit,
        onProgress: (BenchmarkProgress) -> Unit
    ): BenchmarkResult {
        val actual = count.coerceAtLeast(1)
        Log.i(TAG, "--- $name (${actual}x) ---")
        onProgress(BenchmarkProgress(name, "0%"))
        val reportEvery = (actual / 10).coerceAtLeast(1)
        val elapsed = measureTimeMillis {
            repeat(actual) { i ->
                block()
                if (i % reportEvery == 0 && i > 0) {
                    val pct = (i * 100 / actual).coerceAtMost(99)
                    Log.d(TAG, "  $tag: $i/$actual ($pct%)")
                    onProgress(BenchmarkProgress(name, "$pct%", pct))
                }
            }
        }
        val speed = actual.toDouble() / elapsed * 1000
        Log.i(TAG, "  $tag done: ${actual}x in ${elapsed}ms = $speed%.1f pw/s".format(speed))
        return BenchmarkResult(name, actual, elapsed, speed)
    }


    private fun runBatchTest(
        name: String,
        count: Int,
        tag: String,
        hash: HandshakeHash,
        onProgress: (BenchmarkProgress) -> Unit
    ): BenchmarkResult {
        val actual = count.coerceAtLeast(1)
        Log.i(TAG, "--- $name (${actual}x, batch ${NativeCracker.BATCH_SIZE}) ---")
        onProgress(BenchmarkProgress(name, "0%"))
        val reportEvery = (actual / 10).coerceAtLeast(NativeCracker.BATCH_SIZE)
        val elapsed = measureTimeMillis {
            var done = 0
            var seq = 0
            while (done < actual) {
                val batchSize = minOf(NativeCracker.BATCH_SIZE, actual - done)
                val batch = ArrayList<String>(batchSize)
                repeat(batchSize) { batch.add(TEST_PASSWORD + "_$seq") }
                seq++
                WpaCrackSelector.crackBatch(batch, hash)
                done += batchSize
                if (done % reportEvery == 0) {
                    val pct = (done * 100 / actual).coerceAtMost(99)
                    Log.d(TAG, "  $tag: $done/$actual ($pct%)")
                    onProgress(BenchmarkProgress(name, "$pct%", pct))
                }
            }
        }
        val speed = actual.toDouble() / elapsed * 1000
        Log.i(TAG, "  $tag done: ${actual}x in ${elapsed}ms = $speed%.1f pw/s".format(speed))
        return BenchmarkResult(name, actual, elapsed, speed)
    }

    private fun formatSpeed(speed: Double): String =
        if (speed >= 1000) "%.1f kH/s".format(speed / 1000) else "%.0f H/s".format(speed)

    private fun formatDaily(daily: Double): String =
        if (daily >= 1_000_000) "~%.1fM passwords/day".format(daily / 1_000_000)
        else "~%.0f passwords/day".format(daily)
}
