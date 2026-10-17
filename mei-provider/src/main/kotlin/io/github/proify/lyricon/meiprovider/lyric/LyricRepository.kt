/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.meiprovider.lyric

/**
 * 已解析歌词的缓存 —— 预取的结果存在这里，切歌时直接命中，不用再等网络。
 *
 * 只按歌曲 ID + 显示开关做键：
 * 翻译 / 罗马音开关会影响解析结果，所以必须进键，否则用户切换开关后
 * 会命中一份用旧开关解析出来的歌词。
 */
object LyricRepository {

    /** 最多缓存几首。预取只提前 1 首，留些余量给「往回切」的场景 */
    private const val MAX_ENTRIES = 6

    private val cache = object : LinkedHashMap<String, LyricCandidate>(16, 0.75f, true) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, LyricCandidate>?,
        ): Boolean = size > MAX_ENTRIES
    }

    /** 正在预取的键，避免同一首被重复请求 */
    private val pending = HashSet<String>()

    private fun key(songId: String, translation: Boolean): String =
        "$songId|t=$translation"

    fun cached(songId: String?, translation: Boolean): LyricCandidate? {
        if (songId.isNullOrBlank()) return null
        return synchronized(cache) { cache[key(songId, translation)] }
    }

    fun store(
        songId: String?,
        translation: Boolean,
        candidate: LyricCandidate?,
    ) {
        if (songId.isNullOrBlank() || candidate == null || candidate.isEmpty) return
        synchronized(cache) { cache[key(songId, translation)] = candidate }
    }

    /** 标记开始预取；返回 false 表示已经有同键的任务在跑 */
    fun beginPrefetch(songId: String?, translation: Boolean): Boolean {
        if (songId.isNullOrBlank()) return false
        return synchronized(pending) { pending.add(key(songId, translation)) }
    }

    fun endPrefetch(songId: String?, translation: Boolean) {
        if (songId.isNullOrBlank()) return
        synchronized(pending) { pending.remove(key(songId, translation)) }
    }

    fun clear() {
        synchronized(cache) { cache.clear() }
        synchronized(pending) { pending.clear() }
    }

    /**
     * 作废某首歌的所有缓存条目（不管解析时用的开关是什么）。
     *
     * Mei 刚往 `cached_lyric` 里落了新歌词时必须调用，
     * 否则预取时存下的旧版本会一直挡住新歌词。
     */
    fun invalidate(songId: String?) {
        if (songId.isNullOrBlank()) return
        val prefix = "$songId|"
        synchronized(cache) { cache.keys.removeAll { it.startsWith(prefix) } }
        synchronized(pending) { pending.removeAll { it.startsWith(prefix) } }
    }
}
