package com.kaicia.txt2epub.core

import java.io.BufferedOutputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * EPUB 3 패키지 생성.
 *
 * 챕터를 하나씩 스트림에 바로 써서 전체 EPUB을 메모리에 올리지 않는다.
 * 741화짜리 파일도 메모리 문제 없이 처리된다.
 */
object EpubWriter {

    data class Meta(
        val title: String,
        val author: String,
        val language: String = "ko",
        val publisher: String = "",
        val coverBytes: ByteArray? = null,
        val coverMime: String = "image/jpeg"
    )

    private val CSS = """
        body{margin:1em 5%;line-height:1.7;font-size:1em;}
        h1{font-size:1.25em;margin:1.4em 0 1em;line-height:1.4;text-align:left;}
        p{margin:0 0 .85em;text-indent:0;}
    """.trimIndent()

    fun write(
        out: OutputStream,
        chapters: List<ChapterDetector.Chapter>,
        lines: List<String>,
        meta: Meta,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }
    ) {
        val id = "urn:uuid:" + UUID.randomUUID().toString()
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        fmt.timeZone = TimeZone.getTimeZone("UTC")
        val modified = fmt.format(Date())

        val hasCover = meta.coverBytes != null && meta.coverBytes.isNotEmpty()
        val coverName = when (meta.coverMime) {
            "image/png" -> "cover.png"
            "image/gif" -> "cover.gif"
            else -> "cover.jpg"
        }

        ZipOutputStream(BufferedOutputStream(out)).use { zip ->
            // mimetype 은 반드시 첫 항목이고 무압축이어야 한다 (EPUB 규격)
            writeStored(zip, "mimetype", "application/epub+zip".toByteArray(StandardCharsets.US_ASCII))
            zip.setLevel(6)

            put(zip, "META-INF/container.xml", """
                <?xml version="1.0" encoding="utf-8"?>
                <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                  <rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles>
                </container>
            """.trimIndent())

            put(zip, "OEBPS/style.css", CSS)

            if (hasCover) {
                putBytes(zip, "OEBPS/$coverName", meta.coverBytes!!)
                put(zip, "OEBPS/cover.xhtml", buildString {
                    append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<!DOCTYPE html>\n")
                    append("<html xmlns=\"http://www.w3.org/1999/xhtml\" xml:lang=\"${esc(meta.language)}\" lang=\"${esc(meta.language)}\">\n")
                    append("<head><meta charset=\"utf-8\"/><title>표지</title>\n")
                    append("<style>body{margin:0;padding:0;text-align:center}img{max-width:100%;height:auto}</style></head>\n")
                    append("<body><div><img src=\"$coverName\" alt=\"${esc(meta.title)}\"/></div></body>\n</html>")
                })
            }

            // 본문
            val names = ArrayList<String>(chapters.size)
            chapters.forEachIndexed { i, ch ->
                val name = "ch%05d.xhtml".format(i + 1)
                names.add(name)
                put(zip, "OEBPS/$name", page(ch.title, meta.language, body(lines, ch.fromLine, ch.toLine)))
                onProgress(i + 1, chapters.size)
            }

            // 목차 (EPUB3 nav)
            put(zip, "OEBPS/nav.xhtml", buildString {
                append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<!DOCTYPE html>\n")
                append("<html xmlns=\"http://www.w3.org/1999/xhtml\" xmlns:epub=\"http://www.idpf.org/2007/ops\" ")
                append("xml:lang=\"${esc(meta.language)}\" lang=\"${esc(meta.language)}\">\n")
                append("<head><meta charset=\"utf-8\"/><title>목차</title></head>\n<body>\n")
                append("  <nav epub:type=\"toc\" id=\"toc\"><h1>목차</h1>\n    <ol>\n")
                chapters.forEachIndexed { i, ch ->
                    append("      <li><a href=\"${names[i]}\">${esc(ch.title)}</a></li>\n")
                }
                append("    </ol>\n  </nav>\n</body>\n</html>")
            })

            // 목차 (EPUB2 호환 ncx) — 구형 뷰어 대응
            put(zip, "OEBPS/toc.ncx", buildString {
                append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n")
                append("<ncx xmlns=\"http://www.daisy.org/z3986/2005/ncx/\" version=\"2005-1\">\n")
                append("  <head><meta name=\"dtb:uid\" content=\"${esc(id)}\"/><meta name=\"dtb:depth\" content=\"1\"/>")
                append("<meta name=\"dtb:totalPageCount\" content=\"0\"/><meta name=\"dtb:maxPageNumber\" content=\"0\"/></head>\n")
                append("  <docTitle><text>${esc(meta.title)}</text></docTitle>\n  <navMap>\n")
                chapters.forEachIndexed { i, ch ->
                    append("    <navPoint id=\"np${i + 1}\" playOrder=\"${i + 1}\">")
                    append("<navLabel><text>${esc(ch.title)}</text></navLabel>")
                    append("<content src=\"${names[i]}\"/></navPoint>\n")
                }
                append("  </navMap>\n</ncx>")
            })

            put(zip, "OEBPS/content.opf", buildString {
                append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n")
                append("<package xmlns=\"http://www.idpf.org/2007/opf\" version=\"3.0\" unique-identifier=\"pub-id\" xml:lang=\"${esc(meta.language)}\">\n")
                append("  <metadata xmlns:dc=\"http://purl.org/dc/elements/1.1/\">\n")
                append("    <dc:identifier id=\"pub-id\">${esc(id)}</dc:identifier>\n")
                append("    <dc:title>${esc(meta.title)}</dc:title>\n")
                append("    <dc:creator>${esc(meta.author)}</dc:creator>\n")
                append("    <dc:language>${esc(meta.language)}</dc:language>\n")
                if (meta.publisher.isNotBlank()) append("    <dc:publisher>${esc(meta.publisher)}</dc:publisher>\n")
                if (hasCover) append("    <meta name=\"cover\" content=\"cover-image\"/>\n")
                append("    <meta property=\"dcterms:modified\">$modified</meta>\n")
                append("  </metadata>\n  <manifest>\n")
                append("    <item id=\"nav\" href=\"nav.xhtml\" media-type=\"application/xhtml+xml\" properties=\"nav\"/>\n")
                append("    <item id=\"ncx\" href=\"toc.ncx\" media-type=\"application/x-dtbncx+xml\"/>\n")
                append("    <item id=\"css\" href=\"style.css\" media-type=\"text/css\"/>\n")
                if (hasCover) {
                    append("    <item id=\"cover-image\" href=\"$coverName\" media-type=\"${esc(meta.coverMime)}\" properties=\"cover-image\"/>\n")
                    append("    <item id=\"coverpage\" href=\"cover.xhtml\" media-type=\"application/xhtml+xml\"/>\n")
                }
                names.forEach { n ->
                    append("    <item id=\"${n.removeSuffix(".xhtml")}\" href=\"$n\" media-type=\"application/xhtml+xml\"/>\n")
                }
                append("  </manifest>\n  <spine toc=\"ncx\">\n")
                if (hasCover) append("    <itemref idref=\"coverpage\" linear=\"yes\"/>\n")
                names.forEach { n -> append("    <itemref idref=\"${n.removeSuffix(".xhtml")}\"/>\n") }
                append("  </spine>\n</package>")
            })
        }
    }

    private fun page(title: String, lang: String, inner: String): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<!DOCTYPE html>\n")
        append("<html xmlns=\"http://www.w3.org/1999/xhtml\" xml:lang=\"${esc(lang)}\" lang=\"${esc(lang)}\">\n")
        append("<head><meta charset=\"utf-8\"/><title>${esc(title)}</title>")
        append("<link rel=\"stylesheet\" type=\"text/css\" href=\"style.css\"/></head>\n")
        append("<body>\n<h1>${esc(title)}</h1>\n")
        append(inner)
        append("\n</body>\n</html>")
    }

    /** 빈 줄을 문단 경계로 보고, 연속된 줄은 <br/>로 잇는다. */
    private fun body(lines: List<String>, from: Int, to: Int): String {
        val sb = StringBuilder()
        val buf = ArrayList<String>()
        fun flush() {
            if (buf.isEmpty()) return
            sb.append("<p>")
            buf.forEachIndexed { i, s ->
                if (i > 0) sb.append("<br/>")
                sb.append(esc(s))
            }
            sb.append("</p>\n")
            buf.clear()
        }
        for (i in from until minOf(to, lines.size)) {
            val t = lines[i].trim()
            if (t.isEmpty()) flush() else buf.add(t)
        }
        flush()
        return if (sb.isEmpty()) "<p/>" else sb.toString().trimEnd()
    }

    private fun esc(s: String): String = buildString(s.length + 16) {
        for (c in s) when (c) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            '\'' -> append("&#39;")
            // XML 1.0에서 허용되지 않는 제어문자 제거
            in '\u0000'..'\u0008', '\u000B', '\u000C', in '\u000E'..'\u001F' -> {}
            else -> append(c)
        }
    }

    private fun put(zip: ZipOutputStream, name: String, content: String) =
        putBytes(zip, name, content.toByteArray(StandardCharsets.UTF_8))

    private fun putBytes(zip: ZipOutputStream, name: String, bytes: ByteArray) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(bytes)
        zip.closeEntry()
    }

    private fun writeStored(zip: ZipOutputStream, name: String, bytes: ByteArray) {
        val e = ZipEntry(name)
        e.method = ZipEntry.STORED
        e.size = bytes.size.toLong()
        e.compressedSize = bytes.size.toLong()
        e.crc = CRC32().apply { update(bytes) }.value
        zip.putNextEntry(e)
        zip.write(bytes)
        zip.closeEntry()
    }
}
