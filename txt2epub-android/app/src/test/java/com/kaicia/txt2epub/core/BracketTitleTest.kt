package com.kaicia.txt2epub.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `< 제목 >` 형식 파일.
 *
 * 사용자가 보낸 진단 그대로 재현한다. 2,110,943자 / 114,313줄짜리 파일인데
 * 이 형식을 잡는 패턴이 없어서, 본문의 '1.5m짜리 창대', '5.56mm부터', '999.9, GOLD'
 * 네 줄이 '숫자 + 점'에 걸려 49만 자짜리 챕터 다섯 개가 나왔다.
 */
class BracketTitleTest {

    private fun body(n: Int) = buildString {
        repeat(n) { append("본문입니다. 그날의 일은 아무도 기억하지 못했다. 길 위에서 바람이 불었다.\n") }
    }.trimEnd().split("\n")

    private fun novel(): List<String> {
        val lines = ArrayList<String>()
        lines.add("아포칼립스의 고인물 - ⓒ 슬리버")
        lines.add("")
        lines.add("세상이 게임처럼 바뀌었다.")
        lines.add("")
        lines.add("=======================================")
        lines.add("< 프롤로그 >")
        lines.add("")
        lines.addAll(body(60))
        lines.add("")
        for (i in 1..287) {
            lines.add(if (i % 40 == 0) "< 차원문을 이용하려면 - $i > 끝" else "< 차원문을 이용하려면 - $i >")
            lines.add("")
            lines.addAll(body(58))
            // 본문에 섞인 숫자들. 제목으로 잡히면 안 된다.
            if (i == 20) lines.add("1.5m짜리 창대에 창날을 꽂고 못으로 꽝꽝 박아 고정시켰다.")
            if (i == 120) lines.add("5.56mm부터 시작해서 수류탄, 크레모아 등 아주 위험한 물건들로 가득하다.")
            if (i == 140) lines.add("7.62mm 탄박스를 까보니 짙은 녹색의 깡통에 러시아어가 가득했다.")
            if (i == 200) lines.add("999.9, GOLD란 글자가 선명하게 박힌 걸 보니 왠지 탐이 났다.")
            lines.add("")
        }
        return lines
    }

    @Test
    fun `괄호로 감싼 제목을 잡는다`() {
        val lines = novel()
        val best = ChapterDetector.detect(lines).first()
        println("1등: ${best.name} · ${best.count}개 · ${"%.3f".format(best.score)}")
        assertEquals("< 제목 >", best.name)
        assertEquals(288, best.count)          // 프롤로그 + 287화
        assertTrue(best.score >= ChapterDetector.MIN_SCORE)
    }

    @Test
    fun `본문의 소수점 숫자는 제목으로 잡지 않는다`() {
        val lines = novel()
        val bad = ChapterDetector.detect(lines).firstOrNull { it.name == "숫자 + 점" }
        assertTrue("소수점 줄이 제목으로 잡혔다: ${bad?.hits?.map { it.title }}", bad == null)
    }

    @Test
    fun `목차에서 괄호를 벗긴다`() {
        val lines = novel()
        val best = ChapterDetector.detect(lines).first()
        val chapters = ChapterDetector.buildChapters(best.hits, lines, "본문")
        // 첫 챕터 앞의 제목·태그 줄은 '머리말'로 따로 담긴다
        assertEquals("머리말", chapters[0].title)
        assertEquals("프롤로그", chapters[1].title)
        assertTrue(chapters.any { it.title == "차원문을 이용하려면 - 1" })
        assertTrue(chapters.any { it.title == "차원문을 이용하려면 - 40 끝" })
        assertTrue(chapters.none { it.title.contains("<") || it.title.contains(">") })
    }

    @Test
    fun `말이 안 되게 큰 챕터는 후보에서 뺀다`() {
        // 2MB짜리 본문에 숫자 줄 네 개만 있는 경우
        val lines = ArrayList<String>()
        repeat(4) { k ->
            lines.addAll(body(4000))
            lines.add("${k + 1}.5m짜리 창대에 창날을 꽂았다.")
        }
        val list = ChapterDetector.detect(lines)
        assertTrue(
            "49만 자짜리 챕터가 후보로 남았다: ${list.map { "${it.name} ${it.count}개 평균 ${it.meanSize.toInt()}" }}",
            list.none { it.meanSize > 120_000 }
        )
    }

    @Test
    fun `여러 괄호 모양을 받는다`() {
        listOf("< 제목 >", "＜제목＞", "「1화 시작」", "『제목』", "【제목】").forEach {
            assertTrue(it, ChapterDetector.cleanTitle(it).let { t -> !t.contains("<") && t.isNotBlank() })
        }
        assertEquals("제목", ChapterDetector.cleanTitle("< 제목 >"))
        assertEquals("제목 끝", ChapterDetector.cleanTitle("< 제목 > 끝"))
        assertEquals("그냥 제목", ChapterDetector.cleanTitle("그냥 제목"))
    }
}
