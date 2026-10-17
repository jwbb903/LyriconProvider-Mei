/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.meiprovider.bridge

import android.content.SharedPreferences

/**
 * 全局配置常量。
 *
 * 配置通过 libxposed 的 **remote preferences** 在「模块 App 进程」和「Mei 宿主进程」之间共享：
 * - 模块 App 侧：`XposedService.getRemotePreferences([PREF_GROUP])`
 * - 宿主 hook 侧：`XposedInterface.getRemotePreferences([PREF_GROUP])`
 *
 * 两侧必须使用同一个 [PREF_GROUP]。
 */
object Configs {

    /** remote preferences 分组名（两侧必须一致） */
    const val PREF_GROUP = "mei_provider"

    /** 是否显示翻译 */
    const val DISPLAY_TRANSLATION = "display_translation"

    /**
     * 歌词来源:
     * - [SourceMode.AUTO]  优先 Mei 缓存, 取不到回退云端
     * - [SourceMode.MEI]   只用 Mei 缓存
     * - [SourceMode.CLOUD] 只用云端匹配
     */
    const val SOURCE_MODE = "source_mode"

    /** 云端匹配时是否允许联网搜索 */
    const val NET_SEARCH = "net_search"

    /**
     * 是否用网易云接口补全歌词 / 翻译。
     *
     * Mei 的 `cached_lyric` 表里逐字 (YRC) 歌词不落库,
     * 所以开启后才能拿到逐字歌词, 中文歌也才有歌词。
     */
    const val NETEASE_ENRICH = "netease_enrich"

    /**
     * 歌词时间轴偏移 (毫秒, -2000..2000)。
     * 正值 = 歌词提前, 负值 = 歌词延后。
     * 兜底音频链路固定延迟导致的整体不同步。
     */
    const val LYRIC_OFFSET_MS = "lyric_offset_ms"

    const val DEFAULT_DISPLAY_TRANSLATION = true
    const val DEFAULT_SOURCE_MODE = SourceMode.AUTO
    const val DEFAULT_NET_SEARCH = true
    const val DEFAULT_NETEASE_ENRICH = true
    const val DEFAULT_LYRIC_OFFSET_MS = 0
    const val MIN_LYRIC_OFFSET_MS = -2000
    const val MAX_LYRIC_OFFSET_MS = 2000

    fun displayTranslation(preferences: SharedPreferences?): Boolean =
        preferences?.getBoolean(DISPLAY_TRANSLATION, DEFAULT_DISPLAY_TRANSLATION)
            ?: DEFAULT_DISPLAY_TRANSLATION

    fun netSearch(preferences: SharedPreferences?): Boolean =
        preferences?.getBoolean(NET_SEARCH, DEFAULT_NET_SEARCH)
            ?: DEFAULT_NET_SEARCH

    fun neteaseEnrich(preferences: SharedPreferences?): Boolean =
        preferences?.getBoolean(NETEASE_ENRICH, DEFAULT_NETEASE_ENRICH)
            ?: DEFAULT_NETEASE_ENRICH

    fun lyricOffsetMs(preferences: SharedPreferences?): Int =
        preferences?.getInt(LYRIC_OFFSET_MS, DEFAULT_LYRIC_OFFSET_MS)
            ?.coerceIn(MIN_LYRIC_OFFSET_MS, MAX_LYRIC_OFFSET_MS)
            ?: DEFAULT_LYRIC_OFFSET_MS

    fun sourceMode(preferences: SharedPreferences?): String {
        val raw = preferences?.getString(SOURCE_MODE, DEFAULT_SOURCE_MODE)
            ?: DEFAULT_SOURCE_MODE
        return when (raw) {
            SourceMode.MEI, SourceMode.CLOUD, SourceMode.AUTO -> raw
            else -> DEFAULT_SOURCE_MODE
        }
    }
}

object SourceMode {
    const val AUTO = "auto"
    const val MEI = "mei"
    const val CLOUD = "cloud"
}
