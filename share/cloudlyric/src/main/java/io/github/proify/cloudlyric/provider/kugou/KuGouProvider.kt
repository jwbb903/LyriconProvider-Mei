/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.cloudlyric.provider.kugou

import io.github.proify.cloudlyric.LyricsProvider
import io.github.proify.cloudlyric.LyricsResult
import io.github.proify.lyricon.krckit.KrcDecryptor
import io.github.proify.lyricon.krckit.KrcParser
import io.github.proify.lyricon.lyric.model.RichLyricLine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.zip.InflaterInputStream

/**
 * 酷狗 KRC 歌词提供者。
 *
 * 流程 (实测全部可用, 2026-10):
 *   1. GET krcs.kugou.com/search?ver=1&man=yes&client=pc&keyword=<kw>
 *      → JSON, candidates[] 含 id/accesskey/song/singer/score/krctype
 *   2. GET krcs.kugou.com/download?...&fmt=krc → JSON, content = base64(KRC)
 *   3. KRC = 跳过前 4 字节 → XOR(key[16]) → zlib → 明文 (逐字 <offset,dur,0>标签)
 *      解密复用 share:krckit 的 KrcDecryptor, 解析复用 KrcParser
 *      (KrcDocument.richLyricLines 自动挂上 [language:] 字段里的翻译/罗马音)
 *
 * 特点:
 *   - 逐字覆盖率高 (中文歌明显好于 QQ 之外的源), 自带翻译/罗马音
 *   - **搜索接口偶发返回空 candidates (约 1/3 概率), 必须重试** — 这是服务端行为, 不是被封
 *   - 关键词格式敏感: "歌名-歌手" 或 "歌名 歌手" 可用; 乱序大概率搜不到
 *   - 酷狗服务器不支持 https 的该路径 (404), 必须走 http
 *
 * 上游严格过滤 (MatchPolicy.accept) 会二次校验歌名/歌手, 所以这里多带几个候选没问题。
 */
class KuGouProvider : LyricsProvider {
    companion object {
        const val ID = "KuGou"
        private const val SEARCH_URL = "http://krcs.kugou.com/search"
        private const val DOWNLOAD_URL = "http://krcs.kugou.com/download"
        private const val TIMEOUT = 6000
        private const val SEARCH_RETRIES = 3
    }

    override val id: String = ID

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    @Serializable
    private data class SearchResponse(
        val status: Int = 0,
        val candidates: List<Candidate> = emptyList()
    )

    @Serializable
    private data class Candidate(
        val id: String = "",
        val accesskey: String = "",
        val song: String = "",
        val singer: String = "",
        val score: Int = 0
    )

    @Serializable
    private data class DownloadResponse(
        val status: Int = 0,
        val content: String? = null,
        val fmt: String? = null
    )

    override suspend fun search(
        query: String?,
        trackName: String?,
        artistName: String?,
        albumName: String?,
        limit: Int
    ): List<LyricsResult> = withContext(Dispatchers.IO) {
        // 关键词格式敏感: "歌名-歌手" 实测最稳
        val key = buildString {
            query?.let { append(it); return@buildString }
            trackName?.takeIf { it.isNotBlank() }?.let { append(it) }
            artistName?.takeIf { it.isNotBlank() }?.let {
                if (isNotEmpty()) append('-')
                append(it)
            }
        }
        if (key.isBlank()) return@withContext emptyList()

        val candidates = searchWithRetry(key, limit) ?: return@withContext emptyList()

        candidates.take(limit).mapNotNull { candidate ->
            try {
                val rich = downloadKrc(candidate) ?: downloadLrc(candidate) ?: return@mapNotNull null
                if (rich.isEmpty()) null
                else LyricsResult(
                    trackName = candidate.song,
                    artistName = candidate.singer,
                    albumName = null,
                    rich = rich,
                )
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        }
    }

    /** 搜索接口偶发返回空 (服务端行为), 空结果时重试 */
    private fun searchWithRetry(keyword: String, limit: Int): List<Candidate>? {
        repeat(SEARCH_RETRIES) {
            val response = getJson(
                "$SEARCH_URL?ver=1&man=yes&client=pc&pagesize=${limit.coerceAtMost(10)}" +
                    "&keyword=${encode(keyword)}"
            ) ?: return null // 网络异常直接放弃, 不重试 (尊重超时预算)

            val parsed = runCatching {
                json.decodeFromString<SearchResponse>(response)
            }.getOrNull() ?: return null

            if (parsed.candidates.isNotEmpty()) return parsed.candidates

            // 空结果: 50% 概率是服务端抖动 → 短暂等待后重试
            if (it < SEARCH_RETRIES - 1) Thread.sleep(400)
        }
        return emptyList()
    }

    private fun downloadKrc(candidate: Candidate): List<RichLyricLine>? {
        val response = getJson(
            "$DOWNLOAD_URL?ver=1&man=yes&client=pc" +
                "&id=${candidate.id}&accesskey=${candidate.accesskey}&fmt=krc"
        ) ?: return null

        val parsed = runCatching {
            json.decodeFromString<DownloadResponse>(response)
        }.getOrNull() ?: return null

        val content = parsed.content ?: return null
        val raw = runCatching { Base64.getDecoder().decode(content) }.getOrNull() ?: return null

        val plain = KrcDecryptor.decrypt(raw) ?: return null
        val document = KrcParser.parse(plain)
        return document.richLyricLines
    }

    /** KRC 没有 (或解密失败) 时退而求其次拿整行 LRC */
    private fun downloadLrc(candidate: Candidate): List<RichLyricLine>? {
        val response = getJson(
            "$DOWNLOAD_URL?ver=1&man=yes&client=pc" +
                "&id=${candidate.id}&accesskey=${candidate.accesskey}&fmt=lrc"
        ) ?: return null

        val parsed = runCatching {
            json.decodeFromString<DownloadResponse>(response)
        }.getOrNull() ?: return null

        val content = parsed.content ?: return null
        val lrc = runCatching {
            String(Base64.getDecoder().decode(content), StandardCharsets.UTF_8)
        }.getOrNull() ?: return null

        // 借用 lrckit 解析 (KRC 行格式 [t,dur] 与 LRC [mm:ss.xx] 不同, 不能混用)
        val lines = io.github.proify.lrckit.LrcParser.parse(lrc).lines
        return lines.map {
            RichLyricLine(
                begin = it.begin,
                end = it.end,
                duration = it.duration,
                text = it.text,
            )
        }
    }

    private fun getJson(urlString: String): String? {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URI.create(urlString).toURL().openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = TIMEOUT
                readTimeout = TIMEOUT
                setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                doInput = true
            }
            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                connection.inputStream.bufferedReader().use { it.readText() }
            } else null
        } catch (e: Exception) {
            e.printStackTrace()
            null
        } finally {
            connection?.disconnect()
        }
    }

    private fun encode(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.name())
}
