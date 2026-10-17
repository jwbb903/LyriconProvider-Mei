/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.meiprovider.net

import io.github.proify.lyricon.meiprovider.util.Constants
import io.github.proify.lyricon.meiprovider.util.Logs
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * 网易云歌词接口返回的一组歌词文本。
 *
 * 接口同时返回「朴素 LRC」和「逐字 YRC」两套, 以及各自的翻译:
 * - 朴素: `lrc` / `tlyric`
 * - 逐字: `yrc` / `ytlrc`
 */
data class NetEaseLyric(
    val lrc: String? = null,
    val translation: String? = null,
    val yrc: String? = null,
    val yrcTranslation: String? = null,
) {

    val isUsable: Boolean get() = !yrc.isNullOrBlank() || !lrc.isNullOrBlank()

    /** 有逐字歌词时优先用逐字那一套 (翻译也要跟着换) */
    val useVerbatim: Boolean get() = !yrc.isNullOrBlank()

    val bestContent: String? get() = yrc?.takeIf { it.isNotBlank() } ?: lrc?.takeIf { it.isNotBlank() }

    val bestTranslation: String?
        get() = if (useVerbatim) {
            yrcTranslation?.takeIf { it.isNotBlank() } ?: translation?.takeIf { it.isNotBlank() }
        } else {
            translation?.takeIf { it.isNotBlank() } ?: yrcTranslation?.takeIf { it.isNotBlank() }
        }

    val bestParserType: String get() = if (useVerbatim) "YRC" else "LRC"
}

/**
 * 直接查网易云公开歌词接口。
 *
 * 为什么需要它:
 * 当 `isVerbatim=true` (网易云逐字歌词) 时 Mei 根本不把 YRC 文本落库 ——
 * 所以只靠读缓存拿不到逐字歌词, 中文歌也常常拿不到歌词。
 * 这里直接按歌曲 ID 取原始文本, 再交给 lrckit / yrckit 解析。
 */
object NetEaseApi {

    private const val ENDPOINT = "https://music.163.com/api/song/lyric"

    /** 一次性把朴素 / 逐字 / 翻译全要回来 */
    private const val PARAMS = "lv=-1&kv=-1&tv=-1&yv=-1&ytv=-1"

    private const val TIMEOUT_MS = 8_000

    private val executor = Executors.newFixedThreadPool(2) { runnable ->
        Thread(runnable, "MeiProvider-NetEase").apply { isDaemon = true }
    }

    private val pending = AtomicInteger(0)

    /** 歌曲 ID 必须是纯数字, 否则说明不是网易云曲目 */
    fun isNetEaseId(songId: String?): Boolean =
        !songId.isNullOrBlank() && songId.length in 1..20 && songId.all { it.isDigit() }

    /**
     * 异步取歌词。回调在主线程之外执行, 调用方需自行切回主线程。
     */
    fun fetchAsync(songId: String, callback: (NetEaseLyric?) -> Unit) {
        executor.execute {
            val result = fetch(songId)
            runCatching { callback(result) }
        }
    }

    fun fetch(songId: String): NetEaseLyric? {
        if (!isNetEaseId(songId)) return null
        if (pending.get() > 4) return null

        pending.incrementAndGet()
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL("$ENDPOINT?id=$songId&$PARAMS").openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14)")
                setRequestProperty("Referer", "https://music.163.com/")
            }

            val code = connection.responseCode
            if (code !in 200..299) {
                Logs.warn("NetEase lyric HTTP $code (id=$songId)")
                return null
            }

            val body = connection.inputStream.bufferedReader().use { it.readText() }
            parse(body)?.also {
                Logs.debug(
                    "NetEase lyric ok (id=$songId verbatim=${it.useVerbatim} " +
                        "lrc=${it.lrc?.length ?: 0} yrc=${it.yrc?.length ?: 0} " +
                        "trans=${it.translation?.length ?: 0})"
                )
            }
        } catch (t: Throwable) {
            Logs.warn("NetEase lyric failed (id=$songId): ${t.message}")
            null
        } finally {
            pending.decrementAndGet()
            runCatching { connection?.disconnect() }
        }
    }

    private fun parse(body: String): NetEaseLyric? {
        val root = runCatching { JSONObject(body) }.getOrNull() ?: return null
        if (root.optInt("code", 200) != 200) return null

        fun lyric(key: String): String? {
            val obj = root.optJSONObject(key) ?: return null
            return obj.optString("lyric", "").takeIf { it.isNotBlank() }
        }

        val result = NetEaseLyric(
            lrc = lyric("lrc"),
            translation = lyric("tlyric"),
            yrc = lyric("yrc"),
            yrcTranslation = lyric("ytlrc"),
        )
        return result.takeIf { it.isUsable }
    }
}
