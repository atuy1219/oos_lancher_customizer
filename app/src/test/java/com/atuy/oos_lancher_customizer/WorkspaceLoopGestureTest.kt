package com.atuy.oos_lancher_customizer

import org.junit.Assert.*
import org.junit.Test

class WorkspaceLoopGestureTest {
    @Test fun shortSlowEdgeDragsReturnToTheirStartingPage() {
        assertFalse(WorkspaceLoopGesture.shouldWrap(true, 0, -10f, 1000, false, false))
        assertFalse(WorkspaceLoopGesture.shouldWrap(false, 0, 10f, 1000, false, false))
    }

    @Test fun outwardDragsAndFlingsWrapInBothDirections() {
        assertTrue(WorkspaceLoopGesture.shouldWrap(true, 0, -400f, 1000, true, false))
        assertTrue(WorkspaceLoopGesture.shouldWrap(false, 0, 400f, 1000, true, false))
        assertTrue(WorkspaceLoopGesture.shouldWrap(true, -2000, -50f, 1000, false, true))
        assertTrue(WorkspaceLoopGesture.shouldWrap(false, 2000, 50f, 1000, false, true))
    }

    @Test fun reversingTheFlingReturnsToTheStartingPage() {
        assertFalse(WorkspaceLoopGesture.shouldWrap(true, 2000, -400f, 1000, true, true))
        assertFalse(WorkspaceLoopGesture.shouldWrap(false, -2000, 400f, 1000, true, true))
        assertFalse(WorkspaceLoopGesture.shouldWrap(true, 0, 400f, 1000, true, false))
        assertFalse(WorkspaceLoopGesture.shouldWrap(false, 0, -400f, 1000, true, false))
    }
}
