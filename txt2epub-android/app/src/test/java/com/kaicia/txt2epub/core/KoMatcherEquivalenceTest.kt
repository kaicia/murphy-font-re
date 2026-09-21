package com.kaicia.txt2epub.core

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.random.Random

/**
 * 빠른 판정기가 예전 정규식과 같은 답을 내는지 확인한다.
 *
 * 속도 때문에 `^\s*(?:\S.{0,48}?\s+)?제?\s*(\d+)\s*화(\s|$|[.:\-–—])` 를
 * 직접 훑는 코드로 바꿨다. 이 파일은 그 교체가 판정 결과를 바꾸지 않았다는
 * 근거다. 바뀌면 741화 파일에서 챕터가 다르게 잘린다.
 */
class KoMatcherEquivalenceTest {

    /** 교체 전에 쓰던 정규식. 비교 기준이라 손대면 안 된다. */
    private fun oldLoose(unit: String) =
        Regex("""^\s*(?:\S.{0,48}?\s+)?제?\s*(\d+)\s*$unit(\s|$|[.:\-–—])""")

    private fun oldStrict(unit: String) =
        Regex("""^\s*제?\s*(\d+)\s*$unit(\s|$|[.:\-–—])""")

    /** (검출됨?, 번호) 를 같이 본다. 번호가 null이어도 검출은 된 경우가 있다. */
    private fun oldHit(rx: Regex, t: String): Pair<Boolean, Int?> {
        val m = rx.find(t) ?: return false to null
        val g = m.groupValues.getOrNull(1)
        val n = if (g != null && g.isNotEmpty() && g.all { it.isDigit() }) g.toIntOrNull() else null
        return true to n
    }

    private fun newHit(t: String, unit: Char, loose: Boolean): Pair<Boolean, Int?> {
        val k = ChapterDetector.matchKo(t, unit, loose) ?: return false to null
        return true to k.num
    }

    private fun check(t: String) {
        for (unit in listOf("화", "장", "회", "권", "편", "부")) {
            assertEquals(
                "느슨 · unit=$unit · [$t]",
                oldHit(oldLoose(unit), t),
                newHit(t, unit[0], true)
            )
            assertEquals(
                "엄격 · unit=$unit · [$t]",
                oldHit(oldStrict(unit), t),
                newHit(t, unit[0], false)
            )
        }
    }

    @Test
    fun `실제 웹소설에서 나오는 제목 형식들`() {
        listOf(
            "1화", "741화", "제1화", "제 1화", "제  12  화", "12 화",
            "아포칼립스에 집을 숨김 1화", "아포칼립스에 집을 숨김 741화",
            "아포칼립스에 집을 숨김 제741화", "외전 3화", "3화.", "3화:", "3화 - 귀환",
            "3화—끝", "3화–시작", "123화 사건 456화", "문제 12화", "제목 제 12화",
            "6478화", "1화2화", "화", "1", "제화", "0화", "007화",
            "1화입니다", "무언가 1화 뒤에", "  앞뒤 공백 5화  ".trim(),
            "가".repeat(48) + " 12화", "가".repeat(49) + " 12화",
            "가".repeat(50) + " 12화", "가".repeat(80) + " 12화",
            "탭\t12화", "12\t화", "여러  칸  띄고  12화", "12화\t",
            "", "   ", "제목만 있고 숫자 없음", "2025년 1월 3화",
            "1권 1화", "1화 1권", "(1화)", "[1화]", "1.화", "１화",
            "99999999999화", "2147483648화", "2147483647화", "제99999999999화",
            "앞말 99999999999화"
        ).forEach { check(it) }
    }

    @Test
    fun `무작위 문자열에서도 답이 같다`() {
        val alphabet = "0123456789 제화장회권편부가나다.:-–—\t".toCharArray()
        val rnd = Random(7)
        repeat(60_000) {
            val n = rnd.nextInt(0, 24)
            val sb = StringBuilder(n)
            repeat(n) { sb.append(alphabet[rnd.nextInt(alphabet.size)]) }
            check(sb.toString().trim())
        }
    }

    @Test
    fun `접두사 길이 경계가 정규식과 같다`() {
        // 정규식의 \S.{0,48}? 는 앞말 1~49자까지만 받는다
        for (len in 45..53) {
            check("가".repeat(len) + " 7화")
            check("가".repeat(len) + "  7화")
        }
    }
}
