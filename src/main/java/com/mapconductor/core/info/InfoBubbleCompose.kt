package com.mapconductor.core.info

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mapconductor.core.MapViewScope
import com.mapconductor.core.features.GeoPoint
import com.mapconductor.core.marker.MarkerState
import java.util.UUID

// @Composable
// fun MapViewScope.InfoAnchor(
//    props: MarkerState,
//    anchor: Offset = Offset(0.5f, 1f),
//    content: @Composable () -> Unit,
// ) {
//    val bubble = remember { InfoBubbleSpec(props, anchor, content) }
//    SideEffect {
//        selectedInfo.value = bubble
//    }
// }

@Composable
fun MapViewScope.InfoBubble(
    marker: MarkerState,
    bubbleColor: Color = Color.Companion.White,
    borderColor: Color = Color.Companion.Black,
    contentPadding: Dp = 8.dp,
    cornerRadius: Dp = 4.dp,
    tailSize: Dp = 8.dp,
    content: @Composable () -> Unit,
) {
    val wrapped: @Composable () -> Unit = {
        DrawInfoBubble(
            modifier = Modifier,
            bubbleColor = bubbleColor,
            borderColor = borderColor,
            contentPadding = contentPadding,
            cornerRadius = cornerRadius,
            tailSize = tailSize,
            content = content,
        )
    }

    val entry = InfoBubbleEntry(
        id = marker.id,
        positionProvider = { marker.position },
        icon = marker.icon,
        content = wrapped,
    )

    DisposableEffect(marker) {
        val newMap = bubbleFlow.value.toMutableMap()
        newMap[entry.id] = entry
        bubbleFlow.value = newMap

        onDispose {
            val newMap = bubbleFlow.value.toMutableMap()
            newMap.remove(entry.id)
            bubbleFlow.value = newMap
        }
    }
}

/**
 * Places an InfoBubble at the given [position] on the map without requiring a [MarkerState].
 *
 * Usage:
 * ```
 * InfoBubble(position = GeoPoint(35.68, 139.77)) {
 *     Text("Hello!")
 * }
 * ```
 */
@Composable
fun MapViewScope.InfoBubble(
    position: GeoPoint,
    bubbleColor: Color = Color.Companion.White,
    borderColor: Color = Color.Companion.Black,
    contentPadding: Dp = 8.dp,
    cornerRadius: Dp = 4.dp,
    tailSize: Dp = 8.dp,
    content: @Composable () -> Unit,
) {
    val id = remember { UUID.randomUUID().toString() }

    val wrapped: @Composable () -> Unit = {
        DrawInfoBubble(
            modifier = Modifier,
            bubbleColor = bubbleColor,
            borderColor = borderColor,
            contentPadding = contentPadding,
            cornerRadius = cornerRadius,
            tailSize = tailSize,
            content = content,
        )
    }

    // Re-register when position changes so the bubble moves to the new coordinates.
    DisposableEffect(position) {
        val entry = InfoBubbleEntry(
            id = id,
            positionProvider = { position },
            icon = null,
            content = wrapped,
        )
        val newMap = bubbleFlow.value.toMutableMap()
        newMap[id] = entry
        bubbleFlow.value = newMap

        onDispose {
            val newMap = bubbleFlow.value.toMutableMap()
            newMap.remove(id)
            bubbleFlow.value = newMap
        }
    }
}

/**
 * Register a fully custom InfoBubble content positioned by the existing overlay engine.
 *
 * - Reuses the internal InfoBubbleOverlay for screen positioning.
 * - `tailOffset` specifies where, inside your content box (0..1), the connection point sits.
 *   For example, right-side middle is Offset(1f, 0.5f); bottom-center is Offset(0.5f, 1f).
 * - The provided [content] should draw the bubble (including its tail) in any shape you like.
 */
@Composable
fun MapViewScope.InfoBubbleCustom(
    marker: MarkerState,
    tailOffset: Offset,
    content: @Composable () -> Unit,
) {
    val entry = InfoBubbleEntry(
        id = marker.id,
        positionProvider = { marker.position },
        icon = marker.icon,
        tailOffset = tailOffset,
        content = content,
    )

    // Re-register when marker or tail changes
    DisposableEffect(marker, tailOffset) {
        val newMap = bubbleFlow.value.toMutableMap()
        newMap[entry.id] = entry
        bubbleFlow.value = newMap

        onDispose {
            val newMap = bubbleFlow.value.toMutableMap()
            newMap.remove(entry.id)
            bubbleFlow.value = newMap
        }
    }
}
