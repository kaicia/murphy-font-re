package com.kaicia.txt2epub.core

import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CharsetDecoder
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/**
 * 인코딩 감지 및 본문 읽기.
 *
 * 한국어 txt는 UTF-8 아니면 EUC-KR(실제로는 CP949)인 경우가 대부분이다.
 * BOM을 먼저 보고, 없으면 앞부분을 엄격 UTF-8로 디코드해봐서
 * 실패하면 CP949로 판정한다.
 */
object TextReader {

    enum class Encoding(val charsetName: String, val label: String) {
        UTF8("UTF-8", "UTF-8"),
        CP949("x-windows-949", "EUC-KR / CP949"),
        UTF16LE("UTF-16LE", "UTF-16 LE"),
        UTF16BE("UTF-16BE", "UTF-16 BE");
    }

    data class Loaded(
        val lines: List<String>,
        val encoding: Encoding,
        val charCount: Long
    )

    /** 감지에 쓸 표본 크기. 전체를 검사하면 큰 파일에서 느려진다. */
    private const val SNIFF_BYTES = 256 * 1024

    fun sniff(head: ByteArray, len: Int): Encoding {
        if (len >= 3 &&
            head[0] == 0xEF.toByte() && head[1] == 0xBB.toByte() && head[2] == 0xBF.toByte()
        ) return Encoding.UTF8
        if (len >= 2 && head[0] == 0xFF.toByte() && head[1] == 0xFE.toByte()) return Encoding.UTF16LE
        if (len >= 2 && head[0] == 0xFE.toByte() && head[1] == 0xFF.toByte()) return Encoding.UTF16BE
        if (len <= 0) return Encoding.UTF8

        // 엄격 UTF-8 디코드를 시도한다. 깨지는 바이트가 하나라도 있으면 CP949로 본다.
        //
        // endOfInput=false 로 넘기는 게 핵심이다. 표본은 파일 중간에서 끊기므로 마지막
        // 글자가 잘려 있는 게 정상인데, true 로 넘기면 그 조각을 오류로 보고 멀쩡한
        // UTF-8 파일을 CP949로 잘못 판정한다. false 면 UNDERFLOW로 돌아와 구분된다.
        val decoder: CharsetDecoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val input = ByteBuffer.wrap(head, 0, len)
        val output = CharBuffer.allocate(8 * 1024)
        while (true) {
            val r = decoder.decode(input, output, false)
            when {
                r.isError -> return Encoding.CP949
                r.isOverflow -> output.clear()
                else -> return Encoding.UTF8      // UNDERFLOW: 표본 끝까지 정상
            }
        }
    }

    /**
     * 스트림에서 전체를 읽어 줄 단위로 반환한다.
     * [forced]가 null이면 자동 감지한다.
     */
    fun load(open: () -> InputStream, forced: Encoding?): Loaded {
        val enc = forced ?: open().use { input ->
            val head = ByteArray(SNIFF_BYTES)
            val n = readFully(input, head)
            sniff(head, n)
        }

        val charset = try {
            charset(enc.charsetName)
        } catch (e: Exception) {
            // 기기에 x-windows-949가 없으면 EUC-KR로 대체
            charset("EUC-KR")
        }

        val lines = ArrayList<String>(1 shl 14)
        var chars = 0L
        open().use { input ->
            input.bufferedReader(charset).use { r ->
                var first = true
                while (true) {
                    var line = r.readLine() ?: break
                    if (first) {
                        line = line.removePrefix("\uFEFF")
                        first = false
                    }
                    // readLine이 \r\n, \n, \r 를 모두 처리하지만 남은 \r 를 정리
                    if (line.endsWith("\r")) line = line.dropLast(1)
                    lines.add(line)
                    chars += line.length + 1
                }
            }
        }
        return Loaded(lines, enc, chars)
    }

    private fun readFully(input: InputStream, buf: ByteArray): Int {
        var total = 0
        while (total < buf.size) {
            val n = input.read(buf, total, buf.size - total)
            if (n <= 0) break
            total += n
        }
        return total
    }
}
