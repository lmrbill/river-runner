package com.riverrunner

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import java.util.TimeZone

actual val IoDispatcher: CoroutineDispatcher = Dispatchers.IO

actual inline fun <T> locked(lock: Any, block: () -> T): T = synchronized(lock, block)

actual fun localOffsetMinutes(epochMs: Long): Int = TimeZone.getDefault().getOffset(epochMs) / 60_000

@Composable
actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) = BackHandler(enabled, onBack)
