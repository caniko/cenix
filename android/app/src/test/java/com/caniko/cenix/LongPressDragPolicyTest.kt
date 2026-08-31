package com.caniko.cenix

import org.junit.Assert.assertEquals
import org.junit.Test

class LongPressDragPolicyTest {
    @Test
    fun stationaryLongPressOpensAndMovementStartsDrag() {
        val policy = LongPressDragPolicy(8f)
        policy.down(10f, 10f)
        assertEquals(LongPressDragPolicy.State.POPUP_OPEN, policy.longPress())
        assertEquals(LongPressDragPolicy.State.POPUP_OPEN, policy.move(14f, 14f))
        assertEquals(LongPressDragPolicy.State.DRAG_STARTED, policy.move(30f, 10f))
        policy.finish()
        assertEquals(LongPressDragPolicy.State.IDLE, policy.state)
    }

    @Test
    fun cancellationNeverStartsDrag() {
        val policy = LongPressDragPolicy(8f)
        policy.down(0f, 0f)
        policy.cancel()
        assertEquals(LongPressDragPolicy.State.CANCELLED, policy.move(20f, 0f))
    }
}
