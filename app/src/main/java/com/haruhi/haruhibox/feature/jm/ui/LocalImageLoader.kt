package com.haruhi.haruhibox.feature.jm.ui

import androidx.compose.runtime.staticCompositionLocalOf
import coil3.ImageLoader

/**
 * 供 Composable 取到应用级 `ImageLoader`。
 *
 * 为什么需要它：JM 的封面必须走**带正确 UA 与代理设置**的 OkHttp
 * （封面域名对 UA 有校验），而且磁盘缓存要落在 `files/covers`
 * （用户要求第 8 点：封面本地缓存）。这些都在 `AppContainer.imageLoader` 里配好，
 * 通过这个 CompositionLocal 下发，避免每个页面各自造一个 ImageLoader。
 *
 * 用 `staticCompositionLocalOf` 而不是 `compositionLocalOf`：它在整个树的
 * 生命周期内不变，static 版本没有读取追踪的开销。
 */
val LocalImageLoader = staticCompositionLocalOf<ImageLoader> {
    error(
        "LocalImageLoader 没有提供。请在根 Composable 用 " +
            "CompositionLocalProvider(LocalImageLoader provides container.imageLoader) { ... } 包裹。",
    )
}
