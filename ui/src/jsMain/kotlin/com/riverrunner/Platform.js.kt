package com.riverrunner

import androidx.compose.runtime.Composable
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

actual val IoDispatcher: CoroutineDispatcher = Dispatchers.Default

actual inline fun <T> locked(lock: Any, block: () -> T): T = block()

actual fun localOffsetMinutes(epochMs: Long): Int {
    val ms = epochMs.toDouble()
    // JS reports minutes to add to local time to get UTC, so the sign is flipped
    return -(js("new Date(ms).getTimezoneOffset()") as Int)
}

@Composable
actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) {}
