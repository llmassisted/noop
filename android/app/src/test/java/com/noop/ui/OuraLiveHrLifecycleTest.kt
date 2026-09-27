package com.noop.ui

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class OuraLiveHrLifecycleTest {
    private class Owner : LifecycleOwner {
        val registry = LifecycleRegistry.createUnsafe(this)
        override val lifecycle: Lifecycle get() = registry
    }

    @Test
    fun `late observer immediately sees the already resumed activity`() {
        val owner = Owner()
        owner.registry.currentState = Lifecycle.State.RESUMED
        val resumed = mutableListOf<Boolean>()

        OuraLiveHrLifecycle(owner.lifecycle, resumed::add).use {
            // No second ON_RESUME is sent: this is the cold-launch first-composition ordering.
            assertEquals(listOf(true), resumed)
        }
        assertEquals(listOf(true, false), resumed)
    }

    @Test
    fun `background and foreground transitions update mounted screen demand`() {
        val owner = Owner()
        owner.registry.currentState = Lifecycle.State.STARTED
        val resumed = mutableListOf<Boolean>()

        OuraLiveHrLifecycle(owner.lifecycle, resumed::add).use {
            assertFalse(resumed.any { it })
            owner.registry.currentState = Lifecycle.State.RESUMED
            owner.registry.currentState = Lifecycle.State.STARTED
            owner.registry.currentState = Lifecycle.State.RESUMED
            owner.registry.currentState = Lifecycle.State.DESTROYED
            assertEquals(listOf(true, false, true, false), resumed)
        }
    }

    @Test
    fun `disposed root releases demand and stops observing the old activity`() {
        val owner = Owner()
        owner.registry.currentState = Lifecycle.State.RESUMED
        val resumed = mutableListOf<Boolean>()
        val observer = OuraLiveHrLifecycle(owner.lifecycle, resumed::add)

        observer.close()
        owner.registry.currentState = Lifecycle.State.STARTED
        owner.registry.currentState = Lifecycle.State.RESUMED

        assertEquals(listOf(true, false), resumed)
    }
}
