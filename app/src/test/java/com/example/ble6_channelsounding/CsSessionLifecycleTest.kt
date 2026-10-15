package com.example.ble6_channelsounding

import org.junit.Assert.*
import org.junit.Test

class CsSessionLifecycleTest {
    @Test fun restartWaitsForClosedNotMerelyStopped() {
        val state = CsSessionLifecycle()
        state.open = true
        var starts = 0
        assertEquals(CsSessionLifecycle.CloseAction.STOP, state.requestClose { starts++ })
        assertTrue(state.stopped())
        assertEquals(0, starts)
        assertTrue(state.closing)
        state.finish()?.invoke()
        assertEquals(1, starts)
        assertFalse(state.closing)
        assertNull(state.finish())
    }
    @Test fun spontaneousStopDoesNotRequestCloseOrRestart() {
        val state = CsSessionLifecycle()
        state.open = true
        assertFalse(state.stopped())
        assertFalse(state.closing)
        assertNull(state.restart)
        assertEquals(CsSessionLifecycle.CloseAction.CLOSE, state.requestClose {})
    }
    @Test fun userStopCancelsPendingRestartWithoutDuplicateClose() {
        val state = CsSessionLifecycle()
        var starts = 0
        assertEquals(CsSessionLifecycle.CloseAction.CLOSE, state.requestClose { starts++ })
        assertEquals(CsSessionLifecycle.CloseAction.NONE, state.requestClose(null))
        state.finish()?.invoke()
        assertEquals(0, starts)
    }
}
