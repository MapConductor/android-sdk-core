# Map Service Registry SDK

This document provides detailed documentation for the Map Service Registry components. This system
provides a type-safe service locator pattern for registering and retrieving map-scoped services,
with integration into Jetpack Compose.

## `MapServiceKey<T>`

### Signature
```kotlin
interface MapServiceKey<T : Any>
```

### Description
A typed service key used as a unique identifier to register and retrieve map-scoped services (often
called plugins) from a `MapServiceRegistry`.

It is a best practice to define keys as singleton `object`s to ensure there is only one instance of
the key for each service type.

### Example
```kotlin
// Define a service interface
interface CustomAnalyticsService {
    fun logEvent(name: String)
}

// Define a unique key for the service
object CustomAnalyticsServiceKey : MapServiceKey<CustomAnalyticsService>
```

---

## `MapServiceRegistry`

### Signature
```kotlin
interface MapServiceRegistry
```

### Description
Defines the contract for a registry that stores and provides access to map-scoped services. This
interface allows for different implementations, such as mutable or immutable registries.

### Methods

#### `get`
Retrieves a service instance associated with the specified key.

**Signature**
```kotlin
fun <T : Any> get(key: MapServiceKey<T>): T?
```

**Parameters**
- `key`
    - Type: `MapServiceKey<T>`
    - Description: The unique key for the service to retrieve.

**Returns**
The service instance of type `T` if it exists in the registry, or `null` otherwise.

---

## `MutableMapServiceRegistry`

### Signature
```kotlin
class MutableMapServiceRegistry : MapServiceRegistry
```

### Description
A mutable, thread-safe implementation of `MapServiceRegistry`. It allows for adding, retrieving, and
clearing services at runtime. This class uses a `ConcurrentHashMap` internally to manage services.

### Methods

#### `put`
Registers a new service or updates an existing one in the registry.

**Signature**
```kotlin
fun <T : Any> put(key: MapServiceKey<T>, value: T)
```

**Parameters**
- `key`
    - Type: `MapServiceKey<T>`
    - Description: The unique key for the service.
- `value`
    - Type: `T`
    - Description: The service instance to register.

#### `get`
Retrieves a service instance associated with the specified key.

**Signature**
```kotlin
override fun <T : Any> get(key: MapServiceKey<T>): T?
```

**Parameters**
- `key`
    - Type: `MapServiceKey<T>`
    - Description: The unique key for the service to retrieve.

**Returns**
The service instance of type `T` if it is registered, or `null` otherwise.

#### `clear`
Removes all services from the registry.

**Signature**
```kotlin
fun clear()
```

---

## `EmptyMapServiceRegistry`

### Signature
```kotlin
object EmptyMapServiceRegistry : MapServiceRegistry
```

### Description
A singleton, immutable implementation of `MapServiceRegistry` that contains no services. Its `get`
method will always return `null`. It is primarily used as a default or placeholder value, especially
for `LocalMapServiceRegistry`.

---

## `LocalMapServiceRegistry`

### Signature
```kotlin
val LocalMapServiceRegistry: ProvidableCompositionLocal<MapServiceRegistry>
```

### Description
A Jetpack Compose `CompositionLocal` that provides a `MapServiceRegistry` instance to the underlying
composition tree. This allows descendant Composables to access map-scoped services without needing
to pass the registry down explicitly as a parameter.

The default value is `EmptyMapServiceRegistry`, which means that if no registry is provided, any
attempt to retrieve a service will return `null`.

### Example
The following example demonstrates how to define a service, register it with
`MutableMapServiceRegistry`, provide it to the Composable tree, and access it in a child Composable.

```kotlin
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember

// 1. Define a service and its key
interface GreeterService {
    fun greet(name: String): String
}

object GreeterServiceKey : MapServiceKey<GreeterService>

class GreeterServiceImpl : GreeterService {
    override fun greet(name: String) = "Hello, $name!"
}

// 2. Register the service on the map state
//    The registry is owned by `MapViewState.serviceRegistry` (same as the iOS and React SDKs),
//    and `MapViewBase` provides it to the composition through `LocalMapServiceRegistry`.
//    Register before the map content is composed, and take it back when the view goes away.
@Composable
fun AppRoot(state: MapViewStateInterface<*>) {
    remember(state) {
        state.serviceRegistry.put(GreeterServiceKey, GreeterServiceImpl())
    }
    DisposableEffect(state) {
        // `remove()` rather than `clear()`, so other capabilities on the same map survive.
        onDispose { state.serviceRegistry.remove(GreeterServiceKey) }
    }

    MapScreen()
}

// 3. Access the service in a descendant Composable
@Composable
fun MapScreen() {
    // Access the registry from the CompositionLocal
    val registry = LocalMapServiceRegistry.current

    // Retrieve the service using its key
    val greeterService = registry.get(GreeterServiceKey)

    // Use the service
    val greeting = greeterService?.greet("Developer") ?: "Service not found"

    Text(text = greeting) // Displays "Hello, Developer!"
}
```
