/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.meiprovider.cloud

import io.github.proify.cloudlyric.CloudLyrics
import io.github.proify.cloudlyric.ProviderLyrics
import io.github.proify.cloudlyric.SearchOptions
import io.github.proify.lyricon.lyric.model.RichLyricLine
import io.github.proify.lyricon.meiprovider.bridge.MatchPolicy
import io.github.proify.lyricon.meiprovider.util.Constants
import io.github.proify.lyricon.meiprovider.util.Logs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * 云端歌词匹配 —— 完全不依赖 Mei 的内部实现, 即使 Mei 换版本/换混淆也照常工作。
 *
 * 注意: 必须同时使用**全部** provider。之前按系统语言二选一是错的 ——
 * LrcLib 几乎没有中文歌, 只挂它会导致中文歌完全匹配不到。
 */
object CloudSearcher {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /** 默认即 [io.github.proify.cloudlyric.provider.LyricsConfig.ALL_PROVIDERS] (QQ 音乐 + LrcLib) */
    private val cloudLyrics: CloudLyrics by lazy { CloudLyrics() }

    private val cache = ConcurrentHashMap<String, List<RichLyricLine>>()

    private const val MAX_CACHE = 32

    fun search(
        trackName: String,
        artistName: String,
        albumName: String?,
        onSuccess: (List<RichLyricLine>) -> Unit,
        onFailure: (Exception) -> Unit,
    ) {
        if (trackName.isBlank()) {
            onFailure(IllegalArgumentException("empty track name"))
            return
        }

        val cacheKey = "${trackName.lowercase()}|${artistName.lowercase()}"
        cache[cacheKey]?.let {
            onSuccess(it)
            return
        }

        scope.launch {
            try {
                val response = cloudLyrics.search(
                    SearchOptions().apply {
                        this.trackName = trackName
                        this.artistName = artistName
                        this.albumName = albumName
                    }
                )

                Logs.debug(
                    "Cloud search '$trackName - $artistName' -> ${response.size} results"
                )

                val best = pickBest(response, trackName, artistName)
                val rich = best?.lyrics?.rich.orEmpty()

                if (rich.isEmpty()) {
                    onFailure(NoSuchElementException("no lyric matched"))
                    return@launch
                }

                if (cache.size > MAX_CACHE) cache.clear()
                cache[cacheKey] = rich
                onSuccess(rich)
            } catch (e: Exception) {
                Logs.error("Cloud search failed: $trackName", e)
                onFailure(e)
            }
        }
    }

    /**
     * 选最匹配的一条。
     *
     * **先严格过滤再排序** ([MatchPolicy.accept]): 标题 + 歌手对不上的一律拒绝,
     * 宁可返回 null (回退本地), 也不推一份别人的歌词上去。这是用户明确要的行为。
     *
     * 过滤之后的排序只比较「质量」: 逐字 > 翻译 > 罗马音 > 覆盖度。
     */
    private fun pickBest(
        results: List<ProviderLyrics>,
        trackName: String,
        artistName: String,
    ): ProviderLyrics? {
        val accepted = results.filter {
            MatchPolicy.accept(
                candidateTitle = it.lyrics.trackName,
                candidateArtist = it.lyrics.artistName,
                targetTitle = trackName,
                targetArtist = artistName,
            )
        }
        if (accepted.isEmpty()) {
            Logs.warn(
                "Cloud search rejected all ${results.size} results " +
                    "for '$trackName - $artistName' (title/artist mismatch)"
            )
            return null
        }

        return accepted.maxByOrNull { result ->
            var score = 0
            if (result.lyrics.rich.any { !it.words.isNullOrEmpty() }) score += 200
            if (result.lyrics.rich.any { !it.translation.isNullOrBlank() }) score += 100
            score += result.lyrics.rich.size.coerceAtMost(150)
            score
        }
    }
}
