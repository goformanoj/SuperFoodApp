package com.jarvis.os.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.lang.management.ManagementFactory
import java.util.Locale
import kotlin.math.roundToInt

/**
 * The HUD's numbers, and every one of them is REAL: this laptop's CPU and memory
 * (sampled once a second from the JVM's OS bean), how long JARVIS has been up.
 * The phone's rule holds here too — a readout that always said "COMMAND ACCEPTED"
 * would be a lie, so nothing on the panels is decorative data.
 */
class Telemetry(private val scope: CoroutineScope) {

    data class Sample(val cpu: Float, val memUsedBytes: Long, val memTotalBytes: Long)

    /** Last [HISTORY] CPU readings, oldest first, each 0..1. */
    var cpuHistory by mutableStateOf(emptyList<Float>())
        private set
    var latest by mutableStateOf<Sample?>(null)
        private set

    private val startedMs = System.currentTimeMillis()
    fun uptimeMs(now: Long = System.currentTimeMillis()): Long = now - startedMs

    fun start() {
        scope.launch(Dispatchers.Default) {
            val os = ManagementFactory.getOperatingSystemMXBean() as? com.sun.management.OperatingSystemMXBean
            while (isActive) {
                if (os != null) {
                    val cpu = os.cpuLoad.takeIf { it >= 0 }?.toFloat() ?: 0f
                    val total = os.totalMemorySize
                    val used = (total - os.freeMemorySize).coerceAtLeast(0)
                    val s = Sample(cpu.coerceIn(0f, 1f), used, total)
                    latest = s
                    cpuHistory = push(cpuHistory, s.cpu, HISTORY)
                }
                delay(1000)
            }
        }
    }

    companion object {
        const val HISTORY = 60

        /** Appends [v], keeping the newest [cap] values. Pure; tested. */
        fun push(history: List<Float>, v: Float, cap: Int): List<Float> = (history + v).takeLast(cap)

        /** "7.9 / 15.8 GB". */
        fun gb(used: Long, total: Long): String =
            String.format(Locale.US, "%.1f / %.1f GB", used / 1e9, total / 1e9)

        /** "00:04:17" — hours roll past 99 rather than wrapping. */
        fun clock(ms: Long): String {
            val s = (ms / 1000).coerceAtLeast(0)
            return String.format(Locale.US, "%02d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60)
        }

        fun percent(f: Float): String = "${(f.coerceIn(0f, 1f) * 100).roundToInt()}%"
    }
}
