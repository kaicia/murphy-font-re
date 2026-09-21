package com.kaicia.txt2epub.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

class EpubWriterTest {

    private fun sample(count: Int): Pair<List<ChapterDetector.Chapter>, List<String>> {
        val lines = ArrayList<String>()
        val chapters = ArrayList<ChapterDetector.Chapter>()
        for (i in 1..count) {
            val start = lines.size
            lines.add("본문 $i 첫 문단입니다.")
            lines.add("")
            lines.add("둘째 문단입니다. <태그> & \"따옴표\" 도 들어갑니다.")
            chapters.add(ChapterDetector.Chapter("${i}화 제목", start, lines.size))
        }
        return chapters to lines
    }

    private fun write(
        count: Int = 5,
        meta: EpubWriter.Meta = EpubWriter.Meta("제목", "지은이")
    ): ByteArray {
        val (chapters, lines) = sample(count)
        val out = ByteArrayOutputStream()
        EpubWriter.write(out, chapters, lines, meta)
        return out.toByteArray()
    }

    private fun entries(bytes: ByteArray): Map<String, String> {
        val map = LinkedHashMap<String, String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zin ->
            var e: ZipEntry? = zin.nextEntry
            while (e != null) {
                map[e.name] = zin.readBytes().toString(StandardCharsets.UTF_8)
                e = zin.nextEntry
            }
        }
        return map
    }

    @Test
    fun `mimetype이 첫 항목이고 무압축이다`() {
        val bytes = write()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zin ->
            val first = zin.nextEntry!!
            assertEquals("mimetype", first.name)
            assertEquals(ZipEntry.STORED.toLong(), first.method.toLong())
            assertEquals("application/epub+zip", zin.readBytes().toString(StandardCharsets.US_ASCII))
        }
        // 규격상 내용이 파일 시작 38바이트 지점(로컬 헤더 30 + 이름 8)에 그대로 있어야 한다
        assertEquals(
            "application/epub+zip",
            String(bytes, 38, 20, StandardCharsets.US_ASCII)
        )
    }

    @Test
    fun `필수 항목이 모두 들어간다`() {
        val map = entries(write(3))
        listOf(
            "mimetype", "META-INF/container.xml", "OEBPS/content.opf",
            "OEBPS/nav.xhtml", "OEBPS/toc.ncx", "OEBPS/style.css"
        ).forEach { assertTrue("$it 없음", map.containsKey(it)) }
        assertTrue(map.containsKey("OEBPS/ch00001.xhtml"))
        assertTrue(map.containsKey("OEBPS/ch00003.xhtml"))
    }

    @Test
    fun `매니페스트가 실제 파일만 가리킨다`() {
        val map = entries(write(4))
        val opf = map.getValue("OEBPS/content.opf")
        Regex("""href="([^"]+)"""").findAll(opf).forEach { m ->
            val href = m.groupValues[1]
            assertTrue("매니페스트에 없는 파일: $href", map.containsKey("OEBPS/$href"))
        }
    }

    @Test
    fun `목차 두 벌이 같은 챕터를 가리킨다`() {
        val map = entries(write(6))
        val nav = map.getValue("OEBPS/nav.xhtml")
        val ncx = map.getValue("OEBPS/toc.ncx")
        assertEquals(6, Regex("""<li><a href=""").findAll(nav).count())
        assertEquals(6, Regex("""<navPoint """).findAll(ncx).count())
        assertTrue(nav.contains("1화 제목"))
        assertTrue(ncx.contains("6화 제목"))
    }

    @Test
    fun `XML 특수문자를 escape 한다`() {
        val map = entries(write(1))
        val page = map.getValue("OEBPS/ch00001.xhtml")
        assertTrue(page.contains("&lt;태그&gt;"))
        assertTrue(page.contains("&amp;"))
        assertFalse(page.contains("<태그>"))
    }

    @Test
    fun `빈 줄은 문단 경계가 된다`() {
        val map = entries(write(1))
        val page = map.getValue("OEBPS/ch00001.xhtml")
        assertEquals(2, Regex("""<p>""").findAll(page).count())
    }

    @Test
    fun `표지를 넣으면 spine 맨 앞에 오고 두 규격에 모두 등록된다`() {
        val cover = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 1, 2, 3)
        val map = entries(
            write(2, EpubWriter.Meta("제목", "지은이", coverBytes = cover, coverMime = "image/png"))
        )
        assertTrue(map.containsKey("OEBPS/cover.png"))
        assertTrue(map.containsKey("OEBPS/cover.xhtml"))
        val opf = map.getValue("OEBPS/content.opf")
        assertTrue(opf.contains("""properties="cover-image""""))
        assertTrue(opf.contains("""<meta name="cover" content="cover-image"/>"""))
        val spine = opf.substringAfter("<spine")
        assertTrue(spine.indexOf("coverpage") < spine.indexOf("ch00001"))
    }

    @Test
    fun `표지가 없으면 표지 항목도 없다`() {
        val map = entries(write(2))
        assertFalse(map.keys.any { it.contains("cover") })
        assertFalse(map.getValue("OEBPS/content.opf").contains("cover"))
    }

    @Test
    fun `많은 챕터도 진행률과 함께 끝까지 쓴다`() {
        val (chapters, lines) = sample(741)
        val out = ByteArrayOutputStream()
        var last = 0
        EpubWriter.write(out, chapters, lines, EpubWriter.Meta("긴 책", "지은이")) { done, total ->
            assertEquals(741, total)
            last = done
        }
        assertEquals(741, last)
        val map = entries(out.toByteArray())
        // 본문 741 + mimetype, container, css, nav, ncx, opf
        assertEquals(741 + 6, map.size)
        assertTrue(map.containsKey("OEBPS/ch00741.xhtml"))
    }

    @Test
    fun `출판사는 있을 때만 넣는다`() {
        assertTrue(
            entries(write(1, EpubWriter.Meta("제목", "지은이", publisher = "문피아")))
                .getValue("OEBPS/content.opf").contains("<dc:publisher>문피아</dc:publisher>")
        )
        assertFalse(
            entries(write(1)).getValue("OEBPS/content.opf").contains("dc:publisher")
        )
    }
}
