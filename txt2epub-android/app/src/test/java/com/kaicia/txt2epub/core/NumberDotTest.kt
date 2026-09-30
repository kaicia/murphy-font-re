package com.kaicia.txt2epub.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `N. 제목` 형식 파일.
 *
 * 3000화짜리 파일에서 1902개만 잡혔다. 제목이 숫자로 시작하면
 * ('1234. 3일 후') 소수점 막는 규칙에 함께 걸려 떨어졌고,
 * 제목 줄 앞에 `&nbsp;` 가 남아 있으면 정규식의 `^\s*` 가 걸리지 않아 놓쳤다.
 */
class NumberDotTest {

    private fun body(n: Int) = List(n) { "본문입니다. 그날의 일은 아무도 기억하지 못했다. 길에서 바람이 불었다." }

    private fun novel(title: (Int) -> String): List<String> {
        val lines = ArrayList<String>()
        for (i in 1..300) {
            lines.add(title(i))
            lines.add("")
            lines.addAll(body(40))
            lines.add("")
        }
        return lines
    }

    @Test
    fun `제목이 숫자로 시작해도 잡는다`() {
        val best = ChapterDetector.detect(novel { "$it. ${it * 3}일 후의 이야기" }).first()
        println("1등: ${best.name} · ${best.count}개")
        assertEquals("숫자 + 점", best.name)
        assertEquals(300, best.count)
        assertEquals((1..300).toList(), best.hits.mapNotNull { it.num })
    }

    @Test
    fun `줄 앞에 붙은 nbsp 를 떼고 잡는다`() {
        val best = ChapterDetector.detect(novel { "\u00A0$it. 죽음-01-" }).first()
        assertEquals(300, best.count)
        assertEquals((1..300).toList(), best.hits.mapNotNull { it.num })
    }

    @Test
    fun `전각 마침표도 받는다`() {
        val best = ChapterDetector.detect(novel { "$it． 죽음" }).first()
        assertEquals(300, best.count)
    }

    @Test
    fun `소수점은 여전히 막는다`() {
        val lines = ArrayList<String>()
        repeat(6) { k ->
            lines.addAll(body(200))
            lines.add("${k + 1}.5m짜리 창대에 창날을 꽂았다.")
            lines.add("5.56mm 탄약을 챙겼다.")
            lines.add("999.9, GOLD란 글자가 선명했다.")
        }
        val dot = ChapterDetector.detect(lines).firstOrNull { it.name == "숫자 + 점" }
        assertTrue("소수점 줄이 제목으로 잡혔다: ${dot?.hits?.map { it.title }}", dot == null)
    }

    @Test
    fun `진단이 빠진 번호와 그 자리를 알려준다`() {
        val lines = ArrayList<String>()
        for (i in 1..40) {
            // 17번만 앞에 nbsp 가 아니라 다른 형식이라 안 잡히는 상황
            lines.add(if (i == 17) "제17장 죽음" else "$i. 죽음-$i-")
            lines.add("")
            lines.addAll(body(30))
            lines.add("")
        }
        val cands = ChapterDetector.detect(lines)
        val best = cands.first()
        val chapters = ChapterDetector.buildChapters(best.hits, lines, "본문")
        val text = ChapterDetector.diagnose(lines, cands, 0, chapters)
        assertTrue("빠진 번호를 안 알려준다:\n$text", text.contains("빠진 번호"))
        assertTrue("17번 자리를 안 보여준다:\n$text", text.contains("17번이 있어야 할 자리"))
        assertTrue("그 자리 줄을 안 보여준다:\n$text", text.contains("제17장 죽음"))
    }
}
