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
import java.util.concurrent.atomic.LongAdder

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
        val totalAttempts = LongAdder()
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

                            val pw = line!!.trim().trimStart('\uFEFF')
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
                                        fileOffset,
                                        totalAttempts
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
                                fileOffset,
                                totalAttempts
                            )
                        }
                    }
                }
            }

            val progressConsumerJob = scope.launch(Dispatchers.IO) {
                Log.d(TAG, "progressConsumerJob started on IO")
                var progressCount = 0
                for (p in progressChannel) {
                    val attemptsSnapshot = totalAttempts.sum()
                    progressCount++
                    if (progressCount % 10 == 1) {
                        Log.d(
                            TAG,
                            "progress #$progressCount: attempts=$attemptsSnapshot curr=${
                                p.currentPassword.take(20)
                            }"
                        )
                    }
                    val now = System.currentTimeMillis()
                    val elapsed = now - startTime

                    speedWindow.add(elapsed to attemptsSnapshot)
                    while (speedWindow.size > 2 && speedWindow.last().first - speedWindow.first().first > 5000) {
                        speedWindow.removeAt(0)
                    }

                    val speed = if (speedWindow.size >= 2) {
                        val dt = speedWindow.last().first - speedWindow.first().first
                        val da = speedWindow.last().second - speedWindow.first().second
                        if (dt > 0) da.toDouble() / dt * 1000.0 else 0.0
                    } else 0.0

                    val etaMs = if (speed > 0 && totalPasswords > 0) {
                        ((totalPasswords - attemptsSnapshot) / speed * 1000.0).toLong()
                    } else 0L

                    onProgress?.invoke(
                        OfflineProgress(
                            currentPassword = p.currentPassword,
                            attempts = attemptsSnapshot,
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
        val attempts = totalAttempts.sum()
        val avgSpeed = if (elapsed > 0) attempts.toDouble() / elapsed * 1000.0 else 0.0

        Log.d(TAG, "=== OFFLINE BRUTE FORCE END ===")
        Log.d(
            TAG,
            "Found: ${foundPassword != null}, Attempts: $attempts, Elapsed: ${elapsed}ms, Avg speed: ${
                "%.1f".format(avgSpeed)
            } pw/s"
        )

        OfflineResult(
            foundPassword, attempts, elapsed, avgSpeed,
            cancelled = cancelled && foundPassword == null,
            offset = fileOffset
        )
    }

    private suspend fun crackChunk(
        passwords: List<String>,
        hashes: List<HandshakeHash>,
        progressChannel: Channel<OfflineProgress>,
        resultChannel: Channel<String?>,
        chunkOffset: Long,
        attemptsAccumulator: LongAdder
    ) {
        suspend fun report(p: OfflineProgress) {
            attemptsAccumulator.add(p.attempts)
            progressChannel.send(p)
        }

        try {
            if (hashes.isEmpty() || passwords.isEmpty()) {
                report(
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
                if (!NativeCracker.isAvailable) return@filter false
                val hasMic = h.pmkidOrMic.length >= 32
                val hasEapolData = h.anonce != null && h.eapol != null
                when {
                    h.type == HandshakeType.PMKID -> hasMic

                    hasEapolData -> hasMic &&
                            (h.keyver ?: WpaCracker.extractKeyver(
                                WpaCrypto.hexToBytes(h.eapol)
                            )) in 1..2

                    else -> false
                }
            }
            val fallbackHashes = hashes.filter { h -> nativeHashes.none { it === h } }
            // Hashes sharing an ESSID are verified against a single PBKDF2 per password
            val nativeGroups = nativeHashes.groupBy { it.essid }
            val miniBatchSize = NativeCracker.BATCH_SIZE
            val allHashesForVerification = hashes

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
                    var nativeFailed = false
                    for ((_, group) in nativeGroups) {
                        if (found != null || cancelled) break
                        val idx = try {
                            NativeCracker.crackBatchMultiHex(
                                batch,
                                group[0].essid,
                                Array(group.size) { g ->
                                    group[g].macAp.replace(":", "").lowercase()
                                },
                                Array(group.size) { g ->
                                    group[g].macSta.replace(":", "").lowercase()
                                },
                                Array(group.size) { g -> group[g].anonce?.lowercase() ?: "" },
                                Array(group.size) { g -> group[g].eapol?.lowercase() ?: "" },
                                Array(group.size) { g -> group[g].pmkidOrMic.lowercase() },
                                IntArray(group.size) { g -> group[g].keyver ?: 2 },
                                IntArray(group.size) { g ->
                                    when (group[g].type) {
                                        HandshakeType.PMKID -> 1
                                        HandshakeType.EAPOL -> 2
                                        HandshakeType.PMKID_EAPOL -> 3
                                    }
                                }
                            )
                        } catch (e: Throwable) {
                            nativeFailed = true
                            Log.e(TAG, "Native batch error, falling back to JVM: ${e.message}", e)
                            -1
                        }
                        if (idx >= 0 && idx < batch.size) {
                            val candidate = batch[idx]
                            if (group.any { WpaCracker.tryPasswordAny(candidate, it) }) {
                                found = candidate
                                break
                            } else {
                                Log.w(
                                    TAG,
                                    "Native reported hit at $idx but JVM rejected it; " +
                                            "re-verifying whole batch in JVM"
                                )
                                for (pw in batch) {
                                    if (allHashesForVerification.any {
                                            WpaCracker.tryPasswordAny(pw, it)
                                        }
                                    ) {
                                        found = pw
                                        break
                                    }
                                }
                                if (found != null) break
                            }
                        }
                    }
                    if (found == null && nativeFailed) {
                        for (pw in batch) {
                            if (nativeHashes.any { WpaCracker.tryPasswordAny(pw, it) }) {
                                found = pw
                                break
                            }
                        }
                    }
                    val batchSize = end - i
                    chunkAttempts += batchSize
                    lastPassword = batch.last()
                    if (found != null) {
                        Log.d(TAG, "!!! FOUND PASSWORD (native, JVM-confirmed): $found !!!")
                        report(
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
                        resultChannel.send(found)
                        cancelled = true
                        return@crackChunk
                    }
                }
            }

            if (fallbackHashes.isNotEmpty()) {
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
                            report(
                                OfflineProgress(
                                    password,
                                    chunkAttempts.toLong(),
                                    0,
                                    0.0,
                                    0,
                                    0,
                                    chunkOffset
                                )
                            )
                            resultChannel.send(password)
                            cancelled = true
                            return@crackChunk
                        }
                    }
                }
            }
            Log.d(TAG, "chunk done: $chunkAttempts attempts, last=${lastPassword.take(20)}")
            report(
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
            report(
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
                    var sawAnyByte = false
                    var lastByte: Byte = '\n'.code.toByte()
                    val buffer = ByteArray(8192)
                    var read: Int
                    while (stream.read(buffer).also { read = it } != -1) {
                        for (i in 0 until read) {
                            sawAnyByte = true
                            lastByte = buffer[i]
                            if (buffer[i] == '\n'.code.toByte()) count++
                        }
                    }
                    if (sawAnyByte && lastByte != '\n'.code.toByte()) count++
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
