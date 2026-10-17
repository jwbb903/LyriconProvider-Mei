/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.meiprovider.util

/**
 * 全局常量。
 *
 * Mei 的 release 包被 R8 全量混淆, 类名/方法名都不可依赖;
 * 但下面这些是**字符串常量**、**Room 生成的 SQL**、**framework 类**,
 * 混淆不会改变它们 —— 这是我们唯一的稳定锚点。
 */
object Constants {

    const val LOG_TAG = "LyricProvider-Mei"

    /** 插件自身的 Provider 包名, 用于 Lyricon 识别 */
    const val PROVIDER_PACKAGE_NAME = "io.github.proify.lyricon.meiprovider"

    /** 目标应用包名 */
    const val MEI_PACKAGE = "com.ljyh.mei"

    /** Room 生成的建表 SQL 里必然包含的表名 */
    const val LYRIC_TABLE = "cached_lyric"

    /** 缓存歌词表的主键列 */
    const val COLUMN_SONG_ID = "songId"

    /** 不同版本可能用不同列名, 运行时逐个探测 */
    val LYRIC_CONTENT_COLUMNS = listOf("content", "lyric", "lyrics", "rawLyric")
    val LYRIC_TRANSLATION_COLUMNS = listOf("translation", "transLyric")
    val LYRIC_TYPE_COLUMNS = listOf("parserType", "type", "format")

    /** Mei 的 SVG 图标 */
    const val ICON = """
        <svg width="24" height="24" viewBox="0 0 24 24" xmlns="http://www.w3.org/2000/svg">
            <path d="M12,2C6.48,2,2,6.48,2,12s4.48,10,10,10s10-4.48,10-10S17.52,2,12,2z M10,16.5v-9l6,4.5L10,16.5z" fill="#FFFFFF"/>
        </svg>
    """
}
