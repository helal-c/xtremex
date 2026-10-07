package com.xtremex.tv.auth

import org.junit.Assert.*
import org.junit.Test

class AccessControllerTest {
    @Test fun pendingCannotPlayUntilApproval() {
        val access = AccessLease()
        access.serverStatus("pending", 100)
        assertFalse(access.canPlay(100))
        access.serverStatus("approved", 200)
        assertTrue(access.canPlay(200))
    }
    @Test fun offlineAccessExpiresWithoutExtendingTheLastLease() {
        val access = AccessLease()
        access.serverStatus("approved", 1000)
        access.networkFailure(2000)
        assertTrue(access.canPlay(300999))
        access.networkFailure(301000)
        assertFalse(access.canPlay(301000))
    }
    @Test fun blockAndWrongDeviceRevokeImmediately() {
        for (status in listOf("blocked", "wrong_device", "unregistered", "session_expired")) {
            val access = AccessLease()
            access.serverStatus("approved", 10)
            access.serverStatus(status, 20)
            assertFalse(access.canPlay(20))
        }
    }
    @Test fun backgroundAndColdStartRequireANewOnlineCheck() {
        val access = AccessLease()
        access.serverStatus("approved", 100)
        access.invalidate()
        assertFalse(access.canPlay(200))
        assertFalse(AccessLease().canPlay(200))
    }
    @Test fun reconnectRenewsOnlyAfterValidApproval() {
        val access = AccessLease()
        access.serverStatus("approved", 0)
        assertFalse(access.canPlay(300000))
        access.serverStatus("approved", 400000)
        assertTrue(access.canPlay(400001))
    }
}
