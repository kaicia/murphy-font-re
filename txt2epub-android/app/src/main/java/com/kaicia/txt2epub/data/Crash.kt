package com.kaicia.txt2epub.data

import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 앱이 죽으면 이유를 남긴다.
 *
 * 기기에서 죽으면 시스템 팝업만 뜨고 왜 죽었는지는 알 수 없다. 개발자 도구를
 * 연결하지 않고도 원인을 볼 수 있게, 마지막 오류를 파일로 남겨 다음 실행 때 보여준다.
 * 메모리 부족이었는지 다른 오류였는지는 이 기록이 있어야 구분된다.
 */
object Crash {

    private const val FILE = "last-crash.txt"

    fun install(ctx: Context) {
        val app = ctx.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { write(app, thread, error) }
            // 시스템 기본 동작(팝업, 로그)은 그대로 둔다
            previous?.uncaughtException(thread, error)
        }
    }

    private fun write(ctx: Context, thread: Thread, error: Throwable) {
        val sw = StringWriter()
        PrintWriter(sw).use { error.printStackTrace(it) }
        val rt = Runtime.getRuntime()
        val text = buildString {
            append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())).append('\n')
            append("기기: ${Build.MANUFACTURER} ${Build.MODEL} · 안드로이드 ${Build.VERSION.RELEASE}\n")
            append("힙: 쓴 값 ${mb(rt.totalMemory() - rt.freeMemory())}MB / 상한 ${mb(rt.maxMemory())}MB\n")
            append("스레드: ${thread.name}\n\n")
            append(sw.toString())
        }
        File(ctx.filesDir, FILE).writeText(text)
    }

    private fun mb(bytes: Long) = bytes / (1024 * 1024)

    /** 지난번에 죽었으면 그 기록, 아니면 null. */
    fun lastReport(ctx: Context): String? {
        val f = File(ctx.applicationContext.filesDir, FILE)
        return if (f.exists()) runCatching { f.readText() }.getOrNull() else null
    }

    fun clear(ctx: Context) {
        runCatching { File(ctx.applicationContext.filesDir, FILE).delete() }
    }
}
