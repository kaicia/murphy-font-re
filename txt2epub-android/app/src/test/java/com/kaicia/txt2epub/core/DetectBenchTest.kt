package com.kaicia.txt2epub.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * 실제 파일 크기로 돌려보는 성능 시험.
 *
 * 사용자 기기에서 741화(5,440,382자 / 325,417줄) 파일이 '챕터를 분석하는 중'에서
 * 멈춰 보였다. 같은 규모를 만들어 판정 시간을 잰다. 기기는 데스크톱보다
 * 몇 배 느리므로 여기서 넉넉히 빨라야 한다.
 */
class DetectBenchTest {

    private fun bigNovel(): List<String> {
        val rnd = Random(42)
        val words = listOf(
            "그는", "조용히", "문을", "열었다", "하늘은", "붉게", "물들어", "있었다",
            "아무도", "그날의", "일을", "기억하지", "못했다", "집은", "여전히", "거기",
            "있었고", "바람이", "불었다", "나는", "천천히", "걸음을", "옮겼다"
        )
        fun line(n: Int): String {
            val sb = StringBuilder()
            repeat(n) { sb.append(words[rnd.nextInt(words.size)]).append(' ') }
            return sb.toString().trim()
        }

        val lines = ArrayList<String>(340_000)
        for (i in 1..741) {
            lines.add(if (i % 3 == 0) "아포칼립스에 집을 숨김 ${i}화" else "${i}화")
            lines.add("")
            repeat(438) { k ->
                if (k % 5 == 0) lines.add("")
                else lines.add(line(2 + rnd.nextInt(6)))
            }
        }
        return lines
    }

    @Test
    fun `741화 파일 규모에서 판정이 빠르게 끝난다`() {
        val lines = bigNovel()
        val chars = lines.sumOf { it.length + 1 }
        println("줄 ${lines.size} · 글자 $chars")

        val t0 = System.currentTimeMillis()
        val best = ChapterDetector.detect(lines).first()
        val ms = System.currentTimeMillis() - t0
        println("판정 ${ms}ms · ${best.name} · ${best.count}개 · score=${"%.3f".format(best.score)}")

        assertEquals("N화", best.name)
        assertEquals(741, best.count)
        assertTrue("판정이 너무 느리다: ${ms}ms", ms < 3000)
    }
}
