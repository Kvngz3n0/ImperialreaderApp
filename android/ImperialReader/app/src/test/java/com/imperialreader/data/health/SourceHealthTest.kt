package com.imperialreader.data.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SourceHealthTest {
    @Test
    fun failureKeepsLastSuccessAndRecoveryRetainsFailureCount() {
        val success = healthAfterCheck(HealthRecord(), 100, null)
        val failure = healthAfterCheck(success, 200, "HTTP 503")
        assertEquals(HealthStatus.UNAVAILABLE, failure.status)
        assertEquals(100L, failure.lastSuccess)
        assertEquals(1, failure.failures)
        val recovered = healthAfterCheck(failure, 300, null)
        assertEquals(HealthStatus.WORKING, recovered.status)
        assertEquals(300L, recovered.lastSuccess)
        assertEquals(1, recovered.failures)
        assertNull(recovered.lastError)
    }
}
