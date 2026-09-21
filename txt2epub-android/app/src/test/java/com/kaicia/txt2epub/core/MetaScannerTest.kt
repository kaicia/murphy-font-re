package com.kaicia.txt2epub.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MetaScannerTest {

    @Test
    fun `머리말의 라벨 표기를 읽는다`() {
        val lines = listOf(
            "아포칼립스에 집을 숨김",
            "작가 : 김작가",
            "출판사: 문피아",
            "",
            "1화"
        )
        val f = MetaScanner.scan(lines)
        assertEquals("김작가", f.author)
        assertEquals("문피아", f.publisher)
    }

    @Test
    fun `구분자 없이 띄어쓰기만 쓴 표기도 읽는다`() {
        val f = MetaScanner.scan(listOf("지은이 이문열", "펴낸곳 민음사"))
        assertEquals("이문열", f.author)
        assertEquals("민음사", f.publisher)
    }

    @Test
    fun `영문 라벨도 읽는다`() {
        val f = MetaScanner.scan(listOf("Title: The Old Man", "Author: Hemingway", "Publisher - Scribner"))
        assertEquals("The Old Man", f.title)
        assertEquals("Hemingway", f.author)
        assertEquals("Scribner", f.publisher)
    }

    @Test
    fun `따옴표와 괄호는 벗겨낸다`() {
        val f = MetaScanner.scan(listOf("제목: 「데미안」", "작가: (헤르만 헤세)"))
        assertEquals("데미안", f.title)
        assertEquals("헤르만 헤세", f.author)
    }

    @Test
    fun `본문 문장은 값으로 잡지 않는다`() {
        val lines = listOf(
            "그는 작가가 되고 싶다고 말했다. 하지만 아무도 그 말을 믿지 않았고, 시간은 그렇게 흘러갔다.",
            "출판사에 원고를 보냈지만 답장은 오지 않았다. 그래도 그는 매일 아침 책상에 앉았다."
        )
        val f = MetaScanner.scan(lines)
        assertTrue(f.isEmpty)
    }

    @Test
    fun `판권지가 끝에 붙어 있어도 읽는다`() {
        val lines = List(900) { "본문 $it" } + listOf("", "지은이: 김끝말", "발행처: 끝판출판")
        val f = MetaScanner.scan(lines)
        assertEquals("김끝말", f.author)
        assertEquals("끝판출판", f.publisher)
    }

    @Test
    fun `라벨만 있고 값이 없으면 비운다`() {
        val f = MetaScanner.scan(listOf("작가 :", "출판사:"))
        assertTrue(f.isEmpty)
    }

    @Test
    fun `여러 칸 띄어쓰기로 이어 붙인 값은 앞부분만 쓴다`() {
        val f = MetaScanner.scan(listOf("작가: 김작가    출판사: 아무개출판"))
        assertEquals("김작가", f.author)
    }
}
