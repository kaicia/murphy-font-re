package com.kaicia.txt2epub.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 텍스트가 아닌 파일을 막는다.
 *
 * 파일 선택창에서 앱이 만든 EPUB을 txt로 착각해 고르는 일이 실제로 있었다.
 * 그대로 읽으면 압축 바이트가 글자로 풀려 챕터 판정이 엉망이 된다.
 */
class NotTextTest {

    private fun epubBytes(): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("mimetype"))
            zip.write("application/epub+zip".toByteArray())
            zip.closeEntry()
        }
        return out.toByteArray()
    }

    private fun kind(bytes: ByteArray) = TextReader.kindOf(bytes, bytes.size)

    @Test
    fun `EPUB을 고르면 압축 파일로 알아본다`() {
        assertEquals(TextReader.Kind.ZIP, kind(epubBytes()))
    }

    @Test
    fun `EPUB을 읽으려 하면 이유를 알려주고 멈춘다`() {
        val bytes = epubBytes()
        val e = assertThrows(TextReader.NotTextException::class.java) {
            TextReader.load({ ByteArrayInputStream(bytes) }, null)
        }
        assertEquals(TextReader.Kind.ZIP, e.kind)
    }

    @Test
    fun `PDF와 그림도 막는다`() {
        assertEquals(TextReader.Kind.PDF, kind("%PDF-1.7\n...".toByteArray()))
        assertEquals(TextReader.Kind.BINARY, kind(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 13, 10)))
        assertEquals(TextReader.Kind.BINARY, kind(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())))
        assertEquals(TextReader.Kind.BINARY, kind(byteArrayOf(0x1F, 0x8B.toByte(), 8, 0)))
    }

    @Test
    fun `0 바이트가 섞이면 텍스트가 아니다`() {
        val bytes = "정상적인 글".toByteArray(StandardCharsets.UTF_8) + byteArrayOf(0, 1, 2)
        assertEquals(TextReader.Kind.BINARY, kind(bytes))
    }

    @Test
    fun `평범한 한글 txt 는 통과한다`() {
        assertEquals(TextReader.Kind.TEXT, kind("1화\n본문입니다\n".toByteArray(StandardCharsets.UTF_8)))
        val cp949 = runCatching { charset("x-windows-949") }.getOrElse { charset("EUC-KR") }
        assertEquals(TextReader.Kind.TEXT, kind("1화\n본문입니다\n".toByteArray(cp949)))
    }

    @Test
    fun `UTF-16 은 0 바이트가 섞여도 텍스트다`() {
        val bytes = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) +
                "1화\n본문\n".toByteArray(Charsets.UTF_16LE)
        assertEquals(TextReader.Kind.TEXT, kind(bytes))
    }

    @Test
    fun `빈 파일은 막지 않는다`() {
        assertEquals(TextReader.Kind.TEXT, kind(ByteArray(0)))
    }
}
