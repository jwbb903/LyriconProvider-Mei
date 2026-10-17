/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.meiprovider.mei

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import io.github.proify.lyricon.meiprovider.util.Logs
import io.github.proify.lyricon.meiprovider.util.Constants

/**
 * 直接读写 Mei 的 Room 数据库 `app_database` 里的 `cached_lyric` 表。
 *
 * 为什么这样做: Mei 的 release 包被 R8 全量混淆, 类名/方法名/DAO 接口都不可依赖,
 * 但 **Room 生成的表名与列名是字符串常量**, 混淆不会改变。
 * 只要拿到 [SQLiteDatabase] 实例 (hook `SQLiteDatabase.openDatabase` 即可), 就能直接查询。
 */
object MeiLyricDao {

    private const val DB_NAME = "app_database"

    @Volatile
    private var database: SQLiteDatabase? = null

    @Volatile
    private var resolvedColumns: Map<String, String>? = null

    /** 歌词到达时的回调 (用于在播放中动态刷新) */
    @Volatile
    var onLyricCaptured: ((RawLyric) -> Unit)? = null

    fun attach(db: SQLiteDatabase?) {
        if (db == null) return
        val path = runCatching { db.path }.getOrNull() ?: return
        if (!path.contains(DB_NAME)) return
        if (database === db) return
        database = db
        resolvedColumns = null
        Logs.info("Attached Mei database: $path")
    }

    fun isAttached(): Boolean = database != null

    /**
     * 从 `PRAGMA table_info` 解析出真实列名。
     */
    private fun columns(): Map<String, String>? {
        resolvedColumns?.let { return it }
        val db = database ?: return null

        val names = runCatching {
            db.rawQuery("PRAGMA table_info(${Constants.LYRIC_TABLE})", null).use { cursor ->
                val nameIndex = cursor.getColumnIndex("name")
                if (nameIndex < 0) return@runCatching emptyList<String>()
                buildList {
                    while (cursor.moveToNext()) add(cursor.getString(nameIndex))
                }
            }
        }.onFailure {
            Logs.error("PRAGMA table_info failed", it)
        }.getOrDefault(emptyList())

        if (names.isEmpty()) return null

        fun pick(candidates: List<String>): String? =
            candidates.firstOrNull { candidate -> names.any { it.equals(candidate, ignoreCase = true) } }
                ?: names.firstOrNull { name -> candidates.any { name.contains(it, ignoreCase = true) } }

        val resolved = buildMap {
            pick(listOf(Constants.COLUMN_SONG_ID))?.let { put(KEY_SONG_ID, it) }
            pick(Constants.LYRIC_CONTENT_COLUMNS)?.let { put(KEY_CONTENT, it) }
            pick(Constants.LYRIC_TRANSLATION_COLUMNS)?.let { put(KEY_TRANSLATION, it) }
            pick(Constants.LYRIC_TYPE_COLUMNS)?.let { put(KEY_TYPE, it) }
        }

        Logs.info("cached_lyric columns: $names -> $resolved")
        resolvedColumns = resolved
        return resolved
    }

    /**
     * 按 songId 查询缓存歌词。
     */
    fun query(songId: String): RawLyric? {
        val db = database ?: return null
        if (songId.isBlank()) return null
        val cols = columns() ?: return null
        val idColumn = cols[KEY_SONG_ID] ?: return null

        return runCatching {
            db.rawQuery(
                "SELECT * FROM ${Constants.LYRIC_TABLE} WHERE $idColumn = ? LIMIT 1",
                arrayOf(songId),
            ).use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                readFromCursor(cursor, cols, songId)
            }
        }.onFailure {
            Logs.error("Query cached_lyric failed (songId=$songId)", e = it)
        }.getOrNull()
    }

    /**
     * 从 [ContentValues] (Room 写入) 提取歌词。
     */
    fun captureFromValues(table: String?, values: ContentValues?) {
        if (table == null || values == null) return
        if (!table.contains(Constants.LYRIC_TABLE)) return
        val cols = columns() ?: return

        val songId = cols[KEY_SONG_ID]?.let { runCatching { values.getAsString(it) }.getOrNull() } ?: return
        val raw = RawLyric(
            songId = songId,
            content = cols[KEY_CONTENT]?.let { runCatching { values.getAsString(it) }.getOrNull() },
            translation = cols[KEY_TRANSLATION]?.let { runCatching { values.getAsString(it) }.getOrNull() },
            parserType = cols[KEY_TYPE]?.let { runCatching { values.getAsString(it) }.getOrNull() },
        )
        if (raw.isUsable) {
            Logs.debug("Captured lyric from write: songId=$songId")
            onLyricCaptured?.invoke(raw)
        }
    }

    /**
     * 从 [Cursor] 提取歌词 (拦截 Room 读取缓存)。
     */
    fun captureFromCursor(cursor: Cursor?) {
        if (cursor == null || cursor.isClosed) return
        val cols = columns() ?: return
        val position = cursor.position
        runCatching {
            if (cursor.moveToFirst()) {
                val raw = readFromCursor(cursor, cols, null) ?: return@runCatching
                if (raw.isUsable) onLyricCaptured?.invoke(raw)
            }
        }.onFailure {
            Logs.error("captureFromCursor failed", it)
        }
        runCatching { cursor.moveToPosition(position) }
    }

    private fun readFromCursor(
        cursor: Cursor,
        cols: Map<String, String>,
        fallbackId: String?,
    ): RawLyric? {
        fun string(column: String?): String? {
            if (column == null) return null
            val index = cursor.getColumnIndex(column)
            if (index < 0 || cursor.isNull(index)) return null
            return runCatching { cursor.getString(index) }.getOrNull()
        }

        val songId = string(cols[KEY_SONG_ID]) ?: fallbackId ?: return null
        return RawLyric(
            songId = songId,
            content = string(cols[KEY_CONTENT]),
            translation = string(cols[KEY_TRANSLATION]),
            parserType = string(cols[KEY_TYPE]),
        )
    }

    private const val KEY_SONG_ID = "songId"
    private const val KEY_CONTENT = "content"
    private const val KEY_TRANSLATION = "translation"
    private const val KEY_TYPE = "parserType"
}
