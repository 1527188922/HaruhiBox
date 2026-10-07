package com.haruhi.haruhibox

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.haruhi.haruhibox.ui.theme.HaruhiBoxTheme

/**
 * 唯一的 Activity。壳级别的骨架，真正的页面在 P2/P5/P6 逐阶段接入
 * （开屏选择 → 模式主框架 → 书库/发现/收藏/任务）。
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            HaruhiBoxTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    androidx.compose.foundation.layout.Box(
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        AppPlaceholder(modifier = Modifier.padding(24.dp))
                    }
                }
            }
        }
    }
}

/**
 * 临时占位页。
 *
 * 计划 §9.5 明确要求「**未实现的功能不展示空页面或占位开关**」——
 * 所以这里不是"设置入口占位"，而是 P0 阶段用于确认工程可安装可启动的最小界面，
 * 会在 P2 接入开屏选择时整体删除。
 */
@Composable
private fun AppPlaceholder(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.headlineMedium,
        )
        Text(
            text = stringResource(R.string.p0_placeholder_hint),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}
