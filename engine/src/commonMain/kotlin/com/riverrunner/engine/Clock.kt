package com.riverrunner.engine

import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/** Wall-clock time in milliseconds, on every platform (JVM, JS, Wasm). */
@OptIn(ExperimentalTime::class)
fun nowMillis(): Long = Clock.System.now().toEpochMilliseconds()
