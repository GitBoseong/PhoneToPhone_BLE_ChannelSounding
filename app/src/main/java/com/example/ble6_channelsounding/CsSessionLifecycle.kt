package com.example.ble6_channelsounding

/** A replacement may run only after an actual terminal callback. */
class CsSessionLifecycle {
    enum class CloseAction { STOP, CLOSE, NONE }
    var open = false
    var closing = false
    var restart: (() -> Unit)? = null

    fun requestClose(afterClosed: (() -> Unit)?): CloseAction {
        restart = afterClosed // A user stop (null) cancels a queued restart.
        if (closing) return CloseAction.NONE
        closing = true
        return if (open) CloseAction.STOP else CloseAction.CLOSE
    }

    fun stopped(): Boolean {
        open = false
        return closing
    }

    fun finish(): (() -> Unit)? {
        val next = restart
        reset()
        return next
    }

    fun reset() { open = false; closing = false; restart = null }
}
