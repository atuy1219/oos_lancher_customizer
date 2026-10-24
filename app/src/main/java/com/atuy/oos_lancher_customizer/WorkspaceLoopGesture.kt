package com.atuy.oos_lancher_customizer

import kotlin.math.abs

internal object WorkspaceLoopGesture {
    fun shouldWrap(
        toFirstPage: Boolean,
        velocity: Int,
        delta: Float,
        pageSize: Int,
        significant: Boolean,
        fling: Boolean
    ): Boolean {
        // Match OplusPagedViewImpl.shouldReturnToOriginalPageWhenDragged.
        if (fling && abs(delta) > pageSize * 0.25f &&
            Math.signum(velocity.toFloat()) != Math.signum(delta)) return false
        val outwardDelta = if (toFirstPage) delta < 0 else delta > 0
        val outwardVelocity = if (toFirstPage) velocity < 0 else velocity > 0
        return (fling && outwardVelocity) || (!fling && significant && outwardDelta)
    }
}
