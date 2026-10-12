package com.haruhi.haruhibox.ui.theme

import android.content.res.Configuration
import android.content.res.Resources

/**
 * 主题声明文件：**切换 App 主题只改 [ThemeDeclaration.activeTheme] 这一行**。
 *
 * 两套主题都保留着，随时可以来回切（见 [AppThemeVariant]）：
 * - [AppThemeVariant.ORIGINAL]：原主题——跟随系统深浅色，Android 12+ 用动态取色（壁纸取色）；
 * - [AppThemeVariant.LIGHT_BLUE]：浅蓝主题——基底色 #E1E7FD、内容区域 #FFFFFF。
 *
 * 配色本身不在这里声明：浅蓝主题的两个颜色见 `Color.kt`（`LightBlueBackground` /
 * `LightBlueSurface`），角色映射见 `Theme.kt` 的 `LightBlueColorScheme`。
 */
object ThemeDeclaration {

    /** 当前启用的主题。改这一行即可切换，例如 `AppThemeVariant.ORIGINAL`。 */
    val activeTheme: AppThemeVariant = AppThemeVariant.LIGHT_BLUE
}

/** App 主题变体，见 [ThemeDeclaration]。 */
enum class AppThemeVariant {

    /** 原主题：跟随系统深浅色 + Android 12+ 动态取色。 */
    ORIGINAL,

    /**
     * 浅蓝主题：基底色 #E1E7FD、内容区域 #FFFFFF。
     *
     * 只定义了浅色一套配色，所以**固定浅色**：系统开深色也按浅色渲染。
     */
    LIGHT_BLUE,
}

/**
 * 系统栏（状态栏 / 导航栏）图标是否按"深色底、浅色图标"处理。
 *
 * 给 `MainActivity` 的 `enableEdgeToEdge` 用。这里不能直接用系统的深色模式判断：
 * 浅蓝主题固定浅色，系统开深色时默认判断会给出浅色图标，落在 #E1E7FD 底上看不清。
 */
fun isDarkSystemBarUi(resources: Resources): Boolean = when (ThemeDeclaration.activeTheme) {
    AppThemeVariant.ORIGINAL ->
        resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES

    AppThemeVariant.LIGHT_BLUE -> false
}