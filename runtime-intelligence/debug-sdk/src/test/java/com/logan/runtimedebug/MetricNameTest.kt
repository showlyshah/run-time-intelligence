package com.logan.runtimedebug

import org.junit.Assert.assertThrows
import org.junit.Test

class MetricNameTest {
    @Test fun rejectsFreeFormMetricNames() {
        val probe = AuthorizedDebugProbe()
        assertThrows(IllegalArgumentException::class.java) {
            probe.recordMetric("Authorization: bearer secret", 1)
        }
    }
}
