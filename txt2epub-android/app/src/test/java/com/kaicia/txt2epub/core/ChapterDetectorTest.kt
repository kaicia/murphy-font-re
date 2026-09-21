package com.kaicia.txt2epub.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 챕터 판정 검증.
 *
 * 실제 웹소설 txt에서 문제가 됐던 형태를 그대로 재현한다.
 * 제목 형식이 섞여 있고, 본문 중간과 끝에 번호처럼 보이는 줄이 끼어 있는 경우다.
 */
class ChapterDetectorTest {

    /** 한 화 분량의 본문. 너무 짧으면 sizeFit 감점에 걸린다. */
    private fun body(seed: Int): List<String> =
        List(6) { "본문 ${seed}-$it 그날 밤의 일은 아무도 모른다. 가나다라마바사아자차카타파하 " .repeat(4) } + listOf("")

    private fun novel(count: Int, mixed: Boolean = true): MutableList<String> {
        val lines = ArrayList<String>()
        for (i in 1..count) {
            lines.add(if (mixed && i % 3 == 0) "아포칼립스에 집을 숨김 ${i}화" else "${i}화")
            lines.addAll(body(i))
        }
        return lines
    }

    @Test
    fun `번호로 시작하는 형식과 제목이 붙은 형식이 섞여도 한 패턴으로 잡는다`() {
        val lines = novel(200)
        val best = ChapterDetector.detect(lines).first()
        assertEquals("N화", best.name)
        assertEquals(200, best.count)
        assertTrue("연속성이 낮다: ${best.seq}", best.seq > 0.95)
        assertTrue("점수가 낮다: ${best.score}", best.score >= ChapterDetector.MIN_SCORE)
    }

    @Test
    fun `본문 중간의 큰 번호 오탐은 최장 증가 부분수열에서 걸러진다`() {
        val lines = novel(120)
        // 100화 어딘가의 본문 줄이 '6478화'로 읽히는 상황
        lines.add(lines.size / 2, "6478화")
        val best = ChapterDetector.detect(lines).first()
        assertEquals(120, best.count)
        assertTrue(best.hits.none { it.num == 6478 })
        assertEquals(1, best.dropped)
    }

    @Test
    fun `끝에 붙은 오탐은 급점프 제거로 잘라낸다`() {
        val lines = novel(60)
        lines.add("99999화")
        lines.addAll(body(0))
        val best = ChapterDetector.detect(lines).first()
        assertTrue(best.hits.none { it.num == 99999 })
        assertEquals(60, best.count)
    }

    @Test
    fun `번호가 단조증가로 남는다`() {
        val lines = novel(150)
        lines.add(lines.size / 3, "7777화")
        val hits = ChapterDetector.detect(lines).first().hits
        val nums = hits.mapNotNull { it.num }
        assertEquals(nums, nums.sorted())
        assertEquals(nums.distinct(), nums)
    }

    @Test
    fun `구분 패턴이 없으면 점수가 기준에 못 미친다`() {
        val lines = List(400) { "평범한 본문 줄입니다. 번호도 장 표시도 없습니다. $it" }
        val best = ChapterDetector.detect(lines).firstOrNull()
        assertTrue(best == null || best.score < ChapterDetector.MIN_SCORE)
    }

    @Test
    fun `패턴이 없으면 전체가 한 챕터가 된다`() {
        val lines = List(50) { "본문 $it" }
        val chapters = ChapterDetector.buildChapters(emptyList(), lines, "제목없음")
        assertEquals(1, chapters.size)
        assertEquals(0, chapters[0].fromLine)
        assertEquals(lines.size, chapters[0].toLine)
    }

    @Test
    fun `첫 챕터 앞에 본문이 있으면 머리말로 담는다`() {
        val lines = ArrayList<String>()
        lines.add("작가의 말: 이 글은 오래전에 쓴 습작을 다듬은 것입니다. 읽어주셔서 고맙습니다.")
        lines.add("")
        lines.addAll(novel(10))
        val hits = ChapterDetector.detect(lines).first().hits
        val chapters = ChapterDetector.buildChapters(hits, lines, "본문")
        assertEquals("머리말", chapters.first().title)
        assertEquals(11, chapters.size)
    }

    @Test
    fun `번호만 있는 줄은 다음 줄을 제목으로 끌어올린다`() {
        val lines = ArrayList<String>()
        for (i in 1..12) {
            lines.add("$i")
            lines.add("잃어버린 시간을 찾아서")
            lines.addAll(body(i))
        }
        val best = ChapterDetector.detect(lines).first()
        val chapters = ChapterDetector.buildChapters(best.hits, lines, "본문")
        assertTrue(chapters.first().title.contains("잃어버린 시간을 찾아서"))
    }

    @Test
    fun `Chapter N 형식도 잡는다`() {
        val lines = ArrayList<String>()
        for (i in 1..40) {
            lines.add("Chapter $i")
            lines.addAll(body(i))
        }
        val best = ChapterDetector.detect(lines).first()
        assertEquals(40, best.count)
        assertTrue(best.name.startsWith("Chapter"))
    }

    @Test
    fun `직접 지정한 정규식으로도 나눌 수 있다`() {
        val lines = ArrayList<String>()
        for (i in 1..20) {
            lines.add("### $i 회차")
            lines.addAll(body(i))
        }
        val hits = ChapterDetector.detectManual(lines, Regex("""^###\s+(\d+)"""))
        assertEquals(20, hits.size)
        assertEquals(1, hits.first().num)
    }

    @Test
    fun `결번이 있어도 검출은 유지된다`() {
        val lines = ArrayList<String>()
        val kept = ArrayList<Int>()
        for (i in 1..100) {
            if (i in listOf(50, 51, 63)) continue      // 원본 파일에 빠진 화
            kept.add(i)
            lines.add("${i}화")
            lines.addAll(body(i))
        }
        val best = ChapterDetector.detect(lines).first()
        assertEquals(kept.size, best.count)
        assertEquals(kept, best.hits.mapNotNull { it.num })
    }
}
