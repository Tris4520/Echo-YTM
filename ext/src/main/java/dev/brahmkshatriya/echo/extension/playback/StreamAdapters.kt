package dev.brahmkshatriya.echo.extension.playback

import com.metrolist.innertubex.sabr.ExperimentalSabrApi
import com.metrolist.innertubex.sabr.SabrAudioStream
import com.metrolist.innertubex.sabr.SabrBootstrap
import com.metrolist.innertubex.sabr.SabrVideoStream
import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.URLBuilder
import io.ktor.http.Url
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.io.readByteArray
import java.io.IOException
import java.io.InputStream

/** Bounded producer/consumer bridge. Closing the player stream cancels its network producer. */
internal class FlowInputStream(
    scope: CoroutineScope,
    bytes: Flow<ByteArray>,
    private var skipBytes: Long = 0,
    private var remaining: Long = -1,
) : InputStream() {
    private val channel = Channel<ByteArray>(2)
    private var chunk = ByteArray(0)
    private var offset = 0

    @Volatile private var closed = false
    private val producer: Job =
        scope.launch(Dispatchers.IO) {
            try {
                bytes.collect { if (it.isNotEmpty()) channel.send(it) }
                channel.close()
            } catch (error: Throwable) {
                channel.close(error)
            }
        }

    suspend fun prepare(): FlowInputStream {
        try {
            val next = channel.receiveCatching()
            next.exceptionOrNull()?.let { throw IOException("Media stream failed", it) }
            chunk = next.getOrNull() ?: ByteArray(0)
            return this
        } catch (error: Throwable) {
            close()
            throw error
        }
    }

    fun limit(length: Long) {
        if (length >= 0) remaining = if (remaining < 0) length else minOf(remaining, length)
    }

    override fun read(): Int {
        val byte = ByteArray(1)
        return if (read(byte, 0, 1) < 0) -1 else byte[0].toInt() and 255
    }

    override fun read(
        buffer: ByteArray,
        off: Int,
        len: Int,
    ): Int {
        if (off < 0 || len < 0 || off > buffer.size - len) throw IndexOutOfBoundsException()
        if (closed) throw IOException("Stream is closed")
        if (len == 0) return 0
        if (remaining == 0L) return -1
        while (offset >= chunk.size || skipBytes > 0) {
            if (offset >= chunk.size) {
                val next =
                    try {
                        runBlocking { channel.receiveCatching() }
                    } catch (error: Exception) {
                        throw IOException("Media stream failed", error)
                    }
                next.exceptionOrNull()?.let { throw IOException("Media stream failed", it) }
                chunk = next.getOrNull() ?: run {
                    if (remaining > 0 || skipBytes > 0) throw IOException("Media stream ended early")
                    return -1
                }
                offset = 0
            }
            if (skipBytes > 0) {
                val count = minOf(skipBytes, (chunk.size - offset).toLong()).toInt()
                offset += count
                skipBytes -= count
            }
        }
        val count =
            minOf(
                len,
                chunk.size - offset,
                if (remaining <
                    0
                ) {
                    Int.MAX_VALUE
                } else {
                    remaining.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                },
            )
        chunk.copyInto(buffer, off, offset, offset + count)
        offset += count
        if (remaining >= 0) remaining -= count
        return count
    }

    override fun close() {
        closed = true
        producer.cancel()
        channel.cancel()
    }
}

@OptIn(ExperimentalSabrApi::class)
internal class StreamAdapters(
    private val http: HttpClient,
    private val scope: CoroutineScope,
    private val sabrBytes: (SabrBootstrap, Boolean) -> Flow<ByteArray> = { bootstrap, video ->
        if (video) SabrVideoStream(http, bootstrap).bytes() else SabrAudioStream(http, bootstrap).bytes()
    },
) {
    private data class StartKey(
        val bootstrap: SabrBootstrap,
        val video: Boolean,
    )

    private class StartEntry {
        lateinit var job: Deferred<Pair<InputStream, Long>>
        var stream: InputStream? = null
        var claimed = false
    }

    private val startLock = Any()
    private val starts = linkedMapOf<StartKey, StartEntry>()

    /** Open audio/video together while Echo prepares its media sources, with bounded buffers. */
    fun preloadSabr(
        bootstrap: SabrBootstrap,
        video: Boolean,
    ) = synchronized(startLock) {
        val key = StartKey(bootstrap, video)
        if (key in starts) return@synchronized
        while (starts.size >= 2) discardStart(starts.keys.first())
        val entry = StartEntry()
        starts[key] = entry
        entry.job =
            scope.async(start = CoroutineStart.LAZY) {
                val result = createSabr(bootstrap, video, 0, -1)
                synchronized(startLock) {
                    entry.stream = result.first
                    if (starts[key] !== entry && !entry.claimed) result.first.close()
                }
                result
            }
        entry.job.start()
        scope.launch {
            delay(10_000)
            synchronized(startLock) { if (starts[key] === entry) discardStart(key) }
        }
    }

    fun clearStartup() =
        synchronized(startLock) {
            starts.keys.toList().forEach(::discardStart)
        }

    private fun discardStart(key: StartKey) {
        starts.remove(key)?.let {
            it.job.cancel()
            it.stream?.close()
        }
    }

    suspend fun sabr(
        bootstrap: SabrBootstrap,
        video: Boolean,
        position: Long,
        length: Long,
    ): Pair<InputStream, Long> {
        require(position >= 0)
        val warmed =
            if (position == 0L) {
                synchronized(startLock) {
                    starts.remove(StartKey(bootstrap, video))?.also { it.claimed = true }
                }
            } else {
                null
            }
        if (warmed != null) {
            val (stream, size) =
                try {
                    warmed.job.await()
                } catch (error: Throwable) {
                    // A cancelled source open must also release its claimed warm producer.
                    synchronized(startLock) {
                        warmed.claimed = false
                        warmed.job.cancel()
                        warmed.stream?.close()
                    }
                    throw error
                }
            (stream as FlowInputStream).limit(length)
            // The warm stream starts at zero; the player's requested bound still applies.
            return stream to
                if (length >= 0 && size >= 0) {
                    minOf(length, size)
                } else if (length >= 0) {
                    length
                } else {
                    size
                }
        }
        return createSabr(bootstrap, video, position, length)
    }

    private suspend fun createSabr(
        bootstrap: SabrBootstrap,
        video: Boolean,
        position: Long,
        length: Long,
    ): Pair<InputStream, Long> {
        val total = if (video) bootstrap.selectedVideoContentLengthBytes else bootstrap.contentLengthBytes
        val available = availableBytes(total, position, length)
        // Echo seeks by byte offset. Replay/discard gives exact offsets without pretending bytes are timestamps.
        val bytes = sabrBytes(bootstrap, video)
        return FlowInputStream(scope, bytes, position, available).prepare() to available
    }

    suspend fun ranged(
        url: String,
        headers: Map<String, String>,
        total: Long,
        chunkSize: Long,
        position: Long,
        length: Long,
        download: Boolean = false,
    ): Pair<InputStream, Long> {
        require(position >= 0 && total >= 0)
        val available = availableBytes(total, position, length)
        val bytes =
            flow {
                var start = position
                val limit = position + available
                while (start < limit) {
                    // Release enough bytes for player sniffing/buffering quickly, then use
                    // normal chunks for sustained playback. Download sizing stays unchanged.
                    val chunk = if (!download && start == position) 64 * 1024L else chunkSize.coerceIn(64 * 1024, 1024 * 1024)
                    val end = minOf(limit - 1, start + chunk - 1)
                    var failure: Exception? = null
                    var result: ByteArray? = null
                    for (attempt in 0..2) {
                        try {
                            result =
                                http
                                    .prepareGet(downloadRangeUrl(url, start, end)) {
                                        headers
                                            .filterKeys {
                                                !it.equals(HttpHeaders.Range, true) &&
                                                    !it.equals(HttpHeaders.AcceptEncoding, true)
                                            }.forEach { (name, value) -> header(name, value) }
                                        header(HttpHeaders.AcceptEncoding, "identity")
                                        timeout { requestTimeoutMillis = 30_000 }
                                    }.execute { response ->
                                        val queryRangeResponse = response.status.value == 200
                                        if (response.status.value != 206 &&
                                            !queryRangeResponse
                                        ) {
                                            throw IOException("Media HTTP ${response.status.value}")
                                        }
                                        val range = response.headers[HttpHeaders.ContentRange]
                                        val expected = (end - start + 1).toInt()
                                        // Query-selected chunks can report offsets relative to that virtual resource.
                                        val validRange =
                                            range == "bytes $start-$end/$total" ||
                                                range == "bytes 0-${expected - 1}/$expected"
                                        if (!queryRangeResponse &&
                                            !validRange
                                        ) {
                                            throw IOException("Invalid media byte range: expected $start-$end/$total, got $range")
                                        }
                                        val data = response.bodyAsChannel().readRemaining(expected.toLong() + 1).readByteArray()
                                        if (data.size != expected) throw IOException("Truncated media byte range")
                                        data
                                    }
                            break
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: Exception) {
                            failure = error
                            if (error.message?.contains("HTTP 403") == true || error.message?.contains("HTTP 410") == true) break
                            if (attempt < 2) delay(200L * (attempt + 1))
                        }
                    }
                    emit(result ?: throw IOException("Media range request failed", failure))
                    start = end + 1
                }
            }
        return FlowInputStream(scope, bytes, remaining = available).prepare() to available
    }

    private fun availableBytes(
        total: Long?,
        position: Long,
        length: Long,
    ): Long {
        val available = total?.let { (it - position).coerceAtLeast(0) } ?: -1
        return if (length >= 0) {
            if (available >= 0) {
                minOf(length, available)
            } else {
                length
            }
        } else {
            available
        }
    }
}

/** YouTube can pace plain media requests even with a Range header; query ranges request a bounded transfer. */
internal fun downloadRangeUrl(
    url: String,
    start: Long,
    end: Long,
): String {
    require(start >= 0 && end >= start)
    return URLBuilder(Url(url)).apply { parameters["range"] = "$start-$end" }.buildString()
}
