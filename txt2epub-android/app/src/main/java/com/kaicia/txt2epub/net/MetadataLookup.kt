package com.kaicia.txt2epub.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

/**
 * 서지 정보 조회.
 *
 * 나무위키는 공개 API가 없어 문서 HTML을 받아 파싱한다.
 * 안드로이드 앱에서는 CORS가 적용되지 않고 사용자 기기 IP로 나가므로
 * 브라우저에서 불가능했던 조회가 여기서는 동작한다.
 *
 * 주의사항:
 *  - 나무위키는 DOM 구조가 자주 바뀌고 클래스명이 난독화되어 있다.
 *    그래서 클래스 선택자 대신 "작가" 같은 라벨 텍스트를 찾아
 *    그 옆 칸을 읽는 방식으로 짰다. 구조가 바뀌어도 라벨은 잘 안 바뀐다.
 *  - 짧은 간격의 반복 호출은 IP 차단을 부를 수 있어 캐시와 최소 간격을 둔다.
 *  - 가져오는 값은 작가명·연재처 같은 사실 정보로 한정한다.
 *    문서 설명문은 CC BY-NC-SA 대상이라 EPUB에 그대로 싣지 않는다.
 */
object MetadataLookup {

    data class Info(
        val source: String,
        val title: String,
        val author: String = "",
        val publisher: String = "",
        val year: String = "",
        val coverUrl: String = "",
        val pageUrl: String = ""
    ) {
        val hasAnything: Boolean get() = author.isNotBlank() || publisher.isNotBlank() || coverUrl.isNotBlank()
    }

    private val cache = HashMap<String, List<Info>>()
    private var lastCall = 0L
    private const val MIN_INTERVAL_MS = 1200L

    private suspend fun throttle() {
        val since = System.currentTimeMillis() - lastCall
        if (since < MIN_INTERVAL_MS) kotlinx.coroutines.delay(MIN_INTERVAL_MS - since)
        lastCall = System.currentTimeMillis()
    }

    private fun get(url: String): String? = Http.text(url)

    suspend fun downloadCover(url: String): Pair<ByteArray, String>? = withContext(Dispatchers.IO) {
        Http.image(url)
    }

    /**
     * 네이버책(키가 있을 때) + 나무위키 + 위키백과를 함께 조회한다.
     *
     * 네이버를 앞에 두는 이유: 출판된 책은 서지 정보가 정확하고 표지도 실제 표지다.
     * 나무위키는 웹소설 커버리지가 좋지만 값이 파싱에 의존해 흔들린다.
     */
    suspend fun search(
        query: String,
        naverId: String = "",
        naverSecret: String = ""
    ): List<Info> = withContext(Dispatchers.IO) {
        val q = query.trim()
        if (q.isEmpty()) return@withContext emptyList()
        val key = q + "|" + (if (naverId.isNotBlank()) "n" else "-")
        cache[key]?.let { return@withContext it }

        val out = ArrayList<Info>()
        runCatching { out.addAll(NaverBooks.search(q, naverId, naverSecret)) }
        throttle()
        runCatching { namu(q) }.getOrNull()?.let { out.add(it) }
        runCatching { out.addAll(wikipedia(q)) }

        val result = out.filter { it.hasAnything || it.pageUrl.isNotBlank() }
        cache[key] = result
        result
    }

    // ---------- 나무위키 ----------

    /** 정보 테이블의 라벨로 인정할 표현. */
    private val AUTHOR_LABELS = listOf("작가", "저자", "글", "원작", "글/그림", "지은이")
    private val PUBLISHER_LABELS = listOf("연재처", "출판사", "연재 사이트", "플랫폼", "레이블", "출판")
    private val YEAR_LABELS = listOf("연재 기간", "발매일", "출간일", "연재기간", "발행일")

    fun namu(title: String): Info? {
        val url = "https://namu.wiki/w/" + Http.enc(title).replace("+", "%20")
        val html = get(url) ?: return null
        // 문서가 없으면 나무위키는 편집 안내 페이지를 준다
        if (html.contains("해당 문서를 찾을 수 없습니다") || html.contains("문서가 존재하지 않습니다")) return null

        val doc = Jsoup.parse(html, url)
        val author = findByLabel(doc, AUTHOR_LABELS)
        val publisher = findByLabel(doc, PUBLISHER_LABELS)
        val yearRaw = findByLabel(doc, YEAR_LABELS)
        val year = Regex("""(\d{4})""").find(yearRaw)?.groupValues?.get(1) ?: ""

        val cover = doc.selectFirst("meta[property=og:image]")?.attr("content").orEmpty()

        if (author.isBlank() && publisher.isBlank() && cover.isBlank()) return null
        return Info(
            source = "나무위키",
            title = title,
            author = clean(author),
            publisher = clean(publisher),
            year = year,
            coverUrl = cover,
            pageUrl = url
        )
    }

    /**
     * 라벨 텍스트가 든 칸을 찾아 그 옆(또는 다음) 칸의 값을 읽는다.
     * 클래스명에 의존하지 않아 DOM이 바뀌어도 비교적 버틴다.
     */
    private fun findByLabel(doc: Document, labels: List<String>): String {
        for (cell in doc.select("th, td, dt, strong")) {
            val label = cell.text().trim()
            if (label.length > 12) continue
            if (labels.none { label == it || label.replace(" ", "") == it.replace(" ", "") }) continue

            val value = cell.nextElementSibling()?.text()?.trim()
                ?: cell.parent()?.nextElementSibling()?.text()?.trim()
            if (!value.isNullOrBlank() && value.length <= 120) return value
        }
        return ""
    }

    /** 각주 표시([1]), 괄호 주석, 꼬리 구두점을 정리한다. */
    private fun clean(s: String): String {
        var v = s.replace(Regex("""\[\d+]"""), "")
            .replace(Regex("""\[[^\]]{0,20}]"""), "")
            .replace(Regex("""\s+"""), " ")
            .trim()
            .trim(',', '.', '·', ':', '：')
        if (v.length > 60) v = v.substring(0, 60).trim()
        return v
    }

    // ---------- 위키백과 ----------

    private const val WIKI = "https://ko.wikipedia.org/w/api.php"

    fun wikipedia(query: String): List<Info> {
        val searchUrl = "$WIKI?action=query&list=search&srlimit=3&format=json&srsearch=" +
                Http.enc(query)
        val sj = get(searchUrl) ?: return emptyList()
        val titles = ArrayList<String>()
        runCatching {
            val arr = JSONObject(sj).getJSONObject("query").getJSONArray("search")
            for (i in 0 until arr.length()) titles.add(arr.getJSONObject(i).getString("title"))
        }
        if (titles.isEmpty()) return emptyList()

        val detailUrl = "$WIKI?action=query&format=json&prop=extracts|pageimages" +
                "&exintro=1&explaintext=1&exlimit=3&piprop=original&titles=" +
                Http.enc(titles.joinToString("|"))
        val dj = get(detailUrl) ?: return emptyList()

        val out = ArrayList<Info>()
        runCatching {
            val pages = JSONObject(dj).getJSONObject("query").getJSONObject("pages")
            for (key in pages.keys()) {
                val p = pages.getJSONObject(key)
                val t = p.optString("title")
                val ex = p.optString("extract").replace(Regex("""\s+"""), " ").trim()
                val cover = p.optJSONObject("original")?.optString("source").orEmpty()
                out.add(
                    Info(
                        source = "위키백과",
                        title = t,
                        author = guessAuthor(ex),
                        publisher = guessPublisher(ex),
                        year = Regex("""(\d{4})년""").find(ex)?.groupValues?.get(1) ?: "",
                        coverUrl = cover,
                        pageUrl = "https://ko.wikipedia.org/wiki/" +
                                Http.enc(t.replace(" ", "_"))
                    )
                )
            }
        }
        return out
    }

    private val BAD_NAME = Regex(
        """^(대한민국|한국|일본|미국|중국|해당|이것|그것|이후|당시|현재|작가|저자|소설|웹소설|작품)$"""
    )

    /**
     * 요약문에서 작가를 뽑는다.
     * 라벨("작가:") 패턴을 뒤에 두는 이유: "박작가가 쓴"처럼 이름 안에 '작가'가
     * 들어가면 라벨로 오인되어 엉뚱한 구간이 잡힌다.
     */
    private val AUTHOR_PATS = listOf(
        Regex("""([가-힣A-Za-z][가-힣A-Za-z0-9._-]{1,19})(?:이|가)\s*(?:쓴|집필한|연재한|저술한|창작한)"""),
        Regex("""([가-힣A-Za-z][가-힣A-Za-z0-9._-]{1,19})의\s*(?:웹소설|소설|장편소설|장편|판타지|만화)"""),
        Regex("""(?:^|[\s(\[「『·,])(?:작가|저자|지은이|글쓴이)\s*[:：]\s*([가-힣A-Za-z][가-힣A-Za-z0-9._-]{1,19})"""),
        Regex("""(?:^|[\s(\[「『·,])(?:작가|저자|지은이|글쓴이)\s+([가-힣A-Za-z][가-힣A-Za-z0-9._-]{1,19})""")
    )

    fun guessAuthor(s: String): String {
        if (s.isBlank()) return ""
        for (p in AUTHOR_PATS) {
            val g = p.find(s)?.groupValues?.getOrNull(1) ?: continue
            // 조사는 패턴이 이미 소비하므로 여기서 떼지 않는다.
            // ('박작가'의 끝 '가'를 조사로 오인해 '박작'이 되는 문제)
            val v = g.trim().trimEnd('.', ',', '、', '·', ':', '：')
            if (v.length in 2..20 && !BAD_NAME.matches(v)) return v
        }
        return ""
    }

    private val PLATFORMS = Regex(
        """(카카오페이지|네이버\s?시리즈|네이버\s?웹소설|문피아|조아라|노벨피아|리디북스|리디|톡소다|북팔|무툰)"""
    )

    fun guessPublisher(s: String): String {
        if (s.isBlank()) return ""
        PLATFORMS.find(s)?.let { return it.groupValues[1] }
        val m = Regex(
            """(?:출판사|발행처|연재처)\s*[:：]?\s*([가-힣A-Za-z][가-힣A-Za-z0-9 ._-]{1,20}?)(?:[,.、)\]]|에서|은|는|$)"""
        ).find(s)
        return m?.groupValues?.get(1)?.trim() ?: ""
    }
}
