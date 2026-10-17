/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.meiprovider.util

import android.util.Log

/**
 * 模块内部轻量日志。
 *
 * 迁移到 libxposed 后不再依赖 YukiHookAPI 的 `YLog`；hook 相关日志走
 * [io.github.proify.lyricon.meiprovider.xposed.ModuleLogger]（框架日志），
 * 这里只服务不持有 module 引用的工具类。
 */
internal object Logs {

    fun debug(message: String) = Log.d(Constants.LOG_TAG, message)

    fun info(message: String) = Log.i(Constants.LOG_TAG, message)

    fun warn(message: String) = Log.w(Constants.LOG_TAG, message)

    fun error(message: String, e: Throwable? = null) {
        if (e == null) {
            Log.e(Constants.LOG_TAG, message)
        } else {
            Log.e(Constants.LOG_TAG, message, e)
        }
    }
}
