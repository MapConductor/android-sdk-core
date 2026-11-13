package com.mapconductor.core.shapetile

import com.mapconductor.core.InternalMapConductorApi
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max

/** Immutable geometry snapshot. A renderer owns one reference; each reader leases another. */
@InternalMapConductorApi
internal abstract class ShapeTileStorage<T : Any> {
    private val references = AtomicInteger(1)
    abstract val size: Int
    abstract val maxStroke: Float

    abstract operator fun get(index: Int): T?

    abstract fun bbox(index: Int): DoubleArray?

    abstract fun updated(updates: List<Pair<Int, T?>>): ShapeTileStorage<T>

    /**
     * How the shapes are laid out in storage, when not in index order: a large
     * file-backed set is stored by place, so that a tile reads the few blocks
     * round it. Indices stay the list's (draw order, updates, activation);
     * whatever reads many shapes walks them in this order.
     */
    open val storageOrder: StorageOrder? get() = null

    fun retain() {
        while (true) {
            val count = references.get()
            check(count > 0) { "Geometry storage already closed" }
            if (references.compareAndSet(count, count + 1)) return
        }
    }

    fun release() {
        val remaining = references.decrementAndGet()
        check(remaining >= 0)
        if (remaining == 0) close()
    }

    protected open fun close() = Unit
}

/** Existing polygon/polyline storage, and small circle collections. */
@InternalMapConductorApi
internal class MemoryShapeTileStorage<T : Any> private constructor(
    private val items: Array<Any?>,
    private val boxes: Array<DoubleArray?>,
    override val maxStroke: Float,
    private val painter: ShapeTileRenderer.Painter<T>,
) : ShapeTileStorage<T>() {
    constructor(items: List<T?>, painter: ShapeTileRenderer.Painter<T>) : this(
        items.toTypedArray<Any?>(),
        Array(items.size) { items[it]?.let(painter::bbox) },
        items.maxOfOrNull { it?.let(painter::strokeWidth) ?: 0f } ?: 0f,
        painter,
    )

    override val size: Int get() = items.size

    @Suppress("UNCHECKED_CAST")
    override fun get(index: Int): T? = items.getOrNull(index) as T?

    override fun bbox(index: Int): DoubleArray? = boxes.getOrNull(index)

    override fun updated(updates: List<Pair<Int, T?>>): ShapeTileStorage<T> {
        val next = items.copyOf()
        val nextBoxes = boxes.copyOf()
        var widest = maxStroke
        for ((index, item) in updates) {
            if (index in next.indices) {
                next[index] = item
                nextBoxes[index] = item?.let(painter::bbox)
                if (item != null) widest = max(widest, painter.strokeWidth(item))
            }
        }
        return MemoryShapeTileStorage(next, nextBoxes, widest, painter)
    }
}

/** Stored position -> index ([indexAt]) and back ([positionOf]). */
@InternalMapConductorApi
internal class StorageOrder(
    val indexAt: IntArray,
) {
    val positionOf: IntArray =
        IntArray(indexAt.size).also { positions -> for (p in indexAt.indices) positions[indexAt[p]] = p }

    @InternalMapConductorApi
    companion object {
        /**
         * The indices `0 until size` sorted by [keyOf] (24-bit keys), ties in
         * index order: two stable 12-bit counting passes, since a comparison sort
         * of a nationwide set takes most of a second on a phone.
         */
        fun sortedBy(
            size: Int,
            keyOf: (Int) -> Int,
        ): StorageOrder {
            val keys = IntArray(size) { keyOf(it) and 0xffffff }
            var from = IntArray(size) { it }
            var to = IntArray(size)
            val counts = IntArray(4096)
            for (shift in intArrayOf(0, 12)) {
                counts.fill(0)
                for (i in 0 until size) counts[(keys[from[i]] ushr shift) and 4095]++
                var sum = 0
                for (b in 0 until 4096) {
                    val c = counts[b]
                    counts[b] = sum
                    sum += c
                }
                for (i in 0 until size) to[counts[(keys[from[i]] ushr shift) and 4095]++] = from[i]
                val swap = from
                from = to
                to = swap
            }
            return StorageOrder(from)
        }

        /**
         * The z12 cell of a point in Z-order (Morton), 24 bits: cells next to
         * each other mostly get keys next to each other. The same key as
         * js-sdk-core's `mortonCellKey` and ios-sdk-core's.
         */
        fun mortonCellKey(
            latitude: Double,
            longitude: Double,
        ): Int {
            val x = (longitude + 180.0) / 360.0
            val sin = kotlin.math.sin(latitude.coerceIn(-85.0, 85.0) * Math.PI / 180.0)
            val y = 0.5 - kotlin.math.ln((1 + sin) / (1 - sin)) / (4 * Math.PI)
            val cx = kotlin.math.floor(x * 4096).toInt().coerceIn(0, 4095)
            val cy = kotlin.math.floor(y * 4096).toInt().coerceIn(0, 4095)
            var key = 0
            for (bit in 0 until 12) {
                key = key or (((cx ushr bit) and 1) shl (2 * bit)) or (((cy ushr bit) and 1) shl (2 * bit + 1))
            }
            return key
        }
    }
}
