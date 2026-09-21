package com.kaicia.txt2epub.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CoverSearchTest {

    @Test
    fun `구글북스 응답에서 표지를 뽑는다`() {
        val json = """
            {"items":[
              {"volumeInfo":{"title":"데미안","imageLinks":{
                 "smallThumbnail":"http://books.google.com/books/content?id=A1&printsec=frontcover&img=1&zoom=5&edge=curl&source=gbs_api",
                 "thumbnail":"http://books.google.com/books/content?id=A1&printsec=frontcover&img=1&zoom=1&edge=curl&source=gbs_api"}}},
              {"volumeInfo":{"title":"표지없음"}},
              {"volumeInfo":{"title":"작은것만","imageLinks":{
                 "smallThumbnail":"https://books.google.com/books/content?id=B2&img=1&zoom=5"}}}
            ]}
        """.trimIndent()
        val list = CoverSearch.parseGoogleBooks(json)
        assertEquals(2, list.size)
        assertEquals("데미안", list[0].title)
        assertEquals("구글북스", list[0].source)
        // 평문 HTTP는 기기에서 막힌다
        assertTrue(list.all { it.thumbUrl.startsWith("https://") && it.fullUrl.startsWith("https://") })
        // 말린 표지 효과는 뺀다
        assertFalse(list[0].thumbUrl.contains("edge=curl"))
        // 큰 그림은 zoom을 올려 받는다
        assertTrue(list[0].fullUrl.contains("zoom=3"))
        assertEquals("작은것만", list[1].title)
    }

    @Test
    fun `오픈라이브러리 응답에서 표지를 뽑는다`() {
        val json = """
            {"docs":[
              {"title":"Demian","author_name":["Hermann Hesse"],"cover_i":8231856},
              {"title":"표지 없는 책"},
              {"title":"음수","cover_i":-1}
            ]}
        """.trimIndent()
        val list = CoverSearch.parseOpenLibrary(json)
        assertEquals(1, list.size)
        assertEquals("https://covers.openlibrary.org/b/id/8231856-M.jpg", list[0].thumbUrl)
        assertEquals("https://covers.openlibrary.org/b/id/8231856-L.jpg", list[0].fullUrl)
    }

    @Test
    fun `망가진 응답에도 죽지 않는다`() {
        listOf("", "{}", "not json", """{"items":"문자열"}""", """{"docs":[1,2]}""").forEach {
            assertTrue(CoverSearch.parseGoogleBooks(it).isEmpty())
            assertTrue(CoverSearch.parseOpenLibrary(it).isEmpty())
        }
    }

    @Test
    fun `같은 주소는 한 번만 보여준다`() {
        val a = CoverSearch.Cover("A", "책", "t1", "https://x/1.jpg")
        val b = CoverSearch.Cover("B", "책", "t2", "https://x/1.jpg")
        val c = CoverSearch.Cover("C", "책", "t3", "https://x/2.jpg")
        val d = CoverSearch.Cover("D", "책", "t4", "")
        assertEquals(listOf(a, c), CoverSearch.dedupe(listOf(a, b, c, d)))
    }

    @Test
    fun `zoom이 없는 주소에도 붙인다`() {
        assertEquals("https://x/i.jpg?zoom=3", CoverSearch.bigger("https://x/i.jpg"))
        assertEquals("https://x/i?a=1&zoom=3", CoverSearch.bigger("https://x/i?a=1"))
        assertEquals("https://x/i?img=1&zoom=3", CoverSearch.bigger("https://x/i?img=1&zoom=1"))
    }

    @Test
    fun `제목이 맞을 때만 책 DB 결과를 쓴다`() {
        // 웹소설은 책 DB에 없다. 걸러내지 않으면 엉뚱한 책 표지가 깔린다.
        assertTrue(CoverSearch.relevant("아포칼립스에 집을 숨김", "아포칼립스에 집을 숨김"))
        assertTrue(CoverSearch.relevant("아포칼립스에 집을 숨김", "아포칼립스에 집을 숨김 1권"))
        assertTrue(CoverSearch.relevant("데미안", "데미안(세계문학전집)"))
        assertTrue(CoverSearch.relevant("Demian", "demian"))

        assertFalse(CoverSearch.relevant("아포칼립스에 집을 숨김", "아포칼립스 생존기"))
        assertFalse(CoverSearch.relevant("아포칼립스에 집을 숨김", "집에서 만드는 빵"))
        assertFalse(CoverSearch.relevant("데미안", "데미지 컨트롤"))
        assertFalse(CoverSearch.relevant("데미안", ""))
    }

    @Test
    fun `네이버 이미지 응답을 읽는다`() {
        val json = """
            {"items":[
              {"title":"아포칼립스에 <b>집</b>을 숨김",
               "link":"http://img.example.com/cover.jpg",
               "thumbnail":"https://search.pstatic.net/common?src=thumb.jpg",
               "sizeheight":"800","sizewidth":"600"},
              {"title":"주소없음","thumbnail":"https://x/y.jpg"}
            ]}
        """.trimIndent()
        val list = NaverImages.parse(json)
        assertEquals(1, list.size)
        assertEquals("네이버이미지", list[0].source)
        assertEquals("아포칼립스에 집을 숨김", list[0].title)
        assertTrue(list[0].fullUrl.startsWith("https://"))
    }

    @Test
    fun `키가 없으면 이미지 검색은 건너뛴다`() {
        assertTrue(NaverImages.search("아무거나", "", "").isEmpty())
    }

    @Test
    fun `https로 바꾼다`() {
        assertEquals("https://a/b.jpg", CoverSearch.https("http://a/b.jpg"))
        assertEquals("https://a/b.jpg", CoverSearch.https("https://a/b.jpg"))
    }
}
