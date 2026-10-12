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
 * 基底色 #E1E7FD 给 `background`（页面底色）；内容展示区域一律 #FFFFFF：
 * - `surface` / `surfaceVariant`：显式指定了这两个角色的卡片自身在用
 *   （书库/任务/继续阅读卡片用 `surfaceVariant`，章节卡片用 `surface`）；
 * - `surfaceContainer*` 整族：没显式指定颜色的组件在用——底部 tab
 *   （`surfaceContainer`）、弹层/下拉菜单、默认样式的 `Card` 等。
 *   不并成同一种白的话，页面上会混着好几种"偏紫的灰白"。
 *
 * 其余颜色角色不覆盖，沿用 Material 浅色基线：文字仍是深色，读得清；
 * 选中态（`secondaryContainer`）、强调色、Snackbar 反色等属于状态/语义色，不是内容面，不动。
 */
private val LightBlueColorScheme = lightColorScheme(
    background = LightBlueBackground,
    surface = LightBlueSurface,
    surfaceVariant = LightBlueSurface,
    surfaceContainerLowest = LightBlueSurface,
    surfaceContainerLow = LightBlueSurface,
    surfaceContainer = LightBlueSurface,
    surfaceContainerHigh = LightBlueSurface,
    surfaceContainerHighest = LightBlueSurface,
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