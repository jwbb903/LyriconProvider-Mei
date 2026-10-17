/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.meiprovider.app.ui

import android.content.SharedPreferences
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import io.github.proify.lyricon.meiprovider.R
import io.github.proify.lyricon.meiprovider.bridge.Configs
import io.github.proify.lyricon.meiprovider.bridge.SourceMode
import io.github.proify.lyricon.meiprovider.databinding.ActivitySettingsBinding

/**
 * 设置页。
 *
 * 迁移到 libxposed 后不再使用 YukiHookAPI 的 `prefs()` / `dataChannel()`：
 * 配置改走 libxposed **remote preferences**，模块进程与 Mei 宿主进程共用同一份存储，
 * 宿主侧注册的 `OnSharedPreferenceChangeListener` 会自动收到变更并立即生效。
 *
 * 若框架服务不可用（模块未激活），退化为本 App 的私有 SharedPreferences，
 * 设置仍可保存，激活后由 remote preferences 接管。
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding

    @Volatile
    private var preferences: SharedPreferences? = null

    /** 程序化设置控件状态时抑制监听器，避免回写 */
    private var loading = false

    private val serviceListener = object : XposedServiceHelper.OnServiceListener {
        override fun onServiceBind(service: XposedService) {
            Log.i(TAG, "Xposed service bound: ${service.frameworkName} ${service.frameworkVersion}")
            preferences = runCatching {
                service.getRemotePreferences(Configs.PREF_GROUP)
            }.onFailure {
                Log.e(TAG, "getRemotePreferences failed", it)
            }.getOrNull()
            loadValues()
        }

        override fun onServiceDied(service: XposedService) {
            Log.w(TAG, "Xposed service died")
            preferences = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        XposedServiceHelper.registerListener(serviceListener)

        initViews()
        loadValues()
    }

    private fun prefs(): SharedPreferences =
        preferences ?: getSharedPreferences(Configs.PREF_GROUP, MODE_PRIVATE)

    private fun initViews() {
        setSupportActionBar(binding.toolbar)

        binding.switchTranslation.setOnCheckedChangeListener { _, isChecked ->
            store(Configs.DISPLAY_TRANSLATION, isChecked)
        }
        binding.switchNeteaseEnrich.setOnCheckedChangeListener { _, isChecked ->
            store(Configs.NETEASE_ENRICH, isChecked)
        }
        binding.switchAmllDb.setOnCheckedChangeListener { _, isChecked ->
            store(Configs.AMLL_DB, isChecked)
        }
        binding.switchNetSearch.setOnCheckedChangeListener { _, isChecked ->
            store(Configs.NET_SEARCH, isChecked)
        }

        binding.sliderLyricOffset.addOnChangeListener { _, value, fromUser ->
            binding.textLyricOffset.text = formatOffset(value.toInt())
            if (fromUser) store(Configs.LYRIC_OFFSET_MS, value.toInt())
        }

        binding.radioSource.setOnCheckedChangeListener { _, checkedId ->
            val mode = when (checkedId) {
                R.id.radioMei -> SourceMode.MEI
                R.id.radioCloud -> SourceMode.CLOUD
                else -> SourceMode.AUTO
            }
            store(Configs.SOURCE_MODE, mode)
        }
    }

    private fun loadValues() {
        loading = true
        val store = prefs()

        binding.switchTranslation.isChecked =
            Configs.displayTranslation(store)
        binding.switchNeteaseEnrich.isChecked =
            Configs.neteaseEnrich(store)
        binding.switchAmllDb.isChecked =
            Configs.amllDb(store)
        binding.switchNetSearch.isChecked =
            Configs.netSearch(store)
        binding.sliderLyricOffset.value =
            Configs.lyricOffsetMs(store).toFloat()
        binding.textLyricOffset.text =
            formatOffset(Configs.lyricOffsetMs(store))

        when (Configs.sourceMode(store)) {
            SourceMode.MEI -> binding.radioMei.isChecked = true
            SourceMode.CLOUD -> binding.radioCloud.isChecked = true
            else -> binding.radioAuto.isChecked = true
        }
        loading = false
    }

    private fun store(key: String, value: Any) {
        if (loading) return
        Log.d(TAG, "Setting $key -> $value")
        prefs().edit().apply {
            when (value) {
                is Boolean -> putBoolean(key, value)
                is String -> putString(key, value)
                is Int -> putInt(key, value)
                else -> return
            }
            commit()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        binding.radioSource.setOnCheckedChangeListener(null)
    }

    private fun formatOffset(ms: Int): String =
        if (ms >= 0) "+${ms} ms" else "$ms ms"

    private companion object {
        const val TAG = "MeiProvider-Settings"
    }
}
