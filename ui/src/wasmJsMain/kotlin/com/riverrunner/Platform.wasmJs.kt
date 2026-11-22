package com.riverrunner

import androidx.compose.runtime.Composable
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

actual val IoDispatcher: CoroutineDispatcher = Dispatchers.Default

actual inline fun <T> locked(lock: Any, block: () -> T): T = block()

private fun jsTimezoneOffset(ms: Double): Int = js("new Date(ms).getTimezoneOffset()")

// JS reports minutes to add to local time to get UTC, so the sign is flipped
actual fun localOffsetMinutes(epochMs: Long): Int = -jsTimezoneOffset(epochMs.toDouble())

@Composable
actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) {}
