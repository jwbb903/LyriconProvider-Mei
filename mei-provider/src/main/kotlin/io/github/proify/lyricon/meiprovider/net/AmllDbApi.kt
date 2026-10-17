/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.meiprovider.net

import io.github.proify.lyricon.meiprovider.util.Logs
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * AMLL TTML DB 的一条歌词。
 *
 * [musicName] / [artists] 来自文件内嵌的 `<amll:meta>` 元数据, 用来校验
 * 「拿到的确实是这首歌」—— 宁可不出歌词, 也不要把别人的歌词推上去。
 */
data class AmllLyric(
    val ttml: String,
    val musicName: String? = null,
    val artists: String? = null,
    val album: String? = null,
) {
    val isUsable: Boolean get() = ttml.isNotBlank()
}

/**
 * AMLL TTML DB —— 社区维护的 Apple Music 风格逐字 TTML 歌词库。
 *
 * 仓库结构 (amll-dev/amll-ttml-db):
 * ```
 * ncm-lyrics/<网易云歌曲ID>.ttml     ← 与 Mei 的 mediaId 一一对应!
 * qq-lyrics/<QQ音乐songmid>.ttml
 * ```
 *
 * **为什么它是目前最可靠的来源**: 文件名就是歌曲 ID, 属于精确命中,
 * 不需要拿歌名去模糊搜索 —— 而模糊搜索正是「联网搜到的歌词经常是错的」的根源。
 *
 * 仓库 >50MB, jsDelivr 会以 403 拒绝, 所以只能走 raw.githubusercontent.com
 * 或第三方 GitHub 代理。国内直连 raw 基本不通, 因此内置多个镜像依次重试。
 */
object AmllDbApi {

    /** 依次尝试的镜像; 上一次成功的那条会被提到最前面 (见 [preferredIndex]) */
    private val MIRRORS = listOf(
        "https://ghproxy.net/https://raw.githubusercontent.com/amll-dev/amll-ttml-db/main",
        "https://gh-proxy.com/https://raw.githubusercontent.com/amll-dev/amll-ttml-db/main",
        "https://ghfast.top/https://raw.githubusercontent.com/amll-dev/amll-ttml-db/main",
        "https://raw.githubusercontent.com/amll-dev/amll-ttml-db/main",
    )

    private const val TIMEOUT_MS = 10_000

    /** 同时最多几个请求, 避免切歌时把网络打满 */
    private const val MAX_PENDING = 4

    private val executor = Executors.newFixedThreadPool(2) { runnable ->
        Thread(runnable, "MeiProvider-AmllDb").apply { isDaemon = true }
    }

    private val pending = AtomicInteger(0)

    /** 上次成功的镜像下标, 下次优先用它 */
    @Volatile
    private var preferredIndex = 0

    /** 确认不存在的 ID, 避免每次切歌都重打一遍 404 */
    private val missing = java.util.Collections.synchronizedSet(HashSet<String>())

    private val META_REGEX = Regex(
        """<amll:meta\s+key="([^"]+)"\s+value="([^"]*)"\s*/?>""",
        RegexOption.IGNORE_CASE
    )

    /** 网易云歌曲 ID 才能查 `ncm-lyrics` */
    private fun isNetEaseId(songId: String?): Boolean =
        !songId.isNullOrBlank() && songId.length in 1..20 && songId.all { it.isDigit() }

    fun fetchAsync(songId: String, callback: (AmllLyric?) -> Unit) {
        executor.execute {
            val result = fetch(songId)
            runCatching { callback(result) }
        }
    }

    /**
     * 按歌曲 ID 精确取 TTML。
     *
     * 校验: 文件内嵌的 `ncmMusicId` 必须与请求的 ID 一致, 否则视为取错并丢弃。
     * 这一步是「不要错歌词」的最后一道防线。
     */
    fun fetch(songId: String): AmllLyric? {
        if (!isNetEaseId(songId)) return null
        if (songId in missing) return null
        if (pending.get() >= MAX_PENDING) return null

        val path = "ncm-lyrics/$songId.ttml"
        pending.incrementAndGet()
        try {
            val body = download(path) ?: run {
                missing.add(songId)
                return null
            }

            val meta = parseMeta(body)
            val declaredId = meta["ncmMusicId"]
            if (declaredId != null && declaredId != songId) {
                // 文件在, 但元数据对不上 —— 记录为不可用, 不要推错歌词
                Logs.warn("AMLL DB id mismatch: requested=$songId file declares=$declaredId")
                missing.add(songId)
                return null
            }

            return AmllLyric(
                ttml = body,
                musicName = meta["musicName"],
                artists = meta["artists"],
                album = meta["album"],
            ).also {
                Logs.debug(
                    "AMLL DB hit (id=$songId name=${it.musicName} " +
                        "artists=${it.artists} ${body.length}B)"
                )
            }
        } finally {
            pending.decrementAndGet()
        }
    }

    /** 依次尝试镜像; 成功的镜像下次优先 */
    private fun download(path: String): String? {
        val start = preferredIndex
        for (offset in MIRRORS.indices) {
            val index = (start + offset) % MIRRORS.size
            val body = downloadFrom(MIRRORS[index], path)
            if (body != null) {
                if (index != preferredIndex) {
                    preferredIndex = index
                    Logs.debug("AMLL DB mirror switched -> ${MIRRORS[index]}")
                }
                return body
            }
        }
        return null
    }

    private fun downloadFrom(base: String, path: String): String? {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL("$base/$path").openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14)")
            }

            val code = connection.responseCode
            when {
                code == 404 -> return null
                code !in 200..299 -> {
                    Logs.warn("AMLL DB HTTP $code ($base/$path)")
                    return null
                }
            }

            connection.inputStream.bufferedReader().use { it.readText() }
                .takeIf { it.isNotBlank() && looksLikeTtml(it) }
        } catch (t: Throwable) {
            Logs.warn("AMLL DB fetch failed ($base/$path): ${t.message}")
            null
        } finally {
            runCatching { connection?.disconnect() }
        }
    }

    private fun parseMeta(body: String): Map<String, String> =
        META_REGEX.findAll(body).associate { it.groupValues[1] to it.groupValues[2] }

    /** 代理可能返回一个 HTML 错误页而不是 404, 这里做个内容兜底判断 */
    private fun looksLikeTtml(content: String): Boolean {
        val head = content.trimStart()
        return head.startsWith("<tt", ignoreCase = true) ||
                content.contains("http://www.w3.org/ns/ttml", ignoreCase = true)
    }

    /** 清掉已确认缺失的记录 */
    fun clearMissing() {
        missing.clear()
    }
}
