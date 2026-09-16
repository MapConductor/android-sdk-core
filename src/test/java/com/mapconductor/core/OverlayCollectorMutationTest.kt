package com.mapconductor.core

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

/**
 * In-place changes to a collected state have to reach the update handler.
 *
 * The collector used to find them by reading every state's fingerprint inside a
 * `snapshotFlow`, which cost the size of the collection on every snapshot
 * commit anywhere in the app — 270 to 380 ms a frame during a drag with 144k
 * markers. States now report their own writes. The failure mode of that swap is
 * silent: a field whose setter forgets to report leaves the renderer drawing
 * the old value, with nothing logged and nothing thrown.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OverlayCollectorMutationTest {
    private class Thing(
        override val id: String,
        label: String,
    ) : ComponentState {
        override val mutations = StateMutationSignal()
        var label: String by mutations.notifying(label)
    }

    private val window = 5.milliseconds

    private fun collector(dispatcher: TestDispatcher) =
        OverlayCollector<Thing>(
            updateDebounce = window,
            scope = kotlinx.coroutines.CoroutineScope(dispatcher),
        )

    @Test
    fun writingAFieldReachesTheHandler() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val collector = collector(dispatcher)
            val seen = mutableListOf<String>()
            collector.setUpdateHandler { seen.add(it.id) }

            val thing = Thing("a", "before")
            collector.add(thing)
            advanceUntilIdle()

            thing.label = "after"
            advanceTimeBy(window.inWholeMilliseconds * 2)
            advanceUntilIdle()

            assertEquals(listOf("a"), seen)
        }

    /** Writing the same value is not a change, and the old diff ignored it too. */
    @Test
    fun writingTheSameValueIsNotReported() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val collector = collector(dispatcher)
            val seen = mutableListOf<String>()
            collector.setUpdateHandler { seen.add(it.id) }

            val thing = Thing("a", "same")
            collector.add(thing)
            advanceUntilIdle()

            thing.label = "same"
            advanceTimeBy(window.inWholeMilliseconds * 2)
            advanceUntilIdle()

            assertEquals(emptyList<String>(), seen)
        }

    /**
     * A state written several times inside one window is delivered once, with
     * the latest value — the sampling the update channel has always promised.
     */
    @Test
    fun repeatedWritesInOneWindowAreDeliveredOnce() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val collector = collector(dispatcher)
            val seen = mutableListOf<String>()
            collector.setUpdateHandler { seen.add(it.label) }

            val thing = Thing("a", "0")
            collector.add(thing)
            advanceUntilIdle()

            thing.label = "1"
            thing.label = "2"
            thing.label = "3"
            advanceTimeBy(window.inWholeMilliseconds * 2)
            advanceUntilIdle()

            assertEquals(listOf("3"), seen)
        }

    /** A removed state is nobody's business any more. */
    @Test
    fun aRemovedStateStopsReporting() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val collector = collector(dispatcher)
            val seen = mutableListOf<String>()
            collector.setUpdateHandler { seen.add(it.id) }

            val thing = Thing("a", "before")
            collector.add(thing)
            advanceUntilIdle()
            collector.remove("a")
            advanceUntilIdle()

            thing.label = "after"
            advanceTimeBy(window.inWholeMilliseconds * 2)
            advanceUntilIdle()

            assertEquals(emptyList<String>(), seen)
        }

    /** Two states changing in the same window both arrive. */
    @Test
    fun everyChangedStateIsDelivered() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val collector = collector(dispatcher)
            val seen = mutableListOf<String>()
            collector.setUpdateHandler { seen.add(it.id) }

            val a = Thing("a", "0")
            val b = Thing("b", "0")
            collector.add(a)
            collector.add(b)
            advanceUntilIdle()

            a.label = "1"
            b.label = "1"
            advanceTimeBy(window.inWholeMilliseconds * 2)
            advanceUntilIdle()

            assertEquals(listOf("a", "b"), seen.sorted())
        }
}
