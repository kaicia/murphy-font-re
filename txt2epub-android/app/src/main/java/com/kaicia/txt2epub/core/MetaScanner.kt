package com.kaicia.txt2epub.core

/**
 * 본문 안에 적혀 있는 서지 정보를 읽는다. (오프라인)
 *
 * 웹 버전의 '파일에서 찾기'와 같은 일을 한다. 네트워크 조회는 추측이 섞이지만
 * 이쪽은 파일에 실제로 적힌 값이라 맞으면 정확하다. 그래서 웹 조회보다 먼저 쓴다.
 *
 * 머리말·판권지는 파일 앞이나 맨 뒤에 붙는 경우가 많아 양쪽을 모두 본다.
 */
object MetaScanner {

    data class Found(
        val title: String = "",
        val author: String = "",
        val publisher: String = ""
    ) {
        val isEmpty: Boolean get() = title.isBlank() && author.isBlank() && publisher.isBlank()
    }

    /** 앞에서 볼 줄 수. 본문이 시작되면 라벨이 나올 일은 거의 없다. */
    private const val HEAD_LINES = 400

    /** 뒤에서 볼 줄 수. 판권지가 끝에 붙는 책이 있다. */
    private const val TAIL_LINES = 60

    /** 값으로 인정할 최대 길이. 이보다 길면 라벨이 아니라 본문 문장이다. */
    private const val MAX_VALUE_LEN = 40

    private val TITLE_LABELS = listOf("제목", "title", "책이름", "책 제목", "도서명", "작품명")
    private val AUTHOR_LABELS = listOf("작가", "저자", "지은이", "글쓴이", "글", "author", "writer")
    private val PUBLISHER_LABELS =
        listOf("출판사", "발행처", "연재처", "펴낸곳", "publisher", "플랫폼", "연재 사이트", "레이블")

    /** `라벨 : 값` / `라벨 - 값` / `라벨　값` 을 모두 받는다. */
    private fun labelPattern(label: String) = Regex(
        """^\s*[\[(<「『]?\s*${Regex.escape(label)}\s*[\])>」』]?\s*[:：\-–—=]\s*(.+)$""",
        RegexOption.IGNORE_CASE
    )

    /** 구분자 없이 `작가 홍길동` 처럼 띄어쓰기만 쓴 표기. */
    private fun loosePattern(label: String) = Regex(
        """^\s*${Regex.escape(label)}\s+(\S.{0,38})$""",
        RegexOption.IGNORE_CASE
    )

    fun scan(lines: List<String>): Found {
        val head = lines.take(HEAD_LINES)
        val tail = if (lines.size > HEAD_LINES) lines.takeLast(TAIL_LINES) else emptyList()
        val scope = head + tail

        return Found(
            title = pick(scope, TITLE_LABELS),
            author = pick(scope, AUTHOR_LABELS),
            publisher = pick(scope, PUBLISHER_LABELS)
        )
    }

    private fun pick(lines: List<String>, labels: List<String>): String {
        // 구분자가 있는 표기를 먼저 본다. 'X 작가의 신작' 같은 문장에 걸리지 않는다.
        for (line in lines) {
            val t = line.trim()
            if (t.isEmpty() || t.length > 120) continue
            for (label in labels) {
                val v = labelPattern(label).find(t)?.groupValues?.get(1) ?: continue
                val cleaned = clean(v)
                if (cleaned.isNotBlank()) return cleaned
            }
        }
        for (line in lines) {
            val t = line.trim()
            if (t.isEmpty() || t.length > 60) continue
            for (label in labels) {
                if (label.length < 2) continue          // '글' 같은 한 글자는 느슨한 매칭에서 뺀다
                val v = loosePattern(label).find(t)?.groupValues?.get(1) ?: continue
                val cleaned = clean(v)
                if (cleaned.isNotBlank()) return cleaned
            }
        }
        return ""
    }

    private val WRAPPERS = charArrayOf(
        '"', '\'', '“', '”', '‘', '’', '「', '」', '『', '』', '<', '>', '(', ')', '[', ']'
    )

    private fun clean(raw: String): String {
        // 여러 칸 띄어쓰기를 먼저 자른다. 공백을 정리한 뒤에는 경계가 사라진다.
        // '작가: 김작가    출판사: 아무개' 같은 한 줄 표기에서 앞 값만 남긴다.
        var v = raw.trim().split(Regex("""\s{2,}|\s*\|\s*|\t""")).first().trim()
        v = v.replace(Regex("""\s+"""), " ").trim()
        v = v.trim(*WRAPPERS).trim()
        v = v.trimEnd('.', ',', '·', ':', '：', '-', '–', '—').trim()
        if (v.length > MAX_VALUE_LEN) return ""
        // 값이 라벨만 남은 경우(예: '작가 :') 걸러낸다
        if (v.isBlank() || v.length < 1) return ""
        return v
    }
}
