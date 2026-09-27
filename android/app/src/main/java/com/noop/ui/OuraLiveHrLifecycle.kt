package com.noop.ui

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner

/** Follows the Activity's current resumed state, including registration after a cold-launch resume.
 * Lifecycle replays the current state to a new observer; Application callbacks only report future events. */
internal class OuraLiveHrLifecycle(
    private val lifecycle: Lifecycle,
    private val onResumedChanged: (Boolean) -> Unit,
) : DefaultLifecycleObserver, AutoCloseable {
    init {
        lifecycle.addObserver(this)
    }

    override fun onResume(owner: LifecycleOwner) = onResumedChanged(true)

    override fun onPause(owner: LifecycleOwner) = onResumedChanged(false)

    override fun close() {
        lifecycle.removeObserver(this)
        onResumedChanged(false)
    }
}
