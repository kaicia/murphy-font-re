package com.kaicia.txt2epub

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.IntentCompat
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kaicia.txt2epub.core.FileNamer
import com.kaicia.txt2epub.core.TextReader
import com.kaicia.txt2epub.data.Crash
import com.kaicia.txt2epub.ui.Txt2EpubTheme

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Crash.install(this)
        val crash = Crash.lastReport(this)
        handleIncoming(intent)
        setContent {
            Txt2EpubTheme {
                Surface(Modifier.fillMaxSize()) {
                    var report by remember { mutableStateOf(crash) }
                    MainScreen(vm)
                    report?.let {
                        CrashDialog(it) { Crash.clear(this@MainActivity); report = null }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncoming(intent)
    }

    /** 파일 관리자에서 txt를 '열기/공유'로 보낸 경우 바로 읽어들인다. */
    private fun handleIncoming(intent: Intent?) {
        if (intent == null) return
        val uri: Uri? = when (intent.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
            else -> null
        }
        uri?.let { vm.open(it) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(vm: MainViewModel) {
    val s by vm.ui.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val snackbar = remember { SnackbarHostState() }

    var showChapters by remember { mutableStateOf(false) }
    var previewIndex by remember { mutableIntStateOf(-1) }
    var showSettings by remember { mutableStateOf(false) }

    val openFile = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? -> uri?.let { vm.open(it) } }

    val pickCover = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? -> uri?.let { vm.setCoverFrom(it) } }

    val saveFile = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/epub+zip")
    ) { uri: Uri? -> uri?.let { vm.export(it) } }

    val saveFolder = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? -> uri?.let { vm.exportSplit(it) } }

    LaunchedEffect(s.error) {
        if (s.error.isNotBlank()) {
            snackbar.showSnackbar(s.error)
            vm.clearError()
        }
    }
    LaunchedEffect(s.done) {
        if (s.done.isNotBlank()) {
            snackbar.showSnackbar(s.done)
            vm.clearDone()
        }
    }

    if (showChapters) {
        ChapterListDialog(
            vm = vm,
            onPick = { previewIndex = it },
            onDismiss = { showChapters = false }
        )
    }
    if (previewIndex >= 0) {
        ChapterPreviewDialog(vm = vm, index = previewIndex, onDismiss = { previewIndex = -1 })
    }
    if (showSettings) {
        NaverKeyDialog(
            id = s.naverId,
            secret = s.naverSecret,
            onSave = { a, b -> vm.setNaverKeys(a, b); showSettings = false },
            onDismiss = { showSettings = false }
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("TXT → EPUB")
                        // 어떤 빌드가 깔려 있는지 바로 보이게 한다
                        Text(
                            "v${BuildConfig.VERSION_NAME} · ${BuildConfig.GIT_SHA} · ${BuildConfig.BUILT_AT}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = {
                    TextButton(onClick = { showSettings = true }) { Text("설정") }
                }
            )
        }
    ) { pad ->
        Column(
            Modifier
                .padding(pad)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // 진행 상황. 큰 파일은 읽기·분석에 시간이 걸려서
            // 이게 안 보이면 멈춘 것처럼 보인다. 그래서 맨 위에 둔다.
            if (s.busy || s.status.isNotBlank()) {
                Card(Modifier.fillMaxWidth()) {
                    Row(
                        Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (s.busy) {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(14.dp))
                        }
                        Column(Modifier.weight(1f)) {
                            Text(
                                s.status.ifBlank { "처리 중…" },
                                style = MaterialTheme.typography.bodyMedium
                            )
                            if (s.busy && s.progress > 0f) {
                                Spacer(Modifier.height(8.dp))
                                LinearProgressIndicator(
                                    progress = { s.progress },
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }
                }
            }

            // 1. 파일
            Section("1. 파일") {
                Button(
                    // 형식을 걸지 않는다. 기기·파일관리자에 따라 txt가 목록에서 통째로
                    // 사라지는 일이 있었다. 대신 고른 뒤에 텍스트인지 확인해서 막는다.
                    onClick = { openFile.launch(arrayOf("*/*")) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !s.busy
                ) { Text(if (s.fileName.isBlank()) "TXT 파일 선택" else "다른 파일 선택") }

                if (s.fileName.isBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "변환할 원본 .txt 를 고르세요. 앱이 만든 .epub 을 다시 넣으면 안 됩니다.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                if (s.fileName.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(s.fileName, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(
                        "${s.encoding?.label ?: "-"} · ${"%,d".format(s.charCount)}자 · ${"%,d".format(s.lineCount)}줄",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    var encMenu by remember { mutableStateOf(false) }
                    Box {
                        OutlinedButton(onClick = { encMenu = true }, enabled = !s.busy) {
                            Text("인코딩 바꾸기")
                        }
                        DropdownMenu(expanded = encMenu, onDismissRequest = { encMenu = false }) {
                            DropdownMenuItem(text = { Text("자동 감지") }, onClick = {
                                encMenu = false; vm.reopenWith(null)
                            })
                            TextReader.Encoding.values().forEach { e ->
                                DropdownMenuItem(text = { Text(e.label) }, onClick = {
                                    encMenu = false; vm.reopenWith(e)
                                })
                            }
                        }
                    }
                }
            }

            // 2. 챕터
            if (s.chapters.isNotEmpty() || s.detectNote.isNotBlank()) {
                Section("2. 챕터 구분 (자동)") {
                    Text(s.detectNote, style = MaterialTheme.typography.bodyMedium)

                    if (s.candidates.size > 1) {
                        Spacer(Modifier.height(10.dp))
                        Label("다른 후보")
                        Spacer(Modifier.height(4.dp))
                        FlowRowCompat {
                            s.candidates.take(6).forEachIndexed { i, c ->
                                FilterChip(
                                    selected = i == s.selected,
                                    onClick = { vm.selectCandidate(i) },
                                    label = { Text("${c.name} · ${c.count}", fontSize = 12.sp) }
                                )
                            }
                        }
                    }

                    if (s.chapters.isNotEmpty()) {
                        Spacer(Modifier.height(10.dp))
                        Label("미리보기")
                        s.chapters.take(5).forEach {
                            Text(
                                "· ${it.title}",
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        if (s.chapters.size > 5) {
                            Text(
                                "… 외 ${"%,d".format(s.chapters.size - 5)}개",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        OutlinedButton(onClick = { showChapters = true }) {
                            Text("전체 목차 · 본문 미리보기")
                        }
                    }

                    Spacer(Modifier.height(4.dp))
                    OutlinedButton(onClick = {
                        val cm = ctx.getSystemService(android.content.ClipboardManager::class.java)
                        cm?.setPrimaryClip(
                            android.content.ClipData.newPlainText("txt2epub 진단", vm.diagnosis())
                        )
                    }) { Text("판정 진단 복사") }

                    var advanced by remember { mutableStateOf(false) }
                    var manual by remember { mutableStateOf("""^\s*(?:\S.{0,48}?\s+)?제?\s*(\d+)\s*화(\s|$|[.:\-])""") }
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = { advanced = !advanced }) {
                        Text(if (advanced) "고급 설정 닫기" else "고급 설정")
                    }
                    if (advanced) {
                        OutlinedTextField(
                            value = manual,
                            onValueChange = { manual = it },
                            label = { Text("구분 정규식") },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(6.dp))
                        OutlinedButton(onClick = { vm.applyManualRegex(manual) }) { Text("이 패턴으로 다시 나누기") }
                    }
                }
            }

            // 3. 책 정보
            if (s.chapters.isNotEmpty()) {
                Section("3. 책 정보") {
                    Field("제목", s.title, vm::setTitle)
                    Field("지은이", s.author, vm::setAuthor)
                    Field("출판/연재처", s.publisher, vm::setPublisher)

                    Spacer(Modifier.height(10.dp))
                    FlowRowCompat {
                        OutlinedButton(onClick = { vm.scanFile() }, enabled = !s.busy) {
                            Text("파일에서 찾기")
                        }
                        OutlinedButton(onClick = { vm.lookup() }, enabled = !s.lookupBusy) {
                            Text(if (s.lookupBusy) "조회 중…" else "웹에서 찾기")
                        }
                        OutlinedButton(onClick = {
                            val q = Uri.encode(s.title.ifBlank { s.fileName })
                            runCatching {
                                ctx.startActivity(Intent(Intent.ACTION_VIEW, "https://namu.wiki/Search?q=$q".toUri()))
                            }
                        }) { Text("나무위키 열기") }
                    }

                    if (s.naverId.isBlank()) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "설정에서 네이버 API 키를 넣으면 한국 소설 검색 정확도가 올라갑니다.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    if (s.lookupNote.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            s.lookupNote,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    s.lookupResults.forEach { info ->
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    "${info.title} [${info.source}]",
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis
                                )
                                val bits = listOfNotNull(
                                    info.author.ifBlank { null }?.let { "지은이 $it" },
                                    info.publisher.ifBlank { null },
                                    info.year.ifBlank { null }?.let { "${it}년" }
                                )
                                if (bits.isNotEmpty()) {
                                    Text(
                                        bits.joinToString(" · "),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            TextButton(onClick = { vm.applyInfo(info) }) { Text("적용") }
                        }
                    }

                }
            }

            // 4. 표지
            if (s.chapters.isNotEmpty()) {
                Section("4. 표지") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val bytes = s.coverBytes
                        if (bytes != null) {
                            CoverImage(bytes, Modifier.width(56.dp))
                            Spacer(Modifier.width(12.dp))
                            Text(
                                "이 표지가 EPUB에 들어갑니다.",
                                Modifier.weight(1f),
                                style = MaterialTheme.typography.bodySmall
                            )
                            TextButton(onClick = { vm.clearCover() }) { Text("제거") }
                        } else {
                            Text(
                                "표지 없음",
                                Modifier.weight(1f),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Spacer(Modifier.height(10.dp))
                    var coverQuery by remember(s.title) { mutableStateOf(s.title) }
                    OutlinedTextField(
                        value = coverQuery,
                        onValueChange = { coverQuery = it },
                        label = { Text("표지 검색어") },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    FlowRowCompat {
                        Button(
                            onClick = { vm.searchCovers(coverQuery) },
                            enabled = !s.coverBusy
                        ) { Text(if (s.coverBusy) "찾는 중…" else "표지 찾기") }
                        OutlinedButton(onClick = {
                            val q = Uri.encode(coverQuery.ifBlank { s.title })
                            runCatching {
                                ctx.startActivity(
                                    Intent(
                                        Intent.ACTION_VIEW,
                                        "https://www.google.com/search?tbm=isch&q=$q".toUri()
                                    )
                                )
                            }
                        }) { Text("웹 이미지 검색") }
                        OutlinedButton(onClick = { pickCover.launch("image/*") }) {
                            Text("기기에서 고르기")
                        }
                        if (s.coverResults.isNotEmpty()) {
                            TextButton(onClick = { vm.clearCoverResults() }) { Text("목록 닫기") }
                        }
                    }

                    Spacer(Modifier.height(8.dp))
                    var pasted by remember { mutableStateOf("") }
                    OutlinedTextField(
                        value = pasted,
                        onValueChange = { pasted = it },
                        label = { Text("이미지 주소 붙여넣기") },
                        placeholder = { Text("https://…", style = MaterialTheme.typography.bodySmall) },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "브라우저에서 그림을 길게 눌러 '이미지 주소 복사' 후 붙여넣으세요.",
                            Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        TextButton(
                            onClick = { vm.useCoverUrl(pasted); pasted = "" },
                            enabled = pasted.isNotBlank()
                        ) { Text("넣기") }
                    }

                    if (s.coverNote.isNotBlank()) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            s.coverNote,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    if (s.coverResults.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            s.coverResults.forEach { item ->
                                val thumb = item.thumb
                                if (thumb != null) Column(
                                    Modifier
                                        .width(84.dp)
                                        .clickable { vm.useCover(item) },
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    CoverImage(thumb, Modifier.fillMaxWidth())
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        item.cover.source,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                        Text(
                            "눌러서 고르면 큰 그림으로 다시 받아 넣습니다.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // 5. 파일 이름
            if (s.chapters.isNotEmpty()) {
                Section("5. 파일 이름 규칙") {
                    FlowRowCompat {
                        FileNamer.PRESETS.forEach { (name, tpl) ->
                            FilterChip(
                                selected = s.template == tpl,
                                onClick = { vm.setTemplate(tpl) },
                                label = { Text(name, fontSize = 12.sp) }
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Field("템플릿", s.template, vm::setTemplate, mono = true)

                    Spacer(Modifier.height(8.dp))
                    FlowRowCompat {
                        EnumDrop("공백", FileNamer.SpaceMode.values().toList(), s.space, {
                            when (it) {
                                FileNamer.SpaceMode.KEEP -> "그대로"
                                FileNamer.SpaceMode.UNDERSCORE -> "밑줄"
                                FileNamer.SpaceMode.DASH -> "붙임표"
                                FileNamer.SpaceMode.NONE -> "제거"
                            }
                        }, vm::setSpace)
                        EnumDrop("대소문자", FileNamer.CaseMode.values().toList(), s.case, {
                            when (it) {
                                FileNamer.CaseMode.KEEP -> "그대로"
                                FileNamer.CaseMode.LOWER -> "소문자"
                                FileNamer.CaseMode.UPPER -> "대문자"
                            }
                        }, vm::setCase)
                    }

                    Spacer(Modifier.height(10.dp))
                    Label("미리보기")
                    val names = remember(
                        s.template, s.space, s.case, s.title, s.author, s.publisher,
                        s.chapters, s.splitEnabled, s.splitSize, s.fileName
                    ) { vm.outputNames() }
                    names.take(3).forEach {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                        )
                    }
                    if (names.size > 3) {
                        Text(
                            "… 외 ${names.size - 3}개",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        "토큰: {title} {author} {chapters} {source} {lang} {publisher} {date} {datetime} {first} {last} {vol} {vols}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // 6. 분할
            if (s.chapters.isNotEmpty()) {
                Section("6. 나눠 저장") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("여러 권으로 나누기", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "한 파일이 너무 크면 뷰어가 느려집니다.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(checked = s.splitEnabled, onCheckedChange = { vm.setSplitEnabled(it) })
                    }
                    if (s.splitEnabled) {
                        Spacer(Modifier.height(8.dp))
                        var sizeText by remember(s.splitSize) { mutableStateOf(s.splitSize.toString()) }
                        OutlinedTextField(
                            value = sizeText,
                            onValueChange = { v ->
                                sizeText = v.filter { it.isDigit() }.take(6)
                                sizeText.toIntOrNull()?.let { vm.setSplitSize(it) }
                            },
                            label = { Text("권당 챕터 수") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(6.dp))
                        FlowRowCompat {
                            listOf(50, 100, 200, 300).forEach { n ->
                                FilterChip(
                                    selected = s.splitSize == n,
                                    onClick = { vm.setSplitSize(n) },
                                    label = { Text("${n}화씩", fontSize = 12.sp) }
                                )
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "${s.chapters.size}개 챕터 → ${s.volumeCount}권",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }

            // 7. 생성
            if (s.chapters.isNotEmpty()) {
                Section("7. 생성") {
                    Button(
                        onClick = {
                            if (s.splitEnabled && s.volumeCount > 1) saveFolder.launch(null)
                            else saveFile.launch(vm.previewName())
                        },
                        enabled = s.ready,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            if (s.splitEnabled && s.volumeCount > 1)
                                "저장할 폴더 고르기 (${s.volumeCount}권)"
                            else "EPUB 만들기"
                        )
                    }

                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

// ---------- 대화상자 ----------

/** 지난 실행에서 앱이 죽었으면 이유를 보여준다. 복사해서 그대로 전달하면 된다. */
@Composable
private fun CrashDialog(report: String, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                val cm = ctx.getSystemService(android.content.ClipboardManager::class.java)
                cm?.setPrimaryClip(android.content.ClipData.newPlainText("txt2epub crash", report))
                onDismiss()
            }) { Text("복사하고 닫기") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("닫기") } },
        title = { Text("지난번에 앱이 종료됐습니다") },
        text = {
            Column(
                Modifier
                    .heightIn(max = 380.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    "아래 내용을 복사해서 알려주시면 원인을 바로 찾을 수 있습니다.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    report,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                )
            }
        }
    )
}

@Composable
private fun ChapterListDialog(vm: MainViewModel, onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    val s by vm.ui.collectAsStateWithLifecycle()
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("닫기") } },
        title = { Text("목차 ${"%,d".format(s.chapters.size)}개") },
        text = {
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                itemsIndexed(s.chapters) { i, ch ->
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onPick(i) }
                            .padding(vertical = 6.dp)
                    ) {
                        Text(
                            "${i + 1}. ${ch.title}",
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            "${"%,d".format(vm.chapterSize(i))}자",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    HorizontalDivider()
                }
            }
        }
    )
}

@Composable
private fun ChapterPreviewDialog(vm: MainViewModel, index: Int, onDismiss: () -> Unit) {
    val s by vm.ui.collectAsStateWithLifecycle()
    val title = s.chapters.getOrNull(index)?.title ?: ""
    val body = remember(index, s.chapters) { vm.chapterText(index) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("닫기") } },
        title = { Text(title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(
                Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    body.ifBlank { "(본문이 비어 있습니다)" },
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    )
}

@Composable
private fun NaverKeyDialog(
    id: String,
    secret: String,
    onSave: (String, String) -> Unit,
    onDismiss: () -> Unit
) {
    var a by remember { mutableStateOf(id) }
    var b by remember { mutableStateOf(secret) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = { onSave(a, b) }) { Text("저장") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
        title = { Text("네이버 검색 API") },
        text = {
            Column {
                Text(
                    "developers.naver.com에서 검색 API를 등록하면 키가 나옵니다. " +
                            "넣어두면 한국 소설·웹소설 서지 정보를 훨씬 정확하게 찾습니다. " +
                            "키는 이 기기에만 저장됩니다.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = a, onValueChange = { a = it },
                    label = { Text("Client ID") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = b, onValueChange = { b = it },
                    label = { Text("Client Secret") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    )
}

// ---------- 조각 ----------

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}

/** 바이트를 그대로 그린다. 디코드가 실패하면 자리만 비워둔다. */
@Composable
private fun CoverImage(bytes: ByteArray, modifier: Modifier = Modifier) {
    val bmp = remember(bytes) {
        runCatching {
            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }.getOrNull()
    }
    if (bmp != null) {
        Image(
            bitmap = bmp.asImageBitmap(),
            contentDescription = "표지",
            modifier = modifier
        )
    }
}

@Composable
private fun Label(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun Field(label: String, value: String, onChange: (String) -> Unit, mono: Boolean = false) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        textStyle = if (mono)
            MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace)
        else MaterialTheme.typography.bodyMedium,
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 6.dp)
    )
}

@Composable
private fun <T> EnumDrop(
    label: String,
    items: List<T>,
    selected: T,
    render: (T) -> String,
    onPick: (T) -> Unit
) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }) { Text("$label: ${render(selected)}") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            items.forEach { item ->
                DropdownMenuItem(text = { Text(render(item)) }, onClick = { open = false; onPick(item) })
            }
        }
    }
}

/** 칩을 줄바꿈해 배치한다. FlowRow는 아직 실험 API라 opt-in이 필요하다. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FlowRowCompat(content: @Composable () -> Unit) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) { content() }
}
