package com.juhao.murexide.ui.components

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageViewerLaunchGateTest {
    @Test
    fun `a second viewer launch is rejected until the first one exits`() {
        val gate = ImageViewerLaunchGate()
        val activity = Any()
        val firstLaunch = Any()

        assertTrue(gate.tryAcquire(activity, firstLaunch))
        assertFalse(gate.tryAcquire(activity, Any()))

        gate.release(activity, firstLaunch)

        assertTrue(gate.tryAcquire(activity, Any()))
    }

    @Test
    fun `a stale exit callback cannot release a newer viewer launch`() {
        val gate = ImageViewerLaunchGate()
        val activity = Any()
        val firstLaunch = Any()
        val secondLaunch = Any()

        assertTrue(gate.tryAcquire(activity, firstLaunch))
        gate.release(activity, firstLaunch)
        assertTrue(gate.tryAcquire(activity, secondLaunch))

        gate.release(activity, firstLaunch)

        assertFalse(gate.tryAcquire(activity, Any()))
    }
}
