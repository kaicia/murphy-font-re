package com.kaicia.txt2epub.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `#1 - 제목` 형식 파일.
 *
 * 사용자가 보낸 진단 그대로 재현한다. 730,685자 / 44,717줄, 125화짜리 파일인데
 * 마크다운 패턴이 `#` 뒤에 공백을 요구해서 `#1`을 못 잡았다. 그래서 본문의
 * 장면 전환용 `***` 160줄이 '= 구분선 ='으로 1등이 됐고, 거기에 더해
 * 내가 넣은 `< 제목 >` 패턴이 본문의 「」 대사 115줄을 제목으로 잡았다.
 */
class HashTitleTest {

    private fun novel(): List<String> {
        val lines = ArrayList<String>()
        for (i in 1..125) {
            lines.add("#$i - 제목 $i")
            lines.add("")
            repeat(60) { k ->
                lines.add("본문입니다. 그날의 일은 아무도 기억하지 못했다. 길 위에서 바람이 불었다.")
                lines.add("")
                // 장면 전환 표시. 챕터 경계가 아니다.
                if (k == 20 || k == 40) { lines.add("***"); lines.add("") }
                // 시스템창·대사 표시. 제목이 아니다.
                if (k == 10) { lines.add("「알림: 레벨이 올랐습니다」"); lines.add("") }
                if (k == 30) { lines.add("『퀘스트를 수락하시겠습니까』"); lines.add("") }
                if (k == 50) { lines.add("【경고】"); lines.add("") }
            }
        }
        return lines
    }

    @Test
    fun `샵 뒤에 공백이 없어도 잡는다`() {
        val best = ChapterDetector.detect(novel()).first()
        println("1등: ${best.name} · ${best.count}개 · ${"%.3f".format(best.score)}")
        assertEquals("#N", best.name)
        assertEquals(125, best.count)
        assertEquals((1..125).toList(), best.hits.mapNotNull { it.num })
        assertTrue(best.score > 0.6)
    }

    @Test
    fun `장면 전환 별표가 이기지 못한다`() {
        val list = ChapterDetector.detect(novel())
        val stars = list.firstOrNull { it.name == "= 구분선 =" }
        val best = list.first()
        assertTrue("*** 가 1등이 됐다", best.name != "= 구분선 =")
        if (stars != null) assertTrue(best.score > stars.score)
    }

    @Test
    fun `본문의 대사와 시스템창을 제목으로 잡지 않는다`() {
        val bracket = ChapterDetector.detect(novel()).firstOrNull { it.name == "< 제목 >" }
        assertTrue("「」『』【】 를 제목으로 잡았다: ${bracket?.count}건", bracket == null)
    }

    @Test
    fun `번호가 아닌 샵 제목도 따로 잡는다`() {
        val lines = ArrayList<String>()
        for (t in listOf("프롤로그", "1부 시작", "에필로그", "작가 후기")) {
            lines.add("#$t")
            lines.add("")
            repeat(40) { lines.add("본문입니다. 그날의 일은 아무도 기억하지 못했다.") }
            lines.add("")
        }
        val best = ChapterDetector.detect(lines).first()
        assertEquals("마크다운 #", best.name)
        assertEquals(4, best.count)
    }
}
