/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.meiprovider.mei

import io.github.proify.lyricon.lyric.model.LyricWord
import io.github.proify.lyricon.lyric.model.RichLyricLine

/**
 * AMLL / Apple Music 风格的 TTML 逐字歌词解析。
 *
 * 结构:
 * ```xml
 * <tt xmlns="http://www.w3.org/ns/ttml">
 *   <body><div>
 *     <p begin="00:00:12.345" end="00:00:15.678" ttm:agent="v1">
 *       <span begin="00:00:12.345" end="00:00:12.900">Hello </span>
 *       <span begin="00:00:12.900" end="00:00:13.500">world</span>
 *       <span ttm:role="x-translation">你好世界</span>
 *       <span ttm:role="x-bg">
 *         <span begin="..." end="...">(和</span><span begin="..." end="...">声)</span>
 *       </span>
 *     </p>
 *   </div></body>
 * </tt>
 * ```
 *
 * 不引入 XML 依赖 (不会因为 Mei 混淆而失效), 但**不是**用正则匹配 span ——
 * 见下面对嵌套的处理说明。
 */
object TtmlParser {

    private val P_REGEX = Regex(
        """<p\b([^>]*)>(.*?)</p>""",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
    )
    private val ATTR_REGEX = Regex("""([\w:.-]+)\s*=\s*"([^"]*)"""")
    private val WHITESPACE_REGEX = Regex("""\s+""")

    fun canParse(content: String): Boolean {
        val head = content.trimStart()
        return content.contains("http://www.w3.org/ns/ttml", ignoreCase = true) ||
                head.startsWith("<tt", ignoreCase = true)
    }

    fun parse(content: String): List<RichLyricLine> {
        if (content.isBlank()) return emptyList()
        return P_REGEX.findAll(content).mapNotNull { match ->
            parseParagraph(match.groupValues[1], match.groupValues[2])
        }.sortedBy { it.begin }.toList()
    }

    // ------------------------------------------------------------ 轻量 XML 树

    /**
     * 极简节点模型。只需要 element / text 两种。
     */
    private sealed interface Node {
        class Element(val attrs: Map<String, String>, val children: MutableList<Node>) : Node
        class Text(val value: String) : Node
    }

    /**
     * 把一段 XML 片段解析成节点树。
     *
     * 为什么不能继续用正则找 `<span ...>(.*?)</span>`:
     * AMLL 的 `x-bg` (和声轨) **内部还有一层 span**:
     *
     * ```xml
     * <span ttm:role="x-bg"><span begin="..">(谁</span><span begin="..">在)</span></span>
     * ```
     *
     * 非贪婪正则会匹配到第一个 `</span>` 就收尾, 于是:
     * 1. 和声内容被截断;
     * 2. 剩下的 `</span>` 变成游离标签;
     * 3. 和声轨的第二个音节被当成**主音轨**音节 —— 主歌词里会混进和声文字。
     *
     * 实测 AMLL 库里大量歌曲存在这种嵌套 (如 ncm 347230 有 5 处 x-bg),
     * 所以必须按标签栈做真正的树解析。
     */
    private fun parseNodes(body: String): List<Node> {
        val root = Node.Element(emptyMap(), mutableListOf())
        val stack = ArrayDeque<Node.Element>()
        stack.addLast(root)

        var index = 0
        while (index < body.length) {
            val lt = body.indexOf('<', index)
            if (lt < 0) {
                appendText(stack.last(), body.substring(index))
                break
            }
            if (lt > index) appendText(stack.last(), body.substring(index, lt))

            val gt = body.indexOf('>', lt)
            if (gt < 0) break

            val tag = body.substring(lt + 1, gt).trim()
            when {
                tag.startsWith("/") -> if (stack.size > 1) stack.removeLast()
                tag.equals("br", ignoreCase = true) || tag.equals("br/", ignoreCase = true) ->
                    appendText(stack.last(), "\n")

                tag.startsWith("span", ignoreCase = true) -> {
                    val element = Node.Element(parseAttributes(tag), mutableListOf())
                    stack.last().children.add(element)
                    // 自闭合的 span 不入栈
                    if (!tag.endsWith("/")) stack.addLast(element)
                }
                // 其它标签 (ttm:span 之类) 一律忽略标签本身, 内容照常保留
            }
            index = gt + 1
        }
        return root.children
    }

    private fun appendText(parent: Node.Element, raw: String) {
        if (raw.isEmpty()) return
        parent.children.add(Node.Text(raw))
    }

    /** 递归拼接一个节点的全部文本 (含嵌套子节点) */
    private fun textOf(node: Node): String = when (node) {
        is Node.Text -> node.value
        is Node.Element -> buildString { node.children.forEach { append(textOf(it)) } }
    }

    private fun parseParagraph(rawAttrs: String, rawBody: String): RichLyricLine? {
        val attrs = parseAttributes(rawAttrs)
        val begin = attrs["begin"]?.let(::parseTime) ?: return null
        val end = attrs["end"]?.let(::parseTime) ?: (begin + 5_000L)

        val words = mutableListOf<LyricWord>()
        val bgWords = mutableListOf<LyricWord>()
        var translation: String? = null

        for (child in parseNodes(rawBody)) {
            if (child is Node.Text) {
                // span 之间的裸文本属于主音轨。AMLL 里这通常是分隔空格
                // (例: `<span>我</span> <span>寒</span>`), 拼到前一个音节上 ——
                // 这样卡拉OK高亮时空格跟着前一个字一起亮, 不会单独占一个时间片。
                //
                // 但**带换行的纯空白是排版缩进**, 不是歌词内容: 手写 / 格式化过的
                // TTML 每个 span 都另起一行, 直接拼进去会让歌词变成
                // "今\n  天\n  我"。所以含换行的空白一律丢弃。
                val raw = child.value
                if (raw.isBlank()) {
                    if (raw.contains('\n')) continue
                    val last = words.lastOrNull() ?: continue
                    words[words.lastIndex] = last.copy(text = last.text.orEmpty() + " ")
                    continue
                }

                val text = decodeXmlEntities(raw)
                val last = words.lastOrNull()
                if (last != null) {
                    words[words.lastIndex] = last.copy(text = last.text.orEmpty() + text)
                } else {
                    words += LyricWord(begin, end, end - begin, text)
                }
                continue
            }

            child as Node.Element
            when (child.attrs["ttm:role"] ?: child.attrs["role"]) {
                "x-translation" ->
                    if (translation.isNullOrBlank()) {
                        translation = normalizeSpace(decodeXmlEntities(textOf(child)))
                    }

                "x-bg" -> collectWords(child, bgWords, begin, end)

                null, "" -> collectWords(child, words, begin, end)

                // 未知 role: 带字级时间轴的当作主音轨 (例如某些库用 x-other 标装饰音),
                // 没有 begin/end 的纯装饰文本直接丢弃, 避免污染歌词正文。
                else -> if (child.attrs.containsKey("begin")) {
                    collectWords(child, words, begin, end)
                }
            }
        }

        val text = words.joinToString("") { it.text.orEmpty() }.trim()
        val secondary = bgWords.joinToString("") { it.text.orEmpty() }.trim()
        if (text.isEmpty() && translation.isNullOrBlank()) return null

        return RichLyricLine(
            begin = begin,
            end = end,
            duration = (end - begin).coerceAtLeast(0L),
            text = text,
            words = words.ifEmpty { null },
            secondary = secondary.ifEmpty { null },
            secondaryWords = bgWords.ifEmpty { null },
            translation = translation,
        )
    }

    /**
     * 把一个 span 元素按「字级时间轴」收集成 [LyricWord]。
     *
     * 若该 span 自己没有 begin (例如和声轨的外层容器), 则按顺序遍历子节点,
     * 用子 span 各自的时间轴 —— 这样和声轨也能带逐字高亮。
     */
    private fun collectWords(
        element: Node.Element,
        target: MutableList<LyricWord>,
        lineBegin: Long,
        lineEnd: Long,
    ) {
        val spanBegin = element.attrs["begin"]?.let(::parseTime)
        val spanEnd = element.attrs["end"]?.let(::parseTime)

        // 容器节点 (没有自己的时间轴, 但有子元素): 交给子节点各自处理
        if (spanBegin == null && element.children.any { it is Node.Element }) {
            for (child in element.children) {
                when (child) {
                    is Node.Element -> collectWords(child, target, lineBegin, lineEnd)
                    is Node.Text -> {
                        // 容器里 span 之间的裸文本: 只收非空白内容
                        val text = decodeXmlEntities(child.value)
                        if (text.isBlank()) continue
                        target += LyricWord(lineBegin, lineEnd, lineEnd - lineBegin, text)
                    }
                }
            }
            return
        }

        val text = decodeXmlEntities(textOf(element))
        if (text.isEmpty()) return

        val from = spanBegin ?: lineBegin
        val to = spanEnd ?: lineEnd
        target += LyricWord(
            begin = from,
            end = to,
            duration = (to - from).coerceAtLeast(0L),
            text = text,
        )
    }

    /** 折叠空白: 换行 / 连续空格 -> 单空格。用于翻译、罗马音这类整行文本 */
    private fun normalizeSpace(text: String): String =
        text.replace(WHITESPACE_REGEX, " ").trim()

    private fun parseAttributes(raw: String): Map<String, String> =
        ATTR_REGEX.findAll(raw).associate { it.groupValues[1].lowercase() to it.groupValues[2] }

    private fun decodeXmlEntities(text: String): String {
        if (!text.contains('&')) return text
        return text
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&amp;", "&")
    }

    /**
     * 支持 `HH:MM:SS.mmm` / `MM:SS.mmm` / `SS.mmm` / 纯毫秒。
     */
    fun parseTime(value: String): Long? {
        val raw = value.trim()
        if (raw.isEmpty()) return null
        raw.toLongOrNull()?.let { return it }

        val parts = raw.split(':')
        return try {
            when (parts.size) {
                3 -> {
                    val h = parts[0].toLong()
                    val m = parts[1].toLong()
                    val s = parts[2].toDouble()
                    h * 3_600_000L + m * 60_000L + (s * 1000).toLong()
                }

                2 -> {
                    val m = parts[0].toLong()
                    val s = parts[1].toDouble()
                    m * 60_000L + (s * 1000).toLong()
                }

                1 -> (parts[0].toDouble() * 1000).toLong()
                else -> null
            }
        } catch (_: NumberFormatException) {
            null
        }
    }
}
