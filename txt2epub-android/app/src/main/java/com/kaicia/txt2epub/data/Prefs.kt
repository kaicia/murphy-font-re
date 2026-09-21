package com.kaicia.txt2epub.data

import android.content.Context
import com.kaicia.txt2epub.core.FileNamer

/**
 * 설정 저장.
 *
 * 파일명 규칙은 한 번 정하면 계속 쓰는 값이라 앱을 다시 열어도 남아 있어야 한다.
 * 네이버 API 키도 여기 둔다. 기기 안 앱 전용 저장소라 다른 앱이 읽지 못한다.
 */
class Prefs(context: Context) {

    private val sp = context.getSharedPreferences("txt2epub", Context.MODE_PRIVATE)

    var template: String
        get() = sp.getString(KEY_TEMPLATE, DEFAULT_TEMPLATE) ?: DEFAULT_TEMPLATE
        set(v) = sp.edit().putString(KEY_TEMPLATE, v).apply()

    var space: FileNamer.SpaceMode
        get() = enumOr(KEY_SPACE, FileNamer.SpaceMode.KEEP) { FileNamer.SpaceMode.valueOf(it) }
        set(v) = sp.edit().putString(KEY_SPACE, v.name).apply()

    var case: FileNamer.CaseMode
        get() = enumOr(KEY_CASE, FileNamer.CaseMode.KEEP) { FileNamer.CaseMode.valueOf(it) }
        set(v) = sp.edit().putString(KEY_CASE, v.name).apply()

    var splitEnabled: Boolean
        get() = sp.getBoolean(KEY_SPLIT_ON, false)
        set(v) = sp.edit().putBoolean(KEY_SPLIT_ON, v).apply()

    var splitSize: Int
        get() = sp.getInt(KEY_SPLIT_SIZE, 100)
        set(v) = sp.edit().putInt(KEY_SPLIT_SIZE, v.coerceIn(1, 100_000)).apply()

    var naverId: String
        get() = sp.getString(KEY_NAVER_ID, "") ?: ""
        set(v) = sp.edit().putString(KEY_NAVER_ID, v.trim()).apply()

    var naverSecret: String
        get() = sp.getString(KEY_NAVER_SECRET, "") ?: ""
        set(v) = sp.edit().putString(KEY_NAVER_SECRET, v.trim()).apply()

    private fun <T> enumOr(key: String, fallback: T, parse: (String) -> T): T {
        val raw = sp.getString(key, null) ?: return fallback
        return runCatching { parse(raw) }.getOrDefault(fallback)
    }

    companion object {
        const val DEFAULT_TEMPLATE = "{title} - {author} ({chapters}화)"

        private const val KEY_TEMPLATE = "template"
        private const val KEY_SPACE = "space"
        private const val KEY_CASE = "case"
        private const val KEY_SPLIT_ON = "split_on"
        private const val KEY_SPLIT_SIZE = "split_size"
        private const val KEY_NAVER_ID = "naver_id"
        private const val KEY_NAVER_SECRET = "naver_secret"
    }
}
