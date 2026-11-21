package com.nexa.mobilebridge.core.power

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BridgePowerLockManagerTest {

    @Test
    fun startsIdleWithoutHoldingLocks() {
        var acquireCalls = 0
        var releaseCalls = 0
        val manager = BridgePowerLockManager(
            acquirePlatformLocks = { acquireCalls++ },
            releasePlatformLocks = { releaseCalls++ },
            onLog = {}
        )

        assertFalse(manager.isLocked.value)
        assertEquals(0, manager.activeRefCount)
        assertEquals(0, acquireCalls)
        assertEquals(0, releaseCalls)
    }

    @Test
    fun withActiveTransferLockAcquiresAndReleasesCleanly() = runTest {
        var acquireCalls = 0
        var releaseCalls = 0
        val manager = BridgePowerLockManager(
            acquirePlatformLocks = { acquireCalls++ },
            releasePlatformLocks = { releaseCalls++ },
            onLog = {}
        )

        val result = manager.withActiveTransferLock("cmd:GET_BATTERY") {
            assertTrue(manager.isLocked.value)
            assertEquals(1, manager.activeRefCount)
            "ok"
        }

        assertEquals("ok", result)
        assertFalse(manager.isLocked.value)
        assertEquals(0, manager.activeRefCount)
        assertEquals(1, acquireCalls)
        assertEquals(1, releaseCalls)
    }

    @Test
    fun withActiveTransferLockReleasesEvenWhenBlockThrows() = runTest {
        var releaseCalls = 0
        val manager = BridgePowerLockManager(
            acquirePlatformLocks = {},
            releasePlatformLocks = { releaseCalls++ },
            onLog = {}
        )

        var caught = false
        try {
            manager.withActiveTransferLock("cmd:FAIL") {
                assertTrue(manager.isLocked.value)
                throw IllegalStateException("boom")
            }
        } catch (_: IllegalStateException) {
            caught = true
        }

        assertTrue(caught)
        assertFalse(manager.isLocked.value)
        assertEquals(0, manager.activeRefCount)
        assertEquals(1, releaseCalls)
    }

    @Test
    fun refCountingHoldsPlatformLockUntilLastCallerReleases() {
        var acquireCalls = 0
        var releaseCalls = 0
        val manager = BridgePowerLockManager(
            acquirePlatformLocks = { acquireCalls++ },
            releasePlatformLocks = { releaseCalls++ },
            onLog = {},
            nowElapsedMs = { 10_000L }
        )

        manager.acquire("op1")
        manager.acquire("op2")
        assertTrue(manager.isLocked.value)
        assertEquals(2, manager.activeRefCount)
        assertEquals(1, acquireCalls)

        manager.release("op1")
        assertTrue(manager.isLocked.value)
        assertEquals(1, manager.activeRefCount)
        assertEquals(0, releaseCalls)

        manager.release("op2")
        assertFalse(manager.isLocked.value)
        assertEquals(0, manager.activeRefCount)
        assertEquals(1, releaseCalls)
    }

    @Test
    fun setCallLockIsIdempotent() {
        var acquireCalls = 0
        var releaseCalls = 0
        val manager = BridgePowerLockManager(
            acquirePlatformLocks = { acquireCalls++ },
            releasePlatformLocks = { releaseCalls++ },
            onLog = {}
        )

        manager.setCallLock(true)
        manager.setCallLock(true) // duplicate should not increment refs
        assertEquals(1, manager.activeRefCount)
        assertEquals(1, acquireCalls)

        manager.setCallLock(false)
        manager.setCallLock(false) // duplicate should not underflow
        assertFalse(manager.isLocked.value)
        assertEquals(0, manager.activeRefCount)
        assertEquals(1, releaseCalls)
    }
}
