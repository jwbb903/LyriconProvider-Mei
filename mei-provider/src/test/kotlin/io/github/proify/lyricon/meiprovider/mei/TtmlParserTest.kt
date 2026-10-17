/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.meiprovider.mei

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [TtmlParser] 的回归测试。
 *
 * 重点覆盖 AMLL TTML DB 里真实存在的嵌套 `x-bg` 结构 —— 早期用非贪婪正则
 * 匹配 `<span ...>(.*?)</span>` 时, 和声轨的音节会**漏进主歌词**。
 */
class TtmlParserTest {

    private fun ttml(body: String) = """
        <tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata"
            xmlns:amll="http://www.example.com/ns/amll">
          <head><metadata xmlns="">
            <amll:meta key="ncmMusicId" value="347230"/>
          </metadata></head>
          <body dur="04:22.928"><div xmlns="" begin="00:18.432" end="04:22.928">
            $body
          </div></body>
        </tt>
    """.trimIndent()

    @Test
    fun `nested x-bg does not leak into main track`() {
        val content = ttml(
            """
            <p begin="00:18.432" end="00:25.089" ttm:agent="v1" itunes:key="L1">
              <span begin="00:18.432" end="00:18.818">今</span>
              <span begin="00:18.818" end="00:19.286">天</span>
              <span begin="00:19.286" end="00:21.968">我</span> <span begin="00:21.968" end="00:22.142">寒</span>
              <span ttm:role="x-bg">
                <span begin="00:21.968" end="00:22.142">(谁</span>
                <span begin="00:22.142" end="00:22.500">在)</span>
              </span>
              <span ttm:role="x-translation">今天我</span>
            </p>
            """.trimIndent()
        )

        val lines = TtmlParser.parse(content)
        assertEquals(1, lines.size)
        val line = lines.first()

        // 主歌词不能包含和声文字
        assertEquals("今天我 寒", line.text)
        assertTrue("和声漏进主歌词: ${line.text}", line.text?.contains("谁") != true)

        // 和声轨走 secondary, 且保留自己的字级时间轴
        assertEquals("(谁在)", line.secondary)
        assertEquals(2, line.secondaryWords?.size)
        assertEquals("(谁", line.secondaryWords?.get(0)?.text)

        assertEquals("今天我", line.translation)

        // 主音轨: 今 / 天 / 我(含尾随空格) / 寒
        assertEquals(4, line.words?.size)
        assertEquals("我 ", line.words?.get(2)?.text)

        // span 之间的空格归到前一个音节, 这样高亮不会单独占一个时间片
        assertEquals(21_968L, line.words?.get(2)?.end?.toLong())
    }

    @Test
    fun `flat spans without roles still parse as main track`() {
        val content = ttml(
            """
            <p begin="00:00:01.000" end="00:00:03.000">
              <span begin="00:00:01.000" end="00:00:02.000">Hello </span>
              <span begin="00:00:02.000" end="00:00:03.000">world</span>
            </p>
            """.trimIndent()
        )

        val line = TtmlParser.parse(content).single()
        assertEquals("Hello world", line.text)
        assertEquals(2, line.words?.size)
        assertNull(line.secondary)
    }

    @Test
    fun `unknown role without timing is dropped`() {
        val content = ttml(
            """
            <p begin="00:00:01.000" end="00:00:03.000">
              <span begin="00:00:01.000" end="00:00:02.000">你好</span>
              <span ttm:role="x-decoration">***</span>
            </p>
            """.trimIndent()
        )

        val line = TtmlParser.parse(content).single()
        assertEquals("你好", line.text)
    }

    @Test
    fun `xml entities are decoded`() {
        val content = ttml(
            """
            <p begin="00:00:01.000" end="00:00:03.000">
              <span begin="00:00:01.000" end="00:00:03.000">A &amp; B &lt;C&gt;</span>
            </p>
            """.trimIndent()
        )

        assertEquals("A & B <C>", TtmlParser.parse(content).single().text)
    }

    @Test
    fun `parseTime handles all documented formats`() {
        assertEquals(18_432L, TtmlParser.parseTime("00:18.432"))
        assertEquals(4_262_928L, TtmlParser.parseTime("01:11:02.928"))
        assertEquals(1_500L, TtmlParser.parseTime("1.500"))
        assertEquals(18_432L, TtmlParser.parseTime("18432"))
        assertNull(TtmlParser.parseTime("nonsense"))
    }

    @Test
    fun `canParse detects ttml`() {
        assertTrue(TtmlParser.canParse(ttml("<p begin=\"0\" end=\"1\"/>").trimIndent()))
        assertTrue(TtmlParser.canParse("<tt></tt>"))
        assertTrue(!TtmlParser.canParse("[00:01.00]hello"))
    }

    @Test
    fun `lines are sorted by begin`() {
        val content = ttml(
            """
            <p begin="00:00:05.000" end="00:00:06.000">
              <span begin="00:00:05.000" end="00:00:06.000">second</span>
            </p>
            <p begin="00:00:01.000" end="00:00:02.000">
              <span begin="00:00:01.000" end="00:00:02.000">first</span>
            </p>
            """.trimIndent()
        )

        val lines = TtmlParser.parse(content)
        assertEquals(listOf("first", "second"), lines.map { it.text })
        assertNotNull(lines.first().words)
    }
}
