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
