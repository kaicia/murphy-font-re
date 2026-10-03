package com.kaicia.txt2epub.core

import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 저장 전 검사. 쓰다가 끊긴 EPUB이 저장 위치에 남지 않게 한다.
 *
 * 사용자 파일: 3000화 중 2468화까지만 쓰이고 zip 목록(central directory)이
 * 없는 17MB 파일. 뷰어는 'EPUB이 아니다'라고 한다.
 */
class VerifyTest {

    private fun write(n: Int): File {
        val lines = ArrayList<String>()
        val chapters = ArrayList<ChapterDetector.Chapter>()
        for (i in 1..n) {
            val start = lines.size
            lines.add("본문 $i 입니다. 그날의 일은 아무도 몰랐다.")
            chapters.add(ChapterDetector.Chapter("${i}화", start, lines.size))
        }
        val f = File.createTempFile("verify", ".epub")
        f.outputStream().use { EpubWriter.write(it, chapters, lines, EpubWriter.Meta("제목", "지은이")) }
        return f
    }

    @Test
    fun `온전한 파일은 통과한다`() {
        val f = write(300)
        EpubWriter.verify(f, 300)
        f.delete()
    }

    @Test
    fun `쓰다가 끊긴 파일은 걸린다`() {
        val f = write(300)
        val bytes = f.readBytes()
        f.writeBytes(bytes.copyOf((bytes.size * 0.8).toInt()))   // 앞 80%만 남김
        val e = assertThrows(IllegalStateException::class.java) { EpubWriter.verify(f, 300) }
        assertTrue(e.message!!.contains("끝까지"))
        f.delete()
    }

    @Test
    fun `챕터 수가 모자라면 걸린다`() {
        val f = write(120)
        assertThrows(IllegalStateException::class.java) { EpubWriter.verify(f, 300) }
        f.delete()
    }
}
