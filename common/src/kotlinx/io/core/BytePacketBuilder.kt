@file:Suppress("RedundantModalityModifier")

package kotlinx.io.core

import kotlinx.io.bits.Memory
import kotlinx.io.core.internal.*
import kotlinx.io.core.internal.require
import kotlinx.io.pool.*
import kotlin.Boolean
import kotlin.Char
import kotlin.CharSequence
import kotlin.Deprecated
import kotlin.DeprecationLevel
import kotlin.Int
import kotlin.PublishedApi
import kotlin.Suppress
import kotlin.check

/**
 * A builder that provides ability to build byte packets with no knowledge of it's size.
 * Unlike Java's ByteArrayOutputStream it doesn't copy the whole content every time it's internal buffer overflows
 * but chunks buffers instead. Packet building via [build] function is O(1) operation and only does instantiate
 * a new [ByteReadPacket]. Once a byte packet has been built via [build] function call, the builder could be
 * reused again. You also can discard all written bytes via [reset] or [release]. Please note that an instance of
 * builder need to be terminated either via [build] function invocation or via [release] call otherwise it will
 * cause byte buffer leak so that may have performance impact.
 *
 * Byte packet builder is also an [Appendable] so it does append UTF-8 characters to a packet
 *
 * ```
 * buildPacket {
 *     listOf(1,2,3).joinTo(this, separator = ",")
 * }
 * ```
 */
class BytePacketBuilder(private var headerSizeHint: Int = 0, pool: ObjectPool<ChunkBuffer>) :
    @Suppress("DEPRECATION_ERROR") BytePacketBuilderPlatformBase(pool) {
    init {
        require(headerSizeHint >= 0) { "shouldn't be negative: headerSizeHint = $headerSizeHint" }
    }

    /**
     * Number of bytes written to the builder after the creation or the last reset.
     */
    val size: Int
        get() = _size

    /**
     * If no bytes were written or the builder has been reset.
     */
    val isEmpty: Boolean
        get() = _size == 0

    /**
     * If at least one byte was written after the creation or the last reset.
     */
    val isNotEmpty: Boolean
        get() = _size > 0

    @PublishedApi
    internal val _pool: ObjectPool<ChunkBuffer>
        get() = pool

    /**
     * Does nothing for memory-backed output
     */
    final override fun closeDestination() {
    }

    /**
     * Does nothing for memory-backed output
     */
    final override fun flush(source: Memory, offset: Int, length: Int) {
    }

    override fun append(c: Char): BytePacketBuilder {
        return super.append(c) as BytePacketBuilder
    }

    override fun append(csq: CharSequence?): BytePacketBuilder {
        return super.append(csq) as BytePacketBuilder
    }

    override fun append(csq: CharSequence?, start: Int, end: Int): BytePacketBuilder {
        return super.append(csq, start, end) as BytePacketBuilder
    }

    /**
     * Creates a temporary packet view of the packet being build without discarding any bytes from the builder.
     * This is similar to `build().copy()` except that the builder keeps already written bytes untouched.
     * A temporary view packet is passed as argument to [block] function and it shouldn't leak outside of this block
     * otherwise an unexpected behaviour may occur.
     */
    @Suppress("unused")
    @Deprecated("Binary compatibility.", level = DeprecationLevel.HIDDEN)
    fun <R> preview(block: (tmp: ByteReadPacket) -> R): R {
        return preview(block)
    }

    /**
     * Builds byte packet instance and resets builder's state to be able to build another one packet if needed
     */
    fun build(): ByteReadPacket {
        val size = size
        val head = stealAll()

        return when (head) {
            null -> ByteReadPacket.Empty
            else -> ByteReadPacket(head, size.toLong(), pool)
        }
    }

    /**
     * Discard all written bytes and prepare to build another packet.
     */
    final fun reset() {
        release()
    }

    @PublishedApi
    @Deprecated("Binary compatibility.", level = DeprecationLevel.HIDDEN)
    @Suppress("unused")
    internal fun preview(): ByteReadPacket {
        return preview()
    }

    internal fun afterBytesStolen() {
        val head = head
        if (head !== ChunkBuffer.Empty) {
            check(head.next == null)
            head.resetForWrite()
            head.reserveStartGap(headerSizeHint)
            head.reserveEndGap(Buffer.ReservedSize)
        }
    }

    override fun toString(): String {
        return "BytePacketBuilder($size bytes written)"
    }
}
