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
                // 根背景用 `surface`（白）而不是内容区的 `background`（#F5F5F5）：
                // 状态栏那条（edge-to-edge 下由根部 Scaffold 的 windowInsets 让出来）
                // 露出的就是这个颜色，必须是白的，才能和底部导航栏
                // （`NavigationBar` 读 `surfaceContainer`，同为白）以及各页的白色头部连成一片。
                // 5 个 tab 页的**正文区**各自铺自己的 `background` 灰底，见各 Screen 的 body。
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.surface,
                ) {
                    JmModeRoot(container = container)
                }
            }
        }
    }

    /**
     * 退出收尾（返回键退出根页面时 `isFinishing == true`）：**下载暂停、导出任务删除**，
     * 具体见 [com.haruhi.haruhibox.core.di.AppContainer.handleAppExit]（用户拍板的行为）。
     *
     * 必须判 [isFinishing]：转屏、深色模式切换这类配置变更也会销毁重建 Activity，
     * 但那是"页面重建"不是"退出应用"，任务要继续跑。划掉最近任务由
     * `DownloadForegroundService.onTaskRemoved` 触发，两边调同一个入口、且幂等。
     */
    override fun onDestroy() {
        if (isFinishing) {
            HaruhiBoxApp.from(this).handleAppExit()
        }
        super.onDestroy()
    }
}
