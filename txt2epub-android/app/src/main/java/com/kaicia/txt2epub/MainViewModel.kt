package com.kaicia.txt2epub

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kaicia.txt2epub.core.ChapterDetector
import com.kaicia.txt2epub.core.EpubWriter
import com.kaicia.txt2epub.core.FileNamer
import com.kaicia.txt2epub.core.MetaScanner
import com.kaicia.txt2epub.core.TextReader
import com.kaicia.txt2epub.data.Prefs
import com.kaicia.txt2epub.net.MetadataLookup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainViewModel(app: Application) : AndroidViewModel(app) {

    data class UiState(
        val fileName: String = "",
        val fileSize: Long = 0,
        val encoding: TextReader.Encoding? = null,
        val lineCount: Int = 0,
        val charCount: Long = 0,

        val candidates: List<ChapterDetector.Candidate> = emptyList(),
        val selected: Int = 0,
        val chapters: List<ChapterDetector.Chapter> = emptyList(),
        val detectNote: String = "",

        val title: String = "",
        val author: String = "",
        val publisher: String = "",
        val language: String = "ko",

        val lookupResults: List<MetadataLookup.Info> = emptyList(),
        val lookupBusy: Boolean = false,
        val lookupNote: String = "",
        val coverBytes: ByteArray? = null,
        val coverMime: String = "image/jpeg",

        val template: String = Prefs.DEFAULT_TEMPLATE,
        val space: FileNamer.SpaceMode = FileNamer.SpaceMode.KEEP,
        val case: FileNamer.CaseMode = FileNamer.CaseMode.KEEP,

        val splitEnabled: Boolean = false,
        val splitSize: Int = 100,

        val naverId: String = "",
        val naverSecret: String = "",

        val busy: Boolean = false,
        val progress: Float = 0f,
        val status: String = "",
        val error: String = "",
        val done: String = ""
    ) {
        val ready: Boolean get() = chapters.isNotEmpty() && !busy

        /** 분할 저장일 때 나오는 권 수. 분할이 꺼져 있으면 1. */
        val volumeCount: Int
            get() = if (!splitEnabled || splitSize <= 0) 1
            else (chapters.size + splitSize - 1) / splitSize.coerceAtLeast(1)
    }

    private val prefs = Prefs(app)

    private val _ui = MutableStateFlow(
        UiState(
            template = prefs.template,
            space = prefs.space,
            case = prefs.case,
            splitEnabled = prefs.splitEnabled,
            splitSize = prefs.splitSize,
            naverId = prefs.naverId,
            naverSecret = prefs.naverSecret
        )
    )
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    /** 본문은 UI 상태에 넣지 않는다. 수십만 줄이라 재구성 비용이 크다. */
    private var lines: List<String> = emptyList()
    private var sourceUri: Uri? = null

    // ---------- 파일명 ----------

    private fun tokensFor(
        s: UiState,
        chapters: List<ChapterDetector.Chapter>,
        volume: Int = 0,
        volumeCount: Int = 0
    ): FileNamer.Tokens {
        val (first, last) = FileNamer.numberRange(chapters.map { it.title })
        return FileNamer.Tokens(
            title = s.title,
            author = s.author,
            chapters = chapters.size,
            source = s.fileName.substringBeforeLast('.', s.fileName),
            language = s.language,
            publisher = s.publisher,
            first = first,
            last = last,
            volume = volume,
            volumeCount = volumeCount
        )
    }

    /** 분할이 꺼져 있을 때의 파일명, 켜져 있으면 1권 파일명. */
    fun previewName(): String = outputNames().firstOrNull() ?: "book.epub"

    /** 실제로 만들어질 파일명 전부. UI 미리보기와 저장에 같은 값을 쓴다. */
    fun outputNames(): List<String> {
        val s = _ui.value
        val chunks = chunks(s)
        return chunks.mapIndexed { i, chunk ->
            FileNamer.build(
                s.template,
                tokensFor(
                    s, chunk,
                    volume = if (chunks.size > 1) i + 1 else 0,
                    volumeCount = if (chunks.size > 1) chunks.size else 0
                ),
                s.space, s.case
            )
        }
    }

    private fun chunks(s: UiState): List<List<ChapterDetector.Chapter>> {
        if (s.chapters.isEmpty()) return listOf(emptyList())
        if (!s.splitEnabled || s.splitSize <= 0 || s.splitSize >= s.chapters.size) {
            return listOf(s.chapters)
        }
        return s.chapters.chunked(s.splitSize)
    }

    // ---------- 파일 열기 ----------

    fun open(uri: Uri, forced: TextReader.Encoding? = null) {
        sourceUri = uri
        _ui.value = _ui.value.copy(
            busy = true, progress = 0f, status = "파일을 읽는 중…", error = "", done = ""
        )
        viewModelScope.launch {
            try {
                val ctx = getApplication<Application>()
                val resolver = ctx.contentResolver

                var name = "untitled"
                var size = 0L
                runCatching {
                    resolver.query(uri, null, null, null, null)?.use { c ->
                        val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        val si = c.getColumnIndex(OpenableColumns.SIZE)
                        if (c.moveToFirst()) {
                            if (ni >= 0) name = c.getString(ni) ?: name
                            if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
                        }
                    }
                }

                val loaded = withContext(Dispatchers.IO) {
                    TextReader.load({ resolver.openInputStream(uri)!! }, forced)
                }
                lines = loaded.lines

                val guessTitle = name.substringBeforeLast('.', name)
                    .replace('_', ' ')
                    .trim()

                _ui.value = _ui.value.copy(
                    fileName = name,
                    fileSize = size,
                    encoding = loaded.encoding,
                    lineCount = loaded.lines.size,
                    charCount = loaded.charCount,
                    title = _ui.value.title.ifBlank { guessTitle },
                    candidates = emptyList(),
                    chapters = emptyList(),
                    detectNote = "",
                    status = "챕터를 분석하는 중…"
                )
                detect()
            } catch (e: Exception) {
                _ui.value = _ui.value.copy(busy = false, error = "파일을 읽지 못했습니다: ${e.message}")
            }
        }
    }

    fun reopenWith(enc: TextReader.Encoding?) {
        sourceUri?.let { open(it, enc) }
    }

    // ---------- 챕터 판정 ----------

    fun detect() {
        if (lines.isEmpty()) return
        _ui.value = _ui.value.copy(busy = true, progress = 0f, status = "챕터를 분석하는 중…")
        viewModelScope.launch {
            val list = withContext(Dispatchers.Default) {
                ChapterDetector.detect(lines) { done, total ->
                    _ui.value = _ui.value.copy(
                        progress = done.toFloat() / total,
                        status = "챕터 패턴 분석 중… $done / $total"
                    )
                }
            }
            val best = list.firstOrNull()
            if (best == null || best.score < ChapterDetector.MIN_SCORE) {
                val fallback = ChapterDetector.buildChapters(
                    emptyList(), lines, _ui.value.title.ifBlank { "본문" }
                )
                _ui.value = _ui.value.copy(
                    candidates = list,
                    selected = -1,
                    chapters = fallback,
                    detectNote = "구분 패턴을 찾지 못했습니다. 전체를 한 챕터로 담습니다.",
                    busy = false,
                    status = ""
                )
            } else {
                applyCandidate(list, 0)
            }
        }
    }

    fun selectCandidate(index: Int) = applyCandidate(_ui.value.candidates, index)

    private fun applyCandidate(list: List<ChapterDetector.Candidate>, index: Int) {
        val c = list.getOrNull(index) ?: return
        val chapters = ChapterDetector.buildChapters(c.hits, lines, _ui.value.title.ifBlank { "본문" })
        val note = buildString {
            append("${c.name} 형식 · 챕터 ${chapters.size}개 · 신뢰도 ${c.confidence}")
            append(" · 번호 연속성 ${(c.seq * 100).toInt()}%")
            append(" · 평균 ${c.meanSize.toInt()}자")
            if (c.dropped > 0) append(" · 오탐 ${c.dropped}건 제외")
        }
        _ui.value = _ui.value.copy(
            candidates = list,
            selected = index,
            chapters = chapters,
            detectNote = note,
            busy = false,
            status = ""
        )
    }

    fun applyManualRegex(pattern: String) {
        if (lines.isEmpty()) return
        viewModelScope.launch {
            try {
                val rx = Regex(pattern)
                val hits = withContext(Dispatchers.Default) {
                    ChapterDetector.detectManual(lines, rx)
                }
                val refined = ChapterDetector.refine(hits)
                val chapters = ChapterDetector.buildChapters(
                    refined, lines, _ui.value.title.ifBlank { "본문" }
                )
                _ui.value = _ui.value.copy(
                    selected = -1,
                    chapters = chapters,
                    detectNote = "직접 지정 · 챕터 ${chapters.size}개" +
                            if (hits.size != refined.size) " · 오탐 ${hits.size - refined.size}건 제외" else ""
                )
            } catch (e: Exception) {
                _ui.value = _ui.value.copy(error = "정규식이 올바르지 않습니다: ${e.message}")
            }
        }
    }

    // ---------- 미리보기 ----------

    /** 챕터 본문 앞부분. 미리보기 창에서 쓴다. */
    fun chapterText(index: Int, maxChars: Int = 4000): String {
        val ch = _ui.value.chapters.getOrNull(index) ?: return ""
        val sb = StringBuilder()
        var i = ch.fromLine
        while (i < ch.toLine && i < lines.size && sb.length < maxChars) {
            sb.append(lines[i]).append('\n')
            i++
        }
        val text = sb.toString().trim()
        return if (text.length > maxChars) text.take(maxChars) + "\n…" else text
    }

    /** 챕터의 글자 수. 목록에 같이 보여주면 잘못 잘린 챕터가 눈에 띈다. */
    fun chapterSize(index: Int): Int {
        val ch = _ui.value.chapters.getOrNull(index) ?: return 0
        var n = 0
        for (i in ch.fromLine until minOf(ch.toLine, lines.size)) n += lines[i].length
        return n
    }

    // ---------- 서지 정보 ----------

    /** 파일 안에 적힌 값을 읽는다. 네트워크를 쓰지 않아 빠르고, 맞으면 정확하다. */
    fun scanFile() {
        if (lines.isEmpty()) {
            _ui.value = _ui.value.copy(lookupNote = "먼저 파일을 여세요.")
            return
        }
        viewModelScope.launch {
            val found = withContext(Dispatchers.Default) { MetaScanner.scan(lines) }
            if (found.isEmpty) {
                _ui.value = _ui.value.copy(lookupNote = "파일 안에서 지은이·출판사 표기를 찾지 못했습니다.")
                return@launch
            }
            val s = _ui.value
            _ui.value = s.copy(
                title = if (found.title.isNotBlank()) found.title else s.title,
                author = if (found.author.isNotBlank()) found.author else s.author,
                publisher = if (found.publisher.isNotBlank()) found.publisher else s.publisher,
                lookupNote = "파일에서 찾은 값을 넣었습니다: " + listOfNotNull(
                    found.title.ifBlank { null }?.let { "제목 $it" },
                    found.author.ifBlank { null }?.let { "지은이 $it" },
                    found.publisher.ifBlank { null }?.let { "출판 $it" }
                ).joinToString(" · ")
            )
        }
    }

    fun lookup() {
        val q = _ui.value.title.ifBlank { _ui.value.fileName.substringBeforeLast('.', "") }.trim()
        if (q.isEmpty()) {
            _ui.value = _ui.value.copy(lookupNote = "먼저 제목을 입력하세요.")
            return
        }
        _ui.value = _ui.value.copy(lookupBusy = true, lookupNote = "조회하는 중…", lookupResults = emptyList())
        viewModelScope.launch {
            val s = _ui.value
            val res = runCatching {
                MetadataLookup.search(q, s.naverId, s.naverSecret)
            }.getOrDefault(emptyList())
            _ui.value = _ui.value.copy(
                lookupBusy = false,
                lookupResults = res,
                lookupNote = if (res.isEmpty())
                    "결과가 없습니다. 제목을 다르게 입력해 보세요."
                else
                    "자동 추출한 값이라 틀릴 수 있습니다. 적용 후 확인하세요."
            )
        }
    }

    fun applyInfo(info: MetadataLookup.Info) {
        _ui.value = _ui.value.copy(
            author = if (info.author.isNotBlank()) info.author else _ui.value.author,
            publisher = if (info.publisher.isNotBlank()) info.publisher else _ui.value.publisher
        )
        if (info.coverUrl.isNotBlank()) {
            viewModelScope.launch {
                val got = MetadataLookup.downloadCover(info.coverUrl)
                if (got != null) {
                    _ui.value = _ui.value.copy(coverBytes = got.first, coverMime = got.second)
                } else {
                    _ui.value = _ui.value.copy(lookupNote = "표지 이미지를 받지 못했습니다.")
                }
            }
        }
    }

    /** 기기에서 고른 이미지를 표지로 쓴다. */
    fun setCoverFrom(uri: Uri) {
        viewModelScope.launch {
            try {
                val resolver = getApplication<Application>().contentResolver
                val mime = resolver.getType(uri) ?: "image/jpeg"
                val bytes = withContext(Dispatchers.IO) {
                    resolver.openInputStream(uri)?.use { it.readBytes() }
                } ?: throw IllegalStateException("이미지를 열 수 없습니다")
                _ui.value = _ui.value.copy(coverBytes = bytes, coverMime = mime)
            } catch (e: Exception) {
                _ui.value = _ui.value.copy(error = "표지를 읽지 못했습니다: ${e.message}")
            }
        }
    }

    fun clearCover() {
        _ui.value = _ui.value.copy(coverBytes = null)
    }

    // ---------- 필드 편집 ----------

    fun setTitle(v: String) { _ui.value = _ui.value.copy(title = v) }
    fun setAuthor(v: String) { _ui.value = _ui.value.copy(author = v) }
    fun setPublisher(v: String) { _ui.value = _ui.value.copy(publisher = v) }
    fun setLanguage(v: String) { _ui.value = _ui.value.copy(language = v) }

    fun setTemplate(v: String) {
        prefs.template = v
        _ui.value = _ui.value.copy(template = v)
    }

    fun setSpace(v: FileNamer.SpaceMode) {
        prefs.space = v
        _ui.value = _ui.value.copy(space = v)
    }

    fun setCase(v: FileNamer.CaseMode) {
        prefs.case = v
        _ui.value = _ui.value.copy(case = v)
    }

    fun setSplitEnabled(v: Boolean) {
        prefs.splitEnabled = v
        _ui.value = _ui.value.copy(splitEnabled = v)
    }

    fun setSplitSize(v: Int) {
        val n = v.coerceIn(1, 100_000)
        prefs.splitSize = n
        _ui.value = _ui.value.copy(splitSize = n)
    }

    fun setNaverKeys(id: String, secret: String) {
        prefs.naverId = id
        prefs.naverSecret = secret
        _ui.value = _ui.value.copy(naverId = id.trim(), naverSecret = secret.trim())
    }

    fun clearError() { _ui.value = _ui.value.copy(error = "") }
    fun clearDone() { _ui.value = _ui.value.copy(done = "") }

    // ---------- 변환 ----------

    private fun metaFor(s: UiState, volume: Int, volumeCount: Int) = EpubWriter.Meta(
        title = s.title.ifBlank { "제목없음" }
            .let { if (volumeCount > 1) "$it ${volume}권" else it },
        author = s.author.ifBlank { "미상" },
        language = s.language,
        publisher = s.publisher,
        coverBytes = s.coverBytes,
        coverMime = s.coverMime
    )

    /** 한 권으로 저장한다. SAF가 미리 만들어 둔 파일에 쓴다. */
    fun export(target: Uri) {
        val s = _ui.value
        if (s.chapters.isEmpty()) return
        _ui.value = s.copy(busy = true, progress = 0f, status = "EPUB을 만드는 중…", error = "", done = "")
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val resolver = getApplication<Application>().contentResolver
                    resolver.openOutputStream(target)?.use { out ->
                        EpubWriter.write(
                            out = out,
                            chapters = s.chapters,
                            lines = lines,
                            meta = metaFor(s, 1, 1)
                        ) { doneCount, total ->
                            _ui.value = _ui.value.copy(
                                progress = doneCount.toFloat() / total,
                                status = "본문 작성 중… $doneCount / $total"
                            )
                        }
                    } ?: throw IllegalStateException("출력 스트림을 열 수 없습니다")
                }
                _ui.value = _ui.value.copy(
                    busy = false, progress = 1f,
                    status = "완료 · 챕터 ${s.chapters.size}개",
                    done = "EPUB 1개를 저장했습니다."
                )
            } catch (e: Exception) {
                _ui.value = _ui.value.copy(busy = false, error = "생성 실패: ${e.message}")
            }
        }
    }

    /**
     * 여러 권으로 나눠 폴더에 저장한다.
     *
     * 파일이 여러 개라 CreateDocument로는 안 되고 폴더 권한(OpenDocumentTree)이 필요하다.
     */
    fun exportSplit(treeUri: Uri) {
        val s = _ui.value
        if (s.chapters.isEmpty()) return
        val parts = chunks(s)
        val names = outputNames()
        _ui.value = s.copy(busy = true, progress = 0f, status = "EPUB을 만드는 중…", error = "", done = "")

        viewModelScope.launch {
            try {
                val ctx = getApplication<Application>()
                val dir = DocumentFile.fromTreeUri(ctx, treeUri)
                    ?: throw IllegalStateException("폴더를 열 수 없습니다")
                if (!dir.canWrite()) throw IllegalStateException("이 폴더에 쓸 권한이 없습니다")

                val totalChapters = s.chapters.size
                var written = 0

                withContext(Dispatchers.IO) {
                    parts.forEachIndexed { i, part ->
                        val name = names.getOrElse(i) { "book-${i + 1}.epub" }
                        // 같은 이름이 있으면 SAF가 '(1)'을 붙인다. 덮어쓰지 않는다.
                        val file = dir.createFile("application/epub+zip", name)
                            ?: throw IllegalStateException("파일을 만들 수 없습니다: $name")

                        ctx.contentResolver.openOutputStream(file.uri)?.use { out ->
                            EpubWriter.write(
                                out = out,
                                chapters = part,
                                lines = lines,
                                meta = metaFor(s, i + 1, parts.size)
                            ) { doneCount, _ ->
                                val overall = (written + doneCount).toFloat() / totalChapters
                                _ui.value = _ui.value.copy(
                                    progress = overall.coerceIn(0f, 1f),
                                    status = "${i + 1}/${parts.size}권 작성 중… $doneCount / ${part.size}"
                                )
                            }
                        } ?: throw IllegalStateException("출력 스트림을 열 수 없습니다: $name")

                        written += part.size
                    }
                }

                _ui.value = _ui.value.copy(
                    busy = false, progress = 1f,
                    status = "완료 · ${parts.size}권 · 챕터 ${s.chapters.size}개",
                    done = "EPUB ${parts.size}개를 저장했습니다."
                )
            } catch (e: Exception) {
                _ui.value = _ui.value.copy(busy = false, error = "생성 실패: ${e.message}")
            }
        }
    }
}
