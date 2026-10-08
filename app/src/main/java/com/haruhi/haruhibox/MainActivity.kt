package com.haruhi.haruhibox

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.haruhi.haruhibox.feature.jm.ui.JmModeRoot
import com.haruhi.haruhibox.ui.theme.HaruhiBoxTheme

/**
 * 唯一的 Activity。
 *
 * P0/P1 的占位页已经删除：现在直接进入**漫画模式**。
 * 「开屏选择（漫画 / 视频）+ 模式切换」按计划属于 P6（壳与体验），
 * 而 §9.5 明确要求「未实现的功能不展示空页面或占位开关」，
 * 所以这里不放假的开屏，等 P6 一次做到位。
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = HaruhiBoxApp.from(this)
        setContent {
            HaruhiBoxTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    JmModeRoot(container = container)
                }
            }
        }
    }
}
