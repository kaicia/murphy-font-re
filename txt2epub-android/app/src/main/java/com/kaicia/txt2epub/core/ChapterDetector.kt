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

    /**
     * 후보 패턴.
     *
     * [unit]이 있으면 `제?\s*(\d+)\s*화` 꼴을 정규식 대신 직접 훑는다.
     * 원래 쓰던 `^\s*(?:\S.{0,48}?\s+)?제?\s*(\d+)\s*화…` 는 앞자리 접두사를
     * 게으른 수량자로 잡느라 줄마다 수백 번씩 역추적한다. 32만 줄짜리 파일에서는
     * 그게 쌓여 기기에서 멈춘 것처럼 보였다. 판정 결과는 그대로 두고 찾는 방법만 바꾼다.
     */
    class Pattern internal constructor(
        val name: String,
        internal val regex: Regex?,
        internal val unit: Char?,
        internal val allowPrefix: Boolean,
        /** 첫 글자가 이 중 하나일 때만 정규식을 돌린다. 빈 문자열이면 숫자만 받는다. */
        internal val head: String = ""
    ) {
        constructor(name: String, head: String, regex: Regex) :
                this(name, regex, null, false, head)

        /** 줄 첫 글자로 미리 걸러낸다. 정규식은 통과한 줄에만 돌린다. */
        internal fun headOk(c: Char): Boolean =
            if (head.isEmpty()) c.isDigit() else head.indexOf(c) >= 0
    }

    private fun ko(unit: String) = Pattern("N$unit", null, unit[0], true)
    private fun koStrict(unit: String) = Pattern("N$unit (엄격)", null, unit[0], false)

    val PATTERNS: List<Pattern> = listOf(
        ko("화"),
        koStrict("화"),
        ko("장"),
        koStrict("장"),
        ko("회"),
        ko("권"),
        ko("편"),
        ko("부"),
        Pattern("Chapter N", "C", Regex("""^\s*Chapter\s+(\d+)\b""")),
        Pattern("CHAPTER N", "C", Regex("""^\s*CHAPTER\s+(\d+)\b""")),
        Pattern("Part N", "P", Regex("""^\s*Part\s+(\d+)\b""")),
        Pattern("Episode N", "E", Regex("""^\s*Episode\s+(\d+)\b""")),
        Pattern("숫자 + 점", "", Regex("""^\s*(\d+)\s*[.、]\s*\S""")),
        Pattern("마크다운 #", "#", Regex("""^\s*#+\s+(\S)""")),
        Pattern("[N]", "[(<", Regex("""^\s*[\[(<](\d+)[\])>]""")),
        Pattern("= 구분선 =", "=-–—*_", Regex("""^\s*[=\-–—*_]{3,}\s*$""")),
        Pattern("숫자만", "", Regex("""^\s*(\d+)\s*$"""))
    )

    /**
     * 정규식 `\s` 와 같은 범위. Char.isWhitespace()는 전각 공백(U+3000)까지 포함해서
     * 범위가 다르다. 판정 결과가 달라지지 않게 정규식 쪽에 맞춘다.
     */
    private fun isSp(c: Char) =
        c == ' ' || c == '\t' || c == '\n' || c == '\u000B' || c == '\u000C' || c == '\r'

    /** 제목 줄 뒤에 올 수 있는 구분 문자. 원래 정규식의 `(\s|$|[.:\-–—])` 와 같다. */
    private fun isTail(c: Char) = isSp(c) || c == '.' || c == ':' ||
            c == '-' || c == '–' || c == '—'

    /** 접두사로 인정할 최대 길이. 원래 정규식의 `\S.{0,48}?` 와 같다. */
    private const val MAX_PREFIX = 49

    /** 찾았으면 [Ko], 못 찾았으면 null. [Ko.num]은 숫자가 Int 범위를 넘으면 null이다. */
    internal class Ko(@JvmField val num: Int?)

    /** 정규식 `\d` 와 같은 범위. Char.isDigit()은 전각 숫자(１)까지 받아 범위가 다르다. */
    private fun isNum(c: Char) = c in '0'..'9'

    /**
     * `제?\s*(\d+)\s*단위` 를 왼쪽부터 훑는다. 없으면 null.
     *
     * 원래 정규식의 우선순위를 그대로 따른다. 접두사가 붙은 것을 먼저 보고,
     * 없으면 줄 맨 앞에서 시작하는 것을 쓴다.
     */
    internal fun matchKo(t: CharArray, start: Int, end: Int, unit: Char, allowPrefix: Boolean): Ko? {
        var zeroFallback: Ko? = null
        var i = start
        while (i < end) {
            if (!isNum(t[i])) { i++; continue }
            var b = i
            while (b < end && isNum(t[b])) b++

            // 숫자 뒤: 공백을 건너뛰고 단위 글자, 그 뒤는 끝이거나 구분 문자
            var c = b
            while (c < end && isSp(t[c])) c++
            if (c >= end || t[c] != unit) { i = b; continue }
            if (c + 1 < end && !isTail(t[c + 1])) { i = b; continue }

            // 자릿수가 너무 많으면 Int로 못 담는다. 그래도 검출은 된 것으로 친다.
            val num = String(t, i, b - i).toIntOrNull()

            // 숫자 앞: '제'가 붙어 있으면 그 자리도 시작점 후보다 ('제 12화')
            var j = i
            while (j > start && isSp(t[j - 1])) j--
            val hasJe = j > start && t[j - 1] == '제'

            if (i == start || (hasJe && j - 1 == start)) {
                if (zeroFallback == null) zeroFallback = Ko(num)
            }
            if (allowPrefix && (prefixOk(t, start, i) || (hasJe && prefixOk(t, start, j - 1)))) {
                return Ko(num)                  // 접두사가 붙은 쪽이 우선
            }
            i = b
        }
        return zeroFallback
    }

    /** 시험에서 쓰는 문자열 판. */
    internal fun matchKo(t: String, unit: Char, allowPrefix: Boolean): Ko? {
        val a = t.toCharArray()
        return matchKo(a, 0, a.size, unit, allowPrefix)
    }

    private fun contains(chars: CharArray, from: Int, to: Int, c: Char): Boolean {
        for (i in from until to) if (chars[i] == c) return true
        return false
    }

    /** 토큰 앞부분이 `\S.{0,48}?\s+` 로 받아들여지는지 본다. */
    private fun prefixOk(t: CharArray, start: Int, at: Int): Boolean {
        if (at == start) return false
        if (!isSp(t[at - 1])) return false                  // 공백으로 끝나야 한다
        var e = at
        while (e > start && isSp(t[e - 1])) e--
        return (e - start) in 1..MAX_PREFIX                  // 앞말이 1~49자
    }

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

    /**
     * 짧고 비어 있지 않은 줄만 추려 둔 것. 글자는 복사하지 않고 위치만 들고 있는다.
     *
     * 예전에는 패턴마다 32만 줄을 다시 훑고 줄마다 trim을 다시 했다. 17번 반복하면
     * 그대로 17배다. 게다가 잘라낸 String을 들고 있으면 파일만 한 메모리를 한 벌 더 쓴다.
     * 한 번만 위치를 추려 모든 패턴이 나눠 쓴다.
     */
    internal class Lines(
        @JvmField val chars: CharArray,
        @JvmField val lineNo: IntArray,
        @JvmField val from: IntArray,
        @JvmField val to: IntArray,
        @JvmField val n: Int
    )

    /** 정규식에 넘길 때 글자를 복사하지 않으려고 쓰는 창. 한 개를 계속 옮겨 쓴다. */
    private class Window(@JvmField val chars: CharArray) : CharSequence {
        @JvmField var from = 0
        @JvmField var to = 0
        override val length: Int get() = to - from
        override fun get(index: Int): Char = chars[from + index]
        override fun subSequence(startIndex: Int, endIndex: Int): CharSequence =
            String(chars, from + startIndex, endIndex - startIndex)
        override fun toString(): String = String(chars, from, to - from)
    }

    private fun prepare(lines: List<String>): Lines {
        val src = lines as? TextLines ?: materialize(lines)
        val cap = src.size / 2 + 16
        var lineNo = IntArray(cap)
        var from = IntArray(cap)
        var to = IntArray(cap)
        var n = 0

        for (i in 0 until src.size) {
            var a = src.from[i]
            var b = src.to[i]
            if (b - a > MAX_TITLE_LEN + 8) continue          // 긴 줄은 제목일 수 없다
            while (a < b && src.chars[a].isWhitespace()) a++  // trim
            while (b > a && src.chars[b - 1].isWhitespace()) b--
            if (a == b || b - a > MAX_TITLE_LEN) continue

            if (n == lineNo.size) {
                lineNo = lineNo.copyOf(n * 2)
                from = from.copyOf(n * 2)
                to = to.copyOf(n * 2)
            }
            lineNo[n] = i
            from[n] = a
            to[n] = b
            n++
        }
        return Lines(src.chars, lineNo, from, to, n)
    }

    /** 시험용으로 넘어오는 평범한 List<String>을 같은 모양으로 바꾼다. */
    private fun materialize(lines: List<String>): TextLines {
        var total = 0
        for (l in lines) total += l.length + 1
        val chars = CharArray(total)
        val from = IntArray(lines.size)
        val to = IntArray(lines.size)
        var at = 0
        lines.forEachIndexed { i, l ->
            from[i] = at
            l.toCharArray(chars, at)
            at += l.length
            to[i] = at
            chars[at++] = '\n'
        }
        return TextLines(chars, from, to, lines.size)
    }

    /**
     * 후보 패턴을 모두 시험해 점수순으로 돌려준다.
     *
     * [onProgress]는 패턴 하나를 끝낼 때마다 불린다. 큰 파일에서 화면이
     * 멈춘 것처럼 보이지 않게 진행 상황을 내보내기 위한 것이다.
     */
    fun detect(
        lines: List<String>,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }
    ): List<Candidate> {
        val off = lineOffsets(lines)
        val total = off[lines.size].coerceAtLeast(1L)
        val prepared = prepare(lines)
        val out = ArrayList<Candidate>(PATTERNS.size)
        PATTERNS.forEachIndexed { i, p ->
            evaluate(p, prepared, off, total)?.let { out.add(it) }
            onProgress(i + 1, PATTERNS.size)
        }
        return out.sortedByDescending { it.score }
    }

    private fun evaluate(
        pat: Pattern,
        lines: Lines,
        off: LongArray,
        total: Long
    ): Candidate? {
        val raw = ArrayList<Hit>()
        val chars = lines.chars
        val unit = pat.unit
        if (unit != null) {
            for (k in 0 until lines.n) {
                val a = lines.from[k]
                val b = lines.to[k]
                if (!contains(chars, a, b, unit)) continue   // 단위 글자가 없으면 볼 것도 없다
                val m = matchKo(chars, a, b, unit, pat.allowPrefix) ?: continue
                raw.add(Hit(lines.lineNo[k], String(chars, a, b - a), m.num))
            }
        } else {
            val rx = pat.regex!!
            val win = Window(chars)
            for (k in 0 until lines.n) {
                val a = lines.from[k]
                val b = lines.to[k]
                if (!pat.headOk(chars[a])) continue          // 첫 글자로 먼저 거른다
                win.from = a
                win.to = b
                val m = rx.find(win) ?: continue
                val g = m.groupValues.getOrNull(1)
                val n = if (g != null && g.isNotEmpty() && g.all { it.isDigit() }) g.toIntOrNull() else null
                raw.add(Hit(lines.lineNo[k], String(chars, a, b - a), n))
            }
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
