package com.kaicia.txt2epub.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/**
 * 앱 테마.
 *
 * 안드로이드 12부터는 배경화면에서 뽑은 색(Material You)을 쓰고,
 * 그 아래 버전에서는 책 아이콘과 맞춘 고정 색을 쓴다. 다크모드는 시스템 설정을 따른다.
 */

private val Brand = Color(0xFFB4562C)        // 런처 아이콘 배경과 같은 색
private val BrandDark = Color(0xFFFFB68F)

private val LightColors = lightColorScheme(
    primary = Brand,
    onPrimary = Color.White,
    secondary = Color(0xFF7A5B4C),
    surfaceTint = Brand
)

private val DarkColors = darkColorScheme(
    primary = BrandDark,
    onPrimary = Color(0xFF522200),
    secondary = Color(0xFFE6BEAA),
    surfaceTint = BrandDark
)

@Composable
fun Txt2EpubTheme(
    dark: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val ctx = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
