package com.mapconductor.core.circle

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mapconductor.core.MapViewScope
import com.mapconductor.core.features.GeoPointInterface
import java.io.Serializable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun MapViewScope.Circle(state: CircleState) {
    val collector = LocalCircleCollector.current
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
fun MapViewScope.Circles(states: List<CircleState>) {
    val collector = LocalCircleCollector.current
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
fun MapViewScope.Circle(
    center: GeoPointInterface,
    radiusMeters: Double,
    id: String? = null,
    strokeColor: Color = Color.Red,
    strokeWidth: Dp = 2.dp,
    fillColor: Color = Color.White.copy(alpha = 0.5f),
    zIndex: Int? = null,
    extra: Serializable? = null,
    onClick: OnCircleEventHandler? = null,
) {
    val state =
        CircleState(
            id = id,
            center = center,
            radiusMeters = radiusMeters,
            strokeColor = strokeColor,
            strokeWidth = strokeWidth,
            fillColor = fillColor,
            zIndex = zIndex,
            extra = extra,
            onClick = onClick,
        )
    Circle(state)
}
