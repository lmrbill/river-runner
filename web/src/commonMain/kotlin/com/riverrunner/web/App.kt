package com.riverrunner.web

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.riverrunner.engine.Cards
import com.riverrunner.engine.Equity
import com.riverrunner.web.res.Res
import com.riverrunner.web.res.noto_sans_sc
import org.jetbrains.compose.resources.Font
import kotlin.math.roundToInt
import kotlin.random.Random
import kotlin.time.TimeSource

private val Bg = Color(0xFF0C1310)
private val Gold = Color(0xFFE6B54F)
private val Ink = Color(0xFF0C1310)
private val TextColor = Color(0xFFEEF1EA)
private val TextDim = Color(0xFF9DAAA2)

/**
 * First web build: checks that the page loads on the phone, Chinese text renders with the
 * bundled font, and the poker engine runs in the browser at a usable speed.
 * [platform] says which build the browser is running ("Wasm" or "JS"); [onFirstFrame] runs once
 * the first frame is on screen. [bundledFont] false skips the bundled Chinese font (a diagnostic).
 */
@Composable
fun App(platform: String, onFirstFrame: () -> Unit = {}, bundledFont: Boolean = true) {
    LaunchedEffect(Unit) {
        withFrameNanos { }
        onFirstFrame()
    }
    val cjk = if (bundledFont) FontFamily(Font(Res.font.noto_sans_sc)) else FontFamily.Default
    val base = TextStyle(fontFamily = cjk, color = TextColor, fontSize = 16.sp, textAlign = TextAlign.Center)
    var result by remember { mutableStateOf<String?>(null) }
    Box(Modifier.fillMaxSize().background(Bg), contentAlignment = Alignment.Center) {
        Column(
            Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("River Runner 网页版测试", style = base.copy(fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Gold))
            Text("能看到这行中文，说明字体加载正常。", style = base)
            Text("运行方式：$platform", style = base.copy(color = TextDim, fontSize = 14.sp))
            Button(
                onClick = { result = equityDemo() },
                colors = ButtonDefaults.buttonColors(containerColor = Gold, contentColor = Ink),
            ) {
                Text("计算一手：AsKs 对 QhQd", style = base.copy(color = Ink, fontWeight = FontWeight.Bold))
            }
            result?.let { Text(it, style = base) }
        }
    }
}

/** Preflop equity by simulation, timed, to see how fast the engine runs on this device. */
private fun equityDemo(): String {
    val start = TimeSource.Monotonic.markNow()
    val eq = Equity.showdown(listOf(Cards.parseMany("AsKs"), Cards.parseMany("QhQd")), emptyList(), Random(1))
    val ms = start.elapsedNow().inWholeMilliseconds
    fun pct(x: Double): String {
        val tenths = (x * 1000).roundToInt()
        return "${tenths / 10}.${tenths % 10}%"
    }
    return "AsKs 胜率 ${pct(eq[0])}，QhQd ${pct(eq[1])}\n3 万次模拟，用时 $ms 毫秒"
}
