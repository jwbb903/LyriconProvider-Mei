/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.meiprovider.xposed

import android.util.Log
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import io.github.proify.lyricon.meiprovider.util.Constants
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 基于 **libxposed API 102** 的模块入口。
 *
 * 旧版走的是 YukiHookAPI + `de.robv.android.xposed:api` (legacy XposedBridge) +
 * `xposedsharedprefs` (New XSharedPreferences)。这三样在 LSPosed 2.x 上都已被标记废弃,
 * 且 New XSharedPreferences 计划在 2.2.0 移除, 所以整体迁移到现代 libxposed API。
 *
 * 元数据改由 `resources/META-INF/xposed/module.prop` 提供:
 * ```
 * minApiVersion=101
 * targetApiVersion=102
 * staticScope=true
 * exceptionMode=protective
 * ```
 * 入口类由 `resources/META-INF/xposed/java_init.list` 指定。
 */
class HookEntry : XposedModule() {

    private val logger = ModuleLogger(this)
    private val hooksInstalled = AtomicBoolean(false)

    @Volatile
    private var processName: String? = null

    @Suppress("unused")
    private var provider: MeiProvider? = null

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        processName = param.processName
        logger.info(
            "Module loaded: process=${param.processName}, " +
                "framework=$frameworkName $frameworkVersion ($frameworkVersionCode), " +
                "api=$apiVersion, systemServer=${param.isSystemServer}"
        )
    }

    override fun onPackageReady(param: PackageReadyParam) {
        val loadedProcess = processName
        if (loadedProcess == null) {
            logger.warn("Ignoring ${param.packageName}: onModuleLoaded was not received")
            return
        }

        if (param.packageName != Constants.MEI_PACKAGE) {
            logger.debug("Ignoring ${param.packageName} in $loadedProcess")
            return
        }

        if (!hooksInstalled.compareAndSet(false, true)) {
            logger.debug("Hooks already installed in $loadedProcess")
            return
        }

        val instance = MeiProvider(
            module = this,
            logger = logger,
            processName = loadedProcess,
            classLoader = param.classLoader
        )
        provider = instance
        instance.installHooks()
    }
}

/** Routes all module diagnostics through the framework logger. */
internal class ModuleLogger(
    private val module: XposedModule,
    private val tag: String = Constants.LOG_TAG
) {
    fun debug(message: String) = module.log(Log.DEBUG, tag, message)

    fun info(message: String) = module.log(Log.INFO, tag, message)

    fun warn(message: String) = module.log(Log.WARN, tag, message)

    fun error(message: String, throwable: Throwable? = null) {
        if (throwable == null) {
            module.log(Log.ERROR, tag, message)
        } else {
            module.log(Log.ERROR, tag, message, throwable)
        }
    }
}
