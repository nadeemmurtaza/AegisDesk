package com.newax.aegis.engine.manager

import java.util.concurrent.ConcurrentHashMap

class LruCache<K, V>(private val maxSize: Int) {
    private val map = LinkedHashMap<K, V>(maxSize, 0.75f, true)
    private val lock = Any()

    fun get(key: K): V? = synchronized(lock) { map[key] }

    fun put(key: K, value: V) = synchronized(lock) {
        map[key] = value
        if (map.size > maxSize) {
            val oldest = map.keys.first()
            map.remove(oldest)
        }
    }

    fun remove(key: K): V? = synchronized(lock) { map.remove(key) }

    fun evictAll() = synchronized(lock) { map.clear() }

    fun size(): Int = synchronized(lock) { map.size }

    fun keys(): Set<K> = synchronized(lock) { map.keys.toSet() }
}

data class CacheEntry<V>(
    val value: V,
    val createdMs: Long = System.currentTimeMillis(),
    val ttlMs: Long = Long.MAX_VALUE,
    val accessCount: Int = 0
) {
    fun isExpired() = System.currentTimeMillis() > createdMs + ttlMs
}

object CacheManager {

    private val caches = ConcurrentHashMap<String, LruCache<String, CacheEntry<Any>>>()
    private val defaultTtl = ConcurrentHashMap<String, Long>()

    fun createScope(name: String, maxSize: Int = 200, ttlMs: Long = 5 * 60_000L) {
        caches[name] = LruCache(maxSize)
        defaultTtl[name] = ttlMs
    }

    @Suppress("UNCHECKED_CAST")
    fun <V : Any> get(scope: String, key: String): V? {
        val cache = caches[scope] ?: return null
        val entry = cache.get(key) ?: return null
        if (entry.isExpired()) { cache.remove(key); return null }
        return entry.value as? V
    }

    fun <V : Any> put(scope: String, key: String, value: V, ttlMs: Long? = null) {
        val cache = caches.getOrPut(scope) { LruCache(200) }
        val ttl = ttlMs ?: defaultTtl[scope] ?: Long.MAX_VALUE
        @Suppress("UNCHECKED_CAST")
        cache.put(key, CacheEntry(value as Any, ttlMs = ttl))
    }

    fun evict(scope: String, key: String) {
        caches[scope]?.remove(key)
    }

    fun evictScope(scope: String) {
        caches[scope]?.evictAll()
    }

    fun evictExpired() {
        caches.forEach { (_, cache) ->
            cache.keys().toList().forEach { key ->
                val entry = cache.get(key)
                if (entry?.isExpired() == true) cache.remove(key)
            }
        }
    }

    fun evictAll() = caches.values.forEach { it.evictAll() }

    fun <V : Any> getOrCompute(scope: String, key: String, ttlMs: Long? = null, compute: () -> V): V {
        get<V>(scope, key)?.let { return it }
        val value = compute()
        put(scope, key, value, ttlMs)
        return value
    }

    fun stats(): Map<String, CacheStats> = caches.mapValues { (name, cache) ->
        CacheStats(name, cache.size(), 0)
    }

    data class CacheStats(val scope: String, val size: Int, val expiredCount: Int)
}
