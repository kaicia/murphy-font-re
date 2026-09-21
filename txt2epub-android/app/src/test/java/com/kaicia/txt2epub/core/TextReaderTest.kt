package com.kaicia.txt2epub.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets

class TextReaderTest {

    private fun load(bytes: ByteArray, forced: TextReader.Encoding? = null) =
        TextReader.load({ ByteArrayInputStream(bytes) }, forced)

    @Test
    fun `UTF-8 한글을 그대로 읽는다`() {
        val text = "첫 줄입니다\n둘째 줄입니다\n"
        val loaded = load(text.toByteArray(StandardCharsets.UTF_8))
        assertEquals(TextReader.Encoding.UTF8, loaded.encoding)
        assertEquals(listOf("첫 줄입니다", "둘째 줄입니다"), loaded.lines)
    }

    @Test
    fun `BOM은 첫 줄에서 떼어낸다`() {
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) +
                "제목\n본문\n".toByteArray(StandardCharsets.UTF_8)
        val loaded = load(bytes)
        assertEquals(TextReader.Encoding.UTF8, loaded.encoding)
        assertEquals("제목", loaded.lines.first())
    }

    @Test
    fun `CP949 한글은 자동으로 판정한다`() {
        val cp949 = runCatching { charset("x-windows-949") }.getOrElse { charset("EUC-KR") }
        val text = "한글 문서입니다\n둘째 줄\n".repeat(50)
        val loaded = load(text.toByteArray(cp949))
        assertEquals(TextReader.Encoding.CP949, loaded.encoding)
        assertEquals("한글 문서입니다", loaded.lines.first())
    }

    @Test
    fun `CRLF 줄바꿈도 정리한다`() {
        val loaded = load("첫 줄\r\n둘째 줄\r\n".toByteArray(StandardCharsets.UTF_8))
        assertEquals(listOf("첫 줄", "둘째 줄"), loaded.lines)
        assertTrue(loaded.lines.none { it.endsWith("\r") })
    }

    @Test
    fun `인코딩을 직접 지정하면 감지를 건너뛴다`() {
        val cp949 = runCatching { charset("x-windows-949") }.getOrElse { charset("EUC-KR") }
        val loaded = load("한글\n".toByteArray(cp949), TextReader.Encoding.CP949)
        assertEquals("한글", loaded.lines.first())
    }

    @Test
    fun `글자 수를 센다`() {
        val loaded = load("가나다\n라마\n".toByteArray(StandardCharsets.UTF_8))
        assertEquals(7L, loaded.charCount)     // 3 + 개행 + 2 + 개행
    }

    @Test
    fun `표본 끝에서 글자가 잘려도 UTF-8로 판정한다`() {
        // 감지 표본은 파일 중간에서 끊긴다. 마지막 글자가 3바이트 중 1바이트만
        // 남는 경우가 흔한데, 이걸 오류로 보면 멀쩡한 파일이 CP949로 읽혀 깨진다.
        val body = "가나다라마바사아자차카타파하\n".repeat(3)
        for (cut in 1..6) {
            val bytes = body.toByteArray(StandardCharsets.UTF_8)
            val partial = bytes.copyOf(bytes.size - cut)
            assertEquals("$cut 바이트를 잘랐을 때", TextReader.Encoding.UTF8, TextReader.sniff(partial, partial.size))
        }
    }

    @Test
    fun `표본보다 큰 파일도 UTF-8로 판정한다`() {
        val big = "한글 본문 줄입니다 그날의 기억은 선명했다\n".repeat(20_000)
        val loaded = load(big.toByteArray(StandardCharsets.UTF_8))
        assertEquals(TextReader.Encoding.UTF8, loaded.encoding)
        assertEquals("한글 본문 줄입니다 그날의 기억은 선명했다", loaded.lines.first())
        assertEquals(20_000, loaded.lines.size)
    }

    @Test
    fun `CP949 바이트는 여전히 CP949로 본다`() {
        val cp949 = runCatching { charset("x-windows-949") }.getOrElse { charset("EUC-KR") }
        val bytes = "한글 문서입니다\n".repeat(20).toByteArray(cp949)
        assertEquals(TextReader.Encoding.CP949, TextReader.sniff(bytes, bytes.size))
    }

    @Test
    fun `영문만 있는 파일은 UTF-8로 본다`() {
        val bytes = "plain ascii text\nsecond line\n".toByteArray(StandardCharsets.US_ASCII)
        assertEquals(TextReader.Encoding.UTF8, TextReader.sniff(bytes, bytes.size))
    }
}
