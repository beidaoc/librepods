package me.kavishdevar.librepods.utils

import org.junit.Assert.*
import org.junit.Test

class RestartEvidenceTest {
    private val pkg = "me.kavishdevar.librepods"

    @Test fun keepsCallerAndComponentWithExplicitSystemStart() {
        val line = "10-04 00:57:15.772 1000 4336 4916 I ActivityManager: Start proc 9043:$pkg/u0a505 for bound-service {$pkg/.services.NotificationAnnouncementService} caller=android"
        val evidence = RestartEvidence.classify(line, pkg)!!
        assertTrue(evidence.startsWith("SYSTEM_BOUND_SERVICE_START"))
        assertTrue(evidence.endsWith(line))
    }

    @Test fun distinguishesForceStopFromStickyServiceRestartAndActivityLaunch() {
        assertTrue(RestartEvidence.classify("ActivityManager: Force stopping $pkg from process:com.miui.securitycenter", pkg)!!.startsWith("SYSTEM_FORCE_STOP"))
        assertTrue(RestartEvidence.classify("ActivityManager: Scheduling restart of crashed service $pkg/.services.AirPodsService in 1000ms", pkg)!!.startsWith("SYSTEM_SERVICE_RESTART_SCHEDULED"))
        assertTrue(RestartEvidence.classify("ActivityTaskManager: START u0 cmp=$pkg/.MainActivity callingPackage=com.miui.home", pkg)!!.startsWith("SYSTEM_ACTIVITY_START"))
    }

    @Test fun doesNotInventCauseFromPidOrUnrelatedProcess() {
        assertNull(RestartEvidence.classify("$pkg pid=1234", pkg))
        assertNull(RestartEvidence.classify("ActivityManager: Start proc 10:org.other for service", pkg))
        assertNull(RestartEvidence.classify("ActivityManager: Start proc 10:$pkg.other for service", pkg))
        assertNull(RestartEvidence.classify("NotificationListeners: $pkg Service not registered", pkg))
    }
}
