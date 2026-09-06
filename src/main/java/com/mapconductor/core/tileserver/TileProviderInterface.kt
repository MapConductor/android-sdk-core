package com.mapconductor.core.tileserver

interface TileProviderInterface {
    fun renderTile(request: TileRequest): ByteArray?

    /**
     * Renders a tile, giving up if the map stops wanting it.
     *
     * The map asks for tiles it may abandon a moment later: a pinch or a fling
     * changes which ones matter while the old ones are still being drawn, and
     * it closes those connections. Without somewhere to notice that, a
     * provider draws every tile it was ever asked for and the ones still on
     * screen wait their turn behind them.
     *
     * [isCancelled] is cheap to call and safe to ignore -- the default does,
     * so a provider that renders quickly needs no changes.
     */
    fun renderTile(
        request: TileRequest,
        isCancelled: () -> Boolean,
    ): ByteArray? = renderTile(request)
}
