# `ColorDefaultIcon`

Creates a standard pin-shaped map marker icon with a solid color fill. This is the most common and
straightforward marker type to use.

### Constructor

Initializes a new instance of `ColorDefaultIcon`. A convenience constructor is provided with
sensible defaults for most parameters.

**Signature:**
```kotlin
constructor(
    fillColor: Color = Color.Red,
    strokeColor: Color = Color.White,
    strokeWidth: Dp = Settings.Default.iconStroke,
    scale: Float = 1f,
    label: String? = null,
    labelTextColor: Color? = Color.Black,
    labelTextSize: TextUnit = 18.sp,
    labelTypeFace: Typeface = Typeface.DEFAULT,
    labelStrokeColor: Color = Color.White,
    infoAnchor: Offset = Offset(0.5f, 0f),
    iconSize: Dp = Settings.Default.iconSize,
    debug: Boolean = false,
)
```

### Parameters

- `fillColor`
    - Type: `Color`
    - Default: `Color.Red`
    - Description: The fill color of the marker body.
- `strokeColor`
    - Type: `Color`
    - Default: `Color.White`
    - Description: The color of the marker's outline.
- `strokeWidth`
    - Type: `Dp`
    - Default: `Settings.Default.iconStroke`
    - Description: The width of the marker's outline.
- `scale`
    - Type: `Float`
    - Default: `1.0f`
    - Description: The scaling factor for the entire icon.
- `label`
    - Type: `String?`
    - Default: `null`
    - Description: An optional text label to display inside the marker.
- `labelTextColor`
    - Type: `Color?`
    - Default: `Color.Black`
    - Description: The color of the label text.
- `labelTextSize`
    - Type: `TextUnit`
    - Default: `18.sp`
    - Description: The size of the label text.
- `labelTypeFace`
    - Type: `Typeface`
    - Default: `Typeface.DEFAULT`
    - Description: The typeface for the label text.
- `labelStrokeColor`
    - Type: `Color`
    - Default: `Color.White`
    - Description: The color of the outline drawn around the label text for better visibility.
- `infoAnchor`
    - Type: `Offset`
    - Default: `Offset(0.5f, 0f)` (top-center)
    - Description: The anchor point for an info window, relative to the icon's dimensions (0,0 is
      top-left, 1,1 is bottom-right).
- `iconSize`
    - Type: `Dp`
    - Default: `Settings.Default.iconSize`
    - Description: The base size of the icon before scaling.
- `debug`
    - Type: `Boolean`
    - Default: `false`
    - Description: If `true`, a debug frame will be drawn around the icon's canvas.

### Functions

#### `copy`

Creates a new `ColorDefaultIcon` instance, allowing you to modify specific properties while keeping
others the same.

**Signature:**
```kotlin
fun copy(
    fillColor: Color = this.fillColor,
    strokeColor: Color = this.strokeColor,
    strokeWidth: Dp = this.strokeWidth,
    scale: Float = this.scale,
    label: String? = this.label,
    labelTextColor: Color? = this.labelTextColor,
    labelTextSize: TextUnit = this.labelTextSize,
    labelTypeFace: Typeface = this.labelTypeFace,
    labelStrokeColor: Color = this.labelStrokeColor,
    iconSize: Dp = this.iconSize,
    debug: Boolean = this.debug,
): ColorDefaultIcon
```

### Example

```kotlin
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Create a simple blue marker
val simpleMarker = ColorDefaultIcon(fillColor = Color.Blue)

// Create a larger, scaled marker with a label
val labeledMarker = ColorDefaultIcon(
    fillColor = Color(0xFF009688), // Teal
    strokeColor = Color.White,
    strokeWidth = 3.dp,
    scale = 1.5f,
    label = "A",
    labelTextColor = Color.White,
    labelTextSize = 20.sp,
    labelStrokeColor = Color.Black
)
```

---

## `ImageDefaultIcon`

Creates a standard pin-shaped map marker icon filled with a `Bitmap` image. The image is scaled to
fill the marker shape using a center-crop behavior, preserving its aspect ratio.

### Constructor

Initializes a new instance of `ImageDefaultIcon`.

**Signature:**
```kotlin
constructor(
    backgroundImage: Bitmap,
    strokeColor: Color = Color.White,
    strokeWidth: Dp = Settings.Default.iconStroke,
    scale: Float = 1f,
    label: String? = null,
    // ... other parameters are the same as ColorDefaultIcon
)
```

### Parameters

- `backgroundImage`
    - Type: `Bitmap`
    - Default: (none)
    - Description: The bitmap image to use as the marker's fill.
- `strokeColor`
    - Type: `Color`
    - Default: `Color.White`
    - Description: The color of the marker's outline.
- `strokeWidth`
    - Type: `Dp`
    - Default: `Settings.Default.iconStroke`
    - Description: The width of the marker's outline.
- `scale`
    - Type: `Float`
    - Default: `1.0f`
    - Description: The scaling factor for the entire icon.
- `label`
    - Type: `String?`
    - Default: `null`
    - Description: An optional text label to display inside the marker.
- `...`
    - Description: Other parameters are identical to `ColorDefaultIcon`.

### Functions

#### `copy`

Creates a new `ImageDefaultIcon` instance, allowing you to modify specific properties.

**Signature:**
```kotlin
fun copy(
    backgroundImage: Bitmap = this.backgroundImage,
    // ... other parameters are the same as ColorDefaultIcon.copy
): ImageDefaultIcon
```

### Example

```kotlin
// Assume 'myBitmap' is a Bitmap object loaded from resources or network
val myBitmap: Bitmap = // ...

// Create a marker filled with the bitmap
val imageMarker = ImageDefaultIcon(
    backgroundImage = myBitmap,
    strokeWidth = 2.dp,
    scale = 1.2f
)
```

---

## `DrawableDefaultIcon`

Creates a standard pin-shaped map marker icon filled with a `Drawable`. The drawable is scaled to
fill the marker shape. If the drawable has an intrinsic size, its aspect ratio is preserved using a
center-crop behavior.

### Constructor

Initializes a new instance of `DrawableDefaultIcon`.

**Signature:**
```kotlin
constructor(
    backgroundDrawable: Drawable,
    strokeColor: Color = Color.White,
    strokeWidth: Dp = Settings.Default.iconStroke,
    scale: Float = 1f,
    label: String? = null,
    // ... other parameters are the same as ColorDefaultIcon
)
```

### Parameters

- `backgroundDrawable`
    - Type: `Drawable`
    - Default: (none)
    - Description: The drawable to use as the marker's fill.
- `strokeColor`
    - Type: `Color`
    - Default: `Color.White`
    - Description: The color of the marker's outline.
- `strokeWidth`
    - Type: `Dp`
    - Default: `Settings.Default.iconStroke`
    - Description: The width of the marker's outline.
- `scale`
    - Type: `Float`
    - Default: `1.0f`
    - Description: The scaling factor for the entire icon.
- `label`
    - Type: `String?`
    - Default: `null`
    - Description: An optional text label to display inside the marker.
- `...`
    - Description: Other parameters are identical to `ColorDefaultIcon`.

### Functions

#### `copy`

Creates a new `DrawableDefaultIcon` instance, allowing you to modify specific properties.

**Signature:**
```kotlin
fun copy(
    backgroundDrawable: Drawable = this.backgroundDrawable,
    // ... other parameters are the same as ColorDefaultIcon.copy
): DrawableDefaultIcon
```

### Example

```kotlin
import androidx.core.content.ContextCompat

// Assume 'context' is a valid Android Context
val myDrawable = ContextCompat.getDrawable(context, R.drawable.my_gradient_background)

// Create a marker filled with the drawable
val drawableMarker = myDrawable?.let {
    DrawableDefaultIcon(
        backgroundDrawable = it,
        strokeWidth = 2.dp
    )
}
```

---

## `DefaultMarkerIcon`

A type alias for `ColorDefaultIcon`. This is provided for backward compatibility. It is recommended
to use `ColorDefaultIcon` directly in new code.

**Signature:**
```kotlin
typealias DefaultMarkerIcon = ColorDefaultIcon
```

---

## `AbstractDefaultIcon`

An abstract base class that provides the common framework for creating pin-shaped marker icons. It
handles the rendering of the marker's shape, stroke, and label.

Developers should typically use one of the concrete subclasses (`ColorDefaultIcon`,
`ImageDefaultIcon`, `DrawableDefaultIcon`) or extend this class to create custom fill behaviors.

### Properties

- `strokeColor`
    - Type: `Color`
    - Description: The color of the marker's outline.
- `strokeWidth`
    - Type: `Dp`
    - Description: The width of the marker's outline.
- `scale`
    - Type: `Float`
    - Description: The scaling factor for the entire icon.
- `label`
    - Type: `String?`
    - Description: The optional text label to display inside the marker.
- `labelTextColor`
    - Type: `Color?`
    - Description: The color of the label text.
- `labelTextSize`
    - Type: `TextUnit`
    - Description: The size of the label text.
- `labelTypeFace`
    - Type: `Typeface`
    - Description: The typeface for the label text.
- `labelStrokeColor`
    - Type: `Color`
    - Description: The color of the outline drawn around the label text.
- `iconSize`
    - Type: `Dp`
    - Description: The base size of the icon before scaling.
- `anchor`
    - Type: `Offset`
    - Description: The anchor point of the icon, relative to its dimensions. Fixed to `Offset(0.5f,
      1f)` (bottom-center).
- `infoAnchor`
    - Type: `Offset`
    - Description: The anchor point for an info window, relative to the icon's dimensions.
- `debug`
    - Type: `Boolean`
    - Description: If `true`, a debug frame is drawn around the icon's canvas.

### Abstract Functions

Subclasses must implement these methods.

#### `drawMarkerFill`

Defines how the interior (fill) of the marker is drawn.

**Signature:**
```kotlin
protected abstract fun drawMarkerFill(
    canvas: Canvas,
    path: Path,
    canvasSize: Float,
    iconScale: Float,
)
```

**Parameters:**
- `canvas`
    - Type: `Canvas`
    - Description: The canvas to draw on.
- `path`
    - Type: `Path`
    - Description: The path defining the marker's shape.
- `canvasSize`
    - Type: `Float`
    - Description: The total size of the drawing area for the marker.
- `iconScale`
    - Type: `Float`
    - Description: The current scale of the icon.

#### `getUniqueProperties`

Returns an object representing the unique properties of the subclass. This is used for `equals` and
`hashCode` implementations to ensure correct caching and comparison.

**Signature:**
```kotlin
protected abstract fun getUniqueProperties(): Any
```

**Returns:**
- `Any`: An object (e.g., a `Color`, a `Bitmap` hash) that uniquely identifies the state of the
  subclass.
