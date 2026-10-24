package dev.brahmkshatriya.echo.extension.playback

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred

/** Memory only: never persist signed URLs or share them across accounts/settings changes. */
internal class ResolutionCache<K, V>(
    private val capacity: Int = 64,
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private data class Entry<V>(
        val value: V,
        val expiresAt: Long,
    )

    private val lock = Any()
    private val entries = LinkedHashMap<K, Entry<V>>(capacity, 0.75f, true)
    private val pending = mutableMapOf<K, CompletableDeferred<V>>()
    private var generation = 0L

    suspend fun getOrLoad(
        key: K,
        load: suspend () -> Pair<V, Long>,
    ): V {
        val deferred: CompletableDeferred<V>
        val requestGeneration: Long
        val owner: Boolean
        synchronized(lock) {
            entries[key]?.takeIf { it.expiresAt > nowMs() }?.let { return it.value }
            entries.remove(key)
            requestGeneration = generation
            val existing = pending[key]
            owner = existing == null
            deferred = existing ?: CompletableDeferred<V>().also { pending[key] = it }
        }
        if (!owner) return deferred.await()
        try {
            val (value, ttlMs) = load()
            synchronized(lock) {
                if (generation != requestGeneration) throw CancellationException("Playback session changed")
                if (ttlMs > 0) {
                    entries[key] = Entry(value, nowMs() + ttlMs)
                    while (entries.size > capacity) entries.remove(entries.keys.first())
                }
                deferred.complete(value)
            }
            return value
        } catch (error: Throwable) {
            deferred.completeExceptionally(error)
            throw error
        } finally {
            synchronized(lock) { if (pending[key] === deferred) pending.remove(key) }
        }
    }

    fun clear() =
        synchronized(lock) {
            generation++
            entries.clear()
            pending.values.forEach { it.cancel(CancellationException("Playback session changed")) }
            pending.clear()
        }

    fun invalidate(key: K) {
        synchronized(lock) { entries.remove(key) }
    }
}
