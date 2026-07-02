package com.mapconductor.core.info

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.mapconductor.core.features.GeoPointInterface
import com.mapconductor.core.marker.MarkerIconInterface
import kotlinx.coroutines.flow.MutableStateFlow

class InfoBubbleEntry(
    val id: String,
    /** Called during Compose recomposition to read the current position. */
    val positionProvider: () -> GeoPointInterface,
    val icon: MarkerIconInterface? = null,
    val tailOffset: Offset = Offset(0.5f, 1.0f),
    val content: @Composable () -> Unit,
)
