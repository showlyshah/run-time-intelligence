package com.logan.runtimedebug

import android.os.Debug
import android.util.Log

/**
 * Minimal, opt-in diagnostics for apps that include this SDK themselves.
 * It records only developer-supplied metric names and numeric values.
 * It deliberately has no API for credentials, headers, URLs, payloads or keys.
 */
class AuthorizedDebugProbe(private val tag: String = "AuthorizedDebugProbe") {
    fun recordMetric(name: String, value: Long) {
        if (!BuildConfig.DIAGNOSTICS_ENABLED) return
        require(name.matches(Regex("[A-Za-z][A-Za-z0-9_.-]{0,47}"))) {
            "Metric names must be short identifiers"
        }
        Log.d(tag, "metric name=$name value=$value")
    }

    fun memorySnapshot(): MemorySnapshot {
        val runtime = Runtime.getRuntime()
        val info = Debug.MemoryInfo()
        Debug.getMemoryInfo(info)
        return MemorySnapshot(
            javaHeapUsedBytes = runtime.totalMemory() - runtime.freeMemory(),
            javaHeapLimitBytes = runtime.maxMemory(),
            processPssKb = info.totalPss,
            nativeHeapAllocatedBytes = Debug.getNativeHeapAllocatedSize()
        )
    }

    data class MemorySnapshot(
        val javaHeapUsedBytes: Long,
        val javaHeapLimitBytes: Long,
        val processPssKb: Int,
        val nativeHeapAllocatedBytes: Long
    )
}
