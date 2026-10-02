package com.kaicia.txt2epub.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 번호 사이 빈자리 메우기. 3000화 파일에서 마지막 4개가 구분자가 아예 빠진 오타였다.
 *
 *   < 938 여름 방학-30- >     2018 ex wife-93-     2866 위험한 동거 -76-
 */
class GapFillTest {

    private fun body(n: Int) = List(n) { "본문입니다. 그날의 일은 아무도 기억하지 못했다. 길에서 바람이 불었다." }

    @Test
    fun `구분자가 빠진 제목도 번호와 자리가 맞으면 잡는다`() {
        val lines = ArrayList<String>()
        for (i in 1..200) {
            lines.add(
                when (i) {
                    38 -> "< $i 여름 방학-30- >"
                    118 -> "$i ex wife-93-"
                    166 -> "$i 위험한 동거 -76-"
                    else -> "$i. 제목-$i-"
                }
            )
            lines.add("")
            lines.addAll(body(30))
            lines.add("")
        }
        val best = ChapterDetector.detect(lines).first()
        assertEquals("숫자 + 점", best.name)
        assertEquals((1..200).toList(), best.hits.mapNotNull { it.num })
        val chapters = ChapterDetector.buildChapters(best.hits, lines, "본문")
        assertEquals(200, chapters.size)
        assertEquals("38 여름 방학-30-", chapters[37].title)
    }

    @Test
    fun `본문의 연도는 빈자리로 잡지 않는다`() {
        val lines = ArrayList<String>()
        for (i in 1..100) {
            if (i != 50) { lines.add("$i. 제목"); lines.add("") }
            lines.addAll(body(20))
            if (i == 49) lines.add("50년 전의 일이었다.")    // 번호 바로 뒤에 글자가 붙음
            lines.add("")
        }
        val best = ChapterDetector.detect(lines).first()
        assertTrue(best.hits.none { it.title.startsWith("50년") })
        assertEquals(99, best.count)
    }
}
