package com.caniko.cenix

import kotlin.math.hypot

class LongPressDragPolicy(private val slop: Float) {
    enum class State { IDLE, LONG_PRESS_PENDING, POPUP_OPEN, DRAG_STARTED, CANCELLED }

    var state = State.IDLE
        private set
    private var downX = 0f
    private var downY = 0f

    fun down(x: Float, y: Float) {
        downX = x
        downY = y
        state = State.LONG_PRESS_PENDING
    }

    fun longPress(): State {
        if (state == State.LONG_PRESS_PENDING) state = State.POPUP_OPEN
        return state
    }

    fun move(x: Float, y: Float): State {
        if (state == State.POPUP_OPEN && hypot(x - downX, y - downY) > slop) state = State.DRAG_STARTED
        return state
    }

    fun cancel(): State {
        state = State.CANCELLED
        return state
    }

    fun finish() {
        state = State.IDLE
    }
}
