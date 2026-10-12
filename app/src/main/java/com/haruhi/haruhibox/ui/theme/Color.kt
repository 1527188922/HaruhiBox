package com.haruhi.haruhibox.ui.theme

import androidx.compose.ui.graphics.Color

val Purple80 = Color(0xFFD0BCFF)
val PurpleGrey80 = Color(0xFFCCC2DC)
val Pink80 = Color(0xFFEFB8C8)

val Purple40 = Color(0xFF6650a4)
val PurpleGrey40 = Color(0xFF625b71)
val Pink40 = Color(0xFF7D5260)

/**
 * 警示橙（"会覆盖文件"这类提醒）。
 *
 * 主题开了动态取色，`tertiary` 在真机上可能是粉的也可能是别的颜色，
 * 承载不了"警告"这个语义，所以警示单独固定成橙色；深浅色各取一个值，
 * 保证在白底和黑底上都看得清。
 */
val WarningOrangeLight = Color(0xFFB45309)
val WarningOrangeDark = Color(0xFFFFB74D)

/**
 * 浅蓝主题（`ThemeDeclaration` 声明启用）的固定配色。
 *
 * 基底色 #E1E7FD 是页面底色，内容区域 #FFFFFF 是卡片、列表等内容面。
 */
val LightBlueBackground = Color(0xFFE1E7FD)
val LightBlueSurface = Color(0xFFFFFFFF)