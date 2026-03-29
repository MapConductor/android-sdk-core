# MapConductor Core

## Description

MapConductor Core is the foundation library of the MapConductor SDK.
It provides the shared data types, Compose overlay components, and abstraction interfaces used across all map implementations (Google Maps, MapLibre, HERE, Mapbox, ArcGIS, etc.).

App developers use types from this module (e.g. `GeoPoint`, `MarkerState`, `DefaultMarkerIcon`) directly, regardless of which map implementation they choose.
The actual map view (`GoogleMapView`, `MapLibreMapView`, etc.) is provided by each implementation module.

## Setup

https://docs-android.mapconductor.com/setup/

## Data Types

### GeoPoint

```kotlin
// From latitude / longitude
val point = GeoPoint(latitude = 35.6762, longitude = 139.6503)

// Convenience constructors
val point = GeoPoint.fromLatLong(35.6762, 139.6503)
val point = GeoPoint.fromLongLat(139.6503, 35.6762)
```

### GeoRectBounds

```kotlin
val bounds = GeoRectBounds(
    southWest = GeoPoint.fromLatLong(35.0, 139.0),
    northEast = GeoPoint.fromLatLong(36.0, 140.0),
)
```

### MapCameraPosition

```kotlin
val cameraPosition = MapCameraPosition(
    position = GeoPoint(latitude = 35.6762, longitude = 139.6503),
    zoom = 14.0,
    bearing = 45.0,
    tilt = 30.0,
)
```

------------------------------------------------------------------------

## Marker Icons

### DefaultMarkerIcon (color fill)

```kotlin
val icon = DefaultMarkerIcon(
    fillColor = Color.Red,
    strokeColor = Color.White,
    label = "Tokyo",
    labelTextColor = Color.White,
)
```

### ImageDefaultIcon (bitmap fill)

```kotlin
val icon = ImageDefaultIcon(
    backgroundImage = bitmap,
    label = "Tokyo",
)
```

### DrawableDefaultIcon (Drawable fill)

```kotlin
val icon = DrawableDefaultIcon(
    backgroundDrawable = drawable,
    label = "Tokyo",
)
```

------------------------------------------------------------------------

## Overlay Components

All overlay components are Compose functions available inside any `XxxMapView` content block.

### Marker

```kotlin
// Using MarkerState
val markerState = remember {
    MarkerState(
        position = GeoPoint(latitude = 35.6762, longitude = 139.6503),
        icon = DefaultMarkerIcon(
            label = "Tokyo",
            fillColor = Color.Blue,
        ),
        onClick = { state ->
            state.animate(MarkerAnimation.Bounce)
        },
    )
}

XxxMapView(...) {
    Marker(markerState)
}
```

### Markers (batch)

Large numbers of markers can be added efficiently without per-marker Composable overhead:

```kotlin
val markerStates: List<MarkerState> = remember { buildMarkerList() }

XxxMapView(...) {
    Markers(markerStates)
}
```

### MarkerAnimation

```kotlin
// Available animations
MarkerAnimation.Bounce
MarkerAnimation.Drop

markerState.animate(MarkerAnimation.Bounce)
```

### InfoBubble

```kotlin
var selectedMarker by remember { mutableStateOf<MarkerState?>(null) }

XxxMapView(...) {
    Marker(
        MarkerState(
            ...,
            onClick = { selectedMarker = it },
        )
    )

    selectedMarker?.let {
        InfoBubble(marker = it) {
            Text("Hello, world!")
        }
    }
}
```

### Circle

```kotlin
val circleState = remember {
    CircleState(
        center = GeoPoint(latitude = 35.6762, longitude = 139.6503),
        radiusMeters = 500.0,
        fillColor = Color.Blue.copy(alpha = 0.3f),
        strokeColor = Color.Blue,
    )
}

XxxMapView(...) {
    Circle(circleState)
}
```

### Polyline

```kotlin
val polylineState = remember {
    PolylineState(
        points = listOf(
            GeoPoint(35.6762, 139.6503),
            GeoPoint(35.6895, 139.6917),
        ),
        strokeColor = Color.Red,
        strokeWidth = 4.dp,
    )
}

XxxMapView(...) {
    Polyline(polylineState)
}
```

### Polygon

```kotlin
val polygonState = remember {
    PolygonState(
        points = listOf(...),
        fillColor = Color.Green.copy(alpha = 0.4f),
        strokeColor = Color.Green,
    )
}

XxxMapView(...) {
    Polygon(polygonState)
}
```

### GroundImage

```kotlin
val groundImageState = remember {
    GroundImageState(
        bounds = GeoRectBounds(
            southWest = GeoPoint.fromLatLong(35.0, 139.0),
            northEast = GeoPoint.fromLatLong(36.0, 140.0),
        ),
        image = bitmap,
        opacity = 0.7f,
    )
}

XxxMapView(...) {
    GroundImage(groundImageState)
}
```

### RasterLayer

```kotlin
val rasterLayerState = remember {
    RasterLayerState(
        source = RasterLayerSource.XYZ("https://tile.openstreetmap.org/{z}/{x}/{y}.png"),
    )
}

XxxMapView(...) {
    RasterLayer(rasterLayerState)
}
```
