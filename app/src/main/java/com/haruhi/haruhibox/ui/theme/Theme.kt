package com.haruhi.haruhibox.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme = darkColorScheme(
    primary = Purple80,
    secondary = PurpleGrey80,
    tertiary = Pink80
)

private val LightColorScheme = lightColorScheme(
    primary = Purple40,
    secondary = PurpleGrey40,
    tertiary = Pink40

    /* Other default colors to override
    background = Color(0xFFFFFBFE),
    surface = Color(0xFFFFFBFE),
    onPrimary = Color.White,
    onSecondary = Color.White,
    onTertiary = Color.White,
    onBackground = Color(0xFF1C1B1F),
    onSurface = Color(0xFF1C1B1F),
    */
)

/**
 * 浅蓝主题配色（[ThemeDeclaration] 声明 [AppThemeVariant.LIGHT_BLUE] 时使用）。
 *
 * 基底色 #E1E7FD 给 `background`（页面底色）；内容区域 #FFFFFF 同时给 `surface`
 * 与 `surfaceVariant`——本 App 的卡片/内容块这两个角色都在用，只覆盖 `surface` 的话，
 * "继续阅读"卡片这类用 `surfaceVariant` 的内容块会停在默认的灰紫色上。
 * 其余颜色角色不覆盖，沿用 Material 浅色基线：文字仍是深色，读得清。
 */
private val LightBlueColorScheme = lightColorScheme(
    background = LightBlueBackground,
    surface = LightBlueSurface,
    surfaceVariant = LightBlueSurface,
)

@Composable
fun HaruhiBoxTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Dynamic color is available on Android 12+
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = when (ThemeDeclaration.activeTheme) {
        // 浅蓝主题只定义了浅色一套颜色：不跟随系统深色、也不吃动态取色，
        // 声明了它就一定看到它（系统栏图标颜色同样跟着声明走，见 MainActivity）。
        AppThemeVariant.LIGHT_BLUE -> LightBlueColorScheme

        AppThemeVariant.ORIGINAL -> when {
            dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
                val context = LocalContext.current
                if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            }

            darkTheme -> DarkColorScheme
            else -> LightColorScheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}