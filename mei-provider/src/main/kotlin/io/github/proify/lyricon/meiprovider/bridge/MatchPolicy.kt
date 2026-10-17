/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.meiprovider.bridge

/**
 * 歌词匹配严格度。
 *
 * 用户明确选择「宁可没歌词, 也不要错歌词」—— 云端模糊搜索必须先过
 * 标题/歌手校验, 校验不过就回退本地, 绝不硬推一份别人的歌词。
 */
object MatchPolicy {

    /** 归一化: 去大小写 / 空白 / 常见标点, 再砍掉 feat / remaster 等后缀 */
    fun normalize(value: String): String {
        val base = value
            .lowercase()
            .replace(Regex("""[\s\-_()（）\[\]【】{}<>·•・,，.。!！?？:：;；'"“”‘’~～*]"""), "")
        // 砍掉版本后缀: (Live) / (DJ版) / feat. xxx / remastered 等
        val cut = listOf(
            "feat", "featuring", "ft",
            "remaster", "remastered", "remix", "live",
            "cover", "卡拉ok", "伴奏", "instrumental", "纯音乐",
        ).firstOrNull { base.contains(it) } ?: return base
        return base.substring(0, base.indexOf(cut)).ifBlank { base }
    }

    /** 标题是否算「对得上」: 完全相等, 或一方是另一方的前缀 (去掉后缀后) */
    private fun titleMatches(candidate: String, target: String): Boolean {
        if (candidate.isEmpty() || target.isEmpty()) return false
        if (candidate == target) return true
        // 短的一方至少 4 个字符, 防止 "a" containedIn "abcd" 这种误命中
        val short = minOf(candidate.length, target.length)
        if (short < 4) return false
        return candidate.startsWith(target) || target.startsWith(candidate)
    }

    /** 歌手是否算「对得上」: 一方包含另一方 ( collaborations 常见 "A & B" ) */
    private fun artistMatches(candidate: String, target: String): Boolean {
        if (candidate.isEmpty() || target.isEmpty()) return false
        if (candidate == target) return true
        val short = minOf(candidate.length, target.length)
        if (short < 3) return false
        return candidate.contains(target) || target.contains(candidate)
    }

    /**
     * 严格校验: 标题必须对上, 且 (有歌手信息时) 歌手必须对上。
     * 标题 + 歌手都对不上的一律拒绝。
     */
    fun accept(
        candidateTitle: String?,
        candidateArtist: String?,
        targetTitle: String,
        targetArtist: String,
    ): Boolean {
        val ct = normalize(candidateTitle.orEmpty())
        val ca = normalize(candidateArtist.orEmpty())
        val tt = normalize(targetTitle)
        val ta = normalize(targetArtist)

        val titleOk = titleMatches(ct, tt)
        // 目标没有歌手信息 (单字节标题、电台等), 只要标题对上即可
        val artistOk = ta.isEmpty() || ca.isEmpty() || artistMatches(ca, ta)
        return titleOk && artistOk
    }
}
