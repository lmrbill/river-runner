package com.riverrunner.web

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import kotlinx.browser.document
import kotlinx.browser.window

/** Progress line on the loading screen (see index.html). */
private fun rrLog(msg: String): Unit = js("window.__rrLog && window.__rrLog(msg)")

/** Removes the loading screen once the app has drawn its first frame. */
private fun rrReady(): Unit = js("window.__rrReady && window.__rrReady()")

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    rrLog("应用代码已启动（JS）")
    // ?nofont=1 skips the bundled Chinese font, to test whether loading it is what breaks a browser
    val noFont = window.location.search.contains("nofont=1")
    if (noFont) rrLog("测试：不加载中文字体")
    try {
        ComposeViewport(document.body!!) { App(platform = "JS", onFirstFrame = ::rrReady, bundledFont = !noFont) }
        rrLog("界面已创建，等待第一帧…")
    } catch (e: Throwable) {
        rrLog("启动失败: " + (e.message ?: e.toString()))
    }
}
