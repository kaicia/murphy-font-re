package com.kaicia.txt2epub.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 네트워크 없이 확인할 수 있는 부분만 본다.
 * 실제 응답 파싱(나무위키 DOM)은 기기에서 확인해야 한다.
 */
class LookupParseTest {

    @Test
    fun `요약문에서 지은이를 뽑는다`() {
        assertEquals("이문열", MetadataLookup.guessAuthor("《사람의 아들》은 이문열이 쓴 장편소설이다."))
        assertEquals("김초엽", MetadataLookup.guessAuthor("김초엽의 소설로, 2019년에 발표되었다."))
        assertEquals("홍길동", MetadataLookup.guessAuthor("작가: 홍길동, 연재처는 문피아이다."))
    }

    @Test
    fun `이름 안에 작가가 들어가도 자르지 않는다`() {
        assertEquals("박작가", MetadataLookup.guessAuthor("이 작품은 박작가가 쓴 웹소설이다."))
    }

    @Test
    fun `일반명사는 이름으로 쓰지 않는다`() {
        assertEquals("", MetadataLookup.guessAuthor("대한민국의 웹소설이다."))
        assertEquals("", MetadataLookup.guessAuthor(""))
    }

    @Test
    fun `연재 플랫폼을 알아본다`() {
        assertEquals("문피아", MetadataLookup.guessPublisher("문피아에서 연재된 웹소설이다."))
        assertEquals("카카오페이지", MetadataLookup.guessPublisher("카카오페이지 연재작."))
        assertEquals("민음사", MetadataLookup.guessPublisher("출판사: 민음사에서 펴냈다."))
        assertEquals("", MetadataLookup.guessPublisher("아무 정보도 없는 문장."))
    }

    @Test
    fun `네이버 응답의 강조 태그와 엔티티를 정리한다`() {
        assertEquals("아포칼립스에 집을 숨김", NaverBooks.strip("아포칼립스에 <b>집</b>을 숨김"))
        assertEquals("R&B 이야기", NaverBooks.strip("R&amp;B 이야기"))
    }

    @Test
    fun `키가 없으면 네이버는 호출하지 않는다`() {
        assertTrue(NaverBooks.search("아무거나", "", "").isEmpty())
    }
}
