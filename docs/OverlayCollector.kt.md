# OverlayCollector API Reference

The `OverlayCollector` API provides a robust and efficient mechanism for managing a collection of
stateful components. It is designed for reactive systems where you need to observe and react to
changes in a dynamic set of objects.

The core of the API is the `OverlayCollector` class, which handles the addition, removal, and
observation of individual component states.

### `OverlayCollector<T, FingerPrint>`

A generic, thread-safe collector that manages a dynamic collection of objects conforming to the
`ComponentState` interface. It efficiently batches add/remove operations and provides a debounced
mechanism to handle updates to individual states.

#### Signature

```kotlin
class OverlayCollector<T : ComponentState, FingerPrint>(
    private val fingerPrintOf: (T) -> FingerPrint,
    private val updateDebounce: Duration,
    scope: CoroutineScope = CoroutineScope(Dispatchers.Main.immediate),
) : OverlayCollectorInterface<T>
```

#### Description

`OverlayCollector` maintains an in-memory map of component states, identified by their unique
`id`. It exposes this collection as a `StateFlow`, allowing observers to react to any changes in the
set of components.

A key feature is its ability to monitor individual states for changes. This is achieved via the
`fingerPrintOf` function provided during initialization, which defines what constitutes an "update"
for a state object: a single `snapshotFlow` reads every collected state's fingerprint, and
successive fingerprint maps are diffed so only the states that actually changed reach the
`updateHandler`. That stream is **sampled** at `updateDebounce`, so a state mutating every frame
(a drag, for instance) still delivers at most one callback per window instead of being starved by an
ever-extending debounce.

Membership changes (`add` / `remove`) are a separate channel and are **debounced** — a 5ms quiet
window that each event extends, with a count valve (100 adds / 300 removes) so a large mount does
not wait for the burst to finish. They are published through `flow`, never through the update
handler.

All asynchronous operations are managed within the provided `CoroutineScope`.

#### Parameters

- `fingerPrintOf`
    - Type: `(T) -> FingerPrint`
    - Description: A function that takes a state object `T` and returns a value summarising the
      properties whose changes should trigger an update. The collector compares successive
      fingerprints with `equals`, so this is normally a `data class` of the relevant fields.
- `updateDebounce`
    - Type: `Duration`
    - Description: The time duration to wait for new changes before invoking the `updateHandler`.
      This helps to coalesce multiple rapid updates into a single callback.
- `scope`
    - Type: `CoroutineScope`
    - Description: The coroutine scope in which all background jobs for collecting, debouncing, and
      updating will be launched. Defaults to `CoroutineScope(Dispatchers.Main.immediate)`.

---

### Properties

#### `flow`

A `StateFlow` that emits the current map of managed states.

**Signature**
```kotlin
override val flow: MutableStateFlow<MutableMap<String, T>>
```

**Description**
Collectors can subscribe to this flow to receive an updated map (`Map<String, T>`) whenever a state
is added, removed, or the entire collection is replaced. The map's keys are the state `id`s.

---

### Functions

#### `add`

Asynchronously adds a new state to the collection or updates an existing one with the same `id`.

**Signature**
```kotlin
override suspend fun add(state: T)
```

**Description**
This function submits the state to an internal queue. Additions are batched and debounced for
performance, so the state will not be reflected in the main `flow` immediately. If a state with the
same `id` already exists, it will be replaced.

**Parameters**
- `state`
    - Type: `T`
    - Description: The component state object to add or update.

#### `remove`

Removes a state from the collection by its ID.

**Signature**
```kotlin
override fun remove(id: String)
```

**Description**
This is a non-suspending, fire-and-forget operation. Like `add`, removals are batched and debounced
for efficiency. Any active update-monitoring job for the corresponding state will be cancelled.

**Parameters**
- `id`
    - Type: `String`
    - Description: The unique identifier of the state to remove.

#### `setUpdateHandler`

Sets or clears the callback that is invoked when a managed state has an update.

**Signature**
```kotlin
override fun setUpdateHandler(handler: (suspend (T) -> Unit)?)
```

**Description**
When a non-null `handler` is provided, the collector begins monitoring all current and future states
for changes (as defined by the `fingerPrintOf` function). When a change is detected and the
`updateDebounce` period passes, the handler is called with the updated state.

If the handler is set to `null`, all active update-monitoring jobs are cancelled and no further
update callbacks will be triggered.

**Parameters**
- `handler`
    - Type: `(suspend (T) -> Unit)?`
    - Description: The suspendable lambda to execute on a state update, or `null` to clear the
      handler.

#### `replaceAll`

Atomically replaces the entire collection of states with a new list.

**Signature**
```kotlin
override fun replaceAll(states: List<T>)
```

**Description**
This function provides an efficient way to perform a bulk update. It calculates the difference
between the old and new sets of states, cancels monitoring jobs for removed states, and starts new
jobs for added states. The main `flow` is updated with the new map in a single emission. This is
more performant than clearing the collection and adding items individually.

**Parameters**
- `states`
    - Type: `List<T>`
    - Description: The new list of states to manage.

---

### Example

```kotlin
import com.mapconductor.core.OverlayCollector
import com.mapconductor.core.ComponentState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.*
import kotlin.time.Duration.Companion.seconds

// 1. Define a state object that implements ComponentState.
//    In-place mutations are observed through Compose snapshot state, so the
//    mutable properties must be backed by mutableStateOf.
class WidgetState(
    override val id: String,
    name: String,
) : ComponentState {
    var name by mutableStateOf(name)
    var lastUpdated: Long = 0
}

suspend fun main() {
    val scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())

    // 2. Instantiate the OverlayCollector
    val widgetCollector = OverlayCollector<WidgetState, String>(
        // The "fingerprint" is the widget's name. Updates trigger when the name changes.
        fingerPrintOf = { widgetState -> widgetState.name },
        updateDebounce = 1.seconds,
        scope = scope
    )

    // 3. Set an update handler to react to individual widget changes
    widgetCollector.setUpdateHandler { widget ->
        println("UPDATE HANDLER: Widget '${widget.id}' was updated. New name: ${widget.name}")
        widget.lastUpdated = System.currentTimeMillis()
    }

    // 4. Collect the main flow to observe the entire collection
    scope.launch {
        widgetCollector.flow.collect { widgets ->
            println("COLLECTION CHANGED: ${widgets.size} widgets total.")
            println("Current widgets: ${widgets.keys}")
            println("---")
        }
    }

    delay(100) // Allow collector to start

    // 5. Add widgets. Membership is debounced, so both adds land in one emission.
    println("Adding widget-1 and widget-2...")
    val widget1 = WidgetState("widget-1", "First Widget")
    val widget2 = WidgetState("widget-2", "Second Widget")
    widgetCollector.add(widget1)
    widgetCollector.add(widget2)

    delay(1000)

    // 6. Trigger an in-place update on a widget
    println("Updating widget-1's name...")
    widget1.name = "Updated First Widget" // Reaches the updateHandler on the next sample tick

    delay(2000)

    // 7. Remove a widget
    println("Removing widget-2...")
    widgetCollector.remove("widget-2")

    delay(1000)

    // 8. Replace all widgets
    println("Replacing all widgets...")
    val widget3 = WidgetState("widget-3", "Third Widget")
    widgetCollector.replaceAll(listOf(widget1, widget3))

    delay(1000)

    scope.cancel() // Clean up
}

/*
Expected Output:

COLLECTION CHANGED: 0 widgets total.
Current widgets: []
---
Adding widget-1 and widget-2...
COLLECTION CHANGED: 2 widgets total.
Current widgets: [widget-1, widget-2]
---
Updating widget-1's name...
UPDATE HANDLER: Widget 'widget-1' was updated. New name: Updated First Widget
Removing widget-2...
COLLECTION CHANGED: 1 widgets total.
Current widgets: [widget-1]
---
Replacing all widgets...
COLLECTION CHANGED: 2 widgets total.
Current widgets: [widget-1, widget-3]
---
*/
```

---

## Supporting Interfaces

### `OverlayCollectorInterface<T>`

Defines the public contract for a collector of `ComponentState` objects.

#### Signature

```kotlin
interface OverlayCollectorInterface<T : ComponentState>
```

#### Description

This interface abstracts the implementation details of the collector, providing a clear and stable
API for managing a collection of states. It includes methods for adding, removing, and replacing
states, as well as mechanisms for observing the collection as a whole and handling updates to
individual items.

---

### `ComponentState`

A contract for state objects that can be managed by an `OverlayCollector`.

#### Signature

```kotlin
interface ComponentState
```

#### Description

Any class representing a state that will be managed by an `OverlayCollector` must implement this
interface.

#### Properties

- `id`
    - Type: `String`
    - Description: A unique identifier for the state object. This is used as the key in the
      collector's internal map.
