/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.meiprovider.mei

import io.github.proify.lyricon.meiprovider.util.Logs
import io.github.proify.lrckit.EnhanceLrcParser
import io.github.proify.lyricon.meiprovider.util.Constants
import io.github.proify.lyricon.lyric.model.RichLyricLine
import io.github.proify.lyricon.yrckit.YrcParser
import io.github.proify.qrckit.QrcParser

/**
 * 把 Mei 缓存 / 网易云接口拿到的**原始歌词文本**转换成 Lyricon 的 [RichLyricLine]。
 *
 * 因为拿到的是原始文本, 这里直接用 LyricProvider 自带的 lrckit / yrckit / qrckit 解析,
 * 只有 TTML 需要自己解析。
 */
object LyricParserBridge {

    private const val TYPE_TTML = "TTML"
    private const val TYPE_YRC = "YRC"
    private const val TYPE_QRC = "QRC"
    private const val TYPE_LRC = "LRC"

    private val YRC_PATTERN = Regex("""^\[\d+,\d+]\(\d+,\d+,\d+\)""", RegexOption.MULTILINE)
    private val QRC_PATTERN = Regex("""^\[\d+,\d+][<\d]""", RegexOption.MULTILINE)
    private val LRC_TIME_PATTERN = Regex("""\[(\d{1,2}):(\d{1,2})(?:[.:](\d{1,3}))?]""")

    fun parse(
        raw: RawLyric,
        duration: Long = 0L,
        translationEnabled: Boolean = true,
    ): List<RichLyricLine> {
        val content = raw.content?.trim().orEmpty()
        if (content.isEmpty()) return emptyList()

        val type = detectType(content, raw.parserType)
        val lines = parseContent(content, type, duration)

        if (lines.isEmpty()) {
            Logs.warn("No timed line parsed (type=$type, len=${content.length}), fallback to plain text")
            return plainTextToLines(content, duration)
        }

        Logs.debug("Parsed $type -> ${lines.size} lines (trans=${raw.translation?.length ?: 0})")

        return applyExtras(lines, raw, duration, translationEnabled, type)
    }

    /** 根据 `parserType` + 文本特征判断格式 */
    private fun detectType(content: String, parserType: String?): String {
        val declared = parserType?.uppercase().orEmpty()
        if (declared.contains(TYPE_TTML) || TtmlParser.canParse(content)) return TYPE_TTML
        if (declared.contains(TYPE_YRC) || YRC_PATTERN.containsMatchIn(content)) return TYPE_YRC
        if (declared.contains(TYPE_QRC) || QRC_PATTERN.containsMatchIn(content)) return TYPE_QRC
        return TYPE_LRC
    }

    private fun parseContent(content: String, type: String, duration: Long): List<RichLyricLine> {
        when (type) {
            TYPE_TTML -> return runCatching { TtmlParser.parse(content) }
                .onFailure { Logs.error("TTML parse failed", it) }
                .getOrDefault(emptyList())

            TYPE_YRC -> runCatching { YrcParser.parse(content).map { it.toRich() } }
                .onFailure { Logs.error("YRC parse failed", it) }
                .getOrDefault(emptyList())
                .let { if (it.isNotEmpty()) return it }

            TYPE_QRC -> runCatching { QrcParser.parseLyric(content).second.map { it.toRich() } }
                .onFailure { Logs.error("QRC parse failed", it) }
                .getOrDefault(emptyList())
                .let { if (it.isNotEmpty()) return it }
        }

        return runCatching { EnhanceLrcParser.parse(content, duration).lines }
            .onFailure { Logs.error("LRC parse failed", it) }
            .getOrDefault(emptyList())
    }

    /**
     * 合并独立存放的翻译文本, 并应用显示开关。
     */
    private fun applyExtras(
        lines: List<RichLyricLine>,
        raw: RawLyric,
        duration: Long,
        translationEnabled: Boolean,
        type: String,
    ): List<RichLyricLine> {
        // 逐字歌词的翻译也是逐字格式 (ytlrc), 要用 YRC 解析器
        val verbatimExtras = type == TYPE_YRC || type == TYPE_QRC

        val translationMap =
            if (translationEnabled) parseExtraLines(raw.translation, duration, verbatimExtras) else emptyMap()

        val hasExtra = translationMap.isNotEmpty()
        val translationHits = translationMap.size

        return lines.map { line ->
            var result = line

            if (translationEnabled) {
                if (result.translation.isNullOrBlank()) {
                    translationMap[result.begin]?.let { result = result.copy(translation = it) }
                }
            } else {
                result = result.copy(translation = null, translationWords = null)
            }
            result
        }.also {
            if (hasExtra) {
                Logs.debug("Extras merged: translation=$translationHits (verbatim=$verbatimExtras)")
            } else if (translationEnabled) {
                Logs.warn("No translation available (transLen=${raw.translation?.length ?: 0} verbatim=$verbatimExtras)")
            }
        }
    }

    /**
     * 把独立的翻译 / 罗马音文本解析成 `时间 -> 文本` 映射。
     *
     * 先按逐字格式 (YRC) 解析, 失败再按朴素 LRC 解析, 最后退化到正则。
     */
    private fun parseExtraLines(text: String?, duration: Long, verbatim: Boolean): Map<Long, String> {
        val content = text?.trim().orEmpty()
        if (content.isEmpty()) return emptyMap()

        val map = LinkedHashMap<Long, String>()

        if (verbatim || YRC_PATTERN.containsMatchIn(content)) {
            runCatching { YrcParser.parse(content) }.getOrDefault(emptyList()).forEach { line ->
                line.text?.trim()?.takeIf { it.isNotEmpty() }?.let { map[line.begin] = it }
            }
        }

        if (map.isEmpty()) {
            runCatching { EnhanceLrcParser.parse(content, duration).lines }
                .getOrDefault(emptyList())
                .forEach { line ->
                    line.text?.trim()?.takeIf { it.isNotEmpty() }?.let { map[line.begin] = it }
                }
        }

        if (map.isEmpty()) {
            content.lineSequence().forEach { row ->
                val matches = LRC_TIME_PATTERN.findAll(row).toList()
                if (matches.isEmpty()) return@forEach
                val value = row.substring(matches.last().range.last + 1).trim()
                if (value.isEmpty()) return@forEach
                matches.forEach { m ->
                    val minutes = m.groupValues[1].toLongOrNull() ?: 0L
                    val seconds = m.groupValues[2].toLongOrNull() ?: 0L
                    val fraction = m.groupValues[3].padEnd(3, '0').take(3).toLongOrNull() ?: 0L
                    map[minutes * 60_000L + seconds * 1000L + fraction] = value
                }
            }
        }

        return map
    }
}
