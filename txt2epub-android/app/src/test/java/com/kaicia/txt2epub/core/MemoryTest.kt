package com.kaicia.txt2epub.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import kotlin.random.Random

/**
 * 실제 파일 크기를 좁은 힙에서 통과시키는 시험.
 *
 * 기기에서 741화 파일(5,440,382자 / 325,417줄)을 열면 분석 중에 앱이 죽었다.
 * 안드로이드 기본 힙은 기기마다 다르고 작은 것은 128MB도 안 된다.
 * 여기서는 그보다 좁게 잡고 읽기 → 판정 → 챕터 구성을 끝까지 돌린다.
 *
 * 데스크톱 JVM의 String은 ART보다 개당 부담이 커서, 이 조건을 통과하면
 * 기기에서는 더 여유가 있다.
 */
class MemoryTest {

    /** 사용자 파일과 같은 규모의 본문을 만든다. */
    private fun novelBytes(): ByteArray {
        val rnd = Random(11)
        val words = listOf(
            "그는", "조용히", "문을", "열었다", "하늘은", "붉게", "물들어", "있었다",
            "아무도", "그날의", "일을", "기억하지", "못했다", "집은", "여전히", "거기",
            "있었고", "바람이", "불었다", "천천히", "걸음을", "옮겼다", "숨을", "삼켰다"
        )
        val sb = StringBuilder(5_600_000)
        var chapter = 1
        while (sb.length < 5_440_000) {
            sb.append(if (chapter % 3 == 0) "아포칼립스에 집을 숨김 ${chapter}화" else "${chapter}화").append('\n')
            sb.append('\n')
            repeat(438) { k ->
                if (k % 5 == 0) sb.append('\n')
                else {
                    repeat(2 + rnd.nextInt(6)) { sb.append(words[rnd.nextInt(words.size)]).append(' ') }
                    sb.append('\n')
                }
            }
            chapter++
        }
        return sb.toString().toByteArray(StandardCharsets.UTF_8)
    }

    private fun used(): Long {
        val rt = Runtime.getRuntime()
        System.gc()
        return (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024)
    }

    @Test
    fun `실제 파일 규모를 좁은 힙에서 끝까지 처리한다`() {
        val bytes = novelBytes()
        println("파일 ${bytes.size / 1024 / 1024}MB · 힙 상한 ${Runtime.getRuntime().maxMemory() / 1024 / 1024}MB")

        val loaded = TextReader.load({ ByteArrayInputStream(bytes) }, null)
        println("읽기 후 ${used()}MB · ${loaded.lines.size}줄 · ${loaded.charCount}자")
        assertEquals(TextReader.Encoding.UTF8, loaded.encoding)

        val best = ChapterDetector.detect(loaded.lines).first()
        println("판정 후 ${used()}MB · ${best.name} · ${best.count}개")

        val chapters = ChapterDetector.buildChapters(best.hits, loaded.lines, "본문")
        println("구성 후 ${used()}MB · 챕터 ${chapters.size}개")

        assertTrue(chapters.size > 700)
    }
}
