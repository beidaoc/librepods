package me.kavishdevar.librepods.utils

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LogCollectorFilterTest {
    private val filteredSystemUids = setOf("1000", "1041")

    @Test
    fun keepsLibrePodsAndBluetoothLinesWithoutTagFiltering() {
        assertTrue(keep(line(uid = "10402", tag = "AirPodsService")))
        assertTrue(keep(line(uid = "1002", tag = "BluetoothMcpService")))
    }

    @Test
    fun keepsRelevantSystemAudioAndSpatializerTags() {
        assertTrue(keep(line(uid = "1000", tag = "AS.AudioDeviceBroker")))
        assertTrue(keep(line(uid = "1000", tag = "MediaFocusControl")))
        assertTrue(keep(line(uid = "1041", tag = "AudioFlinger")))
        assertTrue(keep(line(uid = "1000", tag = "SpatializerPoseController")))
    }

    @Test
    fun dropsNoisySystemAndAudioserverTags() {
        assertFalse(keep(line(uid = "1000", tag = "SurfaceFlinger")))
        assertFalse(keep(line(uid = "1000", tag = "MiSensorServiceImpl")))
        assertFalse(keep(line(uid = "1041", priority = "D", tag = "PAL")))
    }

    @Test
    fun keepsOnlyPalWarningsAndErrors() {
        assertTrue(keep(line(uid = "1041", priority = "W", tag = "PAL")))
        assertTrue(keep(line(uid = "1041", priority = "E", tag = "PAL")))
        assertFalse(keep(line(uid = "1041", priority = "I", tag = "PAL")))
    }

    @Test
    fun keepsOnlyRelevantActivityManagerLifecycleEvents() {
        assertTrue(
            keep(
                line(
                    uid = "1000",
                    tag = "ActivityManager",
                    message = "Process me.kavishdevar.librepods has died"
                )
            )
        )
        assertFalse(
            keep(
                line(
                    uid = "1000",
                    tag = "ActivityManager",
                    message = "Process org.example.unrelated has died"
                )
            )
        )
    }

    @Test
    fun keepsLogcatSectionHeadersAndDiagnostics() {
        assertTrue(keep("--------- beginning of main"))
        assertTrue(keep("logcat: unexpected diagnostic output"))
    }

    private fun keep(value: String): Boolean =
        LogCollector.shouldKeepLogLine(value, filteredSystemUids)

    private fun line(
        uid: String,
        priority: String = "D",
        tag: String,
        message: String = "message"
    ): String = "08-20 14:14:44.220 $uid 2692 5223 $priority $tag: $message"
}
