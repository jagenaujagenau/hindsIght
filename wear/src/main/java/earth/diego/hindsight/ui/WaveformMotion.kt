package earth.diego.hindsight.ui

import kotlin.math.abs
import kotlin.math.exp

/**
 * Small, allocation-free animation model, driven by audio targets and display frames.
 * Fast attack preserves speech transients; a gentler release removes 64 ms stepping.
 * No oscillator or synthetic noise: silence eventually becomes completely stationary.
 */
internal class WaveformMotion(val sampleCount: Int = 31) {
    init { require(sampleCount > 1) }

    private val targets = FloatArray(sampleCount)
    val levels = FloatArray(sampleCount)
    var activity = 0f
        private set
    private var recording = false

    val needsFrame: Boolean
        get() = activity != (if (recording) 1f else 0f) || levels.indices.any { levels[it] != targets[it] }

    /** Returning to a visible page must never replay old audio or old frame deltas. */
    fun reset(recording: Boolean) {
        this.recording = recording
        targets.fill(0f)
        levels.fill(0f)
        activity = if (recording) 1f else 0f
    }

    fun setRecording(recording: Boolean) {
        this.recording = recording
        if (!recording) targets.fill(0f)
    }

    fun push(level: Float) {
        if (!recording) return
        System.arraycopy(targets, 1, targets, 0, sampleCount - 1)
        targets[sampleCount - 1] = if (level.isFinite()) level.coerceIn(0f, 1f) else 0f
    }

    /** Returns whether drawing changed. A duration scale of zero disables interpolation. */
    fun advance(deltaSeconds: Float, durationScale: Float = 1f): Boolean {
        if (!needsFrame) return false
        if (durationScale <= 0f) {
            targets.copyInto(levels)
            activity = if (recording) 1f else 0f
            return true
        }
        if (!deltaSeconds.isFinite() || deltaSeconds <= 0f) return false
        val dt = deltaSeconds.coerceAtMost(0.1f) / durationScale
        val attack = response(dt, 0.035f)
        val release = response(dt, if (recording) 0.12f else 0.18f)
        var changed = false
        for (i in levels.indices) {
            val previous = levels[i]
            levels[i] = approach(previous, targets[i], if (targets[i] > previous) attack else release)
            changed = changed || previous != levels[i]
        }
        val previousActivity = activity
        activity = approach(activity, if (recording) 1f else 0f, response(dt, 0.16f))
        return changed || activity != previousActivity
    }

    private fun response(dt: Float, timeConstant: Float): Float = 1f - exp(-dt / timeConstant)

    private fun approach(value: Float, target: Float, response: Float): Float {
        val next = value + (target - value) * response
        // Settle exactly, so silent/stopped waves do not request frames indefinitely.
        return if (abs(next - target) < 0.001f) target else next
    }
}
