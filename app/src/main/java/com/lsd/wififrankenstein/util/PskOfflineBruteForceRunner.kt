package com.lsd.wififrankenstein.util

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader

data class OfflineProgress(
    val currentPassword: String,
    val attempts: Long,
    val totalPasswords: Long,
    val speed: Double,
    val elapsedMs: Long,
    val etaMs: Long,
    val offset: Long = 0
)

data class OfflineResult(
    val foundPassword: String?,
    val attempts: Long,
    val elapsedMs: Long,
    val averageSpeed: Double,
    val cancelled: Boolean = false,
    val offset: Long = 0
)

class PskOfflineBruteForceRunner(private val context: Context) {

    companion object {
        private const val TAG = "PskOfflineBruteForceRunner"
        private const val CHUNK_SIZE = 50
        private const val PAUSE_POLL_MS = 200L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var crackJob: Job? = null

    @Volatile
    private var cancelled = false

    @Volatile
    var paused = false

    fun pause() {
        paused = true
    }

    fun resume() {
        paused = false
    }

    suspend fun crackFromWordlist(
        handshakeHash: HandshakeHash,
        extraHashes: List<HandshakeHash> = emptyList(),
        wordlistUri: Uri,
        threadCount: Int = Runtime.getRuntime().availableProcessors().coerceAtLeast(1),
        startOffset: Long = 0,
        onProgress: ((OfflineProgress) -> Unit)? = null
    ): OfflineResult = withContext(Dispatchers.IO) {
        cancelled = false
        paused = false
        val startTime = System.currentTimeMillis()

        val allHashes = (listOf(handshakeHash) + extraHashes).distinctBy { it.dedupKey() }

        val totalPasswords = countLines(wordlistUri)
        var totalAttempts = 0L
        var foundPassword: String? = null
        var fileOffset = startOffset
        val speedWindow = mutableListOf<Pair<Long, Long>>()

        Log.d(TAG, "=== OFFLINE BRUTE FORCE START ===")
        Log.d(
            TAG,
            "Handshake: ${handshakeHash.essid} / ${handshakeHash.macAp} (${allHashes.size} candidate hash(es))"
        )
        Log.d(TAG, "Type: ${handshakeHash.type}, Keyver: ${handshakeHash.keyver}")
        Log.d(
            TAG,
            "Wordlist lines: $totalPasswords, Threads: $threadCount, Start offset: $startOffset"
        )

        try {
            val inputStream = context.contentResolver.openInputStream(wordlistUri)
                ?: return@withContext OfflineResult(null, 0, 0, 0.0, offset = startOffset)

            val progressChannel = Channel<OfflineProgress>(Channel.CONFLATED)
            val resultChannel = Channel<String?>(Channel.CONFLATED)

            val producerJob = scope.launch {
                supervisorScope {
                    val reader = BufferedReader(InputStreamReader(inputStream))
                    var linesSkipped = 0L
                    var batch = mutableListOf<String>()

                    reader.use { br ->
                        var line: String?

                        if (startOffset > 0) {
                            while (linesSkipped < startOffset && br.readLine()
                                    .also { line = it } != null
                            ) {
                                linesSkipped++
                                fileOffset++
                            }
                        }

                        while (br.readLine().also { line = it } != null && !cancelled) {
                            while (paused && !cancelled) {
                                delay(PAUSE_POLL_MS)
                            }
                            if (cancelled) break

                            val pw = line!!.trim()
                            fileOffset++
                            if (pw.isEmpty() || pw.startsWith("#")) continue
                            batch.add(pw)

                            if (batch.size >= CHUNK_SIZE) {
                                val chunk = batch.toList()
                                batch = mutableListOf()
                                launch {
                                    crackChunk(
                                        chunk,
                                        allHashes,
                                        progressChannel,
                                        resultChannel,
                                        fileOffset
                                    )
                                }
                            }
                        }
                    }

                    if (batch.isNotEmpty() && !cancelled) {
                        launch {
                            crackChunk(
                                batch,
                                allHashes,
                                progressChannel,
                                resultChannel,
                                fileOffset
                            )
                        }
                    }
                }
            }

            val progressConsumerJob = scope.launch(Dispatchers.IO) {
                Log.d(TAG, "progressConsumerJob started on IO")
                var progressCount = 0
                for (p in progressChannel) {
                    totalAttempts += p.attempts
                    progressCount++
                    if (progressCount % 10 == 1) {
                        Log.d(
                            TAG,
                            "progress #$progressCount: attempts=$totalAttempts curr=${
                                p.currentPassword.take(20)
                            }"
                        )
                    }
                    val now = System.currentTimeMillis()
                    val elapsed = now - startTime

                    speedWindow.add(elapsed to totalAttempts)
                    while (speedWindow.size > 2 && speedWindow.last().first - speedWindow.first().first > 5000) {
                        speedWindow.removeAt(0)
                    }

                    val speed = if (speedWindow.size >= 2) {
                        val dt = speedWindow.last().first - speedWindow.first().first
                        val da = speedWindow.last().second - speedWindow.first().second
                        if (dt > 0) da.toDouble() / dt * 1000.0 else 0.0
                    } else 0.0

                    val etaMs = if (speed > 0 && totalPasswords > 0) {
                        ((totalPasswords - totalAttempts) / speed * 1000.0).toLong()
                    } else 0L

                    onProgress?.invoke(
                        OfflineProgress(
                            currentPassword = p.currentPassword,
                            attempts = totalAttempts,
                            totalPasswords = totalPasswords,
                            speed = speed,
                            elapsedMs = elapsed,
                            etaMs = etaMs,
                            offset = p.offset
                        )
                    )
                }
            }

            val resultConsumerJob = scope.launch(Dispatchers.IO) {
                Log.d(TAG, "resultConsumerJob started on IO")
                for (password in resultChannel) {
                    if (password != null) {
                        Log.d(TAG, "!!! PASSWORD FOUND: $password !!!")
                        foundPassword = password
                        cancelled = true
                    }
                }
            }

            crackJob = scope.launch {
                producerJob.join()
                progressChannel.close()
                resultChannel.close()
            }

            crackJob?.join()
            progressConsumerJob.join()
            resultConsumerJob.join()

        } catch (e: Exception) {
            if (!cancelled) {
                Log.e(TAG, "Crack failed", e)
            }
        } finally {
            crackJob = null
        }

        val elapsed = System.currentTimeMillis() - startTime
        val avgSpeed = if (elapsed > 0) totalAttempts.toDouble() / elapsed * 1000.0 else 0.0

        Log.d(TAG, "=== OFFLINE BRUTE FORCE END ===")
        Log.d(
            TAG,
            "Found: ${foundPassword != null}, Attempts: $totalAttempts, Elapsed: ${elapsed}ms, Avg speed: ${
                "%.1f".format(avgSpeed)
            } pw/s"
        )

        OfflineResult(
            foundPassword, totalAttempts, elapsed, avgSpeed,
            cancelled = cancelled && foundPassword == null,
            offset = fileOffset
        )
    }

    private suspend fun crackChunk(
        passwords: List<String>,
        hashes: List<HandshakeHash>,
        progressChannel: Channel<OfflineProgress>,
        resultChannel: Channel<String?>,
        chunkOffset: Long
    ) {
        try {
            if (hashes.isEmpty() || passwords.isEmpty()) {
                progressChannel.send(
                    OfflineProgress(
                        passwords.firstOrNull() ?: "?",
                        0,
                        0,
                        0.0,
                        0,
                        0,
                        chunkOffset
                    )
                )
                return
            }

            Log.d(
                TAG,
                "chunk start: size=${passwords.size}, first=${passwords.firstOrNull()?.take(20)}"
            )

            val nativeHashes = hashes.filter { h ->
                NativeCracker.isAvailable && h.anonce != null && h.eapol != null &&
                    (h.keyver ?: WpaCracker.extractKeyver(WpaCrypto.hexToBytes(h.eapol))) in 1..3
            }
            val fallbackHashes = hashes.filter { h -> nativeHashes.none { it === h } }
            val miniBatchSize = NativeCracker.BATCH_SIZE

            var chunkAttempts = 0
            var lastPassword = ""

            if (nativeHashes.isNotEmpty()) {
                for (i in passwords.indices step miniBatchSize) {
                    if (cancelled) break
                    while (paused && !cancelled) {
                        delay(PAUSE_POLL_MS)
                    }
                    if (cancelled) break
                    val end = minOf(i + miniBatchSize, passwords.size)
                    val batch = passwords.subList(i, end).toTypedArray()
                    var found: String? = null
                    for (h in nativeHashes) {
                        val typeCode = when (h.type) {
                            HandshakeType.PMKID -> 1
                            HandshakeType.EAPOL -> 2
                            HandshakeType.PMKID_EAPOL -> 3
                        }
                        val kv = h.keyver ?: WpaCracker.extractKeyver(
                            WpaCrypto.hexToBytes(h.eapol!!)
                        )
                        val idx = NativeCracker.crackBatchHex(
                            batch, h.essid,
                            h.macAp.replace(":", "").lowercase(),
                            h.macSta.replace(":", "").lowercase(),
                            h.anonce!!.lowercase(), h.eapol!!.lowercase(),
                            h.pmkidOrMic.lowercase(), kv, typeCode
                        )
                        if (idx >= 0) {
                            found = batch[idx]
                            break
                        }
                    }
                    val batchSize = end - i
                    chunkAttempts += batchSize
                    lastPassword = batch.last()
                    if (found != null) {
                        Log.d(TAG, "!!! FOUND PASSWORD (native): $found !!!")
                        resultChannel.send(found)
                        cancelled = true
                        return@crackChunk
                    }
                    progressChannel.send(
                        OfflineProgress(
                            lastPassword,
                            batchSize.toLong(),
                            0,
                            0.0,
                            0,
                            0,
                            chunkOffset
                        )
                    )
                }
            }

            for (password in passwords) {
                if (cancelled) break
                while (paused && !cancelled) {
                    delay(PAUSE_POLL_MS)
                }
                if (cancelled) break
                lastPassword = password
                chunkAttempts++
                for (h in fallbackHashes) {
                    if (WpaCracker.tryPasswordAny(password, h)) {
                        Log.d(TAG, "!!! FOUND PASSWORD: $password !!!")
                        resultChannel.send(password)
                        cancelled = true
                        return@crackChunk
                    }
                }
            }
            Log.d(TAG, "chunk done: $chunkAttempts attempts, last=${lastPassword.take(20)}")
            progressChannel.send(
                OfflineProgress(
                    lastPassword,
                    chunkAttempts.toLong(),
                    0,
                    0.0,
                    0,
                    0,
                    chunkOffset
                )
            )
        } catch (e: Throwable) {
            Log.e(TAG, "chunk CRASHED: ${e.message}", e)
            progressChannel.send(
                OfflineProgress(
                    passwords.firstOrNull() ?: "?",
                    0,
                    0,
                    0.0,
                    0,
                    0,
                    chunkOffset
                )
            )
        }
    }

    private suspend fun countLines(uri: Uri): Long {
        return withContext(Dispatchers.IO) {
            try {
                val inputStream =
                    context.contentResolver.openInputStream(uri) ?: return@withContext 0L
                inputStream.use { stream ->
                    var count = 0L
                    val buffer = ByteArray(8192)
                    var read: Int
                    while (stream.read(buffer).also { read = it } != -1) {
                        for (i in 0 until read) {
                            if (buffer[i] == '\n'.code.toByte()) count++
                        }
                    }
                    count
                }
            } catch (e: Exception) {
                Log.e(TAG, "countLines error", e)
                0L
            }
        }
    }

    fun cancel() {
        cancelled = true
        crackJob?.cancel()
        crackJob = null
        scope.cancel()
    }

    fun destroy() {
        cancel()
        scope.cancel()
    }
}
