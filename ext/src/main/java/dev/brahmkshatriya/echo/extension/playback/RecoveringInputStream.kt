package dev.brahmkshatriya.echo.extension.playback

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest

/** Retry once, verifying every byte already delivered before changing transports. */
internal class RecoveringInputStream(
    initial: InputStream,
    private val size: Long,
    private val reopen: suspend (IOException) -> Pair<InputStream, Long>,
) : InputStream() {
    @Volatile private var current = initial

    @Volatile private var candidate: InputStream? = null

    @Volatile private var recoveryJob: Job? = null

    @Volatile private var closed = false
    private val deliveredHash = MessageDigest.getInstance("SHA-256")
    private var delivered = 0L
    private var recovered = false

    override fun read(): Int {
        val single = ByteArray(1)
        return if (read(single, 0, 1) < 0) -1 else single[0].toInt() and 255
    }

    override fun read(
        buffer: ByteArray,
        off: Int,
        len: Int,
    ): Int {
        if (off < 0 || len < 0 || off > buffer.size - len) throw IndexOutOfBoundsException()
        if (closed) throw IOException("Stream is closed")
        if (len == 0) return 0
        val count =
            try {
                current.read(buffer, off, len)
            } catch (first: IOException) {
                if (recovered || closed || generateSequence<Throwable>(first) { it.cause }.any { it is CancellationException }) throw first
                recovered = true
                current.close()
                try {
                    runBlocking {
                        recoveryJob = currentCoroutineContext()[Job]
                        try {
                            if (closed) throw CancellationException("Stream is closed")
                            val (replacement, replacementSize) = reopen(first)
                            candidate = replacement
                            if (closed) throw CancellationException("Stream is closed")
                            if (size >= 0 && replacementSize != size) throw IOException("Replacement stream has a different length")
                            // SABR and direct containers must match before we resume at this byte offset.
                            val replacementHash = MessageDigest.getInstance("SHA-256")
                            val scratch = ByteArray(32 * 1024)
                            var left = delivered
                            while (left > 0) {
                                val read = replacement.read(scratch, 0, minOf(left, scratch.size.toLong()).toInt())
                                if (read <= 0) throw IOException("Replacement stream ended before the resume position")
                                replacementHash.update(scratch, 0, read)
                                left -= read
                            }
                            if (!MessageDigest.isEqual(deliveredHash.digest(), replacementHash.digest())) {
                                throw IOException("Replacement stream differs from the bytes already played")
                            }
                            if (closed) throw CancellationException("Stream is closed")
                            current = replacement
                            candidate = null
                        } finally {
                            recoveryJob = null
                        }
                    }
                    current.read(buffer, off, len)
                } catch (second: Exception) {
                    candidate?.close()
                    candidate = null
                    current.close()
                    if (first !== second) first.addSuppressed(second)
                    throw IOException("Could not recover playback stream", first)
                }
            }
        if (count > 0 && !recovered) {
            deliveredHash.update(buffer, off, count)
            delivered += count
        }
        return count
    }

    override fun close() {
        closed = true
        recoveryJob?.cancel()
        candidate?.close()
        current.close()
    }
}
