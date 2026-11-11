package com.mapconductor.core.circle

interface CircleEntityInterface<ActualCircle> {
    var circle: ActualCircle
    val state: CircleState
    val fingerPrint: CircleFingerPrint
}

class CircleEntity<ActualCircle>(
    override var circle: ActualCircle,
    override val state: CircleState,
) : CircleEntityInterface<ActualCircle> {
    private val applied = state.captureValues()

    // Legacy provider API: create a fingerprint only when a native renderer
    // requests it. No fingerprint object is retained per circle.
    override val fingerPrint: CircleFingerPrint get() = state.snapshotFingerPrint(applied)

    internal fun changeMask(next: CircleState): Int {
        return next.changeMaskSince(applied, next === state)
    }
}
