/*
 * LibrePods - AirPods liberated from Apple's ecosystem
 * Copyright (C) 2025 LibrePods contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * any later version.
 */

package me.kavishdevar.librepods.utils

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

internal enum class SpatialRecenterState {
    LOCKED,
    WAITING,
    RECENTERING
}

internal data class SpatialPoseDiagnostics(
    val rawYawDegrees: Float,
    val outputYawDegrees: Float,
    val angularSpeedDegreesPerSecond: Float,
    val anchorYawDegrees: Float,
    val recenterState: SpatialRecenterState
)

internal data class SpatialPoseSample(
    val pose: HeadPose,
    val diagnostics: SpatialPoseDiagnostics
)

/**
 * Converts AirPods orientation quaternions into the Android Head Tracker HID pose.
 *
 * The processor keeps the stage fixed during normal motion, uses a small adaptive
 * quaternion filter to suppress stationary jitter, and only moves the yaw anchor
 * after the listener deliberately holds a large off-centre pose. This mirrors the
 * perceptual behaviour of screen-anchored spatial audio without modifying the
 * vendor Spatializer or the Bluetooth connection lifecycle.
 */
internal class SpatialHeadPoseProcessor {
    private data class Quaternion(
        val w: Double,
        val x: Double,
        val y: Double,
        val z: Double
    ) {
        fun normalized(): Quaternion {
            val magnitude = sqrt(w * w + x * x + y * y + z * z)
            if (magnitude < EPSILON) return IDENTITY
            return Quaternion(w / magnitude, x / magnitude, y / magnitude, z / magnitude)
        }

        fun conjugate() = Quaternion(w, -x, -y, -z)

        operator fun times(other: Quaternion) = Quaternion(
            w = w * other.w - x * other.x - y * other.y - z * other.z,
            x = w * other.x + x * other.w + y * other.z - z * other.y,
            y = w * other.y - x * other.z + y * other.w + z * other.x,
            z = w * other.z + x * other.y - y * other.x + z * other.w
        )

        fun canonicalized() = if (w < 0.0) {
            Quaternion(-w, -x, -y, -z)
        } else {
            this
        }

        fun dot(other: Quaternion): Double =
            w * other.w + x * other.x + y * other.y + z * other.z

        fun angleTo(other: Quaternion): Double {
            val cosine = abs(normalized().dot(other.normalized())).coerceIn(-1.0, 1.0)
            return 2.0 * acos(cosine)
        }

        fun slerp(other: Quaternion, amount: Double): Quaternion {
            val from = normalized()
            var to = other.normalized()
            var cosine = from.dot(to)
            if (cosine < 0.0) {
                to = Quaternion(-to.w, -to.x, -to.y, -to.z)
                cosine = -cosine
            }
            val t = amount.coerceIn(0.0, 1.0)
            if (cosine > 0.9995) {
                return Quaternion(
                    from.w + (to.w - from.w) * t,
                    from.x + (to.x - from.x) * t,
                    from.y + (to.y - from.y) * t,
                    from.z + (to.z - from.z) * t
                ).normalized()
            }
            val angle = acos(cosine.coerceIn(-1.0, 1.0))
            val denominator = sin(angle)
            val fromScale = sin((1.0 - t) * angle) / denominator
            val toScale = sin(t * angle) / denominator
            return Quaternion(
                from.w * fromScale + to.w * toScale,
                from.x * fromScale + to.x * toScale,
                from.y * fromScale + to.y * toScale,
                from.z * fromScale + to.z * toScale
            ).normalized()
        }

        fun toRotationVector(): Triple<Float, Float, Float> {
            val q = normalized().canonicalized()
            val vectorMagnitude = sqrt(q.x * q.x + q.y * q.y + q.z * q.z)
            if (vectorMagnitude < EPSILON) return Triple(0f, 0f, 0f)
            val angle = 2.0 * atan2(vectorMagnitude, q.w)
            val scale = angle / vectorMagnitude
            return Triple(
                (q.x * scale).toFloat(),
                (q.y * scale).toFloat(),
                (q.z * scale).toFloat()
            )
        }

        fun yaw(): Double = atan2(
            2.0 * (w * z + x * y),
            1.0 - 2.0 * (y * y + z * z)
        )

        companion object {
            val IDENTITY = Quaternion(1.0, 0.0, 0.0, 0.0)

            fun yaw(radians: Double): Quaternion {
                val half = radians / 2.0
                return Quaternion(cos(half), 0.0, 0.0, sin(half))
            }
        }
    }

    private var streamStartedAtNanos: Long? = null
    private var previousInputOrientation: Quaternion? = null
    private var previousInputTimestampNanos: Long? = null

    private var referenceOrientation: Quaternion? = null
    private var previousRawRelative: Quaternion? = null
    private var filteredRelative: Quaternion? = null
    private var previousOutput: Quaternion? = null
    private var previousPoseTimestampNanos: Long? = null
    private var previousFilteredYawRadians: Double? = null
    private var smoothedYawSpeedRadiansPerSecond = 0.0

    private var anchorYawRadians = 0.0
    private var heldOffCentreSinceNanos: Long? = null
    private var recenterState = SpatialRecenterState.LOCKED
    private var discontinuityCounter = 0

    fun processRawComponents(
        xRaw: Int,
        yRaw: Int,
        zRaw: Int,
        timestampNanos: Long = System.nanoTime()
    ): SpatialPoseSample? {
        val x = xRaw / QUATERNION_SCALE
        val y = yRaw / QUATERNION_SCALE
        val z = zRaw / QUATERNION_SCALE
        val w = sqrt(max(0.0, 1.0 - x * x - y * y - z * z))
        return processQuaternion(w, x, y, z, timestampNanos)
    }

    internal fun processQuaternion(
        w: Double,
        x: Double,
        y: Double,
        z: Double,
        timestampNanos: Long
    ): SpatialPoseSample? {
        val orientation = Quaternion(w, x, y, z).normalized().canonicalized()
        if (referenceOrientation == null) {
            updateCalibration(orientation, timestampNanos)
            return null
        }

        // Preserve the device-validated AirPods-to-Android axis and direction mapping.
        val previousTimestamp = previousPoseTimestampNanos
        val elapsedSeconds = previousTimestamp?.let {
            (timestampNanos - it).coerceAtLeast(0L) / NANOS_PER_SECOND
        }
        val validDelta = elapsedSeconds != null &&
            elapsedSeconds in MIN_SAMPLE_SECONDS..MAX_FILTER_SAMPLE_SECONDS

        val inputElapsedSeconds = previousInputTimestampNanos?.let {
            (timestampNanos - it).coerceAtLeast(0L) / NANOS_PER_SECOND
        }
        val impossibleInputJump = previousInputOrientation != null &&
            inputElapsedSeconds != null &&
            inputElapsedSeconds in MIN_SAMPLE_SECONDS..MAX_FILTER_SAMPLE_SECONDS &&
            previousInputOrientation!!.angleTo(orientation) >= INPUT_FRAME_JUMP_RADIANS

        var referenceRebased = false
        if (impossibleInputJump) {
            // AirPods omits quaternion W. If its internal reference crosses the omitted-W
            // branch or resets, the reconstructed orientation can jump by over 60 degrees
            // in a single packet. Move our reference by exactly that discontinuity so the
            // rendered stage does not jump with it.
            val continuousRelative = previousRawRelative ?: filteredRelative ?: Quaternion.IDENTITY
            referenceOrientation = (continuousRelative * orientation).normalized().canonicalized()
            discontinuityCounter = (discontinuityCounter + 1) and 0xFF
            heldOffCentreSinceNanos = null
            recenterState = SpatialRecenterState.LOCKED
            referenceRebased = true
        }

        val rawRelative = (orientation * referenceOrientation!!.conjugate())
            .conjugate()
            .normalized()
            .canonicalized()

        if (elapsedSeconds != null && elapsedSeconds > STREAM_DISCONTINUITY_SECONDS) {
            discontinuityCounter = (discontinuityCounter + 1) and 0xFF
            filteredRelative = rawRelative
            previousOutput = null
            previousFilteredYawRadians = null
            smoothedYawSpeedRadiansPerSecond = 0.0
            heldOffCentreSinceNanos = null
            recenterState = SpatialRecenterState.LOCKED
        }

        val usableDelta = validDelta && !referenceRebased
        val rawAngularSpeed = if (usableDelta && previousRawRelative != null) {
            previousRawRelative!!.angleTo(rawRelative) / elapsedSeconds
        } else {
            0.0
        }

        val filtered = if (usableDelta && filteredRelative != null) {
            val response = (rawAngularSpeed / FAST_MOTION_SPEED_RADIANS).coerceIn(0.0, 1.0)
            val timeConstant = STATIONARY_FILTER_SECONDS +
                (FAST_FILTER_SECONDS - STATIONARY_FILTER_SECONDS) * response
            val alpha = 1.0 - exp(-elapsedSeconds / timeConstant)
            filteredRelative!!.slerp(rawRelative, alpha)
        } else {
            rawRelative
        }

        val filteredYaw = filtered.yaw()
        val instantaneousYawSpeed = if (usableDelta && previousFilteredYawRadians != null) {
            abs(shortestAngle(filteredYaw - previousFilteredYawRadians!!)) / elapsedSeconds
        } else {
            0.0
        }
        val speedFilterAlpha = if (usableDelta) {
            1.0 - exp(-elapsedSeconds / RECENTER_SPEED_FILTER_SECONDS)
        } else {
            1.0
        }
        smoothedYawSpeedRadiansPerSecond += speedFilterAlpha *
            (instantaneousYawSpeed - smoothedYawSpeedRadiansPerSecond)

        val dt = elapsedSeconds?.coerceIn(0.0, MAX_RECENTER_STEP_SECONDS) ?: 0.0
        updateRecentering(filtered, smoothedYawSpeedRadiansPerSecond, timestampNanos, dt)
        val output = (Quaternion.yaw(-anchorYawRadians) * filtered)
            .normalized()
            .canonicalized()

        val (rx, ry, rz) = output.toRotationVector()
        val velocity = if (usableDelta && previousOutput != null) {
            val delta = (previousOutput!!.conjugate() * output).normalized().canonicalized()
            val (vx, vy, vz) = delta.toRotationVector()
            Triple(
                (vx.toDouble() / elapsedSeconds)
                    .coerceIn(-MAX_ANGULAR_SPEED, MAX_ANGULAR_SPEED).toFloat(),
                (vy.toDouble() / elapsedSeconds)
                    .coerceIn(-MAX_ANGULAR_SPEED, MAX_ANGULAR_SPEED).toFloat(),
                (vz.toDouble() / elapsedSeconds)
                    .coerceIn(-MAX_ANGULAR_SPEED, MAX_ANGULAR_SPEED).toFloat()
            )
        } else {
            Triple(0f, 0f, 0f)
        }

        previousRawRelative = rawRelative
        filteredRelative = filtered
        previousOutput = output
        previousPoseTimestampNanos = timestampNanos
        previousFilteredYawRadians = filteredYaw
        previousInputOrientation = orientation
        previousInputTimestampNanos = timestampNanos

        return SpatialPoseSample(
            pose = HeadPose(
                rx = rx,
                ry = ry,
                rz = rz,
                vx = velocity.first,
                vy = velocity.second,
                vz = velocity.third,
                discontinuityCounter = discontinuityCounter
            ),
            diagnostics = SpatialPoseDiagnostics(
                rawYawDegrees = Math.toDegrees(rawRelative.yaw()).toFloat(),
                outputYawDegrees = Math.toDegrees(output.yaw()).toFloat(),
                angularSpeedDegreesPerSecond =
                    Math.toDegrees(smoothedYawSpeedRadiansPerSecond).toFloat(),
                anchorYawDegrees = Math.toDegrees(anchorYawRadians).toFloat(),
                recenterState = recenterState
            )
        )
    }

    private fun updateCalibration(orientation: Quaternion, timestampNanos: Long) {
        val streamStart = streamStartedAtNanos
        if (streamStart == null) {
            streamStartedAtNanos = timestampNanos
        } else if (timestampNanos - streamStart >= STARTUP_SETTLE_NANOS) {
            // Anchor to the current pose, not an average of poses collected while the
            // listener may still be putting the earbuds on or starting playback.
            finishCalibration(orientation)
        }
        previousInputOrientation = orientation
        previousInputTimestampNanos = timestampNanos
    }

    private fun finishCalibration(reference: Quaternion) {
        referenceOrientation = reference.normalized().canonicalized()
        previousRawRelative = null
        filteredRelative = null
        previousOutput = null
        previousPoseTimestampNanos = null
        anchorYawRadians = 0.0
        heldOffCentreSinceNanos = null
        recenterState = SpatialRecenterState.LOCKED
    }

    private fun updateRecentering(
        filtered: Quaternion,
        angularSpeed: Double,
        timestampNanos: Long,
        elapsedSeconds: Double
    ) {
        val outputYaw = shortestAngle(filtered.yaw() - anchorYawRadians)
        val trigger = if (abs(anchorYawRadians) >= RECENTERED_ANCHOR_RADIANS) {
            RECENTER_RETURN_TRIGGER_RADIANS
        } else {
            RECENTER_TRIGGER_RADIANS
        }
        val offCentre = abs(outputYaw) >= trigger
        val still = angularSpeed <= RECENTER_HOLD_MAX_SPEED_RADIANS

        if (angularSpeed >= RECENTER_CANCEL_SPEED_RADIANS) {
            heldOffCentreSinceNanos = null
            recenterState = SpatialRecenterState.LOCKED
            return
        }

        when (recenterState) {
            SpatialRecenterState.LOCKED -> {
                if (offCentre && still) {
                    heldOffCentreSinceNanos = timestampNanos
                    recenterState = SpatialRecenterState.WAITING
                }
            }

            SpatialRecenterState.WAITING -> {
                if (!offCentre || !still) {
                    heldOffCentreSinceNanos = null
                    recenterState = SpatialRecenterState.LOCKED
                } else if (timestampNanos - (heldOffCentreSinceNanos ?: timestampNanos) >=
                    RECENTER_HOLD_NANOS
                ) {
                    recenterState = SpatialRecenterState.RECENTERING
                }
            }

            SpatialRecenterState.RECENTERING -> {
                if (!still) {
                    heldOffCentreSinceNanos = null
                    recenterState = SpatialRecenterState.LOCKED
                    return
                }
                val delta = shortestAngle(filtered.yaw() - anchorYawRadians)
                val exponentialStep = delta * (1.0 - exp(-elapsedSeconds / RECENTER_TIME_CONSTANT_SECONDS))
                val maxStep = RECENTER_MAX_RATE_RADIANS_PER_SECOND * elapsedSeconds
                anchorYawRadians = wrapAngle(
                    anchorYawRadians + exponentialStep.coerceIn(-maxStep, maxStep)
                )
                if (abs(shortestAngle(filtered.yaw() - anchorYawRadians)) <=
                    RECENTER_COMPLETE_RADIANS
                ) {
                    heldOffCentreSinceNanos = null
                    recenterState = SpatialRecenterState.LOCKED
                }
            }
        }
    }

    fun reset() {
        streamStartedAtNanos = null
        previousInputOrientation = null
        previousInputTimestampNanos = null
        referenceOrientation = null
        previousRawRelative = null
        filteredRelative = null
        previousOutput = null
        previousPoseTimestampNanos = null
        previousFilteredYawRadians = null
        smoothedYawSpeedRadiansPerSecond = 0.0
        anchorYawRadians = 0.0
        heldOffCentreSinceNanos = null
        recenterState = SpatialRecenterState.LOCKED
        discontinuityCounter = (discontinuityCounter + 1) and 0xFF
    }

    private fun shortestAngle(angle: Double): Double = wrapAngle(angle)

    private fun wrapAngle(angle: Double): Double {
        var result = angle
        while (result > PI) result -= 2.0 * PI
        while (result < -PI) result += 2.0 * PI
        return result
    }

    private companion object {
        const val EPSILON = 1e-9
        const val QUATERNION_SCALE = 32767.0
        const val NANOS_PER_SECOND = 1_000_000_000.0

        const val MIN_SAMPLE_SECONDS = 0.002
        const val MAX_FILTER_SAMPLE_SECONDS = 0.120
        const val STREAM_DISCONTINUITY_SECONDS = 0.250
        const val MAX_RECENTER_STEP_SECONDS = 0.050
        const val MAX_ANGULAR_SPEED = 32.0

        const val STARTUP_SETTLE_NANOS = 500_000_000L
        val INPUT_FRAME_JUMP_RADIANS = Math.toRadians(65.0)

        const val STATIONARY_FILTER_SECONDS = 0.030
        const val FAST_FILTER_SECONDS = 0.006
        val FAST_MOTION_SPEED_RADIANS = Math.toRadians(160.0)

        val RECENTER_TRIGGER_RADIANS = Math.toRadians(27.0)
        val RECENTERED_ANCHOR_RADIANS = Math.toRadians(8.0)
        val RECENTER_RETURN_TRIGGER_RADIANS = Math.toRadians(8.0)
        val RECENTER_COMPLETE_RADIANS = Math.toRadians(2.0)
        const val RECENTER_SPEED_FILTER_SECONDS = 0.250
        val RECENTER_HOLD_MAX_SPEED_RADIANS = Math.toRadians(12.0)
        val RECENTER_CANCEL_SPEED_RADIANS = Math.toRadians(30.0)
        const val RECENTER_HOLD_NANOS = 2_500_000_000L
        const val RECENTER_TIME_CONSTANT_SECONDS = 5.5
        val RECENTER_MAX_RATE_RADIANS_PER_SECOND = Math.toRadians(7.0)
    }
}
