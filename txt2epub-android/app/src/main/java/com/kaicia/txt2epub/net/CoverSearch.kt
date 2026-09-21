package com.kaicia.txt2epub.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * 표지 이미지 찾기.
 *
 * 구글 이미지 검색은 공개 API가 없다. 대신 키 없이 열려 있는 책 API 두 곳에서
 * 표지를 받아온다. 둘 다 실제 책 표지라서 문서 대표 이미지보다 맞을 확률이 높다.
 *
 *  - 구글 북스: 키 없이 하루 1,000회. 한국 출판물도 상당수 올라와 있다.
 *  - 오픈 라이브러리: 키 없음, 제한 느슨. 번역서·원서 커버리지가 좋다.
 *  - 네이버 책: 키를 넣었을 때만. 한국 책은 이쪽이 가장 정확하다.
 *  - 나무위키·위키백과: 서지 조회에서 이미 받은 대표 이미지를 같이 보여준다.
 *
 * 받은 주소는 반드시 https로 바꾼다. 구글 북스는 http 주소를 주는데
 * targetSdk 34에서는 평문 HTTP가 막혀 그대로 쓰면 그림이 안 나온다.
 */
object CoverSearch {

    data class Cover(
        val source: String,
        val title: String,
        /** 목록에 보여줄 작은 그림 */
        val thumbUrl: String,
        /** EPUB에 넣을 큰 그림 */
        val fullUrl: String
    )

    /** 한 번에 보여줄 최대 개수. 너무 많으면 고르기도 받기도 부담이다. */
    const val MAX = 12

    suspend fun search(
        query: String,
        naverId: String = "",
        naverSecret: String = ""
    ): List<Cover> = withContext(Dispatchers.IO) {
        val q = query.trim()
        if (q.isBlank()) return@withContext emptyList()

        val out = ArrayList<Cover>()

        // 1) 진짜 이미지 검색. 키가 있으면 이게 가장 잘 맞는다.
        runCatching { out.addAll(NaverImages.search(q, naverId, naverSecret)) }
        // 2) 네이버 책 표지
        runCatching { out.addAll(naver(q, naverId, naverSecret)) }
        // 3) 나무위키 문서에 실린 그림 (키 없이 됨)
        runCatching { out.addAll(namu(q)) }
        // 4) 책 DB는 제목이 실제로 맞을 때만. 안 그러면 엉뚱한 책 표지를 집어 온다.
        runCatching { out.addAll(googleBooks(q).filter { relevant(q, it.title) }) }
        runCatching { out.addAll(openLibrary(q).filter { relevant(q, it.title) }) }

        dedupe(out).take(MAX)
    }

    // ---------- 나무위키 ----------

    private fun namu(q: String): List<Cover> =
        MetadataLookup.namuImages(q).map { Cover("나무위키", q, https(it), https(it)) }

    // ---------- 제목 맞는지 보기 ----------

    private val NOISE = Regex("""[\s\-_.,:;!?~()\[\]{}「」『』<>"'·]""")

    fun normalize(s: String): String = NOISE.replace(s.lowercase(), "")

    /**
     * 찾은 책 제목이 검색어와 실제로 맞는지 본다.
     *
     * 구글북스·오픈라이브러리는 못 찾으면 비슷해 보이는 딴 책을 돌려준다.
     * 웹소설은 이 DB에 아예 없어서 그 일이 늘 일어난다. 걸러내지 않으면
     * 검색해도 본 적 없는 그림이 목록에 깔린다.
     */
    fun relevant(query: String, title: String): Boolean {
        if (title.isBlank()) return false
        val q = normalize(query)
        val t = normalize(title)
        if (q.isEmpty() || t.isEmpty()) return false
        if (t.contains(q) || q.contains(t)) return true

        // 낱말 단위로 봤을 때 검색어의 대부분이 제목에 들어 있어야 한다
        val words = query.split(NOISE).filter { it.length >= 2 }
        if (words.isEmpty()) return false
        val hit = words.count { t.contains(normalize(it)) }
        return hit.toDouble() / words.size >= 0.7
    }

    // ---------- 네이버 책 ----------

    private fun naver(q: String, id: String, secret: String): List<Cover> =
        NaverBooks.search(q, id, secret, limit = 5)
            .filter { it.coverUrl.isNotBlank() }
            .map { Cover("네이버책", it.title, https(it.coverUrl), https(it.coverUrl)) }

    // ---------- 구글 북스 ----------

    private const val GOOGLE = "https://www.googleapis.com/books/v1/volumes"

    private fun googleBooks(q: String): List<Cover> {
        val json = Http.text("$GOOGLE?maxResults=8&printType=books&q=" + Http.enc(q))
            ?: return emptyList()
        return parseGoogleBooks(json)
    }

    fun parseGoogleBooks(json: String): List<Cover> {
        val out = ArrayList<Cover>()
        runCatching {
            val items = JSONObject(json).optJSONArray("items") ?: return emptyList()
            for (i in 0 until items.length()) {
                val info = items.getJSONObject(i).optJSONObject("volumeInfo") ?: continue
                val links = info.optJSONObject("imageLinks") ?: continue
                val thumb = links.optString("thumbnail").ifBlank { links.optString("smallThumbnail") }
                if (thumb.isBlank()) continue
                out.add(
                    Cover(
                        source = "구글북스",
                        title = info.optString("title"),
                        thumbUrl = https(stripCurl(thumb)),
                        fullUrl = https(bigger(thumb))
                    )
                )
            }
        }
        return out
    }

    /** 표지를 말아 올린 것처럼 그린 효과. 켜져 있으면 그림이 지저분해진다. */
    fun stripCurl(url: String): String =
        url.replace("&edge=curl", "").replace("edge=curl&", "").replace("?edge=curl", "?")

    /** 같은 주소에서 더 큰 그림을 받는다. zoom 값이 클수록 크다. */
    fun bigger(url: String): String {
        val clean = stripCurl(url)
        return if (clean.contains("zoom=")) Regex("""zoom=\d+""").replace(clean, "zoom=3")
        else if (clean.contains("?")) "$clean&zoom=3" else "$clean?zoom=3"
    }

    // ---------- 오픈 라이브러리 ----------

    private const val OPENLIB = "https://openlibrary.org/search.json"

    private fun openLibrary(q: String): List<Cover> {
        val json = Http.text("$OPENLIB?limit=8&fields=title,author_name,cover_i&q=" + Http.enc(q))
            ?: return emptyList()
        return parseOpenLibrary(json)
    }

    fun parseOpenLibrary(json: String): List<Cover> {
        val out = ArrayList<Cover>()
        runCatching {
            val docs = JSONObject(json).optJSONArray("docs") ?: return emptyList()
            for (i in 0 until docs.length()) {
                val d = docs.getJSONObject(i)
                val id = d.optInt("cover_i", 0)
                if (id <= 0) continue
                out.add(
                    Cover(
                        source = "오픈라이브러리",
                        title = d.optString("title"),
                        thumbUrl = "https://covers.openlibrary.org/b/id/$id-M.jpg",
                        fullUrl = "https://covers.openlibrary.org/b/id/$id-L.jpg"
                    )
                )
            }
        }
        return out
    }

    // ---------- 공통 ----------

    /** 평문 HTTP는 기기에서 막혀 있다. 그림이 안 나오는 대신 https로 바꿔 시도한다. */
    fun https(url: String): String =
        if (url.startsWith("http://")) "https://" + url.removePrefix("http://") else url

    fun dedupe(list: List<Cover>): List<Cover> {
        val seen = HashSet<String>()
        return list.filter { it.fullUrl.isNotBlank() && seen.add(it.fullUrl) }
    }

    /** 큰 그림을 먼저 받아보고 안 되면 작은 것으로 돌아간다. */
    suspend fun download(cover: Cover): Pair<ByteArray, String>? = withContext(Dispatchers.IO) {
        Http.image(cover.fullUrl) ?: Http.image(cover.thumbUrl)
    }
}
