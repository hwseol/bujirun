package com.mhmh2.bujirun.tracking

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The bridge between [WorkoutService] (which owns the tracker and keeps running with the screen
 * off) and the UI (which may be destroyed and recreated at any time). Lives for the process.
 */
object WorkoutSession {
    private val _state = MutableStateFlow(WorkoutSnapshot())
    val state: StateFlow<WorkoutSnapshot> = _state.asStateFlow()

    internal fun publish(snapshot: WorkoutSnapshot) {
        _state.value = snapshot
    }

    /** Back to the start screen after looking at a finished workout. */
    fun reset() {
        if (_state.value.status == WorkoutStatus.FINISHED) _state.value = WorkoutSnapshot()
    }
}
