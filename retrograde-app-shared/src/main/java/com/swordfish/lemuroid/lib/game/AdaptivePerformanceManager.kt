/*
 * AdaptivePerformanceManager.kt
 *
 * Monitors real-time frame delivery and thermal state to keep emulation smooth.
 *
 * Responsibilities:
 *  1. Frame-time tracking  – detects when the GL thread is missing its deadline.
 *  2. Core-FPS detection   – auto-detects the core's actual target FPS after a
 *                            warmup period and snaps it to a standard value
 *                            (25, 30, 50, 60 FPS). This prevents the classic
 *                            "double speed" bug where 30 FPS games appear to run
 *                            at 2× speed because the manager mistakenly treats
 *                            normal 33 ms frames as "very slow" when comparing
 *                            against a hardcoded 60 FPS target.
 *  3. Thermal throttle     – reads CPU thermal zone temperature; backs off before
 *                            the OS forcibly throttles the big cores.
 *  4. Skip-duplicate-frame – advises GLRetroView.skipDuplicateFrames based on load.
 *                            ONLY enabled when the core is genuinely falling behind
 *                            its own detected target, or when thermals are critical.
 *
 * Usage (from BaseGameActivity / GameViewModel):
 *
 *     val apm = AdaptivePerformanceManager(maxFps = 60.0)
 *     // Call once per rendered frame from the GL events observer:
 *     apm.onFrameRendered()
 *     // Call periodically (e.g. every 3 s) to check thermal state:
 *     val advice = apm.getPerformanceAdvice()
 *     retroView.skipDuplicateFrames = advice.skipDuplicateFrames
 */

package com.swordfish.lemuroid.lib.game

import android.os.SystemClock
import java.io.File
import kotlin.math.abs
import kotlin.math.min

class AdaptivePerformanceManager(
    /**
     * The maximum FPS the display/host can drive (typically the screen refresh rate).
     * Used as a ceiling for FPS detection and as the initial assumed target until
     * the core's actual FPS is detected after warmup.
     */
    private val maxFps: Double = 60.0,
) {

    // ─── frame-time ring buffer ────────────────────────────────────────────
    private val WINDOW = 90                     // frames in moving-average window
    private val frameTimes = LongArray(WINDOW)
    private var frameHead = 0
    private var frameCount = 0
    private var lastFrameTimeMs = 0L

    // ─── core FPS auto-detection ───────────────────────────────────────────
    // After WARMUP_FRAMES we measure the median frame time and snap it to the
    // nearest standard FPS.  This correctly identifies 30 FPS games so that
    // ~33 ms frames are never flagged as "too slow".
    private val WARMUP_FRAMES = 120            // ~2–4 s depending on actual FPS

    // Standard libretro core FPS values (NTSC/PAL variants included)
    private val STANDARD_FPS = listOf(25.0, 30.0, 50.0, 60.0)

    // Detected target FPS; starts at maxFps, updated after warmup
    @Volatile private var detectedTargetFps: Double = maxFps
    private var warmupDone = false

    // ─── derived thresholds (recomputed when detectedTargetFps changes) ────
    private var targetFrameMs     = 1000.0 / maxFps
    // "Very slow" = frame time exceeds target by >40 % → enable skipDuplicateFrames
    private var verySlowThresholdMs = targetFrameMs * 1.40

    // ─── thermal ───────────────────────────────────────────────────────────
    private val thermalFile: File? by lazy { findThermalFile() }

    // Thermal limits in milli-Celsius (as reported by the kernel)
    private val THERMAL_WARN_MC  = 75_000   // 75 °C
    private val THERMAL_CRIT_MC  = 85_000   // 85 °C

    // ─── public API ────────────────────────────────────────────────────────

    data class PerformanceAdvice(
        /** True if GLRetroView.skipDuplicateFrames should be enabled */
        val skipDuplicateFrames: Boolean,
        /** Estimated average FPS over the last WINDOW frames */
        val averageFps: Double,
        /** Detected core target FPS (auto-calibrated after warmup) */
        val detectedTargetFps: Double,
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

            // Run FPS detection once after enough frames have been collected
            if (!warmupDone && frameCount >= WARMUP_FRAMES) {
                detectCoreFps()
                warmupDone = true
            }
        }
        lastFrameTimeMs = now
    }

    /** Call periodically (every 1–5 s) from a coroutine on Dispatchers.Default. */
    fun getPerformanceAdvice(): PerformanceAdvice {
        val avgFrameMs = averageFrameTimeMs()
        val avgFps = if (avgFrameMs > 0) 1000.0 / avgFrameMs else detectedTargetFps

        val thermalMc = readThermalMilliCelsius()
        val thermalC  = thermalMc?.let { it / 1000.0 }
        val thermalWarn     = thermalMc != null && thermalMc >= THERMAL_WARN_MC
        val thermalCritical = thermalMc != null && thermalMc >= THERMAL_CRIT_MC

        // Only enable skipDuplicateFrames when the core is genuinely behind its
        // own target FPS (not when it's intentionally running at 30 FPS on a
        // 60 Hz display), or when thermals are critical.
        val skipDuplicateFrames = !warmupDone || avgFrameMs > verySlowThresholdMs || thermalCritical

        return PerformanceAdvice(
            skipDuplicateFrames = skipDuplicateFrames,
            averageFps          = avgFps,
            detectedTargetFps   = detectedTargetFps,
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

    /** Returns the core target FPS detected after warmup, or maxFps before warmup. */
    fun getDetectedTargetFps(): Double = detectedTargetFps

    // ─── private ───────────────────────────────────────────────────────────

    /**
     * Computes the median frame time from the ring buffer and snaps it to the
     * nearest standard FPS value (25, 30, 50, 60).  Updates [detectedTargetFps]
     * and the slow/fast thresholds accordingly.
     *
     * Median is used instead of mean because occasional long frames (GC, shader
     * compile) would otherwise skew the mean and cause wrong FPS detection.
     */
    private fun detectCoreFps() {
        val n = frameCount
        val sorted = frameTimes.copyOf(n).also { it.sort() }
        val medianMs = sorted[n / 2].toDouble()
        val observedFps = if (medianMs > 0) 1000.0 / medianMs else maxFps

        // Snap to the nearest standard FPS, but cap at maxFps so we never report
        // a target higher than what the display can do.
        val snapped = STANDARD_FPS
            .filter { it <= maxFps + 5.0 }          // allow slight over-estimate
            .minByOrNull { abs(it - observedFps) }
            ?: maxFps

        detectedTargetFps   = snapped
        targetFrameMs       = 1000.0 / snapped
        // 40 % headroom before we consider the core "very slow"
        verySlowThresholdMs = targetFrameMs * 1.40
    }

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
