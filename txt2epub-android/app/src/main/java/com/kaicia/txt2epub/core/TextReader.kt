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

    /** 고른 파일이 무엇인지. 텍스트가 아니면 읽기 전에 막는다. */
    enum class Kind(val label: String) {
        TEXT("텍스트"),
        ZIP("EPUB·ZIP 같은 압축 파일"),
        PDF("PDF"),
        BINARY("텍스트가 아닌 파일")
    }

    /** 텍스트가 아닌 파일을 골랐을 때. 읽어봐야 깨진 글자만 나온다. */
    class NotTextException(val kind: Kind) :
        Exception("텍스트 파일이 아닙니다: ${kind.label}")

    data class Loaded(
        val lines: TextLines,
        val encoding: Encoding,
        val charCount: Long
    )

    /** 감지에 쓸 표본 크기. 전체를 검사하면 큰 파일에서 느려진다. */
    private const val SNIFF_BYTES = 256 * 1024

    /**
     * 텍스트 파일이 맞는지 본다.
     *
     * 파일 선택창에서 EPUB을 txt로 착각해 고르는 일이 실제로 있었다. 그대로 읽으면
     * 압축된 바이트가 글자로 풀려 챕터 판정이 엉망이 된다. 읽기 전에 막고 왜 막았는지 알린다.
     */
    fun kindOf(head: ByteArray, len: Int): Kind {
        if (len <= 0) return Kind.TEXT
        fun at(i: Int) = if (i < len) head[i].toInt() and 0xFF else -1

        // PK.. : zip (epub, docx, xlsx, apk …)
        if (at(0) == 0x50 && at(1) == 0x4B &&
            (at(2) == 0x03 || at(2) == 0x05 || at(2) == 0x07)
        ) return Kind.ZIP
        if (at(0) == 0x25 && at(1) == 0x50 && at(2) == 0x44 && at(3) == 0x46) return Kind.PDF   // %PDF
        if (at(0) == 0x1F && at(1) == 0x8B) return Kind.BINARY                                  // gzip
        if (at(0) == 0x89 && at(1) == 0x50 && at(2) == 0x4E && at(3) == 0x47) return Kind.BINARY // png
        if (at(0) == 0xFF && at(1) == 0xD8 && at(2) == 0xFF) return Kind.BINARY                  // jpeg

        // UTF-16은 정상적으로 0 바이트가 섞인다. BOM이 있으면 텍스트로 본다.
        val utf16 = (at(0) == 0xFF && at(1) == 0xFE) || (at(0) == 0xFE && at(1) == 0xFF)
        if (utf16) return Kind.TEXT

        // 0 바이트는 텍스트에 나올 수 없다. 하나라도 있으면 텍스트가 아니다.
        val n = minOf(len, 4096)
        for (i in 0 until n) if (head[i].toInt() == 0) return Kind.BINARY
        return Kind.TEXT
    }

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
     *
     * 줄마다 String을 만들지 않고 글자 배열 한 벌에 담는다. 큰 파일에서 앱이
     * 죽던 원인이 이 부담이었다. 자세한 것은 [TextLines] 설명 참고.
     */
    fun load(open: () -> InputStream, forced: Encoding?): Loaded {
        val enc = open().use { input ->
            val head = ByteArray(SNIFF_BYTES)
            val n = readFully(input, head)
            val kind = kindOf(head, n)
            if (kind != Kind.TEXT) throw NotTextException(kind)
            forced ?: sniff(head, n)
        }

        val charset = try {
            charset(enc.charsetName)
        } catch (e: Exception) {
            // 기기에 x-windows-949가 없으면 EUC-KR로 대체
            charset("EUC-KR")
        }

        val chars = open().use { input -> readAllChars(input, charset) }
        return split(chars, enc)
    }

    /** 조각으로 받아 마지막에 한 번만 이어 붙인다. 배열을 키우며 복사하는 것보다 덜 든다. */
    private fun readAllChars(input: InputStream, charset: java.nio.charset.Charset): CharArray {
        val reader = java.io.InputStreamReader(input, charset)
        val chunks = ArrayList<CharArray>()
        var total = 0
        while (true) {
            val chunk = CharArray(CHUNK)
            var off = 0
            while (off < chunk.size) {
                val n = reader.read(chunk, off, chunk.size - off)
                if (n < 0) break
                off += n
            }
            if (off > 0) {
                chunks.add(if (off == chunk.size) chunk else chunk.copyOf(off))
                total += off
            }
            if (off < chunk.size) break
        }

        val all = CharArray(total)
        var at = 0
        for (c in chunks) {
            System.arraycopy(c, 0, all, at, c.size)
            at += c.size
        }
        chunks.clear()
        return all
    }

    private const val CHUNK = 1 shl 16

    /** 줄 경계를 찾는다. \r\n, \n, \r 를 모두 줄바꿈으로 본다. */
    private fun split(chars: CharArray, enc: Encoding): Loaded {
        val n = chars.size
        var start = 0
        // 첫머리 BOM은 본문이 아니다
        if (n > 0 && chars[0] == '\uFEFF') start = 1

        var count = 0
        var from = IntArray(1 shl 14)
        var to = IntArray(1 shl 14)
        var chars2 = 0L

        var i = start
        var lineStart = start
        while (i < n) {
            val c = chars[i]
            if (c == '\n' || c == '\r') {
                if (count == from.size) {
                    from = from.copyOf(count * 2)
                    to = to.copyOf(count * 2)
                }
                from[count] = lineStart
                to[count] = i
                chars2 += (i - lineStart) + 1
                count++
                i = if (c == '\r' && i + 1 < n && chars[i + 1] == '\n') i + 2 else i + 1
                lineStart = i
            } else {
                i++
            }
        }
        // 줄바꿈으로 끝나지 않은 마지막 줄
        if (lineStart < n) {
            if (count == from.size) {
                from = from.copyOf(count + 1)
                to = to.copyOf(count + 1)
            }
            from[count] = lineStart
            to[count] = n
            chars2 += (n - lineStart) + 1
            count++
        }

        return Loaded(TextLines(chars, from, to, count), enc, chars2)
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
