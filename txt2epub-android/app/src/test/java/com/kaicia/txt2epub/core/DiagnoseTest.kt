package com.kaicia.txt2epub.core

import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnoseTest {

    private fun novel(): List<String> {
        val lines = ArrayList<String>()
        for (i in 1..30) {
            lines.add("%03d화 - 제목 $i".format(i))
            lines.add("")
            repeat(20) { lines.add("본문입니다. 그날의 일은 아무도 기억하지 못했다. 가나다라마바사") }
            lines.add("")
        }
        return lines
    }

    @Test
    fun `진단에 필요한 것이 다 들어간다`() {
        val lines = novel()
        val cands = ChapterDetector.detect(lines)
        val chapters = ChapterDetector.buildChapters(cands.first().hits, lines, "본문")
        val text = ChapterDetector.diagnose(lines, cands, 0, chapters)

        listOf("챕터 판정 진단", "후보", "점수", "검출된 제목", "비어 있지 않은 첫", "가운데")
            .forEach { assertTrue("[$it] 없음:\n$text", text.contains(it)) }
        assertTrue(text.contains("001화"))
        assertTrue(text.contains("N화"))
    }

    @Test
    fun `눈에 안 보이는 공백을 표시해 준다`() {
        val lines = listOf("제목　001화", "본문", "", "제목 002화", "본문")
        val text = ChapterDetector.diagnose(lines, emptyList(), -1, emptyList())
        assertTrue(text.contains("<전각공백>"))
        assertTrue(text.contains("<NBSP>"))
    }

    @Test
    fun `후보가 없어도 죽지 않는다`() {
        val text = ChapterDetector.diagnose(listOf("본문 한 줄"), emptyList(), -1, emptyList())
        assertTrue(text.contains("없음"))
    }
}
