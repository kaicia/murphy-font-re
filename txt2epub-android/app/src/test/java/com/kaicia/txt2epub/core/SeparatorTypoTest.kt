package com.kaicia.txt2epub.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 점 대신 쓴 오타. 3000화 파일에서 마지막으로 남은 11개가 이랬다.
 *
 *   < 142- 낭만의 캠퍼스-11- >
 *   < 433, 도쿄 핫(TOKYO-HOT)-17- >
 */
class SeparatorTypoTest {

    private fun body(n: Int) = List(n) { "본문입니다. 그날의 일은 아무도 기억하지 못했다. 길에서 바람이 불었다." }

    @Test
    fun `꺾쇠 제목에서는 점 대신 쓴 쉼표와 붙임표도 받는다`() {
        val lines = ArrayList<String>()
        for (i in 1..200) {
            val sep = when (i) { 42 -> "-"; 133, 136 -> ","; else -> "." }
            lines.add("< $i$sep 제목-$i- >"); lines.add("")
            lines.addAll(body(30)); lines.add("")
            lines.add("< $i. 제목-$i- > 끝"); lines.add("")
        }
        val best = ChapterDetector.detect(lines).first()
        assertEquals("숫자 + 점", best.name)
        assertEquals((1..200).toList(), best.hits.mapNotNull { it.num })
    }

    @Test
    fun `맨 줄의 쉼표 숫자는 제목이 아니다`() {
        val lines = ArrayList<String>()
        for (i in 1..100) {
            lines.add("$i. 제목"); lines.add("")
            lines.addAll(body(30))
            lines.add("2, 3일 뒤에 다시 만났다.")
            lines.add("3- 4명이 더 왔다.")
            lines.add("")
        }
        val best = ChapterDetector.detect(lines).first()
        assertEquals(100, best.count)
        assertTrue(best.hits.none { it.title.contains("일 뒤") || it.title.contains("명이") })
    }
}
