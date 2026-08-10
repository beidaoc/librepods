/*
    LibrePods - AirPods liberated from Apple’s ecosystem
    Copyright (C) 2025 LibrePods contributors

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with this program.  If not, see <https://www.gnu.org/licenses/>.
*/

package me.kavishdevar.librepods.utils

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class Orientation(val pitch: Float = 0f, val yaw: Float = 0f)
data class Acceleration(val vertical: Float = 0f, val horizontal: Float = 0f)
data class HeadPose(
    val rx: Float,
    val ry: Float,
    val rz: Float,
    val vx: Float = 0f,
    val vy: Float = 0f,
    val vz: Float = 0f,
    val discontinuityCounter: Int = 0
)

object HeadTracking {
    private val _orientation = MutableStateFlow(Orientation())
    val orientation = _orientation.asStateFlow()

    private val _acceleration = MutableStateFlow(Acceleration())
    val acceleration = _acceleration.asStateFlow()

    private val poseProcessor = SpatialHeadPoseProcessor()
    private var lastDiagnosticsLogNanos = 0L
    private var lastRecenterState = SpatialRecenterState.LOCKED

    fun processPacket(packet: ByteArray): HeadPose? {
        if (packet.size < 55) return null

        val horizontalAccel = bytesToInt(packet[51], packet[52]).toFloat()
        val verticalAccel = bytesToInt(packet[53], packet[54]).toFloat()
        _acceleration.value = Acceleration(verticalAccel, horizontalAccel)

        val sample = poseProcessor.processRawComponents(
            bytesToInt(packet[43], packet[44]),
            bytesToInt(packet[45], packet[46]),
            bytesToInt(packet[47], packet[48]),
            System.nanoTime()
        ) ?: return null

        _orientation.value = Orientation(
            pitch = Math.toDegrees(sample.pose.rx.toDouble()).toFloat(),
            yaw = Math.toDegrees(sample.pose.rz.toDouble()).toFloat()
        )

        val now = System.nanoTime()
        if (sample.diagnostics.recenterState != lastRecenterState ||
            now - lastDiagnosticsLogNanos >= DIAGNOSTICS_INTERVAL_NANOS
        ) {
            Log.i(
                TAG,
                "pose rawYaw=%.1f outputYaw=%.1f speed=%.1f anchor=%.1f state=%s".format(
                    sample.diagnostics.rawYawDegrees,
                    sample.diagnostics.outputYawDegrees,
                    sample.diagnostics.angularSpeedDegreesPerSecond,
                    sample.diagnostics.anchorYawDegrees,
                    sample.diagnostics.recenterState
                )
            )
            lastDiagnosticsLogNanos = now
            lastRecenterState = sample.diagnostics.recenterState
        }
        return sample.pose
    }

    private fun bytesToInt(b1: Byte, b2: Byte): Int {
        return (b2.toInt() shl 8) or (b1.toInt() and 0xFF)
    }

    fun reset() {
        poseProcessor.reset()
        lastDiagnosticsLogNanos = 0L
        lastRecenterState = SpatialRecenterState.LOCKED
        _orientation.value = Orientation()
        _acceleration.value = Acceleration()
    }

    private const val TAG = "SpatialHeadPose"
    private const val DIAGNOSTICS_INTERVAL_NANOS = 2_000_000_000L
}
