/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.meiprovider.mei

import io.github.proify.lyricon.lyric.model.LyricLine
import io.github.proify.lyricon.lyric.model.LyricWord
import io.github.proify.lyricon.lyric.model.RichLyricLine
import io.github.proify.lyricon.meiprovider.net.NetEaseLyric

/**
 * 从 Mei 的 `cached_lyric` 表里捕获到的一行原始歌词。
 *
 * Mei 会把下载到的原始歌词文本原样持久化, 并用 [parserType] 标注格式。
 */
data class RawLyric(
    val songId: String,
    val content: String?,
    val translation: String? = null,
    val parserType: String? = null,
) {
    val isUsable: Boolean get() = !content.isNullOrBlank()

    /**
     * 判断还缺哪些内容 (用于决定要不要去网易云接口补全)。
     */
    fun missing(needTranslation: Boolean): Boolean =
        content.isNullOrBlank() ||
            (needTranslation && translation.isNullOrBlank())
}

/**
 * 用网易云接口拿到的文本补齐 Mei 缓存里缺失的部分。
 *
 * 规则: Mei 已有的内容优先保留; 但若 Mei 缓存的是朴素 LRC 而接口能提供逐字 YRC,
 * 则整体切换到逐字那一套 (歌词 / 翻译必须成套, 否则时间轴对不上)。
 */
fun RawLyric.enrichedWith(extra: NetEaseLyric?): RawLyric {
    if (extra == null || !extra.isUsable) return this

    val ownContent = content?.takeIf { it.isNotBlank() }
    val ownIsVerbatim = ownContent != null && looksLikeVerbatim(ownContent, parserType)

    // Mei 没有歌词, 或 Mei 只有朴素 LRC 而接口有逐字 YRC -> 用接口那一套
    if (ownContent == null || (!ownIsVerbatim && extra.useVerbatim)) {
        return copy(
            content = extra.bestContent,
            translation = extra.bestTranslation,
            parserType = extra.bestParserType,
        )
    }

    // Mei 已有逐字歌词 -> 只补缺失的翻译
    return copy(
        translation = translation?.takeIf { it.isNotBlank() } ?: extra.bestTranslation,
    )
}

private val YRC_LIKE = Regex("""^\[\d+,\d+]""", RegexOption.MULTILINE)
private val QRC_LIKE = Regex("""^\[\d+,\d+][<\d]""", RegexOption.MULTILINE)

/** 判断文本是否本身就是逐字歌词 (YRC / QRC / TTML) */
fun looksLikeVerbatim(content: String, parserType: String? = null): Boolean {
    val type = parserType?.uppercase().orEmpty()
    if (type.contains("YRC") || type.contains("QRC") || type.contains("TTML")) return true
    if (content.contains("<tt ") || content.contains("<p ")) return true
    return YRC_LIKE.containsMatchIn(content) || QRC_LIKE.containsMatchIn(content)
}

/**
 * [LyricLine] (无逐字扩展) -> [RichLyricLine]
 */
fun LyricLine.toRich(): RichLyricLine = RichLyricLine(
    begin = begin,
    end = end,
    duration = duration,
    isAlignedRight = isAlignedRight,
    metadata = metadata,
    text = text,
    words = words,
)

/**
 * [LyricWord] 的时间信息平移, 用于把翻译轨的字对齐到主轨。
 */
fun LyricWord.shifted(offset: Long): LyricWord = LyricWord(
    begin = begin + offset,
    end = end + offset,
    duration = duration,
    text = text,
    metadata = metadata,
)

/**
 * 把纯文本歌词按 `\n` 拆成逐行歌词 (无时间轴时的兜底)。
 */
fun plainTextToLines(text: String, duration: Long = 0L): List<RichLyricLine> {
    val rows = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
    if (rows.isEmpty()) return emptyList()
    val span = if (duration > 0) duration / rows.size else 5_000L
    return rows.mapIndexed { index, row ->
        RichLyricLine(
            begin = index * span,
            end = (index + 1) * span,
            duration = span,
            text = row,
        )
    }
}
