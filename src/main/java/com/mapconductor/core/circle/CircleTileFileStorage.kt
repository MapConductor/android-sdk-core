package com.mapconductor.core.circle

import com.mapconductor.core.InternalMapConductorApi
import com.mapconductor.core.shapetile.ShapeTileStorage
import com.mapconductor.core.shapetile.StorageOrder
import java.io.EOFException
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max

/**
 * Packed immutable circle geometry, read through a bounded block cache.
 * No nationwide TileCircle or bounding-box object arrays are retained.
 * Small updates share the file; a bounded override map is compacted when full.
 *
 * With an [order], the records are stored by place ([storageOrder]): a tile's
 * circles then lie in a few blocks instead of most of the file. Everything
 * here still takes the list's indices.
 */
@InternalMapConductorApi
internal class CircleTileFileStorage private constructor(
    private val file: SharedFile,
    override val size: Int,
    override val maxStroke: Float,
    private val order: StorageOrder?,
    private val overrides: Map<Int, TileCircle?> = emptyMap(),
) : ShapeTileStorage<TileCircle>() {
    init {
        file.retain()
    }

    internal val cacheFile: File get() = file.path
    internal val cachedBytes: Int get() = file.cachedBytes
    internal val allocatedReadBuffers: Int get() = file.allocatedReadBuffers

    override val storageOrder: StorageOrder? get() = order

    private fun positionOf(index: Int): Int = order?.positionOf?.get(index) ?: index

    override fun get(index: Int): TileCircle? =
        when {
            index !in 0 until size -> null
            overrides.isNotEmpty() && overrides.containsKey(index) -> overrides[index]
            else -> file.read(positionOf(index)) { bytes, offset -> decode(bytes, offset) }
        }

    /**
     * Calls [action] for each of [indices], with the record's bytes and offset
     * in them, or with the overriding circle (bytes null). Records of one block
     * are read under one lock, not one per circle: a nationwide tile reads
     * hundreds of thousands, on several tile threads at once.
     *
     * The calls come in storage order, which is [indices]' order only without
     * an [order]: `k` says which of [indices] each one is.
     */
    internal fun forEachRecord(
        indices: IntArray,
        checkCancelled: () -> Unit,
        action: RecordAction,
    ) {
        // The block is copied out under the lock and read outside it: other
        // tiles read the same file on other threads, and must not wait while
        // this one records its circles.
        val scratch = ByteBuffer.allocate(BLOCK_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        // Each one's stored position (high half) and k (low half), in storage order.
        val sorted = LongArray(indices.size)
        var count = 0
        for (k in indices.indices) {
            val index = indices[k]
            if (index !in 0 until size) continue
            if (overrides.isNotEmpty() && overrides.containsKey(index)) {
                action.record(k, null, 0, overrides[index])
                continue
            }
            sorted[count++] = (positionOf(index).toLong() shl 32) or k.toLong()
        }
        if (order != null) sorted.sort(0, count)
        var at = 0
        while (at < count) {
            checkCancelled()
            val block = (sorted[at] ushr 32).toInt() / RECORDS_PER_BLOCK
            file.copyBlock(block, scratch)
            while (at < count) {
                val position = (sorted[at] ushr 32).toInt()
                if (position / RECORDS_PER_BLOCK != block) break
                action.record(sorted[at].toInt(), scratch, position % RECORDS_PER_BLOCK * RECORD_BYTES, null)
                at++
            }
        }
    }

    /** What [forEachRecord] calls per circle; primitive arguments, so nothing is boxed per call. */
    internal fun interface RecordAction {
        /** [bytes] at [offset] is the record, or [bytes] is null and [override] the circle. */
        fun record(
            k: Int,
            bytes: ByteBuffer?,
            offset: Int,
            override: TileCircle?,
        )
    }

    override fun bbox(index: Int): DoubleArray? =
        when {
            index !in 0 until size -> null
            overrides.isNotEmpty() && overrides.containsKey(index) -> overrides[index]?.bbox
            else ->
                file.read(positionOf(index)) { bytes, p ->
                    if (bytes.getInt(p + 76) and 1 == 0) {
                        null
                    } else {
                        val x = bytes.getDouble(p + 24)
                        val half = bytes.getDouble(p + 40)
                        doubleArrayOf(x - half, bytes.getDouble(p + 48), x + half, bytes.getDouble(p + 56))
                    }
                }
        }

    override fun updated(updates: List<Pair<Int, TileCircle?>>): ShapeTileStorage<TileCircle> {
        var widest = maxStroke
        for ((index, item) in updates) {
            if (index in 0 until size && item != null) {
                widest = max(widest, CircleTilePainter.strokeWidth(item))
            }
        }
        val changed = HashMap(overrides)
        var compact = false
        for ((index, item) in updates) if (index in 0 until size) {
            if (index !in changed && changed.size == MAX_OVERRIDES) {
                compact = true
                break
            }
            changed[index] = item
        }
        if (!compact) return CircleTileFileStorage(file, size, widest, order, changed)
        // Compact by streaming, never by materialising every decoded circle.
        val next = create(file.path.parentFile!!, size, { get(it) }, order = order)
        try {
            next.file.patch(updates.map { (index, item) -> positionOf(index) to item }, size)
            val result = CircleTileFileStorage(next.file, size, widest, order)
            next.release()
            return result
        } catch (error: Throwable) {
            next.release()
            throw error
        }
    }

    override fun close() = file.release()

    @InternalMapConductorApi
    private class SharedFile(
        val path: File,
    ) {
        private val handle = RandomAccessFile(path, "rw")
        private val channel = handle.channel
        private var references = 0
        private val blocks = LinkedHashMap<Int, ByteBuffer>(MAX_BLOCKS, 0.75f, true)
        var allocatedReadBuffers = 0
            private set
        val cachedBytes: Int get() = synchronized(this) { blocks.values.sumOf { it.capacity() } }

        @Synchronized fun retain() {
            references++
        }

        @Synchronized fun release() {
            check(--references >= 0)
            if (references == 0) dispose()
        }

        @Synchronized fun dispose() {
            try {
                handle.close()
            } finally {
                blocks.clear()
                synchronized(liveFiles) {
                    liveFiles.remove(path.absolutePath)
                    path.delete()
                }
            }
        }

        /** Copies [block]'s bytes into [into] (its whole capacity, from 0). */
        @Synchronized fun copyBlock(
            block: Int,
            into: ByteBuffer,
        ) {
            val bytes = blockBytes(block)
            into.clear()
            into.put(bytes.array(), 0, bytes.limit())
            into.flip()
        }

        @Synchronized fun <R> read(
            index: Int,
            action: (ByteBuffer, Int) -> R,
        ): R {
            val block = index / RECORDS_PER_BLOCK
            return action(blockBytes(block), index % RECORDS_PER_BLOCK * RECORD_BYTES)
        }

        /** The bytes of [block], read into the cache if they are not there. Call with the lock held. */
        private fun blockBytes(block: Int): ByteBuffer {
            val bytes =
                blocks[block] ?: run {
                    val buffer = if (blocks.size < MAX_BLOCKS) {
                        allocatedReadBuffers++
                        ByteBuffer.allocate(BLOCK_BYTES).order(ByteOrder.LITTLE_ENDIAN)
                    } else {
                        // Reuse the eldest block's bytes. Allocating another
                        // 40 KiB array at every miss churns Android's large-object heap.
                        val iterator = blocks.entries.iterator()
                        val reusable = iterator.next().value
                        iterator.remove()
                        reusable.clear()
                        reusable
                    }
                    val start = HEADER_BYTES + block.toLong() * BLOCK_BYTES
                    val count = minOf(BLOCK_BYTES.toLong(), channel.size() - start).toInt()
                    if (count < RECORD_BYTES) throw EOFException("Truncated circle cache")
                    buffer.limit(count)
                    var at = start
                    while (buffer.hasRemaining()) {
                        val n = channel.read(buffer, at)
                        if (n <= 0) throw EOFException("Truncated circle cache")
                        at += n
                    }
                    buffer.flip()
                    blocks[block] = buffer
                    buffer
                }
            return bytes
        }

        fun write(
            size: Int,
            itemAt: (Int) -> TileCircle?,
            order: StorageOrder?,
            checkCancelled: () -> Unit,
        ): Float {
            val header =
                ByteBuffer
                    .allocate(HEADER_BYTES)
                    .order(ByteOrder.LITTLE_ENDIAN)
                    .putInt(MAGIC)
                    .putInt(1)
                    .putInt(size)
                    .putInt(RECORD_BYTES)
            header.flip()
            while (header.hasRemaining()) channel.write(header)
            val bytes = ByteBuffer.allocate(BLOCK_BYTES).order(ByteOrder.LITTLE_ENDIAN)
            var widest = 0f
            for (position in 0 until size) {
                if (position % 4096 == 0) checkCancelled()
                val item = itemAt(order?.indexAt?.get(position) ?: position)
                encode(bytes, bytes.position(), item)
                bytes.position(bytes.position() + RECORD_BYTES)
                if (item != null) widest = max(widest, CircleTilePainter.strokeWidth(item))
                if (!bytes.hasRemaining()) {
                    bytes.flip()
                    while (bytes.hasRemaining()) channel.write(bytes)
                    bytes.clear()
                }
            }
            bytes.flip()
            while (bytes.hasRemaining()) channel.write(bytes)
            return widest
        }

        /** [updates] by stored position. */
        @Synchronized fun patch(
            updates: List<Pair<Int, TileCircle?>>,
            size: Int,
        ) {
            check(references == 1) // Only an unpublished freshly compacted file may be written.
            val bytes = ByteBuffer.allocate(RECORD_BYTES).order(ByteOrder.LITTLE_ENDIAN)
            for ((position, item) in updates) {
                if (position in 0 until size) {
                    bytes.clear()
                    encode(bytes, 0, item)
                    var at = HEADER_BYTES + position.toLong() * RECORD_BYTES
                    while (bytes.hasRemaining()) {
                        val n = channel.write(bytes, at)
                        check(n > 0)
                        at += n
                    }
                }
            }
            blocks.clear()
        }
    }

    @InternalMapConductorApi
    companion object {
        private const val MAGIC = 0x5243434d // MCCR, little endian
        private const val HEADER_BYTES = 16
        private const val RECORD_BYTES = 80
        private const val RECORDS_PER_BLOCK = 512
        private const val BLOCK_BYTES = RECORD_BYTES * RECORDS_PER_BLOCK
        internal const val MAX_BLOCKS = 48 // At most 1.875 MiB per open geometry file.
        private const val MAX_OVERRIDES = 2048
        private val liveFiles = HashSet<String>()

        /** [order]: the order to store them in (see the class); index order when null. */
        fun create(
            directory: File,
            size: Int,
            itemAt: (Int) -> TileCircle?,
            checkCancelled: () -> Unit = {
            },
            order: StorageOrder? = null,
        ): CircleTileFileStorage {
            require(order == null || order.indexAt.size == size)
            require(size >= 0)
            val path =
                synchronized(liveFiles) {
                    check(directory.isDirectory || directory.mkdirs()) { "Cannot create circle cache directory" }
                    // These are disposable private cache files, not user data. Open
                    // files from this process stay registered until their last reader exits.
                    directory
                        .listFiles()
                        ?.filter {
                            it.name.startsWith("circle-geometry-") &&
                                it.name.endsWith(".bin") &&
                                it.absolutePath !in liveFiles
                        }?.forEach { it.delete() }
                    File.createTempFile("circle-geometry-", ".bin", directory).also { liveFiles.add(it.absolutePath) }
                }
            var file: SharedFile? = null
            try {
                file = SharedFile(path)
                val widest = file.write(size, itemAt, order, checkCancelled)
                return CircleTileFileStorage(file, size, widest, order)
            } catch (error: Throwable) {
                if (file !=
                    null
                ) {
                    file.dispose()
                } else {
                    synchronized(liveFiles) {
                        liveFiles.remove(path.absolutePath)
                        path.delete()
                    }
                }
                throw error
            }
        }

        private fun encode(
            bytes: ByteBuffer,
            p: Int,
            item: TileCircle?,
        ) {
            if (item == null) {
                bytes.putInt(p + 76, 0)
                return
            }
            bytes
                .putDouble(p, item.latitude)
                .putDouble(p + 8, item.longitude)
                .putDouble(p + 16, item.radiusMeters)
                .putDouble(p + 24, item.x)
                .putDouble(p + 32, item.y)
                .putDouble(p + 40, item.halfWidth)
                .putDouble(p + 48, item.northY)
                .putDouble(p + 56, item.southY)
                .putInt(p + 64, item.fill)
                .putInt(p + 68, item.stroke)
                .putFloat(p + 72, item.strokeWidth)
                .putInt(p + 76, if (item.geodesic) 3 else 1)
        }

        private fun decode(
            bytes: ByteBuffer,
            p: Int,
        ): TileCircle? {
            val flags = bytes.getInt(p + 76)
            if (flags and 1 == 0) return null
            val x = bytes.getDouble(p + 24)
            val half = bytes.getDouble(p + 40)
            val north = bytes.getDouble(p + 48)
            val south = bytes.getDouble(p + 56)
            return TileCircle(
                bytes.getDouble(p), bytes.getDouble(p + 8), bytes.getDouble(p + 16), flags and 2 != 0,
                x, bytes.getDouble(p + 32), half, north, south, doubleArrayOf(x - half, north, x + half, south),
                bytes.getInt(p + 64), bytes.getInt(p + 68), bytes.getFloat(p + 72),
            )
        }
    }
}
