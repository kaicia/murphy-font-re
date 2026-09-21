package com.kaicia.txt2epub.net

import org.json.JSONObject

/**
 * 네이버 이미지 검색.
 *
 * 브라우저의 '이미지' 탭과 같은 결과다. 표지를 찾을 때 가장 잘 맞는다.
 * 책 DB(구글북스·오픈라이브러리)는 출판된 책만 있어서 웹소설은 아예 안 나오고,
 * 그러면 비슷한 제목의 딴 책 표지를 집어 온다.
 *
 * 다만 키가 필요하다. 구글·네이버 모두 이미지 검색에 공개 무료 API를 주지 않는다.
 * 키는 developers.naver.com에서 무료로 발급되고 책 검색과 같은 키를 쓴다.
 */
object NaverImages {

    private const val ENDPOINT = "https://openapi.naver.com/v1/search/image"

    fun search(
        query: String,
        clientId: String,
        clientSecret: String,
        limit: Int = 10
    ): List<CoverSearch.Cover> {
        if (query.isBlank() || clientId.isBlank() || clientSecret.isBlank()) return emptyList()
        // filter=large: 표지로 쓸 만한 크기만. sort=sim: 정확도순.
        val url = "$ENDPOINT?display=$limit&sort=sim&filter=large&query=" + Http.enc(query)
        val json = Http.text(
            url,
            mapOf(
                "X-Naver-Client-Id" to clientId.trim(),
                "X-Naver-Client-Secret" to clientSecret.trim()
            )
        ) ?: return emptyList()
        return parse(json)
    }

    fun parse(json: String): List<CoverSearch.Cover> {
        val out = ArrayList<CoverSearch.Cover>()
        runCatching {
            val items = JSONObject(json).optJSONArray("items") ?: return emptyList()
            for (i in 0 until items.length()) {
                val o = items.optJSONObject(i) ?: continue
                val link = o.optString("link")
                if (link.isBlank()) continue
                out.add(
                    CoverSearch.Cover(
                        source = "네이버이미지",
                        title = NaverBooks.strip(o.optString("title")),
                        thumbUrl = CoverSearch.https(o.optString("thumbnail").ifBlank { link }),
                        fullUrl = CoverSearch.https(link)
                    )
                )
            }
        }
        return out
    }
}
