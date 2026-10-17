/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.meiprovider.lyric

import io.github.proify.lyricon.lyric.model.RichLyricLine

/**
 * 一份候选歌词 —— 不管是来自 Mei 缓存、网易云接口还是云端匹配，
 * 都先转成这个统一模型再比质量。
 *
 * 之所以要统一：之前的逻辑是「本地有就用，没有才去云端」，
 * 结果本地只有整行 LRC 时也会直接采用，永远不会去拿逐字歌词。
 * 用户要的是**优先显示有逐字的**，所以必须把各来源放在一起比。
 */
data class LyricCandidate(
    val lines: List<RichLyricLine>,
    /** 来源名，仅用于日志 */
    val source: String,
) {

    val isEmpty: Boolean get() = lines.isEmpty()

    /**
     * 带字级时间轴的行占比 (0f..1f)。
     *
     * 这是判断「有没有逐字歌词」的核心指标。不用 `any { it.words }` 是因为
     * 有些来源只在副歌等少量行上给了字级时间轴，用占比能避免误判。
     */
    val verbatimRatio: Float
        get() = if (lines.isEmpty()) {
            0f
        } else {
            lines.count { !it.words.isNullOrEmpty() }.toFloat() / lines.size
        }

    /** 是否算「有逐字歌词」：至少一半的行带字级时间轴 */
    val isVerbatim: Boolean get() = verbatimRatio >= VERBATIM_THRESHOLD

    val hasTranslation: Boolean get() = lines.any { !it.translation.isNullOrBlank() }

    /**
     * 质量分。
     *
     * **逐字占比是绝对主导项**（权重 1000）—— 用户明确要求优先显示逐字歌词，
     * 所以宁可要一份没翻译的逐字歌词，也不要一份带翻译的整行歌词。
     * 翻译只在逐字程度相同时才用来分胜负。
     */
    val score: Float
        get() {
            if (lines.isEmpty()) return 0f
            var value = verbatimRatio * VERBATIM_WEIGHT
            if (hasTranslation) value += TRANSLATION_BONUS
            // 行数只做微弱的覆盖度加分，避免长歌词仅因为行多就压过逐字
            value += lines.size.coerceAtMost(MAX_LINE_BONUS) * LINE_BONUS
            return value
        }

    fun describe(): String =
        "$source(${lines.size}行, 逐字${(verbatimRatio * 100).toInt()}%" +
            (if (hasTranslation) ", 翻译" else "") + ")"

    private companion object {
        const val VERBATIM_THRESHOLD = 0.5f
        const val VERBATIM_WEIGHT = 1000f
        const val TRANSLATION_BONUS = 30f
        const val MAX_LINE_BONUS = 200
        const val LINE_BONUS = 0.1f
    }
}

/**
 * 从多份候选里挑质量最高的。
 *
 * 同分时返回**靠前**的那一份（`maxByOrNull` 取首个最大值），
 * 所以调用方按「更可信的来源排前面」的顺序传进来即可。
 */
fun List<LyricCandidate>.best(): LyricCandidate? =
    filter { !it.isEmpty }.maxByOrNull { it.score }
