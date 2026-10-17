/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.meiprovider.xposed

import android.app.Application
import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteDatabase.CursorFactory
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.SystemClock
import android.os.Handler
import android.os.Looper
import io.github.libxposed.api.XposedModule
import io.github.proify.lyricon.lyric.model.Song
import io.github.proify.lyricon.meiprovider.bridge.Configs
import io.github.proify.lyricon.meiprovider.bridge.SourceMode
import io.github.proify.lyricon.meiprovider.mei.TtmlParser
import io.github.proify.lyricon.meiprovider.net.AmllDbApi
import io.github.proify.lyricon.meiprovider.cloud.CloudSearcher
import io.github.proify.lyricon.meiprovider.mei.LyricParserBridge
import io.github.proify.lyricon.meiprovider.mei.MeiLyricDao
import io.github.proify.lyricon.meiprovider.mei.RawLyric
import io.github.proify.lyricon.meiprovider.mei.enrichedWith
import io.github.proify.lyricon.meiprovider.lyric.LyricCandidate
import io.github.proify.lyricon.meiprovider.lyric.LyricRepository
import io.github.proify.lyricon.meiprovider.lyric.best
import io.github.proify.lyricon.meiprovider.net.NetEaseApi
import io.github.proify.lyricon.meiprovider.net.NetEaseLyric
import io.github.proify.lyricon.meiprovider.util.Constants
import io.github.proify.lyricon.provider.LyriconFactory
import io.github.proify.lyricon.provider.LyriconProvider
import io.github.proify.lyricon.provider.ProviderLogo
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Mei (`com.ljyh.mei`) 歌词提供者 —— libxposed API 102 实现。
 *
 * 数据来源:
 * 1. Mei 自身缓存 (Room `cached_lyric` 表) —— 原始 YRC/QRC/TTML/LRC 文本。
 * 2. 网易云公开歌词接口 —— Mei 缓存缺歌词/翻译时补全。
 * 3. 云端匹配 (QQ 音乐 / LrcLib) —— 完全不依赖 Mei 内部实现, 抗混淆兜底。
 *
 * 所有 hook 目标都是 **Android 框架类** (`android.media.session.MediaSession` /
 * `android.database.sqlite.SQLiteDatabase` / `android.app.Application`),
 * Mei 的 R8 混淆不会影响它们。
 */
internal class MeiProvider(
    private val module: XposedModule,
    private val logger: ModuleLogger,
    private val processName: String,
    private val classLoader: ClassLoader
) {

    @Volatile
    private var provider: LyriconProvider? = null
    private var preferences: SharedPreferences? = null

    @Volatile
    private var currentSongId: String? = null

    @Volatile
    private var currentTitle: String? = null

    @Volatile
    private var currentArtist: String? = null

    @Volatile
    private var currentAlbum: String? = null

    @Volatile
    private var currentDuration: Long = 0L

    @Volatile
    private var currentLyric: RawLyric? = null

    /** 框架 MediaSession 实例, 从 setMetadata 的 thisObject 拿到, 用来读播放队列 */
    @Volatile
    private var mediaSession: MediaSession? = null

    /** 当前播放队列 (从 MediaSession.getQueue() 或 setQueue hook 得到) */
    @Volatile
    private var queue: List<MediaSession.QueueItem> = emptyList()

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    private val workExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "MeiProvider-Work").apply { isDaemon = true }
    }

    /** 网易云接口结果缓存, 避免同一首歌反复请求 */
    private val neteaseCache = HashMap<String, NetEaseLyric>()
    private val neteaseMissing = HashSet<String>()
    private val neteaseLock = Any()

    /** 每次推送递增, 用于丢弃过期的异步结果 */
    private var generation = 0

    /** 最近一次播放状态, 用于合并高频回调 */
    @Volatile
    private var latestPlaybackState: PlaybackState? = null

    /**
     * [latestPlaybackState] 到达时的 [SystemClock.elapsedRealtime]。
     * 框架的 PlaybackState 是一个快照, 播放中重新推送时必须按
     * 经过的时间把进度外推, 否则会把歌词进度拉回到旧位置。
     */
    @Volatile
    private var latestPlaybackStateAt = 0L

    /** 已经推送过"空歌词"基础信息的歌曲 id, 避免重试时反复清空歌词 */
    private var baseSongId: String? = null

    /** 播放状态日志节流 */
    private var lastLoggedState = -1
    private var lastLoggedAt = 0L

    /** 播放状态推送是否已排队, 防止播放期间每次进度更新都触发一次 IPC */
    private val playbackPushScheduled = AtomicBoolean(false)

    /** provider 补建尝试次数, 避免失败时反复重试刷日志 */
    private val providerSetupAttempts = AtomicInteger(0)

    private var pushedSongId: String? = null
    private var retryCount = 0
    private var retrySongId: String? = null

    private val preferencesListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            logger.info("Settings changed signal received")
            applyProviderSettings()
            pushedSongId = null
            pushCurrentSong()
        }

    // ------------------------------------------------------------------ 入口

    fun installHooks() {
        logger.info("Installing hooks in $processName (pkg=${Constants.MEI_PACKAGE})")
        initRemotePreferences()
        hookApplication()
        hookMediaSession()
        hookMediaSessionQueue()
        hookDatabase()
        MeiLyricDao.onLyricCaptured = { raw -> onLyricCaptured(raw) }
    }

    // ------------------------------------------------------------------ 设置

    private fun initRemotePreferences() {
        runCatching {
            module.getRemotePreferences(Configs.PREF_GROUP).apply {
                registerOnSharedPreferenceChangeListener(preferencesListener)
                preferences = this
            }
        }.onFailure {
            logger.error("Unable to obtain remote preferences", it)
        }
    }

    private fun setupLyriconProvider(context: Context) {
        if (provider != null) return
        runCatching {
            provider = LyriconFactory.createProvider(
                context = context,
                providerPackageName = Constants.PROVIDER_PACKAGE_NAME,
                playerPackageName = Constants.MEI_PACKAGE,
                logo = ProviderLogo.fromSvg(Constants.ICON)
            ).apply { register() }
        }.onSuccess {
            logger.info("Lyricon provider registered")
            applyProviderSettings()
            runCatching {
                provider?.player?.setPositionUpdateInterval(POSITION_SYNC_INTERVAL_MS.toInt())
            }.onFailure { logger.error("setPositionUpdateInterval failed", it) }
            startPositionTicker()
        }.onFailure {
            logger.error("Failed to create Lyricon provider", it)
        }
    }

    private fun applyProviderSettings() {
        val player = provider?.player ?: return
        val translation = translationEnabled()
        player.setDisplayTranslation(translation)
        logger.debug("Settings applied: translation=$translation")
    }

    private fun translationEnabled(): Boolean = Configs.displayTranslation(preferences)

    private fun netSearchEnabled(): Boolean = Configs.netSearch(preferences)

    private fun neteaseEnrichEnabled(): Boolean = Configs.neteaseEnrich(preferences)

    private fun sourceMode(): String = Configs.sourceMode(preferences)

    // ---------------------------------------------------------------- 上下文

    private fun hookApplication() {
        currentApplication()?.let {
            logger.debug("Application obtained from ActivityThread")
            setupLyriconProvider(it)
            return
        }

        // 注意：不能 hook `Application.attachBaseContext`。
        // 该方法声明在 **ContextWrapper** 上, Application 只是继承;
        // `Class.getDeclaredMethod` 不会向上查找父类, 所以必然抛
        //   NoSuchMethodException: android.app.Application.attachBaseContext
        //     [class android.content.Context]
        // 一旦这里抛异常, provider 就永远建不起来, 表现为“一点歌词都没有”。
        //
        // 改用 `Application.onCreate()` —— 它确实声明在 Application 上,
        // 时机在 attachBaseContext 之后、任何 Activity/Service 之前。
        hookOne("Application.onCreate") {
            module.hook(
                Application::class.java.getDeclaredMethod("onCreate")
            ).intercept { chain ->
                val result = chain.proceed()
                runCatching {
                    (chain.thisObject as? Application)?.let { setupLyriconProvider(it) }
                }.onFailure { logger.error("Application.onCreate hook body failed: ${it.describe()}", it) }
                result
            }
        }
    }

    /** 把异常类名与消息拼进日志, 这样 LSPosed 日志单行就能定位问题 */
    private fun Throwable.describe(): String =
        "${javaClass.name}: ${message ?: "(no message)"}"

    private fun currentApplication(): Application? = runCatching {
        Class.forName("android.app.ActivityThread", false, classLoader)
            .getDeclaredMethod("currentApplication")
            .apply { isAccessible = true }
            .invoke(null) as? Application
    }.getOrNull()

    /**
     * 兜底：如果 provider 还没建起来, 就再试一次。
     *
     * 早期在 `attachBaseContext` / `onPackageReady` 里建 provider 依赖 hook 的时序,
     * 一旦那个时机错过 (框架回调顺序、宿主自定义 AppComponentFactory 等),
     * provider 会永远是 null 且**没有任何日志** —— 表现为“一点歌词都没有”。
     * 所以推送前再自查一次, 用此时必定已存在的 Application 补建。
     */
    private fun ensureProvider() {
        if (provider != null) return
        if (providerSetupAttempts.incrementAndGet() > MAX_PROVIDER_SETUP_ATTEMPTS) return
        val application = currentApplication()
        if (application == null) {
            logger.warn("Provider missing and no Application available yet")
            return
        }
        logger.info("Provider missing, retrying setup via ActivityThread")
        setupLyriconProvider(application)
    }

    // ---------------------------------------------------------------- 元数据

    private fun hookMediaSession() {
        runCatching {
            val clazz = Class.forName(
                "android.media.session.MediaSession", false, classLoader
            )

            module.hook(
                clazz.getDeclaredMethod("setMetadata", MediaMetadata::class.java)
            ).intercept { chain ->
                val result = chain.proceed()
                // 宿主代码绝对不能被我们的异常影响。
                // 这里抛出的任何异常都会从 MediaSession.setMetadata 抛回 Mei 的
                // Media3 代码, 被包装成 PlaybackException(ERROR_CODE_FAILED_RUNTIME_CHECK),
                // 表现为播放中途 "Unexpected runtime error"。所以全部吞掉。
                //
                // 而且必须**异步**处理: setMetadata 是 Media3 在 onTimelineChanged /
                // onMediaItemTransition 里调的。我们下面的处理会读远程 SharedPreferences
                // (每次一次 IPC) 甚至注册 Lyricon provider, 同步执行等于把 LSPosed
                // daemon 的 IPC 延迟直接压进宿主的播放链路。
                runCatching {
                    // chain.thisObject 就是框架 MediaSession 实例, 顺手存下来。
                    (chain.thisObject as? MediaSession)?.let { mediaSession = it }
                    val metadata = chain.getArg(0) as? MediaMetadata
                    if (metadata != null) {
                        mainHandler.post {
                            runCatching { onMetadataChanged(metadata) }
                                .onFailure { logger.error("onMetadataChanged failed", it) }
                        }
                    }
                }.onFailure { logger.error("setMetadata hook failed", it) }
                result
            }

            module.hook(
                clazz.getDeclaredMethod("setPlaybackState", PlaybackState::class.java)
            ).intercept { chain ->
                val result = chain.proceed()
                // 播放期间此方法被高频调用 (每次进度更新)。
                // 这里只写一次内存并排队, 绝不能同步做 binder IPC。
                runCatching {
                    (chain.getArg(0) as? PlaybackState)?.let {
                        latestPlaybackState = it
                        latestPlaybackStateAt = SystemClock.elapsedRealtime()
                        logPlaybackState(it)
                        schedulePlaybackStatePush()
                    }
                }.onFailure { logger.error("setPlaybackState hook failed", it) }
                result
            }
        }.onFailure {
            logger.error("Hook MediaSession failed: ${it.describe()}", it)
        }
    }

    // ------------------------------------------------------------ 播放队列

    /**
     * 监听播放队列变化。
     *
     * 队列一般在歌单加载时一次性设好, 所以这个 hook 平时很少触发。
     * Media3 会在时间线变化时同步到 legacy session, 所以 setQueue 是可靠的；
     * 但 `MediaSession` **没有** `getQueue()`, 读不回来, 只能靠 hook 捕获。
     * 兜底: 每次切歌也会再试一次 [prefetchNext]。
     */
    private fun hookMediaSessionQueue() {
        hookOne("MediaSession.setQueue") {
            val clazz = Class.forName("android.media.session.MediaSession", false, classLoader)
            module.hook(clazz.getDeclaredMethod("setQueue", List::class.java))
                .intercept { chain ->
                    val result = chain.proceed()
                    // 同样必须异步: 下面的 prefetchNext() 会读远程 SharedPreferences。
                    runCatching {
                        @Suppress("UNCHECKED_CAST")
                        val items = chain.getArg(0) as? List<MediaSession.QueueItem>
                        if (items != null) {
                            mainHandler.post {
                                runCatching { onQueueChanged(items) }
                                    .onFailure { logger.error("onQueueChanged failed", it) }
                            }
                        }
                    }.onFailure { logger.error("setQueue hook failed", it) }
                    result
                }
        }
    }

    private fun onQueueChanged(items: List<MediaSession.QueueItem>) {
        // getQueue() 每次可能返回一个新 List, 所以只按长度判断“变没变”,
        // 否则日志会被刷爆。真正防重复请求的是 LyricRepository.beginPrefetch。
        val changed = items.size != queue.size
        queue = items
        if (changed) logger.debug("Queue updated: ${items.size} items")
        prefetchNext()
    }

    /** 当前播放项在队列里的下一个 (找不到当前项就返回 null) */
    private fun nextQueueItem(): MediaSession.QueueItem? {
        val items = queue
        if (items.isEmpty()) return null

        val songId = currentSongId
        val title = currentTitle
        val index = runCatching {
            var found = items.indexOfFirst { it.description?.mediaId?.toString() == songId }
            if (found < 0 && !title.isNullOrBlank()) {
                found = items.indexOfFirst { it.description?.title?.toString() == title }
            }
            found
        }.getOrDefault(-1)

        if (index < 0) return null
        return items.getOrNull(index + 1)
    }

    // ---------------------------------------------------------------- 预取

    /**
     * 预取下一首的歌词。
     *
     * 切歌后再走「网易云接口 + 云端匹配」要等 1-2 秒网络往返, 歌词会明显慢半拍。
     * 这里提前把下一首的候选歌词拉下来, 按质量选最好的一份存进
     * [LyricRepository], 切歌时直接命中。
     *
     * 触发时机: 队列变化 + 切歌。同一首只预取一次。
     */
    private fun prefetchNext() {
        val next = nextQueueItem() ?: return
        val description = next.description ?: return
        val id = description.mediaId?.toString()
        val title = description.title?.toString()
        val artist = description.subtitle?.toString()
        val album = description.extras?.getString(MediaMetadata.METADATA_KEY_ALBUM)

        if (id.isNullOrBlank() && title.isNullOrBlank()) return

        val duration = description.extras
            ?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L

        workExecutor.execute {
            // 远程 SharedPreferences 的读取一律放工作线程 —— 每次 getBoolean
            // 都是一次到 LSPosed daemon 的 IPC, 放主线程会卡住宿主切歌。
            val translation = translationEnabled()
            val enrich = neteaseEnrichEnabled()
            val cloud = netSearchEnabled()
            // 两个联网开关都关了就没什么可预取的
            if (!enrich && !cloud) return@execute
            if (LyricRepository.cached(id, translation) != null) return@execute
            if (!LyricRepository.beginPrefetch(id, translation)) return@execute

            var local: LyricCandidate? = null

            @Suppress("NAME_SHADOWING") fun amllCandidate(id: String): LyricCandidate? {
                val lyric = runCatching { AmllDbApi.fetch(id) }
                    .onFailure { logger.warn("AmllDb prefetch failed: ${it.message}") }
                    .getOrNull() ?: return null
                val lines = runCatching { TtmlParser.parse(lyric.ttml) }
                    .onFailure { logger.warn("AmllDb parse failed: ${it.message}") }
                    .getOrDefault(emptyList())
                return lines.takeIf { it.isNotEmpty() }?.let { LyricCandidate(it, "AMLL DB") }
            }

            // AMLL DB —— 按 ID 精确命中, 永远不会错歌, 有逐字直接用
            val amll = if (enrich) id?.let { amllCandidate(it) } else null
            if (amll?.isVerbatim == true) {
                LyricRepository.store(id, translation, amll)
                LyricRepository.endPrefetch(id, translation)
                logger.debug("Prefetched next: ${amll.describe()}")
                return@execute
            }

            // 网易云接口是逐字歌词的主力
            if (enrich && NetEaseApi.isNetEaseId(id)) {
                neteaseLyric(id!!)?.let { extra ->
                    local = LyricCandidate(
                        lines = LyricParserBridge.parse(RawLyric(
                                songId = id,
                                content = extra.bestContent,
                                translation = extra.bestTranslation,
                                parserType = extra.bestParserType,
                            ),
                            duration,
                            translation,
                        ),
                        source = "网易云预取",
                    )
                }
            }

            // 已经拿到逐字了, 不用再多打一次云端请求
            if (local?.isVerbatim == true || !cloud || title.isNullOrBlank()) {
                val winner = listOfNotNull(amll?.takeIf { !it.isEmpty }, local).best()
                LyricRepository.store(id, translation, winner)
                LyricRepository.endPrefetch(id, translation)
                logger.debug("Prefetched next: ${winner?.describe() ?: "无"}")
                return@execute
            }

            CloudSearcher.search(
                trackName = title,
                artistName = artist.orEmpty(),
                albumName = album,
                onSuccess = { rich ->
                    val winner = listOfNotNull(
                        LyricCandidate(rich, "云端预取").takeIf { !it.isEmpty },
                        amll?.takeIf { !it.isEmpty },
                        local,
                    ).best()
                    LyricRepository.store(id, translation, winner)
                    LyricRepository.endPrefetch(id, translation)
                    logger.debug("Prefetched next: ${winner?.describe() ?: "无"}")
                },
                onFailure = {
                    val winner = listOfNotNull(amll?.takeIf { !it.isEmpty }, local).best()
                    LyricRepository.store(id, translation, winner)
                    LyricRepository.endPrefetch(id, translation)
                    logger.debug("Prefetched next (cloud failed): ${winner?.describe() ?: "无"}")
                }
            )
        }
    }

    /**
     * 合并高频的播放状态推送。
     *
     * 一次播放期间 [android.media.session.MediaSession.setPlaybackState] 会被调用
     * 几十次/秒; 每次都往 Lyricon 发 binder 调用会把 Mei 主线程拖住。
     * 这里只保证「工作线程空闲时推一次最新值」, 多余的调用直接丢弃。
     */
    private fun schedulePlaybackStatePush() {
        if (!playbackPushScheduled.compareAndSet(false, true)) return
        workExecutor.execute {
            playbackPushScheduled.set(false)
            val state = latestPlaybackState ?: return@execute
            // 关键: 这里推的 state.position 是「快照时刻」的旧进度, 而
            // positionTicker 推的是外推后的新进度 —— 两个通道打架会把进度往回拽,
            // 表现为歌词一跳一跳 / 越播越不同步。统一重建为外推后的进度。
            val position = estimatePositionMs()
            val rebuilt = runCatching {
                PlaybackState.Builder(state)
                    .setState(state.state, position, state.playbackSpeed)
                    .build()
            }.getOrNull() ?: state
            runCatching { provider?.player?.setPlaybackState(rebuilt) }
                .onFailure { logger.error("Push playback state failed", it) }
        }
    }

    private fun onMetadataChanged(metadata: MediaMetadata) {
        val songId = metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID)
            ?: metadata.description.mediaId?.toString()
        val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?: metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
            ?: metadata.description.title?.toString()
        val artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)
            ?: metadata.description.subtitle?.toString()
        val album = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM)

        val changed = songId != currentSongId || title != currentTitle
        currentSongId = songId
        currentTitle = title
        currentArtist = artist
        currentAlbum = album
        currentDuration = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION)

        if (changed) {
            currentLyric = null
            retryCount = 0
            retrySongId = songId
            pushedSongId = null
            logger.debug("Metadata changed: id=$songId title=$title artist=$artist")
        }
        pushCurrentSong()
        // 提前把下一首的歌词拉下来, 切歌时才能秒出
        if (changed) prefetchNext()
    }

    // --------------------------------------------------------------- 数据库

    private fun hookDatabase() {
        val clazz = runCatching {
            Class.forName("android.database.sqlite.SQLiteDatabase", false, classLoader)
        }.onFailure {
            logger.error("SQLiteDatabase class not found", it)
        }.getOrNull() ?: return

        // 拿到 Mei 的数据库句柄, 用于按 songId 主动查询缓存歌词。
        // 注意: 第 2 个参数的真实类型是 SQLiteDatabase.CursorFactory,
        // 之前写成 Any 导致 hook 永远不命中, 本地歌词路径整个失效。
        hookOne("SQLiteDatabase.openDatabase(String,CursorFactory,int)") {
            module.hook(
                clazz.getDeclaredMethod(
                    "openDatabase",
                    String::class.java,
                    CursorFactory::class.java,
                    Int::class.javaPrimitiveType
                )
            ).intercept { chain ->
                val result = chain.proceed()
                runCatching { MeiLyricDao.attach(result as? SQLiteDatabase) }
                    .onFailure { logger.error("Attach database failed", it) }
                result
            }
        }

        hookOne("SQLiteDatabase.openDatabase(String,CursorFactory,int,DatabaseErrorHandler)") {
            module.hook(
                clazz.getDeclaredMethod(
                    "openDatabase",
                    String::class.java,
                    CursorFactory::class.java,
                    Int::class.javaPrimitiveType,
                    DatabaseErrorHandler::class.java
                )
            ).intercept { chain ->
                val result = chain.proceed()
                runCatching { MeiLyricDao.attach(result as? SQLiteDatabase) }
                    .onFailure { logger.error("Attach database failed", it) }
                result
            }
        }

        // 歌词下载完成后写入缓存时顺手捕获 (快路径)
        hookOne("SQLiteDatabase.insertWithOnConflict") {
            module.hook(
                clazz.getDeclaredMethod(
                    "insertWithOnConflict",
                    String::class.java,
                    String::class.java,
                    ContentValues::class.java,
                    Int::class.javaPrimitiveType
                )
            ).intercept { chain ->
                val result = chain.proceed()
                runCatching {
                    MeiLyricDao.captureFromValues(
                        table = chain.getArg(0) as? String,
                        values = chain.getArg(2) as? ContentValues
                    )
                }.onFailure { logger.error("Capture insert failed", it) }
                result
            }
        }

        hookOne("SQLiteDatabase.update") {
            module.hook(
                clazz.getDeclaredMethod(
                    "update",
                    String::class.java,
                    ContentValues::class.java,
                    String::class.java,
                    Array<String>::class.java
                )
            ).intercept { chain ->
                val result = chain.proceed()
                runCatching {
                    MeiLyricDao.captureFromValues(
                        table = chain.getArg(0) as? String,
                        values = chain.getArg(1) as? ContentValues
                    )
                }.onFailure { logger.error("Capture update failed", it) }
                result
            }
        }
    }

    /**
     * 每个 hook 独立安装、独立报错。
     *
     * 之前四个 hook 包在同一个 runCatching 里, 只要第一个失败,
     * 后面的 insert/update 捕获 hook 就全部装不上, 而日志只显示一句
     * 笼统的 "Hook SQLiteDatabase failed", 无法定位到底是哪一个。
     */
    private inline fun hookOne(name: String, block: () -> Unit) {
        runCatching(block).onFailure {
            logger.error("Hook $name failed: ${it.describe()}", it)
        }
    }

    private fun onLyricCaptured(raw: RawLyric) {
        if (raw.songId != currentSongId) return
        if (currentLyric == raw) return
        currentLyric = raw
        // Mei 刚落了新歌词, 预取缓存里那份可能更旧, 作废掉让新歌词生效
        LyricRepository.invalidate(raw.songId)
        logger.debug("Lyric captured from cache: type=${raw.parserType}")
        workExecutor.execute {
            pushedSongId = null
            pushCurrentSong()
        }
    }

    // ------------------------------------------------------------------ 推送

    /**
     * 推送歌曲信息。
     *
     * Lyricon provider 随时可能被释放 (服务重启、作用域变更)，
     * [io.github.proify.lyricon.provider.remote.RemotePlayer] 在失效时会抛
     * `IllegalStateException`。这个调用链会经过 Mei 的 Media3 回调，
     * 一旦异常泄漏出去就会被包装成
     * `PlaybackException(ERROR_CODE_FAILED_RUNTIME_CHECK)`，
     * 表现为播放中途突然报错中断。所以这里必须全部吞掉。
     */
    private fun pushSong(song: Song) {
        runCatching { provider?.player?.setSong(song) }
            .onFailure { logger.error("setSong failed", it) }
    }

    /**
     * 把当前估算出的播放进度重新推一次。
     *
     * `setSong()` 之后 Lyricon 会重新定位歌词, 而框架的
     * `MediaSession.setPlaybackState` **只在播放状态跳变时才会被调用**
     * (正常播放中可能几十秒都不触发一次)。如果不在这里补推一次, 歌词就会
     * 从 0 开始走 —— 表现为「歌词不同步」。
     */
    private fun restorePlaybackState() {
        val state = latestPlaybackState ?: return
        val position = estimatePositionMs()
        val rebuilt = runCatching {
            PlaybackState.Builder(state)
                .setState(state.state, position, state.playbackSpeed)
                .build()
        }.getOrNull() ?: return

        runCatching { provider?.player?.setPlaybackState(rebuilt) }
            .onFailure { logger.error("restore playback state failed", it) }
        logger.debug("Restored position ${position}ms (state=${state.state}, speed=${state.playbackSpeed})")
    }

    /**
     * 按速度把快照进度外推到当前时刻, 再叠加用户设置的歌词偏移。
     *
     * 偏移在这里统一生效 —— [positionTicker] 和 [restorePlaybackState]
     * 两条推进度通道都经过这个函数, 不会互相打架。
     * (正值 = 歌词提前, 负值 = 歌词延后)
     */
    private fun estimatePositionMs(): Long {
        val state = latestPlaybackState ?: return lyricOffsetMs().toLong()
        var position = state.position.coerceAtLeast(0L)
        if (state.state == PlaybackState.STATE_PLAYING && state.playbackSpeed > 0f) {
            val elapsed = SystemClock.elapsedRealtime() - latestPlaybackStateAt
            if (elapsed > 0L) position += (elapsed * state.playbackSpeed).toLong()
        }
        return (position + lyricOffsetMs()).coerceAtLeast(0L)
    }

    /** 歌词偏移设置 (远程 SharedPreferences, 调用方保证在工作线程) */
    private fun lyricOffsetMs(): Int = Configs.lyricOffsetMs(preferences)

    /** 播放状态节流日志: 状态跳变必打, 否则最多每 2 秒一条 */
    private fun logPlaybackState(state: PlaybackState) {
        val now = SystemClock.elapsedRealtime()
        if (state.state == lastLoggedState && now - lastLoggedAt < 2_000L) return
        lastLoggedState = state.state
        lastLoggedAt = now
        logger.debug(
            "playback state=${state.state} position=${state.position} speed=${state.playbackSpeed}"
        )
    }

    /**
     * 进度心跳。
     *
     * 框架的 `MediaSession.setPlaybackState` **只在播放状态跳变时才会被调用**
     * (正常播放中可能几十秒都不触发一次)。如果只靠它来更新进度, 歌词推进速度
     * 就会慢于真实播放 —— 表现为「越到后面差越多」。
     *
     * 这里自己按固定间隔把「快照进度 + 按速度外推」的结果推给 Lyricon,
     * 这样不管 Lyricon 端是否会自己插值, 进度都是准的。
     */
    private val positionTicker = object : Runnable {
        override fun run() {
            runCatching {
                if (latestPlaybackState?.state == PlaybackState.STATE_PLAYING) {
                    provider?.player?.setPosition(estimatePositionMs())
                }
            }.onFailure { logger.error("position tick failed", it) }
            mainHandler.postDelayed(this, POSITION_SYNC_INTERVAL_MS)
        }
    }

    private fun startPositionTicker() {
        mainHandler.removeCallbacks(positionTicker)
        mainHandler.postDelayed(positionTicker, POSITION_SYNC_INTERVAL_MS)
    }

    private fun pushCurrentSong() {
        ensureProvider()
        if (provider?.player == null) {
            logger.warn("Skipping push: Lyricon provider unavailable")
            return
        }
        val songId = currentSongId
        val title = currentTitle
        if (title.isNullOrBlank() && songId.isNullOrBlank()) return

        val base = Song(
            id = songId,
            name = title,
            artist = currentArtist,
            duration = currentDuration
        )
        // 只在换歌时清空上一首的歌词。
        // 重试 / 补全 / 设置变更也会走到这里, 如果每次都先推一份没有歌词的
        // Song, 歌词显示会被反复清空再重建 —— 看起来就是「不同步 / 闪断」。
        if (baseSongId != songId) {
            baseSongId = songId
            pushSong(base)
        }

        val current = ++generation
        val duration = currentDuration
        val artist = currentArtist
        val album = currentAlbum

        workExecutor.execute {
            // 远程 SharedPreferences 的读取一律放工作线程 (见 prefetchNext 的说明)。
            // 之前是在主线程读的, 切歌时会连续做 4 次 IPC。
            val mode = sourceMode()
            val translation = translationEnabled()
            val enrich = neteaseEnrichEnabled() && NetEaseApi.isNetEaseId(songId)
            val allowLocal = mode != SourceMode.CLOUD
            val allowCloud = mode != SourceMode.MEI && netSearchEnabled()

            // 每次切歌都顺手预取下一首 (队列变化时也会触发, 两边互为兜底)
            runCatching { prefetchNext() }.onFailure { logger.error("prefetchNext failed", it) }
            // 0) 预取命中 —— 直接推, 不用等网络
            LyricRepository.cached(songId, translation)?.let { hit ->
                logger.debug("Prefetch hit: ${hit.describe()}")
                publish(base, songId, hit, current)
                return@execute
            }

            // 0.5) AMLL DB —— 按网易云 ID 精确命中, 逐字质量最高, 永远不会错歌。
            //      覆盖率有限, 没有就返回 null 继续走后面的源。
            val amll = if (enrich) songId?.let { amllCandidate(it) } else null
            if (amll?.isVerbatim == true) {
                logger.debug("AmllDb exact hit (verbatim): ${amll.describe()}")
                publish(base, songId, amll, current)
                return@execute
            }

            // 1) 本地候选: Mei 缓存 (+ 网易云接口补全), 合并成一份
            val raw = if (allowLocal || enrich) resolveLocalLyric(songId, enrich) else null
            val local = raw?.let {
                LyricCandidate(
                    lines = LyricParserBridge.parse(it, duration, translation),
                    source = "本地",
                )
            }

            // 2) 本地就是逐字歌词 —— 已经是最优解, 不再多打一次云端请求
            if (local != null && local.isVerbatim) {
                publish(base, songId, local, current)
                return@execute
            }

            if (!allowCloud || title.isNullOrBlank()) {
                val fallback = listOfNotNull(amll, local).best()
                if (fallback != null) publish(base, songId, fallback, current) else retryIfCurrent(current)
                return@execute
            }

            // 3) 本地没有逐字 -> 问云端, 再按质量取最优。
            //    注意这里是「比质量」而不是「本地优先」: 云端若有逐字歌词,
            //    即使本地有一份带翻译的整行歌词, 也应该用云端那份。
            CloudSearcher.search(
                trackName = title,
                artistName = artist.orEmpty(),
                albumName = album,
                onSuccess = { rich ->
                    val cloud = LyricCandidate(rich, "云端匹配")
                    val winner = listOfNotNull(
                        cloud.takeIf { !it.isEmpty },
                        amll,
                        local,
                    ).best()
                    if (winner != null) {
                        logger.debug(
                            "Candidates cloud=${cloud.describe()} " +
                                "amll=${amll?.describe() ?: "无"} " +
                                "local=${local?.describe() ?: "无"} -> ${winner.describe()}"
                        )
                        publish(base, songId, winner, current)
                    } else {
                        retryIfCurrent(current)
                    }
                },
                onFailure = {
                    val fallback = listOfNotNull(amll, local).best()
                    if (fallback != null) publish(base, songId, fallback, current) else retryIfCurrent(current)
                }
            )
        }
    }

    /**
     * AMLL DB 拉原始 TTML -> 解析成候选。拿到的一定是这首歌 (按 ID 精确命中 + 元数据校验)。
     */
    private fun amllCandidate(songId: String): LyricCandidate? {
        val lyric = runCatching { AmllDbApi.fetch(songId) }
            .onFailure { logger.warn("AmllDb failed: ${it.message}") }
            .getOrNull() ?: return null
        val lines = runCatching { TtmlParser.parse(lyric.ttml) }
            .onFailure { logger.warn("AmllDb parse failed: ${it.message}") }
            .getOrDefault(emptyList())
        if (lines.isEmpty()) return null
        return LyricCandidate(lines, "AMLL DB")
    }

    /**
     * 推送选中的歌词。
     *
     * 统一在这里切主线程 + 做 generation 校验。之前每个分支各写一遍
     * `mainHandler.post { if (current != generation) ... }`, 很容易漏掉一处,
     * 导致切歌后旧结果把新歌词覆盖掉。
     */
    private fun publish(base: Song, songId: String?, candidate: LyricCandidate?, token: Int) {
        if (candidate == null || candidate.isEmpty) return
        mainHandler.post {
            if (token != generation) return@post
            pushedSongId = songId
            pushSong(base.copy(lyrics = candidate.lines))
            restorePlaybackState()
            logger.debug("Pushed ${candidate.describe()}")
        }
    }

    private fun retryIfCurrent(token: Int) {
        mainHandler.post { if (token == generation) scheduleRetry() }
    }

    /**
     * 取本地歌词: Mei 缓存 + 网易云接口补全。
     * 只在工作线程调用 (会做数据库查询与网络请求)。
     */
    private fun resolveLocalLyric(songId: String?, enrich: Boolean): RawLyric? {
        if (songId.isNullOrBlank()) return null

        var raw = currentLyric?.takeIf { it.songId == songId } ?: MeiLyricDao.query(songId)

        if (enrich) {
            val extra = neteaseLyric(songId)
            if (extra != null) {
                val merged =
                    (raw ?: RawLyric(songId = songId, content = null)).enrichedWith(extra)
                if (merged != raw) {
                    raw = merged
                    currentLyric = merged
                    logger.debug(
                        "Enriched from NetEase: type=${merged.parserType} " +
                            "trans=${merged.translation?.length ?: 0}"
                    )
                }
            }
        }

        return raw?.takeIf { it.isUsable }
    }

    private fun neteaseLyric(songId: String): NetEaseLyric? = synchronized(neteaseLock) {
        neteaseCache[songId]?.let { return it }
        if (neteaseMissing.contains(songId)) return null

        val fetched = NetEaseApi.fetch(songId)
        if (fetched != null) {
            if (neteaseCache.size > 24) neteaseCache.clear()
            neteaseCache[songId] = fetched
        } else {
            neteaseMissing.add(songId)
        }
        fetched
    }

    private fun scheduleRetry() {
        val songId = currentSongId ?: return
        if (pushedSongId == songId) return
        if (retrySongId != songId) {
            retrySongId = songId
            retryCount = 0
        }
        if (retryCount >= MAX_RETRY) return
        retryCount++
        mainHandler.postDelayed({
            if (currentSongId == songId && pushedSongId != songId) {
                pushCurrentSong()
            }
        }, RETRY_INTERVAL_MS)
    }

    private companion object {
        const val MAX_RETRY = 8
        const val RETRY_INTERVAL_MS = 1_500L
        const val MAX_PROVIDER_SETUP_ATTEMPTS = 3

        /** 进度心跳间隔。播放中每秒补推一次进度, 避免歌词推进速度偏慢 */
        const val POSITION_SYNC_INTERVAL_MS = 100L
    }
}
