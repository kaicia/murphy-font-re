package com.kaicia.txt2epub.core

import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 출력 파일명 규칙.
 *
 * 템플릿 토큰을 치환한 뒤 파일시스템에서 문제되는 문자를 정리한다.
 * 길이 제한은 글자 수가 아니라 UTF-8 바이트 기준이다.
 * 한글은 글자당 3바이트라 100자면 300바이트가 되어 상한(255)을 넘는다.
 */
object FileNamer {

    enum class SpaceMode { KEEP, UNDERSCORE, DASH, NONE }
    enum class CaseMode { KEEP, LOWER, UPPER }

    data class Tokens(
        val title: String,
        val author: String,
        val chapters: Int,
        val source: String,
        val language: String,
        val publisher: String,
        val first: String,
        val last: String,
        /** 분할 저장일 때의 권 번호. 0이면 분할이 아니다. */
        val volume: Int = 0,
        val volumeCount: Int = 0
    )

    val PRESETS: List<Pair<String, String>> = listOf(
        "제목만" to "{title}",
        "제목 - 지은이" to "{title} - {author}",
        "제목 (N화)" to "{title} ({chapters}화)",
        "제목 [1-741]" to "{title} [{first}-{last}]",
        "제목_날짜" to "{title}_{date}",
        "원본 파일명" to "{source}"
    )

    /** 확장자와 여유분을 뺀 본체 최대 바이트. */
    private const val MAX_BYTES = 180

    // 안드로이드는 ICU 정규식이라 짝 없는 } 와 ] 를 문법 오류로 막는다.
    // 데스크톱 JVM은 그냥 글자로 받아줘서 시험에서는 드러나지 않는다. 반드시 escape 한다.
    private val TOKEN = Regex("""\{(\w+)\}""")
    private val FORBIDDEN = Regex("""[/\\:*?"<>|]""")
    private val CONTROL = Regex("""[\u0000-\u001f\u007f]""")
    private val EMPTY_BRACKETS = Regex("""\(\s*\)|\[\s*\]""")
    private val MULTI_SEP = Regex("""[_\-]{2,}""")

    fun build(
        template: String,
        t: Tokens,
        space: SpaceMode = SpaceMode.KEEP,
        case: CaseMode = CaseMode.KEEP,
        now: Date = Date()
    ): String {
        val map = buildMap(t, now)
        val tpl = template.ifBlank { "{title}" }
        var s = TOKEN.replace(tpl) { m ->
            map[m.groupValues[1]] ?: ""     // 미지 토큰은 제거
        }

        s = FORBIDDEN.replace(s, "")
        s = CONTROL.replace(s, "")
        s = s.replace(Regex("""\s+"""), " ")
        s = EMPTY_BRACKETS.replace(s, "")   // 토큰이 빠져 생긴 빈 괄호
        s = s.replace(Regex("""\s+"""), " ")
        s = s.trim().trim('.', '-', '_').trim()

        s = when (space) {
            SpaceMode.UNDERSCORE -> s.replace(' ', '_')
            SpaceMode.DASH -> s.replace(' ', '-')
            SpaceMode.NONE -> s.replace(" ", "")
            SpaceMode.KEEP -> s
        }
        s = when (case) {
            CaseMode.LOWER -> s.lowercase(Locale.ROOT)
            CaseMode.UPPER -> s.uppercase(Locale.ROOT)
            CaseMode.KEEP -> s
        }
        s = MULTI_SEP.replace(s) { it.value.first().toString() }
        if (s.isBlank()) s = "book"

        // 분할 저장: 권 번호가 반드시 파일명에 남아야 한다.
        // 길이 제한에 걸려 번호가 잘리면 권마다 같은 이름이 되어 덮어쓰기가 난다.
        if (t.volume > 0) {
            val sep = when (space) {
                SpaceMode.UNDERSCORE -> "_"
                SpaceMode.DASH -> "-"
                SpaceMode.NONE -> ""
                SpaceMode.KEEP -> " "
            }
            return if (tpl.contains("{vol}")) {
                val cut = clampBytes(s, MAX_BYTES)
                // 번호가 끝에 있어 잘려 나갔으면 다시 붙인다
                if (cut.contains(t.volume.toString())) cut + ".epub"
                else clampBytes(cut, MAX_BYTES - 8) + sep + t.volume + ".epub"
            } else {
                val suffix = sep + "(" + t.volume + "권)"
                val room = MAX_BYTES - suffix.toByteArray(StandardCharsets.UTF_8).size
                clampBytes(s, room) + suffix + ".epub"
            }
        }
        return clampBytes(s, MAX_BYTES) + ".epub"
    }

    private fun buildMap(t: Tokens, now: Date): Map<String, String> {
        val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(now)
        val datetime = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(now)
        return mapOf(
            "title" to t.title.ifBlank { t.source.ifBlank { "제목없음" } },
            "author" to t.author.ifBlank { "미상" },
            "chapters" to t.chapters.toString(),
            "source" to t.source.ifBlank { "untitled" },
            "lang" to t.language,
            "publisher" to t.publisher,
            "date" to date,
            "datetime" to datetime,
            "first" to t.first.ifBlank { "1" },
            "last" to t.last.ifBlank { t.chapters.coerceAtLeast(1).toString() },
            "vol" to if (t.volume > 0) t.volume.toString() else "",
            "vols" to if (t.volumeCount > 0) t.volumeCount.toString() else ""
        )
    }

    /** UTF-8 바이트 기준으로 자른다. 글자 경계는 유지된다. */
    fun clampBytes(s: String, max: Int): String {
        if (s.toByteArray(StandardCharsets.UTF_8).size <= max) return s
        var lo = 0
        var hi = s.length
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (s.substring(0, mid).toByteArray(StandardCharsets.UTF_8).size <= max) lo = mid else hi = mid - 1
        }
        val cut = s.substring(0, lo).trimEnd(' ', '_', '-', '.')
        return cut.ifBlank { "book" }
    }

    /** 챕터 제목들에서 첫/끝 번호를 뽑는다. */
    fun numberRange(titles: List<String>): Pair<String, String> {
        val rx = Regex("""(\d+)""")
        var first = ""
        var last = ""
        for (t in titles) {
            val m = rx.find(t) ?: continue
            if (first.isEmpty()) first = m.groupValues[1]
            last = m.groupValues[1]
        }
        return first to last
    }
}
