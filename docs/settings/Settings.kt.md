# Settings

Shared configuration values used by Map Conductor components. `Settings` centralizes small UI and
interaction tuning values so platform modules can use the same defaults for taps, marker animation,
icon sizing, and Compose event throttling.

## Settings

### Signature

```kotlin
sealed class Settings(
    val tapTolerance: Dp,
    val markerDropAnimateDuration: Long,
    val markerBounceAnimateDuration: Long,
    val iconSize: Dp,
    val iconStroke: Dp,
    val composeEventDebounce: Duration,
)
```

### Properties

- `tapTolerance`
    - Type: `Dp`
    - Description: Distance around a touch point that should still be treated as a tap target hit.
- `markerDropAnimateDuration`
    - Type: `Long`
    - Description: Duration in milliseconds for marker drop animations.
- `markerBounceAnimateDuration`
    - Type: `Long`
    - Description: Duration in milliseconds for marker bounce animations.
- `iconSize`
    - Type: `Dp`
    - Description: Default marker icon size.
- `iconStroke`
    - Type: `Dp`
    - Description: Default stroke width used by marker icons.
- `composeEventDebounce`
    - Type: `Duration`
    - Description: Debounce interval used when forwarding high-frequency Compose events.

## Settings.Default

Default SDK tuning values.

```kotlin
object Default : Settings(
    tapTolerance = 14.dp,
    markerDropAnimateDuration = 300,
    markerBounceAnimateDuration = 2000,
    iconSize = MarkerIconSize.Regular,
    iconStroke = 1.dp,
    composeEventDebounce = 5.milliseconds,
)
```

## MarkerIconSize

Common marker icon size constants.

```kotlin
object MarkerIconSize {
    val Small: Dp = 32.dp
    val Regular: Dp = 48.dp
    val Large: Dp = 60.dp
}
```

