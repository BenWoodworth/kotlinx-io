@file:Suppress("LocalVariableName", "RedundantModalityModifier")

package kotlinx.io.core

import kotlinx.io.bits.*
import kotlinx.io.core.internal.*
import kotlinx.io.pool.*

/**
 * The default [Output] implementation.
 * @see flush
 * @see closeDestination
 */
@ExperimentalIoApi
abstract class AbstractOutput
internal constructor(
    private val headerSizeHint: Int,
    protected val pool: ObjectPool<ChunkBuffer>
) : Appendable, Output {
    constructor(pool: ObjectPool<ChunkBuffer> = ChunkBuffer.Pool) : this(0, pool)

    @Suppress("UNCHECKED_CAST", "DEPRECATION")
    @Deprecated("Use ChunkBuffer's pool instead", level = DeprecationLevel.ERROR)
    constructor(pool: ObjectPool<IoBuffer>) : this(pool as ObjectPool<ChunkBuffer>)

    /**
     * An implementation should write the whole [buffer] to the destination. It should never capture the [buffer] instance
     * longer than this method execution since it will be disposed after return.
     */
    protected abstract fun flush(buffer: Buffer)

    /**
     * An implementation should only close the destination.
     */
    protected abstract fun closeDestination()

    private var _head: ChunkBuffer? = null
    private var _tail: ChunkBuffer? = null

    internal val head: ChunkBuffer
        get() = _head ?: ChunkBuffer.Empty

    @PublishedApi
    @Deprecated("Will be removed in future releases.", level = DeprecationLevel.HIDDEN)
    internal val tail: ChunkBuffer
        get() {
            return prepareWriteHead(1)
        }

    @Deprecated("Will be removed. Override flush(buffer) properly.", level = DeprecationLevel.ERROR)
    protected var currentTail: ChunkBuffer
        get() = prepareWriteHead(1)
        set(newValue) {
            appendChain(newValue)
        }

    internal var tailMemory: Memory = Memory.Empty
    internal var tailPosition = 0
    internal var tailEndExclusive = 0
        private set

    private var tailInitialPosition = 0

    /**
     * Number of bytes buffered in the chain except the tail chunk
     */
    private var chainedSize: Int = 0

    internal inline val tailRemaining: Int get() = tailEndExclusive - tailPosition

    /**
     * Number of bytes currently buffered (pending).
     */
    protected final var _size: Int
        get() = chainedSize + (tailPosition - tailInitialPosition)
        @Deprecated("There is no need to update/reset this value anymore.")
        set(_) {
        }

    /**
     * Byte order (Endianness) to be used by future write functions calls on this builder instance. Doesn't affect any
     * previously written values. Note that [reset] doesn't change this value back to the default byte order.
     * @default [ByteOrder.BIG_ENDIAN]
     */
    @Deprecated(
        "This is no longer supported. All operations are big endian by default. Use readXXXLittleEndian " +
            "to read primitives in little endian",
        level = DeprecationLevel.ERROR
    )
    final override var byteOrder: ByteOrder = ByteOrder.BIG_ENDIAN
        set(value) {
            field = value
            if (value != ByteOrder.BIG_ENDIAN) {
                throw IllegalArgumentException(
                    "Only BIG_ENDIAN is supported. Use corresponding functions to read/write" +
                        "in the little endian"
                )
            }
        }

    final override fun flush() {
        flushChain()
    }

    private fun flushChain() {
        val oldTail = stealAll() ?: return

        try {
            oldTail.forEachChunk { chunk ->
                flush(chunk)
            }
        } finally {
            oldTail.releaseAll(pool)
        }
    }

    /**
     * Detach all chunks and cleanup all internal state so builder could be reusable again
     * @return a chain of buffer views or `null` of it is empty
     */
    internal fun stealAll(): ChunkBuffer? {
        val head = this._head ?: return null

        _tail?.commitWrittenUntilIndex(tailPosition)

        this._head = null
        this._tail = null
        tailPosition = 0
        tailEndExclusive = 0
        tailInitialPosition = 0
        chainedSize = 0
        tailMemory = Memory.Empty

        return head
    }

    internal final fun appendSingleChunk(buffer: ChunkBuffer) {
        check(buffer.next == null) { "It should be a single buffer chunk." }
        appendChainImpl(buffer, buffer, 0)
    }

    internal final fun appendChain(head: ChunkBuffer) {
        val tail = head.findTail()
        val chainedSizeDelta = (head.remainingAll() - tail.readRemaining).toIntOrFail("total size increase")
        appendChainImpl(head, head.findTail(), chainedSizeDelta)
    }

    private final fun appendChainImpl(head: ChunkBuffer, newTail: ChunkBuffer, chainedSizeDelta: Int) {
        val _tail = _tail
        if (_tail == null) {
            _head = head
            chainedSize = 0
        } else {
            _tail.next = head
            val tailPosition = tailPosition
            _tail.commitWrittenUntilIndex(tailPosition)
            chainedSize += tailPosition - tailInitialPosition
        }

        this._tail = newTail
        chainedSize += chainedSizeDelta
        tailMemory = newTail.memory
        tailPosition = newTail.writePosition
        tailInitialPosition = newTail.readPosition
        tailEndExclusive = newTail.limit
    }

    final override fun writeByte(v: Byte) {
        val index = tailPosition
        if (index < tailEndExclusive) {
            tailPosition = index + 1
            tailMemory[index] = v
            return
        }

        return writeByteFallback(v)
    }

    private fun writeByteFallback(v: Byte) {
        appendNewBuffer().writeByte(v)
        tailPosition++
    }

    /**
     * Should flush and close the destination
     */
    final override fun close() {
        try {
            flush()
        } finally {
            closeDestination() // TODO check what should be done here
        }
    }

    @Deprecated("Binary compatibility.", level = DeprecationLevel.HIDDEN)
    final override fun writeFully(src: ByteArray, offset: Int, length: Int) {
        (this as Output).writeFully(src, offset, length)
    }

    @Deprecated("Binary compatibility.", level = DeprecationLevel.HIDDEN)
    final override fun writeLong(v: Long) {
        (this as Output).writeLong(v)
    }

    @Deprecated("Binary compatibility.", level = DeprecationLevel.HIDDEN)
    final override fun writeInt(v: Int) {
        (this as Output).writeInt(v)
    }

    @Deprecated("Binary compatibility.", level = DeprecationLevel.HIDDEN)
    final override fun writeShort(v: Short) {
        (this as Output).writeShort(v)
    }

    @Deprecated("Binary compatibility.", level = DeprecationLevel.HIDDEN)
    final override fun writeDouble(v: Double) {
        (this as Output).writeDouble(v)
    }

    @Deprecated("Binary compatibility.", level = DeprecationLevel.HIDDEN)
    final override fun writeFloat(v: Float) {
        (this as Output).writeFloat(v)
    }

    @Deprecated("Binary compatibility.", level = DeprecationLevel.HIDDEN)
    override fun writeFully(src: ShortArray, offset: Int, length: Int) {
        (this as Output).writeFully(src, offset, length)
    }

    @Deprecated("Binary compatibility.", level = DeprecationLevel.HIDDEN)
    override fun writeFully(src: IntArray, offset: Int, length: Int) {
        (this as Output).writeFully(src, offset, length)
    }

    @Deprecated("Binary compatibility.", level = DeprecationLevel.HIDDEN)
    override fun writeFully(src: LongArray, offset: Int, length: Int) {
        (this as Output).writeFully(src, offset, length)
    }

    @Deprecated("Binary compatibility.", level = DeprecationLevel.HIDDEN)
    override fun writeFully(src: FloatArray, offset: Int, length: Int) {
        (this as Output).writeFully(src, offset, length)
    }

    @Deprecated("Binary compatibility.", level = DeprecationLevel.HIDDEN)
    override fun writeFully(src: DoubleArray, offset: Int, length: Int) {
        (this as Output).writeFully(src, offset, length)
    }

    @Suppress("DEPRECATION")
    @Deprecated("Binary compatibility.", level = DeprecationLevel.HIDDEN)
    override fun writeFully(src: IoBuffer, length: Int) {
        writeFully(src as Buffer, length)
    }

    fun writeFully(src: Buffer, length: Int) {
        (this as Output).writeFully(src, length)
    }

    @Deprecated("Binary compatibility.", level = DeprecationLevel.HIDDEN)
    override fun fill(n: Long, v: Byte) {
        (this as Output).fill(n, v)
    }

    /**
     * Append single UTF-8 character
     */
    override fun append(c: Char): AbstractOutput {
        write(3) {
            it.putUtf8Char(c.toInt())
        }
        return this
    }

    override fun append(csq: CharSequence?): AbstractOutput {
        if (csq == null) {
            appendChars("null", 0, 4)
        } else {
            appendChars(csq, 0, csq.length)
        }
        return this
    }

    override fun append(csq: CharSequence?, start: Int, end: Int): AbstractOutput {
        if (csq == null) {
            return append("null", start, end)
        }

        appendChars(csq, start, end)

        return this
    }

    /**
     * Writes another packet to the end. Please note that the instance [p] gets consumed so you don't need to release it
     */
    internal open fun writePacket(p: ByteReadPacket) {
        val foreignStolen = p.stealAll()
        if (foreignStolen == null) {
            p.release()
            return
        }

        val _tail = _tail
        if (_tail == null) {
            appendChain(foreignStolen)
            return
        }

        writePacketMerging(_tail, foreignStolen, p)
    }

    private fun writePacketMerging(tail: ChunkBuffer, foreignStolen: ChunkBuffer, p: ByteReadPacket) {
        tail.commitWrittenUntilIndex(tailPosition)

        val lastSize = tail.readRemaining
        val nextSize = foreignStolen.readRemaining

        // at first we evaluate if it is reasonable to merge chunks
        val maxCopySize = PACKET_MAX_COPY_SIZE
        val appendSize = if (nextSize < maxCopySize && nextSize <= (tail.endGap + tail.writeRemaining)) {
            nextSize
        } else -1

        val prependSize =
            if (lastSize < maxCopySize && lastSize <= foreignStolen.startGap && foreignStolen.isExclusivelyOwned()) {
                lastSize
            } else -1

        if (appendSize == -1 && prependSize == -1) {
            // simply enqueue if there is no reason to merge
            appendChain(foreignStolen)
        } else if (prependSize == -1 || appendSize <= prependSize) {
            // do append
            tail.writeBufferAppend(foreignStolen, tail.writeRemaining + tail.endGap)
            afterHeadWrite()
            foreignStolen.next?.let { next ->
                appendChain(next)
            }

            foreignStolen.release(p.pool)
        } else if (appendSize == -1 || prependSize < appendSize) {
            writePacketSlowPrepend(foreignStolen, tail)
        } else {
            throw IllegalStateException("prep = $prependSize, app = $appendSize")
        }
    }

    /**
     * Do prepend current [tail] to the beginning of [foreignStolen].
     */
    private fun writePacketSlowPrepend(foreignStolen: ChunkBuffer, tail: ChunkBuffer) {
        foreignStolen.writeBufferPrepend(tail)

        val _head = _head ?: error("head should't be null since it is already handled in the fast-path")
        if (_head === tail) {
            this._head = foreignStolen
        } else {
            // we need to fix next reference of the previous chunk before the tail
            // we have to traverse from the beginning to find it
            var pre = _head
            while (true) {
                val next = pre.next!!
                if (next === tail) break
                pre = next
            }

            pre.next = foreignStolen
        }

        tail.release(pool)

        this._tail = foreignStolen.findTail()
    }

    /**
     * Write exact [n] bytes from packet to the builder
     */
    fun writePacket(p: ByteReadPacket, n: Int) {
        var remaining = n

        while (remaining > 0) {
            val headRemaining = p.headRemaining
            if (headRemaining <= remaining) {
                remaining -= headRemaining
                appendSingleChunk(p.steal() ?: throw EOFException("Unexpected end of packet"))
            } else {
                p.read { view ->
                    writeFully(view, remaining)
                }
                break
            }
        }
    }

    /**
     * Write exact [n] bytes from packet to the builder
     */
    fun writePacket(p: ByteReadPacket, n: Long) {
        var remaining = n

        while (remaining > 0L) {
            val headRemaining = p.headRemaining.toLong()
            if (headRemaining <= remaining) {
                remaining -= headRemaining
                appendSingleChunk(p.steal() ?: throw EOFException("Unexpected end of packet"))
            } else {
                p.read { view ->
                    writeFully(view, remaining.toInt())
                }
                break
            }
        }
    }

    override fun append(csq: CharArray, start: Int, end: Int): Appendable {
        appendChars(csq, start, end)
        return this
    }

    private inline fun appendCharsTemplate(
        start: Int,
        end: Int,
        block: Buffer.(index: Int) -> Int
    ): Int {
        var idx = start
        if (idx >= end) return idx
        idx = prepareWriteHead(1).block(idx)
        afterHeadWrite()

        while (idx < end) {
            idx = appendNewBuffer().block(idx)
            afterHeadWrite()
        }

        return idx
    }

    private fun appendChars(csq: CharSequence, start: Int, end: Int): Int {
        return appendCharsTemplate(start, end) { idx -> appendChars(csq, idx, end) }
    }

    private fun appendChars(csq: CharArray, start: Int, end: Int): Int {
        return appendCharsTemplate(start, end) { idx -> appendChars(csq, idx, end) }
    }

    fun writeStringUtf8(s: String) {
        append(s, 0, s.length)
    }

    fun writeStringUtf8(cs: CharSequence) {
        append(cs, 0, cs.length)
    }

//    fun writeStringUtf8(cb: CharBuffer) {
//        append(cb, 0, cb.remaining())
//    }

    @Suppress("NOTHING_TO_INLINE")
    private inline fun Buffer.putUtf8Char(v: Int) = when {
        v in 1..0x7f -> {
            writeByte(v.toByte())
            1
        }
        v > 0x7ff -> {
            writeExact(3, "3 bytes character") { memory, offset ->
                memory[offset] = (0xe0 or ((v shr 12) and 0x0f)).toByte()
                memory[offset + 1] = (0x80 or ((v shr 6) and 0x3f)).toByte()
                memory[offset + 2] = (0x80 or (v and 0x3f)).toByte()
            }
            3
        }
        else -> {
            writeExact(2, "2 bytes character") { memory, offset ->
                memory[offset] = (0xc0 or ((v shr 6) and 0x1f)).toByte()
                memory[offset + 1] = (0x80 or (v and 0x3f)).toByte()
            }
            2
        }
    }

    /**
     * Release any resources that the builder holds. Builder shouldn't be used after release
     */
    final fun release() {
        close()
    }

    @DangerousInternalIoApi
    fun prepareWriteHead(n: Int): ChunkBuffer {
        if (tailRemaining >= n) {
            _tail?.let { return it }
        }
        return appendNewBuffer()
    }

    @DangerousInternalIoApi
    fun afterHeadWrite() {
        _tail?.let { tailPosition = it.writePosition }
    }

    @PublishedApi
    internal inline fun write(size: Int, block: (Buffer) -> Int) {
        val buffer = prepareWriteHead(size)
        try {
            check(block(buffer) >= 0) { "The returned value shouldn't be negative" }
        } finally {
            afterHeadWrite()
        }
    }

    @PublishedApi
    @Deprecated("There is no need to do that anymore.", level = DeprecationLevel.HIDDEN)
    internal fun addSize(n: Int) {
        check(n >= 0) { "It should be non-negative size increment: $n" }
        check(n <= tailRemaining) { "Unable to mark more bytes than available: $n > $tailRemaining" }

        // For binary compatibility we need to update pointers
        tailPosition += n
    }

    @Suppress("DEPRECATION")
    @Deprecated("Binary compatibility.", level = DeprecationLevel.HIDDEN)
    internal open fun last(buffer: IoBuffer) {
        appendSingleChunk(buffer as ChunkBuffer)
    }

    @PublishedApi
    internal fun appendNewBuffer(): ChunkBuffer {
        val new = pool.borrow()
        new.reserveEndGap(Buffer.ReservedSize)

        appendSingleChunk(new)

        return new
    }
}