package com.kaicia.txt2epub.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.charset.StandardCharsets

/**
 * 실제 사용자 파일로 도는 회귀 시험.
 *
 * 기기에서 '챕터 분류가 개판'이라는 말을 듣고도 어디가 문제인지 못 찾았다.
 * 사용자가 잘 나왔던 EPUB을 줘서 거기서 본문을 되살려 넣었다. 제목 형식은
 * 원본 그대로다: `001화 - 라카기가르` 처럼 세 자리 번호에 구분자가 붙는다.
 *
 * 파일이 없으면 시험을 건너뛴다. 저장소에 본문을 넣지 않기 위해서다.
 */
class RealFileRegressionTest {

    private fun sample(): List<String>? {
        val p = System.getProperty("txt2epub.sample") ?: return null
        val f = File(p)
        if (!f.exists()) return null
        return TextReader.load({ ByteArrayInputStream(f.readBytes()) }, null).lines
    }

    @Test
    fun `세 자리 번호에 구분자가 붙은 제목을 전부 잡는다`() {
        val lines = sample() ?: return
        val list = ChapterDetector.detect(lines)
        val best = list.first()
        println("1등: ${best.name} · ${best.count}개 · ${"%.3f".format(best.score)}")
        list.take(5).forEach {
            println("   ${it.name} · ${it.count}개 · ${"%.3f".format(it.score)}")
        }
        assertEquals("N화", best.name)
        assertEquals(375, best.count)

        val chapters = ChapterDetector.buildChapters(best.hits, lines, "본문")
        assertEquals(375, chapters.size)
        assertTrue(chapters.first().title.startsWith("001화"))
        assertTrue(chapters.last().title.startsWith("375화"))

        // 번호가 1부터 375까지 빠짐없이 이어져야 한다
        val nums = best.hits.mapNotNull { it.num }
        assertEquals((1..375).toList(), nums)
    }

    /**
     * 본문 없이도 같은 것을 지킨다.
     *
     * 사용자 본문은 저장소에 넣지 않는다. 대신 제목 형식만 같게 만들어 둔다.
     * 세 자리 번호, 붙임표와 줄표가 섞인 구분자, 문단 사이 빈 줄 — 실제 파일 그대로다.
     */
    @Test
    fun `같은 형식을 만들어 넣어도 375개가 나온다`() {
        val lines = ArrayList<String>()
        for (i in 1..375) {
            val sep = if (i % 5 == 0) "–" else "-"          // 줄표와 붙임표가 섞여 있다
            lines.add("%03d화 %s 제목 %d".format(i, sep, i))
            lines.add("")
            repeat(24) {
                lines.add("본문입니다. 그날의 일은 아무도 기억하지 못했다. 길 위에서 바람이 불었다.")
                lines.add("")
            }
        }
        val best = ChapterDetector.detect(lines).first()
        assertEquals("N화", best.name)
        assertEquals(375, best.count)
        assertEquals((1..375).toList(), best.hits.mapNotNull { it.num })

        val chapters = ChapterDetector.buildChapters(best.hits, lines, "본문")
        assertEquals(375, chapters.size)
        assertTrue(chapters[0].title.startsWith("001화"))
    }
}
