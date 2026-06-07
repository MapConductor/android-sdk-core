package com.mapconductor.core.marker

import android.content.Context
import com.dylibso.chicory.runtime.Instance
import com.dylibso.chicory.wasm.Parser
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Accelerates per-tile spatial query + coordinate transform by running a compiled
 * Wasm module via Chicory (pure-Java interpreter, no JNI required).
 *
 * Replaces two hot-path operations in [MarkerTileRenderer.renderTile]:
 *   1. [MarkerManager.findMarkersInBounds] — linear scan over all marker positions
 *   2. [geoToTilePoint] loop — batch Web-Mercator transform for matching markers
 *
 * Both operations are fused into a single Wasm call, eliminating repeated
 * JVM→Wasm boundary crossings.
 *
 * Thread safety: Chicory instances are NOT thread-safe.
 * [MarkerTileRenderer] synchronizes all calls to this engine on the engine instance.
 *
 * Wasm source: android-sdk-core/tile-wasm/src/lib.rs
 * Rebuild:     ./gradlew :android-sdk-core:buildTileWasm
 */
class TileRenderWasmEngine private constructor(private val instance: Instance) {

    data class QueryResult(
        val indices: IntArray,
        val normX: DoubleArray,
        val normY: DoubleArray,
    ) {
        val count: Int get() = indices.size
        val isEmpty: Boolean get() = indices.isEmpty()
    }

    /**
     * Uploads N marker positions into the Wasm spatial index.
     * Must be called once before [queryAndTransform] whenever marker data changes.
     */
    fun buildIndex(lats: DoubleArray, lons: DoubleArray) {
        val count = lats.size
        if (count == 0) return

        val byteCount = count * Double.SIZE_BYTES
        val latsPtr = wasmAlloc(byteCount)
        val lonsPtr = wasmAlloc(byteCount)
        try {
            val memory = instance.memory()
            val buf = ByteBuffer.allocate(byteCount).order(ByteOrder.LITTLE_ENDIAN)

            buf.clear()
            lats.forEach { buf.putDouble(it) }
            memory.write(latsPtr, buf.array())

            buf.clear()
            lons.forEach { buf.putDouble(it) }
            memory.write(lonsPtr, buf.array())

            instance.export("build_index").apply(
                latsPtr.toLong(),
                lonsPtr.toLong(),
                count.toLong(),
            )
        } finally {
            wasmDealloc(latsPtr, byteCount)
            wasmDealloc(lonsPtr, byteCount)
        }
    }

    /**
     * Finds all indexed markers within [minLat..maxLat] × [minLon..maxLon] and
     * returns their indices (into the last [buildIndex] snapshot) together with
     * normalized tile coordinates: (tilePoint.x − tileX, tilePoint.y − tileY).
     *
     * @param tileX  integer tile column (tile origin x in tile-space)
     * @param tileY  integer tile row    (tile origin y in tile-space)
     * @param zoomN  2^zoom — tiles per axis at this zoom level
     */
    fun queryAndTransform(
        minLat: Double,
        maxLat: Double,
        minLon: Double,
        maxLon: Double,
        tileX: Double,
        tileY: Double,
        zoomN: Double,
    ): QueryResult {
        val count = instance.export("query_and_transform").apply(
            d(minLat), d(maxLat), d(minLon), d(maxLon),
            d(tileX), d(tileY), d(zoomN),
        )[0].toInt()

        if (count == 0) return QueryResult(IntArray(0), DoubleArray(0), DoubleArray(0))

        val memory = instance.memory()
        val indicesPtr = callI32("get_result_indices_ptr")
        val normXPtr = callI32("get_result_norm_x_ptr")
        val normYPtr = callI32("get_result_norm_y_ptr")

        val indicesBytes = memory.readBytes(indicesPtr, count * Int.SIZE_BYTES)
        val normXBytes = memory.readBytes(normXPtr, count * Double.SIZE_BYTES)
        val normYBytes = memory.readBytes(normYPtr, count * Double.SIZE_BYTES)

        val indicesBuf = ByteBuffer.wrap(indicesBytes).order(ByteOrder.LITTLE_ENDIAN)
        val normXBuf = ByteBuffer.wrap(normXBytes).order(ByteOrder.LITTLE_ENDIAN)
        val normYBuf = ByteBuffer.wrap(normYBytes).order(ByteOrder.LITTLE_ENDIAN)

        return QueryResult(
            indices = IntArray(count) { indicesBuf.int },
            normX = DoubleArray(count) { normXBuf.double },
            normY = DoubleArray(count) { normYBuf.double },
        )
    }

    private fun wasmAlloc(bytes: Int): Int =
        instance.export("wasm_alloc").apply(bytes.toLong())[0].toInt()

    private fun wasmDealloc(ptr: Int, bytes: Int) {
        instance.export("wasm_dealloc").apply(ptr.toLong(), bytes.toLong())
    }

    private fun callI32(name: String): Int =
        instance.export(name).apply()[0].toInt()

    private fun d(v: Double): Long = java.lang.Double.doubleToRawLongBits(v)

    companion object {
        private const val WASM_ASSET = "tile_wasm.wasm"

        fun create(context: Context): TileRenderWasmEngine {
            val bytes = context.assets.open(WASM_ASSET).use { it.readBytes() }
            val module = Parser.parse(bytes.inputStream())
            val instance = Instance.builder(module).build()
            return TileRenderWasmEngine(instance)
        }

        fun createOrNull(context: Context, enabled: Boolean): TileRenderWasmEngine? =
            if (enabled) runCatching { create(context) }.getOrNull() else null
    }
}
