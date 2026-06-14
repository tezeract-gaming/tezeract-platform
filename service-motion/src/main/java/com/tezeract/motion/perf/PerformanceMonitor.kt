package com.tezeract.motion.perf

import android.os.Debug
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

/**
 * Performance monitor for the Motion Engine service.
 * Tracks latency, FPS, memory, and CPU usage against PRD targets.
 *
 * PRD Section 2.5 targets:
 *   - Camera-to-callback latency: < 40ms
 *   - Pose inference time: < 15ms
 *   - Frame delivery: 30 fps minimum
 *   - Memory: < 200MB for service
 *   - CPU steady-state: < 30%
 */
class PerformanceMonitor {

    companion object {
        private const val TAG = "PerfMonitor"
        private const val REPORT_INTERVAL_MS = 10_000L  // Log every 10s
        private const val LATENCY_TARGET_MS = 40L
        private const val INFERENCE_TARGET_MS = 15L
        private const val FPS_TARGET = 30
        private const val MEMORY_TARGET_MB = 200L
    }

    // Latency tracking
    private val latencyWindow = ArrayDeque<Long>(120)
    private val inferenceWindow = ArrayDeque<Long>(120)

    // FPS tracking
    private var frameCount = 0L
    private var fpsWindowStart = SystemClock.elapsedRealtime()
    private var lastFps = 0f

    // Monitoring job
    private var monitorJob: Job? = null

    fun recordFrameLatency(cameraTimestamp: Long, deliveryTimestamp: Long) {
        val latency = deliveryTimestamp - cameraTimestamp
        synchronized(latencyWindow) {
            if (latencyWindow.size >= 120) latencyWindow.removeFirst()
            latencyWindow.addLast(latency)
        }
        frameCount++
    }

    fun recordInferenceTime(durationMs: Long) {
        synchronized(inferenceWindow) {
            if (inferenceWindow.size >= 120) inferenceWindow.removeFirst()
            inferenceWindow.addLast(durationMs)
        }
    }

    fun start(scope: CoroutineScope) {
        monitorJob = scope.launch(Dispatchers.Default) {
            while (isActive) {
                delay(REPORT_INTERVAL_MS)
                logReport()
            }
        }
    }

    fun stop() {
        monitorJob?.cancel()
        monitorJob = null
    }

    private fun logReport() {
        val now = SystemClock.elapsedRealtime()
        val elapsed = (now - fpsWindowStart) / 1000f
        lastFps = if (elapsed > 0) frameCount / elapsed else 0f

        val avgLatency = synchronized(latencyWindow) {
            if (latencyWindow.isEmpty()) 0.0 else latencyWindow.average()
        }
        val p95Latency = synchronized(latencyWindow) {
            if (latencyWindow.isEmpty()) 0L
            else latencyWindow.sorted().let { it[(it.size * 0.95).toInt().coerceAtMost(it.size - 1)] }
        }
        val avgInference = synchronized(inferenceWindow) {
            if (inferenceWindow.isEmpty()) 0.0 else inferenceWindow.average()
        }

        val memoryMb = getMemoryUsageMb()
        val cpuPercent = getCpuPercent()

        val latencyOk = avgLatency <= LATENCY_TARGET_MS
        val inferenceOk = avgInference <= INFERENCE_TARGET_MS
        val fpsOk = lastFps >= FPS_TARGET
        val memoryOk = memoryMb <= MEMORY_TARGET_MB

        Log.i(TAG, buildString {
            append("╔══ Performance Report ══╗\n")
            append("║ FPS: %.1f %s (target: %d)\n".format(lastFps, if (fpsOk) "✓" else "✗", FPS_TARGET))
            append("║ Latency avg: %.1fms %s (target: %dms)\n".format(avgLatency, if (latencyOk) "✓" else "✗", LATENCY_TARGET_MS))
            append("║ Latency p95: %dms\n".format(p95Latency))
            append("║ Inference avg: %.1fms %s (target: %dms)\n".format(avgInference, if (inferenceOk) "✓" else "✗", INFERENCE_TARGET_MS))
            append("║ Memory: %dMB %s (target: %dMB)\n".format(memoryMb, if (memoryOk) "✓" else "✗", MEMORY_TARGET_MB))
            append("║ CPU: %.1f%%\n".format(cpuPercent))
            append("╚════════════════════════╝")
        })

        // Reset FPS window
        frameCount = 0
        fpsWindowStart = now
    }

    private fun getMemoryUsageMb(): Long {
        val info = Debug.MemoryInfo()
        Debug.getMemoryInfo(info)
        return info.totalPss.toLong() / 1024  // PSS is in KB
    }

    private fun getCpuPercent(): Float {
        return try {
            val pid = android.os.Process.myPid()
            val statFile = File("/proc/$pid/stat")
            if (!statFile.exists()) return -1f

            val fields = statFile.readText().split(" ")
            // fields[13] = utime, fields[14] = stime (in clock ticks)
            val utime = fields[13].toLongOrNull() ?: 0
            val stime = fields[14].toLongOrNull() ?: 0
            val totalTicks = utime + stime

            // Read system uptime
            val uptimeMs = SystemClock.elapsedRealtime()
            val ticksPerSec = 100L // CLK_TCK on most Android

            val cpuSeconds = totalTicks.toFloat() / ticksPerSec
            val uptimeSeconds = uptimeMs / 1000f

            if (uptimeSeconds > 0) (cpuSeconds / uptimeSeconds) * 100f else 0f
        } catch (_: Exception) {
            -1f
        }
    }

    fun getSnapshot(): PerfSnapshot {
        val avgLatency = synchronized(latencyWindow) {
            if (latencyWindow.isEmpty()) 0.0 else latencyWindow.average()
        }
        val avgInference = synchronized(inferenceWindow) {
            if (inferenceWindow.isEmpty()) 0.0 else inferenceWindow.average()
        }
        return PerfSnapshot(
            fps = lastFps,
            avgLatencyMs = avgLatency,
            avgInferenceMs = avgInference,
            memoryMb = getMemoryUsageMb(),
            cpuPercent = getCpuPercent()
        )
    }
}

data class PerfSnapshot(
    val fps: Float,
    val avgLatencyMs: Double,
    val avgInferenceMs: Double,
    val memoryMb: Long,
    val cpuPercent: Float
)
