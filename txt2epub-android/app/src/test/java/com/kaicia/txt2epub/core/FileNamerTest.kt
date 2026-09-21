package com.kaicia.txt2epub.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Locale

class FileNamerTest {

    private val fixed = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).parse("2026-09-21 14:30")!!

    private fun tokens(
        title: String = "아포칼립스에 집을 숨김",
        author: String = "작가미상",
        chapters: Int = 737,
        source: String = "아포칼립스에_집을_숨김_1-741_완",
        publisher: String = "문피아",
        first: String = "1",
        last: String = "741",
        volume: Int = 0,
        volumeCount: Int = 0
    ) = FileNamer.Tokens(
        title = title, author = author, chapters = chapters, source = source,
        language = "ko", publisher = publisher, first = first, last = last,
        volume = volume, volumeCount = volumeCount
    )

    private fun build(
        tpl: String,
        t: FileNamer.Tokens = tokens(),
        space: FileNamer.SpaceMode = FileNamer.SpaceMode.KEEP,
        case: FileNamer.CaseMode = FileNamer.CaseMode.KEEP
    ) = FileNamer.build(tpl, t, space, case, fixed)

    @Test
    fun `토큰을 채우고 확장자를 붙인다`() {
        assertEquals("아포칼립스에 집을 숨김 - 작가미상 (737화).epub", build("{title} - {author} ({chapters}화)"))
    }

    @Test
    fun `금지 문자는 제거한다`() {
        val name = build("{title}", tokens(title = """하늘/땅\바다:별*물?음"표<>|"""))
        assertFalse(name.dropLast(5).any { it in """/\:*?"<>|""" })
    }

    @Test
    fun `모르는 토큰은 지우고 빈 괄호도 없앤다`() {
        assertEquals("아포칼립스에 집을 숨김.epub", build("{title} ({unknown})"))
    }

    @Test
    fun `빈 템플릿은 제목으로 대체한다`() {
        assertEquals("아포칼립스에 집을 숨김.epub", build(""))
    }

    @Test
    fun `제목이 비면 원본 파일명을 쓴다`() {
        assertEquals("아포칼립스에_집을_숨김_1-741_완.epub", build("{title}", tokens(title = "")))
    }

    @Test
    fun `공백 처리와 대소문자 변환이 적용된다`() {
        assertEquals("apocalypse_house.epub", build("{title}", tokens(title = "Apocalypse House"), FileNamer.SpaceMode.UNDERSCORE, FileNamer.CaseMode.LOWER))
        assertEquals("APOCALYPSE-HOUSE.epub", build("{title}", tokens(title = "Apocalypse House"), FileNamer.SpaceMode.DASH, FileNamer.CaseMode.UPPER))
        assertEquals("ApocalypseHouse.epub", build("{title}", tokens(title = "Apocalypse House"), FileNamer.SpaceMode.NONE))
    }

    @Test
    fun `한글은 글자수가 아니라 UTF-8 바이트로 자른다`() {
        val long = "가".repeat(200)
        val name = build("{title}", tokens(title = long))
        val bytes = name.toByteArray(StandardCharsets.UTF_8).size
        assertTrue("파일시스템 상한을 넘었다: $bytes", bytes <= 255)
        assertTrue(name.endsWith(".epub"))
        // 글자 경계가 깨지면 깨진 문자가 남는다
        assertFalse(name.contains('�'))
    }

    @Test
    fun `날짜 토큰은 고정 형식이다`() {
        assertEquals("아포칼립스에 집을 숨김_2026-09-21.epub", build("{title}_{date}"))
        assertEquals("20260921-1430.epub", build("{datetime}"))
    }

    @Test
    fun `화 번호 범위 토큰이 들어간다`() {
        assertEquals("아포칼립스에 집을 숨김 [1-741].epub", build("{title} [{first}-{last}]"))
    }

    @Test
    fun `분할 저장이면 권 번호가 자동으로 붙는다`() {
        val a = build("{title}", tokens(volume = 1, volumeCount = 8))
        val b = build("{title}", tokens(volume = 2, volumeCount = 8))
        assertEquals("아포칼립스에 집을 숨김 (1권).epub", a)
        assertNotEquals(a, b)
    }

    @Test
    fun `권 번호를 직접 넣은 템플릿은 그 자리에 들어간다`() {
        assertEquals("아포칼립스에 집을 숨김 3권 (8권 중).epub", build("{title} {vol}권 ({vols}권 중)", tokens(volume = 3, volumeCount = 8)))
    }

    @Test
    fun `제목이 길어도 권 번호는 살아남는다`() {
        val long = "가".repeat(200)
        val names = (1..3).map { build("{title}", tokens(title = long, volume = it, volumeCount = 3)) }
        assertEquals(3, names.distinct().size)
        names.forEach {
            assertTrue(it.toByteArray(StandardCharsets.UTF_8).size <= 255)
        }
    }

    @Test
    fun `챕터 제목에서 첫 화와 끝 화를 뽑는다`() {
        val (first, last) = FileNamer.numberRange(listOf("1화 시작", "2화", "740화", "741화 끝"))
        assertEquals("1", first)
        assertEquals("741", last)
    }

    @Test
    fun `프리셋은 모두 유효한 이름을 만든다`() {
        FileNamer.PRESETS.forEach { (_, tpl) ->
            val name = build(tpl)
            assertTrue(tpl, name.endsWith(".epub"))
            assertTrue(tpl, name.length > 5)
        }
    }
}
