package com.kaicia.txt2epub.net

import org.json.JSONObject

/**
 * 네이버 책 검색 API.
 *
 * 한국 소설·웹소설은 위키류보다 이쪽 커버리지가 훨씬 좋다. 다만 키가 필요해서
 * 앱에 넣어 배포할 수 없다. 사용자가 본인 키를 설정에 넣으면 그때만 쓴다.
 * 키 발급: developers.naver.com > 애플리케이션 등록 > 검색 API
 *
 * 하루 25,000회 무료다. 책 한 권당 한 번이면 충분히 남는다.
 */
object NaverBooks {

    private const val ENDPOINT = "https://openapi.naver.com/v1/search/book.json"

    /** 키가 없으면 빈 목록. 호출부에서 키 유무를 따로 검사하지 않아도 된다. */
    fun search(query: String, clientId: String, clientSecret: String, limit: Int = 3): List<MetadataLookup.Info> {
        if (query.isBlank() || clientId.isBlank() || clientSecret.isBlank()) return emptyList()

        val url = "$ENDPOINT?display=$limit&query=" + Http.enc(query)
        val json = Http.text(
            url,
            mapOf(
                "X-Naver-Client-Id" to clientId.trim(),
                "X-Naver-Client-Secret" to clientSecret.trim()
            )
        ) ?: return emptyList()

        val out = ArrayList<MetadataLookup.Info>()
        runCatching {
            val items = JSONObject(json).getJSONArray("items")
            for (i in 0 until items.length()) {
                val o = items.getJSONObject(i)
                out.add(
                    MetadataLookup.Info(
                        source = "네이버책",
                        title = strip(o.optString("title")),
                        author = strip(o.optString("author")).replace("|", ", "),
                        publisher = strip(o.optString("publisher")),
                        year = o.optString("pubdate").take(4),
                        coverUrl = o.optString("image"),
                        pageUrl = o.optString("link")
                    )
                )
            }
        }
        return out
    }

    /** 검색어와 일치하는 부분에 <b> 태그가 섞여 온다. */
    private val TAG = Regex("""</?b>""")
    private val ENTITY = mapOf(
        "&amp;" to "&", "&lt;" to "<", "&gt;" to ">", "&quot;" to "\"", "&#39;" to "'", "&apos;" to "'"
    )

    fun strip(s: String): String {
        var v = TAG.replace(s, "")
        ENTITY.forEach { (k, r) -> v = v.replace(k, r) }
        return v.replace(Regex("""\s+"""), " ").trim()
    }
}
