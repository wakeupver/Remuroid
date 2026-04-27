/*
 * AdaptivePerformanceManager.kt
 *
 * Monitors real-time frame delivery and thermal state to keep emulation smooth.
 *
 * Responsibilities:
 *  1. Frame-time tracking  – detects when the GL thread is missing its deadline.
 *  2. Thermal throttle     – reads CPU thermal zone temperature; backs off before
 *                            the OS forcibly throttles the big cores.
 *  3. Skip-duplicate-frame – advises GLRetroView.skipDuplicateFrames based on load.
 *
 * Usage (from BaseGameActivity / GameViewModel):
 *
 *     val apm = AdaptivePerformanceManager(targetFps = 60.0)
 *     // Call once per rendered frame from the GL events observer:
 *     apm.onFrameRendered()
 *     // Call periodically (e.g. every 2 s) to check thermal state:
 *     val advice = apm.getPerformanceAdvice()
 *     retroView.skipDuplicateFrames = advice.skipDuplicateFrames
 */

package com.swordfish.lemuroid.lib.game

import android.os.SystemClock
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class AdaptivePerformanceManager(private val targetFps: Double = 60.0) {

    // ─── frame-time ring buffer ────────────────────────────────────────────
    private val WINDOW = 90                     // frames in moving-average window
    private val frameTimes = LongArray(WINDOW)
    private var frameHead = 0
    private var frameCount = 0
    private var lastFrameTimeMs = 0L

    // ─── thermal ───────────────────────────────────────────────────────────
    // Android exposes thermal zones under /sys/class/thermal/thermal_zone*/
    // We pick the first zone whose type contains "cpu" or "soc".
    private val thermalFile: File? by lazy { findThermalFile() }

    // ─── thresholds ────────────────────────────────────────────────────────
    private val targetFrameMs  = 1000.0 / targetFps
    // Considered "slow" when average frame time exceeds target by >15 %
    private val slowThresholdMs = targetFrameMs * 1.15
    // Considered "very slow" (recommend duplicate-frame skip) at >40 %
    private val verySlowThresholdMs = targetFrameMs * 1.40

    // Thermal limits in milli-Celsius (as reported by the kernel)
    private val THERMAL_WARN_MC  = 75_000   // 75 °C  → suggest light countermeasures
    private val THERMAL_CRIT_MC  = 85_000   // 85 °C  → aggressive countermeasures

    // ─── public API ────────────────────────────────────────────────────────

    data class PerformanceAdvice(
        /** True if GLRetroView.skipDuplicateFrames should be enabled */
        val skipDuplicateFrames: Boolean,
        /** Estimated average FPS over the last WINDOW frames */
        val averageFps: Double,
        /** Current thermal temperature in °C, or null if unavailable */
        val thermalCelsius: Double?,
        /** True when the thermal sensor is above warning threshold */
        val thermalWarn: Boolean,
        /** True when the thermal sensor is above critical threshold */
        val thermalCritical: Boolean,
    )

    /** Must be called once per rendered frame (from GL-thread observer). */
    fun onFrameRendered() {
        val now = SystemClock.elapsedRealtime()
        if (lastFrameTimeMs != 0L) {
            val dt = now - lastFrameTimeMs
            frameTimes[frameHead] = dt
            frameHead = (frameHead + 1) % WINDOW
            frameCount = min(frameCount + 1, WINDOW)
        }
        lastFrameTimeMs = now
    }

    /** Call periodically (every 1–5 s) from a coroutine on Dispatchers.Default. */
    fun getPerformanceAdvice(): PerformanceAdvice {
        val avgFrameMs = averageFrameTimeMs()
        val avgFps = if (avgFrameMs > 0) 1000.0 / avgFrameMs else targetFps

        val thermalMc = readThermalMilliCelsius()
        val thermalC  = thermalMc?.let { it / 1000.0 }
        val thermalWarn     = thermalMc != null && thermalMc >= THERMAL_WARN_MC
        val thermalCritical = thermalMc != null && thermalMc >= THERMAL_CRIT_MC

        // Skip duplicate frames when:
        //  (a) average frame time is very high (GPU/CPU bound), OR
        //  (b) thermal situation is critical
        val skipDuplicateFrames =
            avgFrameMs > verySlowThresholdMs || thermalCritical

        return PerformanceAdvice(
            skipDuplicateFrames = skipDuplicateFrames,
            averageFps          = avgFps,
            thermalCelsius      = thermalC,
            thermalWarn         = thermalWarn,
            thermalCritical     = thermalCritical,
        )
    }

    /** Returns the average frame-delivery time over the sampled window, in ms. */
    fun averageFrameTimeMs(): Double {
        if (frameCount == 0) return targetFrameMs
        var sum = 0L
        val n   = frameCount
        for (i in 0 until n) sum += frameTimes[i]
        return sum.toDouble() / n
    }

    // ─── private ───────────────────────────────────────────────────────────

    private fun readThermalMilliCelsius(): Int? {
        return try {
            thermalFile?.readText()?.trim()?.toIntOrNull()
        } catch (_: Exception) {
            null
        }
    }

    private fun findThermalFile(): File? {
        val base = File("/sys/class/thermal")
        if (!base.exists()) return null
        val preferred = listOf("cpu", "soc", "skin", "battery")
        // Iterate zones and pick the most CPU-relevant one
        return base.listFiles()
            ?.filter { it.isDirectory && it.name.startsWith("thermal_zone") }
            ?.sortedBy { it.name }
            ?.firstOrNull { zone ->
                val type = File(zone, "type").runCatching { readText().trim() }.getOrNull() ?: ""
                preferred.any { type.contains(it, ignoreCase = true) }
            }
            ?.let { File(it, "temp") }
            ?.takeIf { it.canRead() }
    }
}
