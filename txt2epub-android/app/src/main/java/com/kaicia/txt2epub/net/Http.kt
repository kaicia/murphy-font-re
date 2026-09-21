package com.kaicia.txt2epub.net

import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * 조회에 쓰는 공용 HTTP 클라이언트.
 *
 * 커넥션 풀을 나눠 쓰려고 한 곳에 모았다. 실패는 예외 대신 null로 돌려준다.
 * 서지 조회는 실패해도 변환 자체는 계속돼야 하기 때문이다.
 */
internal object Http {

    // 일부 사이트는 기본 UA를 봇으로 보고 막는다.
    const val UA =
        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"

    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    fun text(url: String, headers: Map<String, String> = emptyMap()): String? = try {
        val b = Request.Builder()
            .url(url)
            .header("User-Agent", UA)
            .header("Accept-Language", "ko-KR,ko;q=0.9")
        headers.forEach { (k, v) -> b.header(k, v) }
        client.newCall(b.build()).execute().use { resp ->
            if (resp.isSuccessful) resp.body?.string() else null
        }
    } catch (e: Exception) {
        null
    }

    /** 이미지를 받아 (바이트, MIME)로 돌려준다. 이미지가 아니면 null. */
    fun image(url: String): Pair<ByteArray, String>? = try {
        val req = Request.Builder().url(url).header("User-Agent", UA).build()
        client.newCall(req).execute().use { resp ->
            val body = resp.body
            if (resp.isSuccessful && body != null) {
                val mime = body.contentType()?.toString()?.substringBefore(";")?.trim() ?: "image/jpeg"
                if (mime.startsWith("image/")) body.bytes() to mime else null
            } else null
        }
    } catch (e: Exception) {
        null
    }

    fun enc(s: String): String = java.net.URLEncoder.encode(s, "UTF-8")
}
