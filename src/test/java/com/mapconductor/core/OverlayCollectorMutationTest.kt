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
import kotlinx.coroutines.test.runCurrent

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

    @Test
    fun bulkRemovalStopsListeningAndReinsertionRestoresIt() = runTest {
        val collector = collector(StandardTestDispatcher(testScheduler))
        val seen = mutableListOf<String>()
        collector.setUpdateHandler { seen.add(it.label) }
        val state = Thing("bulk", "before")
        collector.flow.value = copyOverlayStates(mapOf(state.id to state))
        advanceUntilIdle()
        state.label = "visible"
        advanceUntilIdle()
        collector.flow.value = copyOverlayStates(emptyMap())
        advanceUntilIdle()
        state.label = "hidden"
        advanceUntilIdle()
        collector.flow.value = copyOverlayStates(mapOf(state.id to state))
        advanceUntilIdle()
        state.label = "restored"
        advanceUntilIdle()
        assertEquals(listOf("visible", "restored"), seen)
    }

    @Test
    fun bulkPublicationKeepsPendingDirectAddsListening() = runTest {
        val collector = collector(StandardTestDispatcher(testScheduler))
        val seen = mutableListOf<String>()
        collector.setUpdateHandler { seen.add(it.id) }
        val pending = Thing("single", "before")
        val bulk = Thing("bulk", "before")
        runCurrent()
        collector.add(pending)
        collector.flow.value = copyOverlayStates(mapOf(bulk.id to bulk))
        runCurrent() // Collect the bulk map before the direct add's debounce expires.
        pending.label = "after"
        advanceUntilIdle()
        assertEquals(listOf("single"), seen)
        assertEquals(setOf("single", "bulk"), collector.flow.value.keys)
    }

    @Test
    fun aBulkListThatDropsAndRestoresSomeStatesKeepsTheRestReporting() = runTest {
        val collector = collector(StandardTestDispatcher(testScheduler))
        val seen = mutableListOf<String>()
        collector.setUpdateHandler { seen.add("${it.id}=${it.label}") }
        val states = List(5) { Thing("s$it", "0") }
        fun publish(list: List<Thing>) {
            collector.flow.value = copyOverlayStates(list.associateBy { it.id })
        }
        publish(states)
        advanceUntilIdle()
        publish(states.filterIndexed { index, _ -> index % 2 == 0 })
        advanceUntilIdle()
        states.forEach { it.label = "1" }
        advanceUntilIdle()
        assertEquals(listOf("s0=1", "s2=1", "s4=1"), seen.sorted())
        seen.clear()
        publish(states)
        advanceUntilIdle()
        states.forEach { it.label = "2" }
        advanceUntilIdle()
        assertEquals(states.map { "${it.id}=2" }, seen.sorted())
    }

    @Test
    fun aListHandOverReportsWritesOfItsStatesOnly() = runTest {
        val collector = collector(StandardTestDispatcher(testScheduler))
        val seen = mutableListOf<String>()
        collector.setUpdateHandler { seen.add("${it.id}=${it.label}") }
        val states = List(4) { Thing("l$it", "0") }
        collector.flow.value = OverlayStateList(states)
        advanceUntilIdle()
        collector.flow.value = OverlayStateList(states.take(2))
        advanceUntilIdle()
        states.forEach { it.label = "1" }
        advanceUntilIdle()
        assertEquals(listOf("l0=1", "l1=1"), seen.sorted())
    }

    @Test
    fun aWrittenListFollowsItsIndex() {
        val states = List(3) { Thing("w$it", "0") }
        val list = OverlayStateList(states)
        assertEquals(states, list.states())
        assertEquals(states[1], list["w1"])
        list.remove("w1")
        assertEquals(listOf(states[0], states[2]), list.states())
        assertEquals(setOf("w0", "w2"), list.keys)
    }

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
