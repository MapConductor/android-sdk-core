package com.mapconductor.core.tileserver

interface TileProviderInterface {
    /**
     * Draws one tile, or returns null when there is nothing to draw there.
     *
     * Null is "this spot is empty", and the server answers it with a
     * transparent tile — not a 404, which map SDKs remember as permanent. A
     * render that cannot be completed right now should throw instead; the
     * server turns that into a 503 the map knows to retry.
     */
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
