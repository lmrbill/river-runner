package com.riverrunner.web

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import kotlinx.browser.document

/** Progress line on the loading screen (see index.html). */
private fun rrLog(msg: String): Unit = js("window.__rrLog && window.__rrLog(msg)")

/** Removes the loading screen once the app has drawn its first frame. */
private fun rrReady(): Unit = js("window.__rrReady && window.__rrReady()")

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    rrLog("应用代码已启动（JS）")
    try {
        // draws into <div id="app">; the loading screen sits next to it, and index.html turns it in portrait
        ComposeViewport(document.getElementById("app")!!) { WebApp(onFirstFrame = ::rrReady) }
        rrLog("界面已创建，等待第一帧…")
    } catch (e: Throwable) {
        rrLog("启动失败: " + (e.message ?: e.toString()))
    }
}
