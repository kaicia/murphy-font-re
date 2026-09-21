package com.kaicia.txt2epub.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NamuImageTest {

    private val html = """
        <html><head>
          <meta property="og:image" content="https://i.namu.wiki/i/cover-og.webp"/>
        </head><body>
          <img src="//i.namu.wiki/i/infobox1.webp"/>
          <img src="/img/namu-logo.svg"/>
          <img data-src="https://i.namu.wiki/i/second.webp"/>
          <img src="https://cdn.example.com/icon-32.png"/>
          <img src="http://i.namu.wiki/i/third.jpg"/>
        </body></html>
    """.trimIndent()

    @Test
    fun `문서에 실린 그림을 순서대로 모은다`() {
        val list = MetadataLookup.imagesIn(html)
        assertEquals("https://i.namu.wiki/i/cover-og.webp", list.first())
        assertTrue(list.contains("https://i.namu.wiki/i/infobox1.webp"))
        assertTrue(list.contains("https://i.namu.wiki/i/second.webp"))
    }

    @Test
    fun `로고와 아이콘은 뺀다`() {
        val list = MetadataLookup.imagesIn(html)
        assertFalse(list.any { it.contains("logo") || it.contains("icon") })
    }

    @Test
    fun `주소를 https로 맞춘다`() {
        val list = MetadataLookup.imagesIn(html)
        assertTrue(list.all { it.startsWith("https://") })
    }

    @Test
    fun `개수를 넘지 않는다`() {
        assertTrue(MetadataLookup.imagesIn(html, limit = 2).size <= 2)
    }

    @Test
    fun `빈 문서에도 죽지 않는다`() {
        assertTrue(MetadataLookup.imagesIn("").isEmpty())
        assertTrue(MetadataLookup.imagesIn("<html></html>").isEmpty())
    }
}
