package me.kavishdevar.librepods.utils

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpatialHeadPoseProcessorTest {
    @Test
    fun stationaryCalibrationProducesCenteredPose() {
        val fixture = Fixture()

        val pose = fixture.finishCalibration()

        assertEquals(0f, pose.pose.rz, 0.001f)
        assertEquals(SpatialRecenterState.LOCKED, pose.diagnostics.recenterState)
    }

    @Test
    fun startupSettlingCentersOnTheLatestPoseInsteadOfTheMotionAverage() {
        val fixture = Fixture()

        repeat(26) { step -> fixture.sampleYawOrNull(step.toDouble()) }
        val firstPose = fixture.sampleYaw(26.0)

        assertTrue(abs(firstPose.diagnostics.outputYawDegrees) < 2f)
    }

    @Test
    fun aFastTurnReportsMatchingAngularVelocity() {
        val fixture = Fixture()
        fixture.finishCalibration()

        val turned = fixture.sampleYaw(10.0)

        assertTrue("turn should be visible in the pose", turned.diagnostics.outputYawDegrees > 8f)
        assertTrue("turn should report non-zero angular velocity", turned.pose.vz > 0.5f)
    }

    @Test
    fun smallOffsetKeepsTheStageLocked() {
        val fixture = Fixture()
        fixture.finishCalibration()
        fixture.sampleYaw(20.0)

        var last = fixture.sampleYaw(20.0)
        repeat(500) {
            last = fixture.sampleYaw(20.0)
        }

        assertEquals(SpatialRecenterState.LOCKED, last.diagnostics.recenterState)
        assertTrue(abs(last.diagnostics.anchorYawDegrees) < 0.5f)
        assertTrue(last.diagnostics.outputYawDegrees > 18f)
    }

    @Test
    fun heldLargeOffsetRecentersGradually() {
        val fixture = Fixture()
        fixture.finishCalibration()
        repeat(10) { step -> fixture.sampleYaw(4.0 * (step + 1)) }
        repeat(50) { fixture.sampleYaw(40.0) }
        val beforeRecenter = fixture.sampleYaw(40.0)

        var last = beforeRecenter
        repeat(425) {
            last = fixture.sampleYaw(40.0)
        }

        assertTrue(beforeRecenter.diagnostics.outputYawDegrees > 35f)
        assertTrue(last.diagnostics.anchorYawDegrees > 15f)
        assertTrue(last.diagnostics.outputYawDegrees < 25f)
    }

    @Test
    fun rollNoiseDoesNotKeepResettingYawRecentering() {
        val fixture = Fixture()
        fixture.finishCalibration()
        repeat(10) { step -> fixture.sampleYaw(4.0 * (step + 1)) }

        var last = fixture.sampleYawWithRollNoise(40.0, 0.8)
        repeat(500) { step ->
            last = fixture.sampleYawWithRollNoise(40.0, if (step % 2 == 0) -0.8 else 0.8)
        }

        assertTrue(last.diagnostics.anchorYawDegrees > 10f)
        assertTrue(last.diagnostics.outputYawDegrees < 30f)
    }

    @Test
    fun returningToTheOriginalDirectionRestoresAMovedAnchor() {
        val fixture = Fixture()
        fixture.finishCalibration()
        repeat(10) { step -> fixture.sampleYaw(4.0 * (step + 1)) }
        repeat(550) { fixture.sampleYaw(40.0) }

        repeat(10) { step -> fixture.sampleYaw(36.0 - 4.0 * step) }
        var last = fixture.sampleYaw(0.0)
        repeat(900) {
            last = fixture.sampleYaw(0.0)
        }

        assertTrue(abs(last.diagnostics.anchorYawDegrees) < 3f)
        assertTrue(abs(last.diagnostics.outputYawDegrees) < 3f)
    }

    @Test
    fun impossibleSinglePacketJumpKeepsTheStageContinuous() {
        val fixture = Fixture()
        fixture.finishCalibration()
        val beforeJump = fixture.sampleYaw(10.0)

        val afterJump = fixture.sampleYaw(100.0)

        assertEquals(beforeJump.diagnostics.outputYawDegrees, afterJump.diagnostics.outputYawDegrees, 1f)
        assertEquals(
            (beforeJump.pose.discontinuityCounter + 1) and 0xFF,
            afterJump.pose.discontinuityCounter
        )
        assertEquals(0f, afterJump.pose.vz, 0.001f)
    }

    @Test
    fun aLongPacketGapMarksAReferenceDiscontinuity() {
        val fixture = Fixture()
        val initial = fixture.finishCalibration()

        fixture.advanceNanos(300_000_000L)
        val afterGap = fixture.sampleYaw(0.0)

        assertEquals(
            (initial.pose.discontinuityCounter + 1) and 0xFF,
            afterGap.pose.discontinuityCounter
        )
        assertEquals(0f, afterGap.pose.vz, 0.001f)
    }

    private class Fixture {
        private val processor = SpatialHeadPoseProcessor()
        private var timestampNanos = 0L

        fun finishCalibration(): SpatialPoseSample {
            repeat(32) {
                sampleYawOrNull(0.0)
            }
            return sampleYaw(0.0)
        }

        fun sampleYaw(degrees: Double): SpatialPoseSample =
            requireNotNull(sampleYawOrNull(degrees))

        fun advanceNanos(nanos: Long) {
            timestampNanos += nanos
        }

        fun sampleYawWithRollNoise(yawDegrees: Double, rollDegrees: Double): SpatialPoseSample {
            val yaw = Math.toRadians(-yawDegrees)
            val roll = Math.toRadians(rollDegrees)
            val yawHalf = yaw / 2.0
            val rollHalf = roll / 2.0
            val result = processor.processQuaternion(
                w = cos(yawHalf) * cos(rollHalf),
                x = cos(yawHalf) * sin(rollHalf),
                y = sin(yawHalf) * sin(rollHalf),
                z = sin(yawHalf) * cos(rollHalf),
                timestampNanos = timestampNanos
            )
            timestampNanos += SAMPLE_INTERVAL_NANOS
            return requireNotNull(result)
        }

        fun sampleYawOrNull(degrees: Double): SpatialPoseSample? {
            val inputYaw = Math.toRadians(-degrees)
            val half = inputYaw / 2.0
            val result = processor.processQuaternion(
                w = cos(half),
                x = 0.0,
                y = 0.0,
                z = sin(half),
                timestampNanos = timestampNanos
            )
            timestampNanos += SAMPLE_INTERVAL_NANOS
            return result
        }
    }

    private companion object {
        const val SAMPLE_INTERVAL_NANOS = 20_000_000L
    }
}
