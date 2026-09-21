package com.kaicia.txt2epub.core

/**
 * 챕터 구분 자동 판정.
 *
 * 여러 후보 패턴을 모두 시험해보고 네 가지 지표로 점수를 매겨 1등을 고른다.
 *  - 번호 연속성 (42%) : 추출한 숫자가 1씩 증가하는가
 *  - 길이 균일도 (20%) : 챕터별 길이의 변동계수
 *  - 본문 커버리지 (18%) : 파일 전체에 고르게 퍼져 있는가
 *  - 크기/개수 적정성 (20%)
 */
object ChapterDetector {

    data class Pattern(val name: String, val regex: Regex)

    data class Hit(val line: Int, val title: String, val num: Int?)

    data class Candidate(
        val name: String,
        val hits: List<Hit>,
        val score: Double,
        val seq: Double,
        val evenness: Double,
        val coverage: Double,
        val meanSize: Double,
        val dropped: Int
    ) {
        val count: Int get() = hits.size
        /** 신뢰도 표시용 */
        val confidence: String
            get() = when {
                score >= 0.60 -> "높음"
                score >= 0.40 -> "보통"
                else -> "낮음"
            }
    }

    data class Chapter(val title: String, val fromLine: Int, val toLine: Int)

    /** 점수가 이 값 미만이면 패턴 없음으로 본다. */
    const val MIN_SCORE = 0.22

    /** 제목 줄로 인정할 최대 길이. */
    private const val MAX_TITLE_LEN = 80

    private fun ko(unit: String) =
        Regex("""^\s*(?:\S.{0,48}?\s+)?제?\s*(\d+)\s*$unit(\s|$|[.:\-–—])""")

    val PATTERNS: List<Pattern> = listOf(
        Pattern("N화", ko("화")),
        Pattern("N화 (엄격)", Regex("""^\s*제?\s*(\d+)\s*화(\s|$|[.:\-–—])""")),
        Pattern("N장", ko("장")),
        Pattern("N장 (엄격)", Regex("""^\s*제?\s*(\d+)\s*장(\s|$|[.:\-–—])""")),
        Pattern("N회", ko("회")),
        Pattern("N권", ko("권")),
        Pattern("N편", ko("편")),
        Pattern("N부", ko("부")),
        Pattern("Chapter N", Regex("""^\s*Chapter\s+(\d+)\b""")),
        Pattern("CHAPTER N", Regex("""^\s*CHAPTER\s+(\d+)\b""")),
        Pattern("Part N", Regex("""^\s*Part\s+(\d+)\b""")),
        Pattern("Episode N", Regex("""^\s*Episode\s+(\d+)\b""")),
        Pattern("숫자 + 점", Regex("""^\s*(\d+)\s*[.、]\s*\S""")),
        Pattern("마크다운 #", Regex("""^\s*#+\s+(\S)""")),
        Pattern("[N]", Regex("""^\s*[\[(<](\d+)[\])>]""")),
        Pattern("= 구분선 =", Regex("""^\s*[=\-–—*_]{3,}\s*$""")),
        Pattern("숫자만", Regex("""^\s*(\d+)\s*$"""))
    )

    /** 각 줄의 시작 오프셋(문자 기준). 길이 계산에 쓴다. */
    private fun lineOffsets(lines: List<String>): LongArray {
        val off = LongArray(lines.size + 1)
        var acc = 0L
        for (i in lines.indices) {
            off[i] = acc
            acc += lines[i].length + 1
        }
        off[lines.size] = acc
        return off
    }

    fun detect(lines: List<String>): List<Candidate> {
        val off = lineOffsets(lines)
        val total = off[lines.size].coerceAtLeast(1L)
        return PATTERNS.mapNotNull { evaluate(it, lines, off, total) }
            .sortedByDescending { it.score }
    }

    private fun evaluate(
        pat: Pattern,
        lines: List<String>,
        off: LongArray,
        total: Long
    ): Candidate? {
        val raw = ArrayList<Hit>()
        for (i in lines.indices) {
            val t = lines[i].trim()
            if (t.isEmpty() || t.length > MAX_TITLE_LEN) continue
            val m = pat.regex.find(t) ?: continue
            val g = m.groupValues.getOrNull(1)
            val n = if (g != null && g.isNotEmpty() && g.all { it.isDigit() }) g.toIntOrNull() else null
            raw.add(Hit(i, t, n))
        }
        if (raw.size < 2) return null

        val hits = refine(raw)
        if (hits.size < 2) return null
        val dropped = raw.size - hits.size

        // 챕터별 크기
        val sizes = DoubleArray(hits.size) { k ->
            val start = off[hits[k].line]
            val end = if (k + 1 < hits.size) off[hits[k + 1].line] else total
            (end - start).toDouble()
        }
        val mean = sizes.average()
        val sd = kotlin.math.sqrt(sizes.sumOf { (it - mean) * (it - mean) } / sizes.size)
        val cv = if (mean > 0) sd / mean else 99.0
        val evenness = 1.0 / (1.0 + cv)

        // 번호 연속성
        val nums = hits.mapNotNull { it.num }
        var seq = 0.0
        if (nums.size >= 2 && nums.size >= hits.size * 0.9) {
            var good = 0.0
            for (n in 1 until nums.size) {
                if (nums[n] == nums[n - 1] + 1) good += 1.0
                else if (nums[n] > nums[n - 1]) good += 0.4   // 결번 허용
            }
            seq = good / (nums.size - 1)
            if (nums.first() <= 2) seq = minOf(1.0, seq + 0.08)
        }

        val coverage =
            ((off[hits.last().line] - off[hits.first().line]).toDouble() / total).coerceAtLeast(0.0)

        val sizeFit = when {
            mean in 400.0..400_000.0 -> 1.0
            mean < 400.0 -> mean / 400.0
            else -> 0.25
        }

        var countFit =
            if (hits.size >= 3) minOf(1.0, kotlin.math.ln(hits.size.toDouble()) / kotlin.math.ln(400.0))
            else 0.3
        if (mean < 200) countFit *= 0.2   // 조각조각 잘렸다는 신호

        val score = seq * 0.42 + evenness * 0.20 + coverage * 0.18 + sizeFit * 0.12 + countFit * 0.08

        return Candidate(pat.name, hits, score, seq, evenness, coverage, mean, dropped)
    }

    /**
     * 번호 순서에서 튀는 오탐을 제거한다.
     *
     * 1차: 최장 증가 부분수열만 남긴다. 본문 중간에 우연히 제목처럼 보이는 줄
     *      (예: 잘못 붙은 큰 번호)은 수열에서 빠진다.
     * 2차: 양 끝의 급점프를 잘라낸다. 끝에 붙은 오탐은 여전히 "증가"라서
     *      1차만으로는 걸러지지 않는다.
     */
    fun refine(hits: List<Hit>): List<Hit> {
        val numbered = hits.count { it.num != null }
        if (numbered < hits.size * 0.8 || numbered < 4) return hits

        val tails = ArrayList<Int>()
        val prev = IntArray(hits.size) { -1 }
        for (h in hits.indices) {
            val v = hits[h].num ?: continue
            var lo = 0
            var hi = tails.size
            while (lo < hi) {                      // 엄격 증가: v 이상인 첫 자리
                val mid = (lo + hi) ushr 1
                if (hits[tails[mid]].num!! < v) lo = mid + 1 else hi = mid
            }
            prev[h] = if (lo > 0) tails[lo - 1] else -1
            if (lo == tails.size) tails.add(h) else tails[lo] = h
        }
        if (tails.isEmpty()) return hits

        val keep = HashSet<Int>()
        var cur = tails.last()
        while (cur != -1) {
            keep.add(cur)
            cur = prev[cur]
        }
        val out = ArrayList(hits.filterIndexed { i, _ -> i in keep })

        if (out.size >= 5) {
            val steps = (1 until out.size).map { out[it].num!! - out[it - 1].num!! }.sorted()
            val med = steps[steps.size / 2].coerceAtLeast(1)
            val limit = maxOf(25, med * 12)
            while (out.size >= 4 && out[out.size - 1].num!! - out[out.size - 2].num!! > limit) {
                out.removeAt(out.size - 1)
            }
            while (out.size >= 4 && out[1].num!! - out[0].num!! > limit) {
                out.removeAt(0)
            }
        }
        return if (out.size >= 2) out else hits
    }

    private val NUMERIC_TITLE = Regex("""^[\s\d=\-–—*_.\[\]()<>]+$""")

    /** 검출 결과를 실제 챕터 경계로 바꾼다. */
    fun buildChapters(hits: List<Hit>, lines: List<String>, fallbackTitle: String): List<Chapter> {
        if (hits.isEmpty()) {
            return listOf(Chapter(fallbackTitle, 0, lines.size))
        }
        val out = ArrayList<Chapter>()

        if (hits.first().line > 0) {
            val preHasText = (0 until hits.first().line).any { lines[it].isNotBlank() }
            val preLen = (0 until hits.first().line).sumOf { lines[it].trim().length }
            if (preHasText && preLen > 40) out.add(Chapter("머리말", 0, hits.first().line))
        }

        for (j in hits.indices) {
            val start = hits[j].line
            val end = if (j + 1 < hits.size) hits[j + 1].line else lines.size
            var title = hits[j].title
            var bodyStart = start + 1

            // 제목이 번호뿐이거나 구분선이면 다음 줄을 제목으로 끌어올린다
            if (NUMERIC_TITLE.matches(title)) {
                var k = bodyStart
                while (k < end && k < bodyStart + 3) {
                    val s = lines[k].trim()
                    if (s.isNotEmpty() && s.length <= MAX_TITLE_LEN) {
                        title = (if (title.isBlank()) "" else title.trim() + " ") + s
                        bodyStart = k + 1
                        break
                    }
                    k++
                }
            }
            val finalTitle = title.trim().ifEmpty { "제${j + 1}장" }
            out.add(Chapter(finalTitle, bodyStart, end))
        }
        return out
    }

    /** 사용자가 직접 지정한 정규식으로 검출한다. */
    fun detectManual(lines: List<String>, regex: Regex, maxTitleLen: Int = 60): List<Hit> {
        val out = ArrayList<Hit>()
        for (i in lines.indices) {
            val t = lines[i].trim()
            if (t.isEmpty() || t.length > maxTitleLen) continue
            val m = regex.find(t) ?: continue
            val g = m.groupValues.getOrNull(1)
            val n = if (g != null && g.isNotEmpty() && g.all { it.isDigit() }) g.toIntOrNull() else null
            out.add(Hit(i, t, n))
        }
        return out
    }
}
