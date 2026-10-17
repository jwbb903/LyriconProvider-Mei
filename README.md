# Lyricon Provider · Mei

[Mei](https://github.com/ljyh2003/Mei) (网易云音乐第三方 Android 客户端) 的
[Lyricon](https://github.com/PpyLya/lyricon) 歌词提供模块 (Xposed / LSPosed 模块)。

把 Mei 的歌词、播放队列、播放进度桥接给 Lyricon 桌面歌词, 并在 Mei 自身缓存之外
接入多个歌词源补全 / 逐字歌词。

> 本项目 fork 自 [tomakino/LyricProvider](https://github.com/tomakino/LyricProvider),
> 只保留并持续维护 Mei 模块。感谢原项目及上游所有贡献者。

## 功能

- **多源歌词聚合**: Mei 本地缓存 → 网易云公开接口 → AMLL TTML DB → 云端搜索
  (QQ 音乐 / 酷狗 / LrcLib), 按质量评分自动选最优 (逐字 > 整行, 有翻译加分)
- **AMLL DB 精确命中**: Mei 的歌曲 ID 即网易云 ID, 直接按 ID 取
  [applemusic-like-lyrics](https://github.com/Steve-xmh/applemusic-like-lyrics)
  的 TTML 逐字歌词, 不会搜错歌
- **逐字歌词优先**: 候选里只要有逐字时间轴 (TTML / YRC / QRC / KRC) 就优先采用
- **自动预取下一首**: hook 播放队列, 提前取好下一首的歌词, 切歌秒出
- **严格匹配**: 云端搜索按歌名 + 歌手置信度过滤, 宁可没歌词也不推错误的歌词
- **歌词偏移调节**: ±2000ms 滑条, 兜底音频链路固定延迟造成的整体不同步
- **翻译显示** (可开关)

## 使用

1. 设备需已安装 LSPosed / Xposed 框架
2. 安装 APK, 在 LSPosed 中启用模块, 作用域勾选 **Mei**
3. 重启 Mei (或强制停止后重新打开)
4. 在模块设置里按需调整歌词来源 / 翻译 / 偏移

歌词提供走 Lyricon 的 `libs.lyricon.provider` 接口, 需要设备上已安装 Lyricon。

## 构建

```bash
./gradlew :mei-provider:assembleDebug
```

Release 签名通过环境变量注入:

```
RELEASE_STORE_FILE / RELEASE_STORE_PASSWORD / RELEASE_KEY_ALIAS / RELEASE_KEY_PASSWORD
```

## 模块结构

```
mei-provider/     Mei 模块本体 (Xposed hook + 歌词源聚合 + 设置界面)
share/            上游共享库: 歌词解析 (lrc/yrc/qrc/krc) 与云端搜索 (cloudlyric)
```

## License

[Apache License 2.0](LICENSE)
