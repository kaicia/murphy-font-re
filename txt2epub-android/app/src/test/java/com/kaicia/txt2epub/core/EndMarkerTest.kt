package com.kaicia.txt2epub.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 화 끝 표시와 꺾쇠로 감싼 번호 제목.
 *
 * 3000화짜리 파일에서 진단으로 확인한 구조를 재현한다. 앞쪽은 맨 숫자,
 * 중간부터 일부는 꺾쇠로 감싸고 화 끝마다 '> 끝' 표시가 붙는다.
 *
 *   1. 죽음-01-
 *   < 68. 새터섹터-31- >
 *   < 68. 새터섹터-31- > 끝
 *
 * 꺾쇠 쪽을 못 잡아 3000화 중 1943개만 나왔다. 끝 표시를 제목으로 잡으면
 * 화마다 빈 챕터가 하나씩 낀다.
 */
class EndMarkerTest {

    private fun body(n: Int) = List(n) { "본문입니다. 그날의 일은 아무도 기억하지 못했다. 길에서 바람이 불었다." }

    private fun mixed(): List<String> {
        val lines = ArrayList<String>()
        for (i in 1..300) {
            val title = "$i. 새터섹터-$i-"
            val wrapped = i % 3 == 0          // 셋 중 하나는 꺾쇠 + 끝 표시
            lines.add(if (wrapped) "< $title >" else title)
            lines.add("")
            lines.addAll(body(30))
            lines.add("")
            if (wrapped) { lines.add("< $title > 끝"); lines.add("") }
        }
        return lines
    }

    @Test
    fun `맨 숫자와 꺾쇠 숫자가 섞여도 전부 잡는다`() {
        val best = ChapterDetector.detect(mixed()).first()
        println("1등: ${best.name} · ${best.count}개 · ${"%.3f".format(best.score)}")
        assertEquals("숫자 + 점", best.name)
        assertEquals(300, best.count)
        assertEquals((1..300).toList(), best.hits.mapNotNull { it.num })
    }

    @Test
    fun `끝 표시는 제목이 아니다`() {
        val lines = mixed()
        val best = ChapterDetector.detect(lines).first()
        assertTrue(best.hits.none { it.title.trimEnd().endsWith("끝") })

        val chapters = ChapterDetector.buildChapters(best.hits, lines, "본문")
        assertEquals(300, chapters.size)
        // 괄호는 벗겨져 맨 숫자 제목과 모양이 같아진다
        assertEquals("3. 새터섹터-3-", chapters[2].title)
        assertEquals("1. 새터섹터-1-", chapters[0].title)
    }

    @Test
    fun `꺾쇠 제목 파일에서 끝 표시가 빈 챕터를 만들지 않는다`() {
        // 고인물 형식: 화마다 여는 표시와 닫는 표시가 모두 있다
        val lines = ArrayList<String>()
        lines.add("< 프롤로그 >"); lines.add("")
        lines.addAll(body(40)); lines.add("")
        lines.add("< 프롤로그 > 끝"); lines.add("")
        for (i in 1..120) {
            lines.add("< 차원문을 이용하려면 - $i >"); lines.add("")
            lines.addAll(body(40)); lines.add("")
            lines.add("< 차원문을 이용하려면 - $i > 끝"); lines.add("")
        }
        val best = ChapterDetector.detect(lines).first()
        assertEquals("< 제목 >", best.name)
        assertEquals(121, best.count)
        val chapters = ChapterDetector.buildChapters(best.hits, lines, "본문")
        assertEquals(121, chapters.size)
        assertEquals("프롤로그", chapters[0].title)
    }

    @Test
    fun `끝 표시 판별`() {
        assertTrue(ChapterDetector.isEndMarker("< 68. 새터섹터-31- > 끝"))
        assertTrue(ChapterDetector.isEndMarker("< 차원문을 이용하려면 - 2 > 끝"))
        assertTrue(ChapterDetector.isEndMarker("< 제목 > (끝)"))
        assertTrue(ChapterDetector.isEndMarker("＜제목＞끝"))
        assertFalse(ChapterDetector.isEndMarker("12. 세상의 끝"))
        assertFalse(ChapterDetector.isEndMarker("< 세상의 끝 >"))
        assertFalse(ChapterDetector.isEndMarker("< 끝 >"))
        assertFalse(ChapterDetector.isEndMarker("끝"))
    }
}
