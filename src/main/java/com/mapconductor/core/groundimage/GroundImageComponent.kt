package com.mapconductor.core.groundimage

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import com.mapconductor.core.MapViewScope
import com.mapconductor.core.features.GeoRectBounds
import java.io.Serializable
import android.graphics.drawable.Drawable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun MapViewScope.GroundImage(state: GroundImageState) {
    val collector = LocalGroundImageCollector.current
    LaunchedEffect(state) {
        collector.add(state)
    }

    DisposableEffect(state.id) {
        onDispose {
            collector.remove(state.id)
        }
    }
}

@Composable
fun MapViewScope.GroundImages(states: List<GroundImageState>) {
    val collector = LocalGroundImageCollector.current
    val prevIdsState = remember { mutableStateOf<Set<String>>(emptySet()) }

    LaunchedEffect(states) {
        withContext(Dispatchers.Default) {
            prevIdsState.value = states.asSequence().map { it.id }.toSet()
            collector.flow.value = states.associateBy { it.id }.toMutableMap()
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            collector.flow.value = mutableMapOf()
            prevIdsState.value = emptySet()
        }
    }
}

@Composable
fun MapViewScope.GroundImage(
    bounds: GeoRectBounds,
    image: Drawable,
    opacity: Float = 0.5f,
    tileSize: Int = GroundImageTileProvider.DEFAULT_TILE_SIZE,
    id: String? = null,
    extra: Serializable? = null,
    onClick: OnGroundImageEventHandler? = null,
) {
    val state =
        GroundImageState(
            bounds = bounds,
            image = image,
            opacity = opacity,
            tileSize = tileSize,
            id = id,
            extra = extra,
            onClick = onClick,
        )
    GroundImage(state)
}
