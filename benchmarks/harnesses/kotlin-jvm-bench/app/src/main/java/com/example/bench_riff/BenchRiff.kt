package com.example.bench_boltffi

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

class FfiException(val code: Int, message: String) : Exception(message)

class BoltFFIException(val errorBuffer: ByteBuffer) : Exception("Structured error") {
    init {
        errorBuffer.order(ByteOrder.nativeOrder())
    }
}

private inline fun <T> useNativeBuffer(buffer: ByteBuffer, block: (ByteBuffer) -> T): T {
    buffer.order(ByteOrder.LITTLE_ENDIAN)
    return try {
        block(buffer)
    } finally {
        Native.boltffi_free_native_buffer(buffer)
    }
}

private const val RIFF_FUTURE_POLL_READY: Byte = 0
private const val RIFF_FUTURE_POLL_WAKE: Byte = 1

internal class BoltFFIHandleMap<T: Any> {
    private val map = ConcurrentHashMap<Long, T>()
    private val counter = AtomicLong(1)

    fun insert(obj: T): Long {
        val handle = counter.getAndAdd(2)
        map[handle] = obj
        return handle
    }

    fun remove(handle: Long): T =
        map.remove(handle) ?: throw IllegalStateException("BoltFFIHandleMap: invalid handle $handle")

    fun get(handle: Long): T =
        map[handle] ?: throw IllegalStateException("BoltFFIHandleMap: invalid handle $handle")
}

private val riffContinuationMap = BoltFFIHandleMap<CancellableContinuation<Byte>>()

internal fun riffFutureContinuationCallback(handle: Long, pollResult: Byte) {
    try {
        riffContinuationMap.remove(handle).resume(pollResult)
    } catch (e: Exception) {
    }
}

internal suspend inline fun <T> riffCallAsync(
    crossinline createFuture: () -> Long,
    crossinline poll: (Long, Long) -> Unit,
    crossinline complete: (Long) -> T,
    crossinline free: (Long) -> Unit,
    crossinline cancel: (Long) -> Unit
): T {
    val rustFuture = createFuture()
    try {
        var pollResult: Byte
        do {
            pollResult = suspendCancellableCoroutine<Byte> { continuation ->
                continuation.invokeOnCancellation { cancel(rustFuture) }
                poll(rustFuture, riffContinuationMap.insert(continuation))
            }
        } while (pollResult != RIFF_FUTURE_POLL_READY)
        return complete(rustFuture)
    } finally {
        free(rustFuture)
    }
}

class WireBuffer private constructor(private val buffer: ByteBuffer) {
    companion object {
        fun fromByteBuffer(buf: ByteBuffer): WireBuffer =
            WireBuffer(buf.slice().order(ByteOrder.LITTLE_ENDIAN))
    }

    fun readBool(offset: Int): Boolean = buffer.get(offset) != 0.toByte()
    fun readI8(offset: Int): Byte = buffer.get(offset)
    fun readU8(offset: Int): UByte = buffer.get(offset).toUByte()
    fun readI16(offset: Int): Short = buffer.getShort(offset)
    fun readU16(offset: Int): UShort = buffer.getShort(offset).toUShort()
    fun readI32(offset: Int): Int = buffer.getInt(offset)
    fun readU32(offset: Int): UInt = buffer.getInt(offset).toUInt()
    fun readI64(offset: Int): Long = buffer.getLong(offset)
    fun readU64(offset: Int): ULong = buffer.getLong(offset).toULong()
    fun readF32(offset: Int): Float = buffer.getFloat(offset)
    fun readF64(offset: Int): Double = buffer.getDouble(offset)

    fun readString(offset: Int): Pair<String, Int> {
        val len = readU32(offset).toInt()
        val bytes = ScratchBytes.acquire(len)
        val view = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        view.position(offset + 4)
        view.get(bytes, 0, len)
        return String(bytes, 0, len, Charsets.UTF_8) to (4 + len)
    }

    fun readBytes(offset: Int): Pair<ByteArray, Int> {
        val len = readU32(offset).toInt()
        val bytes = ByteArray(len)
        val view = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        view.position(offset + 4)
        view.get(bytes)
        return bytes to (4 + len)
    }

    fun readByteArray(offset: Int): Pair<ByteArray, Int> = readBytes(offset)

    fun readShortArray(offset: Int): Pair<ShortArray, Int> {
        val count = readU32(offset).toInt()
        val bytes = (count.toLong() * 2L).toInt()
        val arr = ShortArray(count)
        val view = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        view.position(offset + 4)
        view.limit(offset + 4 + bytes)
        view.slice().order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(arr)
        return arr to (4 + bytes)
    }

    fun readIntArray(offset: Int): Pair<IntArray, Int> {
        val count = readU32(offset).toInt()
        val bytes = (count.toLong() * 4L).toInt()
        val arr = IntArray(count)
        val view = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        view.position(offset + 4)
        view.limit(offset + 4 + bytes)
        view.slice().order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().get(arr)
        return arr to (4 + bytes)
    }

    fun readLongArray(offset: Int): Pair<LongArray, Int> {
        val count = readU32(offset).toInt()
        val bytes = (count.toLong() * 8L).toInt()
        val arr = LongArray(count)
        val view = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        view.position(offset + 4)
        view.limit(offset + 4 + bytes)
        view.slice().order(ByteOrder.LITTLE_ENDIAN).asLongBuffer().get(arr)
        return arr to (4 + bytes)
    }

    fun readFloatArray(offset: Int): Pair<FloatArray, Int> {
        val count = readU32(offset).toInt()
        val bytes = (count.toLong() * 4L).toInt()
        val arr = FloatArray(count)
        val view = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        view.position(offset + 4)
        view.limit(offset + 4 + bytes)
        view.slice().order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(arr)
        return arr to (4 + bytes)
    }

    fun readDoubleArray(offset: Int): Pair<DoubleArray, Int> {
        val count = readU32(offset).toInt()
        val bytes = (count.toLong() * 8L).toInt()
        val arr = DoubleArray(count)
        val view = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        view.position(offset + 4)
        view.limit(offset + 4 + bytes)
        view.slice().order(ByteOrder.LITTLE_ENDIAN).asDoubleBuffer().get(arr)
        return arr to (4 + bytes)
    }

    fun readBooleanArray(offset: Int): Pair<BooleanArray, Int> {
        val count = readU32(offset).toInt()
        val arr = BooleanArray(count) { i -> readBool(offset + 4 + i) }
        return arr to (4 + count)
    }

    inline fun <T> readNullable(offset: Int, reader: (Int) -> Pair<T, Int>): Pair<T?, Int> {
        return if (readU8(offset).toInt() == 0) {
            null to 1
        } else {
            val (value, size) = reader(offset + 1)
            value to (1 + size)
        }
    }

    inline fun <T> readList(offset: Int, reader: (Int) -> Pair<T, Int>): Pair<List<T>, Int> {
        val count = readU32(offset).toInt()
        var pos = offset + 4
        val result = ArrayList<T>(count)
        repeat(count) {
            val (item, size) = reader(pos)
            result.add(item)
            pos += size
        }
        return result to (pos - offset)
    }

    inline fun <reified T, reified E : Throwable> readResult(
        offset: Int,
        okReader: (Int) -> Pair<T, Int>,
        errReader: (Int) -> Pair<E, Int>
    ): Pair<Result<T>, Int> {
        val tag = readU8(offset).toInt()
        return if (tag == 0) {
            val (ok, size) = okReader(offset + 1)
            Result.success<T>(ok) to (1 + size)
        } else {
            val (err, size) = errReader(offset + 1)
            Result.failure<T>(err) to (1 + size)
        }
    }

}

private object Utf8Codec {
    fun maxBytes(value: String): Int = value.length * 3
}

private object ScratchBytes {
    private val scratch: ThreadLocal<ByteArray> = ThreadLocal.withInitial { ByteArray(256) }

    fun acquire(requiredCapacity: Int): ByteArray {
        val bytes = scratch.get()
        return if (bytes.size >= requiredCapacity) {
            bytes
        } else {
            ByteArray(requiredCapacity).also { scratch.set(it) }
        }
    }
}

class WireWriter(initialCapacity: Int = 256) {
    private var buffer: ByteBuffer = ByteBuffer.allocateDirect(initialCapacity).order(ByteOrder.LITTLE_ENDIAN)
    private var pos: Int = 0

    internal fun reset(requiredCapacity: Int) {
        if (buffer.capacity() < requiredCapacity) {
            buffer = ByteBuffer.allocateDirect(requiredCapacity).order(ByteOrder.LITTLE_ENDIAN)
        }
        pos = 0
    }

    internal fun asDirectBuffer(): ByteBuffer {
        val view = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        view.limit(pos)
        view.position(0)
        return view.slice().order(ByteOrder.LITTLE_ENDIAN)
    }

    private fun ensureCapacity(needed: Int) {
        val required = pos + needed
        if (required <= buffer.capacity()) return
        val nextCapacity = maxOf(buffer.capacity() * 2, required)
        val next = ByteBuffer.allocateDirect(nextCapacity).order(ByteOrder.LITTLE_ENDIAN)
        val source = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        source.limit(pos)
        source.position(0)
        next.put(source)
        buffer = next
    }

    internal inline fun writeRawBytes(byteCount: Int, writer: (ByteBuffer, Int) -> Unit) {
        ensureCapacity(byteCount)
        val baseOffset = pos
        writer(buffer, baseOffset)
        pos = baseOffset + byteCount
    }

    fun writeBool(v: Boolean) { ensureCapacity(1); buffer.put(pos, if (v) 1 else 0); pos += 1 }
    fun writePadding(count: Int) {
        ensureCapacity(count)
        val view = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        view.position(pos)
        repeat(count) { view.put(0) }
        pos += count
    }
    fun writeI8(v: Byte) { ensureCapacity(1); buffer.put(pos, v); pos += 1 }
    fun writeU8(v: UByte) { ensureCapacity(1); buffer.put(pos, v.toByte()); pos += 1 }

    fun writeI16(v: Short) { ensureCapacity(2); buffer.putShort(pos, v); pos += 2 }
    fun writeU16(v: UShort) = writeI16(v.toShort())

    fun writeI32(v: Int) { ensureCapacity(4); buffer.putInt(pos, v); pos += 4 }
    fun writeU32(v: UInt) = writeI32(v.toInt())

    fun writeI64(v: Long) { ensureCapacity(8); buffer.putLong(pos, v); pos += 8 }
    fun writeU64(v: ULong) = writeI64(v.toLong())

    fun writeF32(v: Float) = writeI32(java.lang.Float.floatToRawIntBits(v))
    fun writeF64(v: Double) = writeI64(java.lang.Double.doubleToRawLongBits(v))

    fun writeString(v: String) {
        val bytes = v.toByteArray(Charsets.UTF_8)
        writeU32(bytes.size.toUInt())
        ensureCapacity(bytes.size)
        val view = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        view.position(pos)
        view.put(bytes)
        pos += bytes.size
    }

    fun writeBytes(v: ByteArray) {
        writeU32(v.size.toUInt())
        ensureCapacity(v.size)
        val view = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        view.position(pos)
        view.put(v)
        pos += v.size
    }

    fun writePrimitiveList(v: IntArray) {
        writeU32(v.size.toUInt())
        val bytes = v.size * 4
        writeRawBytes(bytes) { buf, baseOffset ->
            val view = buf.duplicate().order(ByteOrder.LITTLE_ENDIAN)
            view.position(baseOffset)
            view.limit(baseOffset + bytes)
            view.slice().order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().put(v)
        }
    }

    fun writePrimitiveList(v: LongArray) {
        writeU32(v.size.toUInt())
        val bytes = v.size * 8
        writeRawBytes(bytes) { buf, baseOffset ->
            val view = buf.duplicate().order(ByteOrder.LITTLE_ENDIAN)
            view.position(baseOffset)
            view.limit(baseOffset + bytes)
            view.slice().order(ByteOrder.LITTLE_ENDIAN).asLongBuffer().put(v)
        }
    }

    fun writePrimitiveList(v: FloatArray) {
        writeU32(v.size.toUInt())
        val bytes = v.size * 4
        writeRawBytes(bytes) { buf, baseOffset ->
            val view = buf.duplicate().order(ByteOrder.LITTLE_ENDIAN)
            view.position(baseOffset)
            view.limit(baseOffset + bytes)
            view.slice().order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().put(v)
        }
    }

    fun writePrimitiveList(v: DoubleArray) {
        writeU32(v.size.toUInt())
        val bytes = v.size * 8
        writeRawBytes(bytes) { buf, baseOffset ->
            val view = buf.duplicate().order(ByteOrder.LITTLE_ENDIAN)
            view.position(baseOffset)
            view.limit(baseOffset + bytes)
            view.slice().order(ByteOrder.LITTLE_ENDIAN).asDoubleBuffer().put(v)
        }
    }

    fun writePrimitiveList(v: ShortArray) {
        writeU32(v.size.toUInt())
        val bytes = v.size * 2
        writeRawBytes(bytes) { buf, baseOffset ->
            val view = buf.duplicate().order(ByteOrder.LITTLE_ENDIAN)
            view.position(baseOffset)
            view.limit(baseOffset + bytes)
            view.slice().order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(v)
        }
    }

    fun writePrimitiveList(v: ByteArray) {
        writeU32(v.size.toUInt())
        ensureCapacity(v.size)
        val view = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        view.position(pos)
        view.put(v)
        pos += v.size
    }

    fun writePrimitiveList(v: BooleanArray) {
        writeU32(v.size.toUInt())
        v.forEach { writeBool(it) }
    }

    @JvmName("writeIntList")
    fun writePrimitiveList(v: List<Int>) {
        writeU32(v.size.toUInt())
        v.forEach { writeI32(it) }
    }

    @JvmName("writeLongList")
    fun writePrimitiveList(v: List<Long>) {
        writeU32(v.size.toUInt())
        v.forEach { writeI64(it) }
    }

    inline fun <reified T> writeBlittable(v: T) {
        when (v) {
            is Byte -> writeI8(v)
            is Short -> writeI16(v)
            is Int -> writeI32(v)
            is Long -> writeI64(v)
            is Float -> writeF32(v)
            is Double -> writeF64(v)
            is Boolean -> writeBool(v)
            else -> throw IllegalArgumentException("Cannot write blittable: ${T::class}")
        }
    }

    inline fun <reified T> writeBlittableList(v: List<T>) {
        writeU32(v.size.toUInt())
        v.forEach { item -> writeBlittable(item) }
    }
}

private const val MAX_CACHED_WIRE_WRITER_BYTES: Int = 1024 * 1024

internal class WireWriterPoolState(private val cacheSize: Int = 4) {
    private val cachedWriters: Array<WireWriter?> = arrayOfNulls(cacheSize)
    private var depth: Int = 0

    fun acquire(requiredCapacity: Int): BorrowedWireWriter {
        val slot = depth
        depth = slot + 1
        val shouldCache = requiredCapacity <= MAX_CACHED_WIRE_WRITER_BYTES && slot < cacheSize
        val writer = if (shouldCache) {
            cachedWriters[slot] ?: WireWriter(requiredCapacity).also { cachedWriters[slot] = it }
        } else {
            WireWriter(requiredCapacity)
        }

        writer.reset(requiredCapacity)
        return BorrowedWireWriter(this, writer)
    }

    fun release() {
        depth -= 1
    }
}

internal class BorrowedWireWriter(
    private val state: WireWriterPoolState,
    internal val writer: WireWriter
) : AutoCloseable {
    internal val buffer: ByteBuffer
        get() = writer.asDirectBuffer()

    override fun close() {
        state.release()
    }
}

internal object WireWriterPool {
    private val state: ThreadLocal<WireWriterPoolState> = ThreadLocal.withInitial { WireWriterPoolState() }

    fun acquire(requiredCapacity: Int): BorrowedWireWriter = state.get().acquire(requiredCapacity)
}

enum class Direction(val value: Int) {
    NORTH(0),
    EAST(1),
    SOUTH(2),
    WEST(3);

    companion object {
        fun fromValue(value: Int): Direction = entries.first { it.value == value }
    }
}

sealed class ApiResult {
    data object Success : ApiResult()
    data class ErrorCode(val value0: Int) : ApiResult()
    data class ErrorWithData(val code: Int, val detail: Int) : ApiResult()

    companion object {
        fun decode(wire: WireBuffer, offset: Int): Pair<ApiResult, Int> {
            var pos = offset
            val tag = wire.readI32(pos); pos += 4
            return when (tag) {
                0 -> {
                    Success to (pos - offset)
                }
                1 -> {
                    val __0_ = run { val v = wire.readI32(pos); pos += 4; v }
                    ErrorCode(__0_) to (pos - offset)
                }
                2 -> {
                    val _code_ = run { val v = wire.readI32(pos); pos += 4; v }
                    val _detail_ = run { val v = wire.readI32(pos); pos += 4; v }
                    ErrorWithData(_code_, _detail_) to (pos - offset)
                }
                else -> throw FfiException(-1, "Unknown ApiResult tag: $tag")
            }
        }
    }

    fun wireEncodedSize(): Int = 4 + when (this) {
        is Success -> 0
        is ErrorCode -> 4
        is ErrorWithData -> 4 + 4
    }

    fun wireEncodeTo(wire: WireWriter) {
        when (this) {
            is Success -> wire.writeI32(0)
            is ErrorCode -> {
                wire.writeI32(1)
                wire.writeI32(value0)
            }
            is ErrorWithData -> {
                wire.writeI32(2)
                wire.writeI32(code)
                wire.writeI32(detail)
            }
        }
    }
}

object ApiResultCodec {
    const val STRUCT_SIZE = 12
    private const val TAG_OFFSET = 0
    private const val PAYLOAD_OFFSET = 4
    private const val TAG_SUCCESS = 0
    private const val TAG_ERROR_CODE = 1
    private const val TAG_ERROR_WITH_DATA = 2

    fun pack(value: ApiResult): ByteArray {
        val bytes = ByteArray(STRUCT_SIZE)
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.nativeOrder())

        when (value) {
            ApiResult.Success -> {
                buf.putInt(TAG_OFFSET, TAG_SUCCESS)
            }
            is ApiResult.ErrorCode -> {
                buf.putInt(TAG_OFFSET, TAG_ERROR_CODE)
                buf.putInt(PAYLOAD_OFFSET + 0, value.value0)
            }
            is ApiResult.ErrorWithData -> {
                buf.putInt(TAG_OFFSET, TAG_ERROR_WITH_DATA)
                buf.putInt(PAYLOAD_OFFSET + 0, value.code)
                buf.putInt(PAYLOAD_OFFSET + 4, value.detail)
            }
        }

        return bytes
    }

    fun read(buf: ByteBuffer): ApiResult {
        buf.order(ByteOrder.nativeOrder())
        return when (val tag = buf.getInt(TAG_OFFSET)) {
            TAG_SUCCESS ->
                ApiResult.Success
            TAG_ERROR_CODE ->
                ApiResult.ErrorCode(
                    value0 = buf.getInt(PAYLOAD_OFFSET + 0)
                )
            TAG_ERROR_WITH_DATA ->
                ApiResult.ErrorWithData(
                    code = buf.getInt(PAYLOAD_OFFSET + 0),
                    detail = buf.getInt(PAYLOAD_OFFSET + 4)
                )
            else -> throw FfiException(-1, "Unknown ApiResult tag: $tag")
        }
    }
}

sealed class ComputeError : Exception() {
    data class InvalidInput(val value0: Int) : ComputeError()
    data class Overflow(val `value`: Int, val limit: Int) : ComputeError()

    companion object {
        fun decode(wire: WireBuffer, offset: Int): Pair<ComputeError, Int> {
            var pos = offset
            val tag = wire.readI32(pos); pos += 4
            return when (tag) {
                0 -> {
                    val __0_ = run { val v = wire.readI32(pos); pos += 4; v }
                    InvalidInput(__0_) to (pos - offset)
                }
                1 -> {
                    val _value_ = run { val v = wire.readI32(pos); pos += 4; v }
                    val _limit_ = run { val v = wire.readI32(pos); pos += 4; v }
                    Overflow(_value_, _limit_) to (pos - offset)
                }
                else -> throw FfiException(-1, "Unknown ComputeError tag: $tag")
            }
        }
    }

    fun wireEncodedSize(): Int = 4 + when (this) {
        is InvalidInput -> 4
        is Overflow -> 4 + 4
    }

    fun wireEncodeTo(wire: WireWriter) {
        when (this) {
            is InvalidInput -> {
                wire.writeI32(0)
                wire.writeI32(value0)
            }
            is Overflow -> {
                wire.writeI32(1)
                wire.writeI32(`value`)
                wire.writeI32(limit)
            }
        }
    }
}

object ComputeErrorCodec {
    const val STRUCT_SIZE = 12
    private const val TAG_OFFSET = 0
    private const val PAYLOAD_OFFSET = 4
    private const val TAG_INVALID_INPUT = 0
    private const val TAG_OVERFLOW = 1

    fun pack(value: ComputeError): ByteArray {
        val bytes = ByteArray(STRUCT_SIZE)
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.nativeOrder())

        when (value) {
            is ComputeError.InvalidInput -> {
                buf.putInt(TAG_OFFSET, TAG_INVALID_INPUT)
                buf.putInt(PAYLOAD_OFFSET + 0, value.value0)
            }
            is ComputeError.Overflow -> {
                buf.putInt(TAG_OFFSET, TAG_OVERFLOW)
                buf.putInt(PAYLOAD_OFFSET + 0, value.`value`)
                buf.putInt(PAYLOAD_OFFSET + 4, value.limit)
            }
        }

        return bytes
    }

    fun read(buf: ByteBuffer): ComputeError {
        buf.order(ByteOrder.nativeOrder())
        return when (val tag = buf.getInt(TAG_OFFSET)) {
            TAG_INVALID_INPUT ->
                ComputeError.InvalidInput(
                    value0 = buf.getInt(PAYLOAD_OFFSET + 0)
                )
            TAG_OVERFLOW ->
                ComputeError.Overflow(
                    `value` = buf.getInt(PAYLOAD_OFFSET + 0),
                    limit = buf.getInt(PAYLOAD_OFFSET + 4)
                )
            else -> throw FfiException(-1, "Unknown ComputeError tag: $tag")
        }
    }
}

enum class Priority(val value: Int) {
    LOW(0),
    MEDIUM(1),
    HIGH(2),
    CRITICAL(3);

    companion object {
        fun fromValue(value: Int): Priority = entries.first { it.value == value }
    }
}

sealed class TaskStatus {
    data object Pending : TaskStatus()
    data class InProgress(val progress: Int) : TaskStatus()
    data class Completed(val result: Int) : TaskStatus()
    data class Failed(val errorCode: Int, val retryCount: Int) : TaskStatus()

    companion object {
        fun decode(wire: WireBuffer, offset: Int): Pair<TaskStatus, Int> {
            var pos = offset
            val tag = wire.readI32(pos); pos += 4
            return when (tag) {
                0 -> {
                    Pending to (pos - offset)
                }
                1 -> {
                    val _progress_ = run { val v = wire.readI32(pos); pos += 4; v }
                    InProgress(_progress_) to (pos - offset)
                }
                2 -> {
                    val _result_ = run { val v = wire.readI32(pos); pos += 4; v }
                    Completed(_result_) to (pos - offset)
                }
                3 -> {
                    val _error_code_ = run { val v = wire.readI32(pos); pos += 4; v }
                    val _retry_count_ = run { val v = wire.readI32(pos); pos += 4; v }
                    Failed(_error_code_, _retry_count_) to (pos - offset)
                }
                else -> throw FfiException(-1, "Unknown TaskStatus tag: $tag")
            }
        }
    }

    fun wireEncodedSize(): Int = 4 + when (this) {
        is Pending -> 0
        is InProgress -> 4
        is Completed -> 4
        is Failed -> 4 + 4
    }

    fun wireEncodeTo(wire: WireWriter) {
        when (this) {
            is Pending -> wire.writeI32(0)
            is InProgress -> {
                wire.writeI32(1)
                wire.writeI32(progress)
            }
            is Completed -> {
                wire.writeI32(2)
                wire.writeI32(result)
            }
            is Failed -> {
                wire.writeI32(3)
                wire.writeI32(errorCode)
                wire.writeI32(retryCount)
            }
        }
    }
}

object TaskStatusCodec {
    const val STRUCT_SIZE = 12
    private const val TAG_OFFSET = 0
    private const val PAYLOAD_OFFSET = 4
    private const val TAG_PENDING = 0
    private const val TAG_IN_PROGRESS = 1
    private const val TAG_COMPLETED = 2
    private const val TAG_FAILED = 3

    fun pack(value: TaskStatus): ByteArray {
        val bytes = ByteArray(STRUCT_SIZE)
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.nativeOrder())

        when (value) {
            TaskStatus.Pending -> {
                buf.putInt(TAG_OFFSET, TAG_PENDING)
            }
            is TaskStatus.InProgress -> {
                buf.putInt(TAG_OFFSET, TAG_IN_PROGRESS)
                buf.putInt(PAYLOAD_OFFSET + 0, value.progress)
            }
            is TaskStatus.Completed -> {
                buf.putInt(TAG_OFFSET, TAG_COMPLETED)
                buf.putInt(PAYLOAD_OFFSET + 0, value.result)
            }
            is TaskStatus.Failed -> {
                buf.putInt(TAG_OFFSET, TAG_FAILED)
                buf.putInt(PAYLOAD_OFFSET + 0, value.errorCode)
                buf.putInt(PAYLOAD_OFFSET + 4, value.retryCount)
            }
        }

        return bytes
    }

    fun read(buf: ByteBuffer): TaskStatus {
        buf.order(ByteOrder.nativeOrder())
        return when (val tag = buf.getInt(TAG_OFFSET)) {
            TAG_PENDING ->
                TaskStatus.Pending
            TAG_IN_PROGRESS ->
                TaskStatus.InProgress(
                    progress = buf.getInt(PAYLOAD_OFFSET + 0)
                )
            TAG_COMPLETED ->
                TaskStatus.Completed(
                    result = buf.getInt(PAYLOAD_OFFSET + 0)
                )
            TAG_FAILED ->
                TaskStatus.Failed(
                    errorCode = buf.getInt(PAYLOAD_OFFSET + 0),
                    retryCount = buf.getInt(PAYLOAD_OFFSET + 4)
                )
            else -> throw FfiException(-1, "Unknown TaskStatus tag: $tag")
        }
    }
}

data class Location(
    val id: Long,
    val lat: Double,
    val lng: Double,
    val rating: Double,
    val reviewCount: Int,
    val isOpen: Boolean
) {
    companion object {
        const val SIZE_BYTES: Int = 40

        fun decode(wire: WireBuffer, offset: Int): Pair<Location, Int> =
            Location(
                wire.readI64(offset + 0),
                wire.readF64(offset + 8),
                wire.readF64(offset + 16),
                wire.readF64(offset + 24),
                wire.readI32(offset + 32),
                wire.readBool(offset + 36)
            ) to SIZE_BYTES
    }
    fun wireEncodedSize(): Int = SIZE_BYTES

    fun wireEncodeTo(wire: WireWriter) {
        wire.writeI64(id)
        wire.writeF64(lat)
        wire.writeF64(lng)
        wire.writeF64(rating)
        wire.writeI32(reviewCount)
        wire.writeBool(isOpen)
        wire.writePadding(3)
    }
}

private object LocationReader {
    const val STRUCT_SIZE = 40
    const val OFFSET_ID = 0
    const val OFFSET_LAT = 8
    const val OFFSET_LNG = 16
    const val OFFSET_RATING = 24
    const val OFFSET_REVIEW_COUNT = 32
    const val OFFSET_IS_OPEN = 36

    fun read(buf: ByteBuffer, offset: Int): Location =
        Location(
            id = buf.getLong(offset + OFFSET_ID),
            lat = buf.getDouble(offset + OFFSET_LAT),
            lng = buf.getDouble(offset + OFFSET_LNG),
            rating = buf.getDouble(offset + OFFSET_RATING),
            reviewCount = buf.getInt(offset + OFFSET_REVIEW_COUNT),
            isOpen = buf.get(offset + OFFSET_IS_OPEN) != 0.toByte()
        )

    fun readAll(buf: ByteBuffer, baseOffset: Int, count: Int): List<Location> =
        List(count) { i -> read(buf, baseOffset + i * STRUCT_SIZE) }
}

private object LocationWriter {
    const val STRUCT_SIZE = 40
    const val OFFSET_ID = 0
    const val OFFSET_LAT = 8
    const val OFFSET_LNG = 16
    const val OFFSET_RATING = 24
    const val OFFSET_REVIEW_COUNT = 32
    const val OFFSET_IS_OPEN = 36

    fun pack(items: List<Location>): ByteBuffer {
        val buf = ByteBuffer.allocateDirect(items.size * STRUCT_SIZE).order(ByteOrder.nativeOrder())
        items.forEachIndexed { index, item ->
            val base = index * STRUCT_SIZE
            buf.putLong(base + OFFSET_ID, item.id)
            buf.putDouble(base + OFFSET_LAT, item.lat)
            buf.putDouble(base + OFFSET_LNG, item.lng)
            buf.putDouble(base + OFFSET_RATING, item.rating)
            buf.putInt(base + OFFSET_REVIEW_COUNT, item.reviewCount)
            buf.put(base + OFFSET_IS_OPEN, (if (item.isOpen) 1 else 0).toByte())
        }
        return buf
    }

    fun writeAllToWire(wire: WireWriter, items: List<Location>) {
        val bytes = items.size * STRUCT_SIZE
        wire.writeRawBytes(bytes) { buf, baseOffset ->
            items.forEachIndexed { index, item ->
                val base = baseOffset + index * STRUCT_SIZE
                buf.putLong(base + OFFSET_ID, item.id)
                buf.putDouble(base + OFFSET_LAT, item.lat)
                buf.putDouble(base + OFFSET_LNG, item.lng)
                buf.putDouble(base + OFFSET_RATING, item.rating)
                buf.putInt(base + OFFSET_REVIEW_COUNT, item.reviewCount)
                buf.put(base + OFFSET_IS_OPEN, (if (item.isOpen) 1 else 0).toByte())
            }
        }
    }
}

data class Trade(
    val id: Long,
    val symbolId: Int,
    val price: Double,
    val quantity: Long,
    val bid: Double,
    val ask: Double,
    val volume: Long,
    val timestamp: Long,
    val isBuy: Boolean
) {
    companion object {
        const val SIZE_BYTES: Int = 72

        fun decode(wire: WireBuffer, offset: Int): Pair<Trade, Int> =
            Trade(
                wire.readI64(offset + 0),
                wire.readI32(offset + 8),
                wire.readF64(offset + 16),
                wire.readI64(offset + 24),
                wire.readF64(offset + 32),
                wire.readF64(offset + 40),
                wire.readI64(offset + 48),
                wire.readI64(offset + 56),
                wire.readBool(offset + 64)
            ) to SIZE_BYTES
    }
    fun wireEncodedSize(): Int = SIZE_BYTES

    fun wireEncodeTo(wire: WireWriter) {
        wire.writeI64(id)
        wire.writeI32(symbolId)
        wire.writePadding(4)
        wire.writeF64(price)
        wire.writeI64(quantity)
        wire.writeF64(bid)
        wire.writeF64(ask)
        wire.writeI64(volume)
        wire.writeI64(timestamp)
        wire.writeBool(isBuy)
        wire.writePadding(7)
    }
}

private object TradeReader {
    const val STRUCT_SIZE = 72
    const val OFFSET_ID = 0
    const val OFFSET_SYMBOL_ID = 8
    const val OFFSET_PRICE = 16
    const val OFFSET_QUANTITY = 24
    const val OFFSET_BID = 32
    const val OFFSET_ASK = 40
    const val OFFSET_VOLUME = 48
    const val OFFSET_TIMESTAMP = 56
    const val OFFSET_IS_BUY = 64

    fun read(buf: ByteBuffer, offset: Int): Trade =
        Trade(
            id = buf.getLong(offset + OFFSET_ID),
            symbolId = buf.getInt(offset + OFFSET_SYMBOL_ID),
            price = buf.getDouble(offset + OFFSET_PRICE),
            quantity = buf.getLong(offset + OFFSET_QUANTITY),
            bid = buf.getDouble(offset + OFFSET_BID),
            ask = buf.getDouble(offset + OFFSET_ASK),
            volume = buf.getLong(offset + OFFSET_VOLUME),
            timestamp = buf.getLong(offset + OFFSET_TIMESTAMP),
            isBuy = buf.get(offset + OFFSET_IS_BUY) != 0.toByte()
        )

    fun readAll(buf: ByteBuffer, baseOffset: Int, count: Int): List<Trade> =
        List(count) { i -> read(buf, baseOffset + i * STRUCT_SIZE) }
}

private object TradeWriter {
    const val STRUCT_SIZE = 72
    const val OFFSET_ID = 0
    const val OFFSET_SYMBOL_ID = 8
    const val OFFSET_PRICE = 16
    const val OFFSET_QUANTITY = 24
    const val OFFSET_BID = 32
    const val OFFSET_ASK = 40
    const val OFFSET_VOLUME = 48
    const val OFFSET_TIMESTAMP = 56
    const val OFFSET_IS_BUY = 64

    fun pack(items: List<Trade>): ByteBuffer {
        val buf = ByteBuffer.allocateDirect(items.size * STRUCT_SIZE).order(ByteOrder.nativeOrder())
        items.forEachIndexed { index, item ->
            val base = index * STRUCT_SIZE
            buf.putLong(base + OFFSET_ID, item.id)
            buf.putInt(base + OFFSET_SYMBOL_ID, item.symbolId)
            buf.putDouble(base + OFFSET_PRICE, item.price)
            buf.putLong(base + OFFSET_QUANTITY, item.quantity)
            buf.putDouble(base + OFFSET_BID, item.bid)
            buf.putDouble(base + OFFSET_ASK, item.ask)
            buf.putLong(base + OFFSET_VOLUME, item.volume)
            buf.putLong(base + OFFSET_TIMESTAMP, item.timestamp)
            buf.put(base + OFFSET_IS_BUY, (if (item.isBuy) 1 else 0).toByte())
        }
        return buf
    }

    fun writeAllToWire(wire: WireWriter, items: List<Trade>) {
        val bytes = items.size * STRUCT_SIZE
        wire.writeRawBytes(bytes) { buf, baseOffset ->
            items.forEachIndexed { index, item ->
                val base = baseOffset + index * STRUCT_SIZE
                buf.putLong(base + OFFSET_ID, item.id)
                buf.putInt(base + OFFSET_SYMBOL_ID, item.symbolId)
                buf.putDouble(base + OFFSET_PRICE, item.price)
                buf.putLong(base + OFFSET_QUANTITY, item.quantity)
                buf.putDouble(base + OFFSET_BID, item.bid)
                buf.putDouble(base + OFFSET_ASK, item.ask)
                buf.putLong(base + OFFSET_VOLUME, item.volume)
                buf.putLong(base + OFFSET_TIMESTAMP, item.timestamp)
                buf.put(base + OFFSET_IS_BUY, (if (item.isBuy) 1 else 0).toByte())
            }
        }
    }
}

data class Particle(
    val id: Long,
    val x: Double,
    val y: Double,
    val z: Double,
    val vx: Double,
    val vy: Double,
    val vz: Double,
    val mass: Double,
    val charge: Double,
    val active: Boolean
) {
    companion object {
        const val SIZE_BYTES: Int = 80

        fun decode(wire: WireBuffer, offset: Int): Pair<Particle, Int> =
            Particle(
                wire.readI64(offset + 0),
                wire.readF64(offset + 8),
                wire.readF64(offset + 16),
                wire.readF64(offset + 24),
                wire.readF64(offset + 32),
                wire.readF64(offset + 40),
                wire.readF64(offset + 48),
                wire.readF64(offset + 56),
                wire.readF64(offset + 64),
                wire.readBool(offset + 72)
            ) to SIZE_BYTES
    }
    fun wireEncodedSize(): Int = SIZE_BYTES

    fun wireEncodeTo(wire: WireWriter) {
        wire.writeI64(id)
        wire.writeF64(x)
        wire.writeF64(y)
        wire.writeF64(z)
        wire.writeF64(vx)
        wire.writeF64(vy)
        wire.writeF64(vz)
        wire.writeF64(mass)
        wire.writeF64(charge)
        wire.writeBool(active)
        wire.writePadding(7)
    }
}

private object ParticleReader {
    const val STRUCT_SIZE = 80
    const val OFFSET_ID = 0
    const val OFFSET_X = 8
    const val OFFSET_Y = 16
    const val OFFSET_Z = 24
    const val OFFSET_VX = 32
    const val OFFSET_VY = 40
    const val OFFSET_VZ = 48
    const val OFFSET_MASS = 56
    const val OFFSET_CHARGE = 64
    const val OFFSET_ACTIVE = 72

    fun read(buf: ByteBuffer, offset: Int): Particle =
        Particle(
            id = buf.getLong(offset + OFFSET_ID),
            x = buf.getDouble(offset + OFFSET_X),
            y = buf.getDouble(offset + OFFSET_Y),
            z = buf.getDouble(offset + OFFSET_Z),
            vx = buf.getDouble(offset + OFFSET_VX),
            vy = buf.getDouble(offset + OFFSET_VY),
            vz = buf.getDouble(offset + OFFSET_VZ),
            mass = buf.getDouble(offset + OFFSET_MASS),
            charge = buf.getDouble(offset + OFFSET_CHARGE),
            active = buf.get(offset + OFFSET_ACTIVE) != 0.toByte()
        )

    fun readAll(buf: ByteBuffer, baseOffset: Int, count: Int): List<Particle> =
        List(count) { i -> read(buf, baseOffset + i * STRUCT_SIZE) }
}

private object ParticleWriter {
    const val STRUCT_SIZE = 80
    const val OFFSET_ID = 0
    const val OFFSET_X = 8
    const val OFFSET_Y = 16
    const val OFFSET_Z = 24
    const val OFFSET_VX = 32
    const val OFFSET_VY = 40
    const val OFFSET_VZ = 48
    const val OFFSET_MASS = 56
    const val OFFSET_CHARGE = 64
    const val OFFSET_ACTIVE = 72

    fun pack(items: List<Particle>): ByteBuffer {
        val buf = ByteBuffer.allocateDirect(items.size * STRUCT_SIZE).order(ByteOrder.nativeOrder())
        items.forEachIndexed { index, item ->
            val base = index * STRUCT_SIZE
            buf.putLong(base + OFFSET_ID, item.id)
            buf.putDouble(base + OFFSET_X, item.x)
            buf.putDouble(base + OFFSET_Y, item.y)
            buf.putDouble(base + OFFSET_Z, item.z)
            buf.putDouble(base + OFFSET_VX, item.vx)
            buf.putDouble(base + OFFSET_VY, item.vy)
            buf.putDouble(base + OFFSET_VZ, item.vz)
            buf.putDouble(base + OFFSET_MASS, item.mass)
            buf.putDouble(base + OFFSET_CHARGE, item.charge)
            buf.put(base + OFFSET_ACTIVE, (if (item.active) 1 else 0).toByte())
        }
        return buf
    }

    fun writeAllToWire(wire: WireWriter, items: List<Particle>) {
        val bytes = items.size * STRUCT_SIZE
        wire.writeRawBytes(bytes) { buf, baseOffset ->
            items.forEachIndexed { index, item ->
                val base = baseOffset + index * STRUCT_SIZE
                buf.putLong(base + OFFSET_ID, item.id)
                buf.putDouble(base + OFFSET_X, item.x)
                buf.putDouble(base + OFFSET_Y, item.y)
                buf.putDouble(base + OFFSET_Z, item.z)
                buf.putDouble(base + OFFSET_VX, item.vx)
                buf.putDouble(base + OFFSET_VY, item.vy)
                buf.putDouble(base + OFFSET_VZ, item.vz)
                buf.putDouble(base + OFFSET_MASS, item.mass)
                buf.putDouble(base + OFFSET_CHARGE, item.charge)
                buf.put(base + OFFSET_ACTIVE, (if (item.active) 1 else 0).toByte())
            }
        }
    }
}

data class SensorReading(
    val sensorId: Long,
    val timestamp: Long,
    val temperature: Double,
    val humidity: Double,
    val pressure: Double,
    val light: Double,
    val battery: Double,
    val signalStrength: Int,
    val isValid: Boolean
) {
    companion object {
        const val SIZE_BYTES: Int = 64

        fun decode(wire: WireBuffer, offset: Int): Pair<SensorReading, Int> =
            SensorReading(
                wire.readI64(offset + 0),
                wire.readI64(offset + 8),
                wire.readF64(offset + 16),
                wire.readF64(offset + 24),
                wire.readF64(offset + 32),
                wire.readF64(offset + 40),
                wire.readF64(offset + 48),
                wire.readI32(offset + 56),
                wire.readBool(offset + 60)
            ) to SIZE_BYTES
    }
    fun wireEncodedSize(): Int = SIZE_BYTES

    fun wireEncodeTo(wire: WireWriter) {
        wire.writeI64(sensorId)
        wire.writeI64(timestamp)
        wire.writeF64(temperature)
        wire.writeF64(humidity)
        wire.writeF64(pressure)
        wire.writeF64(light)
        wire.writeF64(battery)
        wire.writeI32(signalStrength)
        wire.writeBool(isValid)
        wire.writePadding(3)
    }
}

private object SensorReadingReader {
    const val STRUCT_SIZE = 64
    const val OFFSET_SENSOR_ID = 0
    const val OFFSET_TIMESTAMP = 8
    const val OFFSET_TEMPERATURE = 16
    const val OFFSET_HUMIDITY = 24
    const val OFFSET_PRESSURE = 32
    const val OFFSET_LIGHT = 40
    const val OFFSET_BATTERY = 48
    const val OFFSET_SIGNAL_STRENGTH = 56
    const val OFFSET_IS_VALID = 60

    fun read(buf: ByteBuffer, offset: Int): SensorReading =
        SensorReading(
            sensorId = buf.getLong(offset + OFFSET_SENSOR_ID),
            timestamp = buf.getLong(offset + OFFSET_TIMESTAMP),
            temperature = buf.getDouble(offset + OFFSET_TEMPERATURE),
            humidity = buf.getDouble(offset + OFFSET_HUMIDITY),
            pressure = buf.getDouble(offset + OFFSET_PRESSURE),
            light = buf.getDouble(offset + OFFSET_LIGHT),
            battery = buf.getDouble(offset + OFFSET_BATTERY),
            signalStrength = buf.getInt(offset + OFFSET_SIGNAL_STRENGTH),
            isValid = buf.get(offset + OFFSET_IS_VALID) != 0.toByte()
        )

    fun readAll(buf: ByteBuffer, baseOffset: Int, count: Int): List<SensorReading> =
        List(count) { i -> read(buf, baseOffset + i * STRUCT_SIZE) }
}

private object SensorReadingWriter {
    const val STRUCT_SIZE = 64
    const val OFFSET_SENSOR_ID = 0
    const val OFFSET_TIMESTAMP = 8
    const val OFFSET_TEMPERATURE = 16
    const val OFFSET_HUMIDITY = 24
    const val OFFSET_PRESSURE = 32
    const val OFFSET_LIGHT = 40
    const val OFFSET_BATTERY = 48
    const val OFFSET_SIGNAL_STRENGTH = 56
    const val OFFSET_IS_VALID = 60

    fun pack(items: List<SensorReading>): ByteBuffer {
        val buf = ByteBuffer.allocateDirect(items.size * STRUCT_SIZE).order(ByteOrder.nativeOrder())
        items.forEachIndexed { index, item ->
            val base = index * STRUCT_SIZE
            buf.putLong(base + OFFSET_SENSOR_ID, item.sensorId)
            buf.putLong(base + OFFSET_TIMESTAMP, item.timestamp)
            buf.putDouble(base + OFFSET_TEMPERATURE, item.temperature)
            buf.putDouble(base + OFFSET_HUMIDITY, item.humidity)
            buf.putDouble(base + OFFSET_PRESSURE, item.pressure)
            buf.putDouble(base + OFFSET_LIGHT, item.light)
            buf.putDouble(base + OFFSET_BATTERY, item.battery)
            buf.putInt(base + OFFSET_SIGNAL_STRENGTH, item.signalStrength)
            buf.put(base + OFFSET_IS_VALID, (if (item.isValid) 1 else 0).toByte())
        }
        return buf
    }

    fun writeAllToWire(wire: WireWriter, items: List<SensorReading>) {
        val bytes = items.size * STRUCT_SIZE
        wire.writeRawBytes(bytes) { buf, baseOffset ->
            items.forEachIndexed { index, item ->
                val base = baseOffset + index * STRUCT_SIZE
                buf.putLong(base + OFFSET_SENSOR_ID, item.sensorId)
                buf.putLong(base + OFFSET_TIMESTAMP, item.timestamp)
                buf.putDouble(base + OFFSET_TEMPERATURE, item.temperature)
                buf.putDouble(base + OFFSET_HUMIDITY, item.humidity)
                buf.putDouble(base + OFFSET_PRESSURE, item.pressure)
                buf.putDouble(base + OFFSET_LIGHT, item.light)
                buf.putDouble(base + OFFSET_BATTERY, item.battery)
                buf.putInt(base + OFFSET_SIGNAL_STRENGTH, item.signalStrength)
                buf.put(base + OFFSET_IS_VALID, (if (item.isValid) 1 else 0).toByte())
            }
        }
    }
}

data class UserProfile(
    val id: Long,
    val name: String,
    val email: String,
    val bio: String,
    val age: Int,
    val score: Double,
    val tags: List<String>,
    val scores: IntArray,
    val isActive: Boolean
) {
    companion object {
        fun decode(wire: WireBuffer, offset: Int): Pair<UserProfile, Int> {
            var pos = offset
            val _id_ = run { val v = wire.readI64(pos); pos += 8; v }
            val _name_ = run { val (v, s) = wire.readString(pos); pos += s; v }
            val _email_ = run { val (v, s) = wire.readString(pos); pos += s; v }
            val _bio_ = run { val (v, s) = wire.readString(pos); pos += s; v }
            val _age_ = run { val v = wire.readI32(pos); pos += 4; v }
            val _score_ = run { val v = wire.readF64(pos); pos += 8; v }
            val _tags_ = run { val (v, s) = wire.readList(pos) { wire.readString(it) }; pos += s; v }
            val _scores_ = run { val (v, s) = wire.readIntArray(pos); pos += s; v }
            val _is_active_ = run { val v = wire.readBool(pos); pos += 1; v }
            return UserProfile(_id_, _name_, _email_, _bio_, _age_, _score_, _tags_, _scores_, _is_active_) to (pos - offset)
        }
    }
    fun wireEncodedSize(): Int =
        8 +
        (4 + Utf8Codec.maxBytes(name)) +
        (4 + Utf8Codec.maxBytes(email)) +
        (4 + Utf8Codec.maxBytes(bio)) +
        4 +
        8 +
        (4 + tags.sumOf { item -> (4 + Utf8Codec.maxBytes(item)) }) +
        (4 + scores.size * 4) +
        1

    fun wireEncodeTo(wire: WireWriter) {
        wire.writeI64(id)
        wire.writeString(name)
        wire.writeString(email)
        wire.writeString(bio)
        wire.writeI32(age)
        wire.writeF64(score)
        wire.writeU32(tags.size.toUInt()); tags.forEach { item -> wire.writeString(item) }
        wire.writePrimitiveList(scores)
        wire.writeBool(isActive)
    }
}

data class DataPoint(
    val x: Double,
    val y: Double,
    val timestamp: Long
) {
    companion object {
        const val SIZE_BYTES: Int = 24

        fun decode(wire: WireBuffer, offset: Int): Pair<DataPoint, Int> =
            DataPoint(
                wire.readF64(offset + 0),
                wire.readF64(offset + 8),
                wire.readI64(offset + 16)
            ) to SIZE_BYTES
    }
    fun wireEncodedSize(): Int = SIZE_BYTES

    fun wireEncodeTo(wire: WireWriter) {
        wire.writeF64(x)
        wire.writeF64(y)
        wire.writeI64(timestamp)
    }
}

private object DataPointReader {
    const val STRUCT_SIZE = 24
    const val OFFSET_X = 0
    const val OFFSET_Y = 8
    const val OFFSET_TIMESTAMP = 16

    fun read(buf: ByteBuffer, offset: Int): DataPoint =
        DataPoint(
            x = buf.getDouble(offset + OFFSET_X),
            y = buf.getDouble(offset + OFFSET_Y),
            timestamp = buf.getLong(offset + OFFSET_TIMESTAMP)
        )

    fun readAll(buf: ByteBuffer, baseOffset: Int, count: Int): List<DataPoint> =
        List(count) { i -> read(buf, baseOffset + i * STRUCT_SIZE) }
}

data class StreamReading(
    val sensorId: Int,
    val timestampMs: Long,
    val `value`: Double
) {
    companion object {
        const val SIZE_BYTES: Int = 24

        fun decode(wire: WireBuffer, offset: Int): Pair<StreamReading, Int> =
            StreamReading(
                wire.readI32(offset + 0),
                wire.readI64(offset + 8),
                wire.readF64(offset + 16)
            ) to SIZE_BYTES
    }
    fun wireEncodedSize(): Int = SIZE_BYTES

    fun wireEncodeTo(wire: WireWriter) {
        wire.writeI32(sensorId)
        wire.writePadding(4)
        wire.writeI64(timestampMs)
        wire.writeF64(`value`)
    }
}

data class Address(
    val street: String,
    val city: String,
    val zipCode: Int
) {
    companion object {
        fun decode(wire: WireBuffer, offset: Int): Pair<Address, Int> {
            var pos = offset
            val _street_ = run { val (v, s) = wire.readString(pos); pos += s; v }
            val _city_ = run { val (v, s) = wire.readString(pos); pos += s; v }
            val _zip_code_ = run { val v = wire.readI32(pos); pos += 4; v }
            return Address(_street_, _city_, _zip_code_) to (pos - offset)
        }
    }
    fun wireEncodedSize(): Int =
        (4 + Utf8Codec.maxBytes(street)) +
        (4 + Utf8Codec.maxBytes(city)) +
        4

    fun wireEncodeTo(wire: WireWriter) {
        wire.writeString(street)
        wire.writeString(city)
        wire.writeI32(zipCode)
    }
}

data class Person(
    val name: String,
    val age: Int,
    val address: Address
) {
    companion object {
        fun decode(wire: WireBuffer, offset: Int): Pair<Person, Int> {
            var pos = offset
            val _name_ = run { val (v, s) = wire.readString(pos); pos += s; v }
            val _age_ = run { val v = wire.readI32(pos); pos += 4; v }
            val _address_ = run { val (v, s) = Address.decode(wire, pos); pos += s; v }
            return Person(_name_, _age_, _address_) to (pos - offset)
        }
    }
    fun wireEncodedSize(): Int =
        (4 + Utf8Codec.maxBytes(name)) +
        4 +
        address.wireEncodedSize()

    fun wireEncodeTo(wire: WireWriter) {
        wire.writeString(name)
        wire.writeI32(age)
        address.wireEncodeTo(wire)
    }
}

data class Company(
    val name: String,
    val ceo: Person,
    val employees: List<Person>,
    val headquarters: Address
) {
    companion object {
        fun decode(wire: WireBuffer, offset: Int): Pair<Company, Int> {
            var pos = offset
            val _name_ = run { val (v, s) = wire.readString(pos); pos += s; v }
            val _ceo_ = run { val (v, s) = Person.decode(wire, pos); pos += s; v }
            val _employees_ = run { val (v, s) = wire.readList(pos) { Person.decode(wire, it) }; pos += s; v }
            val _headquarters_ = run { val (v, s) = Address.decode(wire, pos); pos += s; v }
            return Company(_name_, _ceo_, _employees_, _headquarters_) to (pos - offset)
        }
    }
    fun wireEncodedSize(): Int =
        (4 + Utf8Codec.maxBytes(name)) +
        ceo.wireEncodedSize() +
        (4 + employees.sumOf { item -> item.wireEncodedSize() }) +
        headquarters.wireEncodedSize()

    fun wireEncodeTo(wire: WireWriter) {
        wire.writeString(name)
        ceo.wireEncodeTo(wire)
        wire.writeU32(employees.size.toUInt()); employees.forEach { item -> item.wireEncodeTo(wire) }
        headquarters.wireEncodeTo(wire)
    }
}

data class Coordinate(
    val x: Double,
    val y: Double
) {
    companion object {
        const val SIZE_BYTES: Int = 16

        fun decode(wire: WireBuffer, offset: Int): Pair<Coordinate, Int> =
            Coordinate(
                wire.readF64(offset + 0),
                wire.readF64(offset + 8)
            ) to SIZE_BYTES
    }
    fun wireEncodedSize(): Int = SIZE_BYTES

    fun wireEncodeTo(wire: WireWriter) {
        wire.writeF64(x)
        wire.writeF64(y)
    }
}

private object CoordinateReader {
    const val STRUCT_SIZE = 16
    const val OFFSET_X = 0
    const val OFFSET_Y = 8

    fun read(buf: ByteBuffer, offset: Int): Coordinate =
        Coordinate(
            x = buf.getDouble(offset + OFFSET_X),
            y = buf.getDouble(offset + OFFSET_Y)
        )

    fun readAll(buf: ByteBuffer, baseOffset: Int, count: Int): List<Coordinate> =
        List(count) { i -> read(buf, baseOffset + i * STRUCT_SIZE) }
}

private object CoordinateWriter {
    const val STRUCT_SIZE = 16
    const val OFFSET_X = 0
    const val OFFSET_Y = 8

    fun pack(items: List<Coordinate>): ByteBuffer {
        val buf = ByteBuffer.allocateDirect(items.size * STRUCT_SIZE).order(ByteOrder.nativeOrder())
        items.forEachIndexed { index, item ->
            val base = index * STRUCT_SIZE
            buf.putDouble(base + OFFSET_X, item.x)
            buf.putDouble(base + OFFSET_Y, item.y)
        }
        return buf
    }

    fun writeAllToWire(wire: WireWriter, items: List<Coordinate>) {
        val bytes = items.size * STRUCT_SIZE
        wire.writeRawBytes(bytes) { buf, baseOffset ->
            items.forEachIndexed { index, item ->
                val base = baseOffset + index * STRUCT_SIZE
                buf.putDouble(base + OFFSET_X, item.x)
                buf.putDouble(base + OFFSET_Y, item.y)
            }
        }
    }
}

data class BoundingBox(
    val min: Coordinate,
    val max: Coordinate
) {
    companion object {
        fun decode(wire: WireBuffer, offset: Int): Pair<BoundingBox, Int> {
            var pos = offset
            val _min_ = run { val (v, s) = Coordinate.decode(wire, pos); pos += s; v }
            val _max_ = run { val (v, s) = Coordinate.decode(wire, pos); pos += s; v }
            return BoundingBox(_min_, _max_) to (pos - offset)
        }
    }
    fun wireEncodedSize(): Int =
        min.wireEncodedSize() +
        max.wireEncodedSize()

    fun wireEncodeTo(wire: WireWriter) {
        min.wireEncodeTo(wire)
        max.wireEncodeTo(wire)
    }
}

data class Region(
    val name: String,
    val bounds: BoundingBox,
    val points: List<Coordinate>
) {
    companion object {
        fun decode(wire: WireBuffer, offset: Int): Pair<Region, Int> {
            var pos = offset
            val _name_ = run { val (v, s) = wire.readString(pos); pos += s; v }
            val _bounds_ = run { val (v, s) = BoundingBox.decode(wire, pos); pos += s; v }
            val _points_ = run { val (v, s) = wire.readList(pos) { Coordinate.decode(wire, it) }; pos += s; v }
            return Region(_name_, _bounds_, _points_) to (pos - offset)
        }
    }
    fun wireEncodedSize(): Int =
        (4 + Utf8Codec.maxBytes(name)) +
        bounds.wireEncodedSize() +
        (4 + points.size * 16)

    fun wireEncodeTo(wire: WireWriter) {
        wire.writeString(name)
        bounds.wireEncodeTo(wire)
        wire.writeU32(points.size.toUInt()); CoordinateWriter.writeAllToWire(wire, points)
    }
}

data class WorkItem(
    val id: Long,
    val title: String,
    val priority: Priority,
    val status: TaskStatus,
    val assignee: Person?,
    val subtasks: List<WorkItem>
) {
    companion object {
        fun decode(wire: WireBuffer, offset: Int): Pair<WorkItem, Int> {
            var pos = offset
            val _id_ = run { val v = wire.readI64(pos); pos += 8; v }
            val _title_ = run { val (v, s) = wire.readString(pos); pos += s; v }
            val _priority_ = run { val v = Priority.fromValue(wire.readI32(pos)); pos += 4; v }
            val _status_ = run { val (v, s) = TaskStatus.decode(wire, pos); pos += s; v }
            val _assignee_ = run { val (v, s) = wire.readNullable(pos) { Person.decode(wire, it) }; pos += s; v }
            val _subtasks_ = run { val (v, s) = wire.readList(pos) { WorkItem.decode(wire, it) }; pos += s; v }
            return WorkItem(_id_, _title_, _priority_, _status_, _assignee_, _subtasks_) to (pos - offset)
        }
    }
    fun wireEncodedSize(): Int =
        8 +
        (4 + Utf8Codec.maxBytes(title)) +
        4 +
        status.wireEncodedSize() +
        (assignee?.let { v -> 1 + v.wireEncodedSize() } ?: 1) +
        (4 + subtasks.sumOf { item -> item.wireEncodedSize() })

    fun wireEncodeTo(wire: WireWriter) {
        wire.writeI64(id)
        wire.writeString(title)
        wire.writeI32(priority.value)
        status.wireEncodeTo(wire)
        assignee?.let { v -> wire.writeU8(1u); v.wireEncodeTo(wire) } ?: wire.writeU8(0u)
        wire.writeU32(subtasks.size.toUInt()); subtasks.forEach { item -> item.wireEncodeTo(wire) }
    }
}

data class Project(
    val name: String,
    val tasks: List<WorkItem>,
    val owner: Person,
    val collaborators: List<Person>
) {
    companion object {
        fun decode(wire: WireBuffer, offset: Int): Pair<Project, Int> {
            var pos = offset
            val _name_ = run { val (v, s) = wire.readString(pos); pos += s; v }
            val _tasks_ = run { val (v, s) = wire.readList(pos) { WorkItem.decode(wire, it) }; pos += s; v }
            val _owner_ = run { val (v, s) = Person.decode(wire, pos); pos += s; v }
            val _collaborators_ = run { val (v, s) = wire.readList(pos) { Person.decode(wire, it) }; pos += s; v }
            return Project(_name_, _tasks_, _owner_, _collaborators_) to (pos - offset)
        }
    }
    fun wireEncodedSize(): Int =
        (4 + Utf8Codec.maxBytes(name)) +
        (4 + tasks.sumOf { item -> item.wireEncodedSize() }) +
        owner.wireEncodedSize() +
        (4 + collaborators.sumOf { item -> item.wireEncodedSize() })

    fun wireEncodeTo(wire: WireWriter) {
        wire.writeString(name)
        wire.writeU32(tasks.size.toUInt()); tasks.forEach { item -> item.wireEncodeTo(wire) }
        owner.wireEncodeTo(wire)
        wire.writeU32(collaborators.size.toUInt()); collaborators.forEach { item -> item.wireEncodeTo(wire) }
    }
}

data class ApiResponse(
    val requestId: Long,
    val result: Result<DataPoint>
) {
    companion object {
        fun decode(wire: WireBuffer, offset: Int): Pair<ApiResponse, Int> {
            var pos = offset
            val _request_id_ = run { val v = wire.readI64(pos); pos += 8; v }
            val _result_ = run { val (v, s) = wire.readResult(pos, { DataPoint.decode(wire, it) }, { ComputeError.decode(wire, it) }); pos += s; v }
            return ApiResponse(_request_id_, _result_) to (pos - offset)
        }
    }
    fun wireEncodedSize(): Int =
        8 +
        result.fold({ okVal -> 1 + okVal.wireEncodedSize() }, { t -> val e = t as ComputeError; 1 + e.wireEncodedSize() })

    fun wireEncodeTo(wire: WireWriter) {
        wire.writeI64(requestId)
        result.fold({ okVal -> wire.writeU8(0u); okVal.wireEncodeTo(wire) }, { t -> val e = t as ComputeError; wire.writeU8(1u); e.wireEncodeTo(wire) })
    }
}

fun interface DataPointCallback {
    fun invoke(p0: java.nio.ByteBuffer)
}

fun generateUserProfiles(count: Int): List<UserProfile> {
    val buf = Native.boltffi_generate_user_profiles(count)
        ?: throw FfiException(-1, "Null buffer returned")
    return useNativeBuffer(buf) { buffer ->
        val wire = WireBuffer.fromByteBuffer(buffer)
        wire.readList(0) { UserProfile.decode(wire, it) }.first
    }
}

fun sumUserScores(users: List<UserProfile>): Double {
    val wire_writer_users = WireWriterPool.acquire((4 + users.sumOf { item -> item.wireEncodedSize() }))
    try {
        run {
            val wire = wire_writer_users.writer
            wire.writeU32(users.size.toUInt()); users.forEach { item -> item.wireEncodeTo(wire) }
        }
        return Native.boltffi_sum_user_scores(wire_writer_users.buffer)
    } finally {
        wire_writer_users.close()
    }
}

fun countActiveUsers(users: List<UserProfile>): Int {
    val wire_writer_users = WireWriterPool.acquire((4 + users.sumOf { item -> item.wireEncodedSize() }))
    try {
        run {
            val wire = wire_writer_users.writer
            wire.writeU32(users.size.toUInt()); users.forEach { item -> item.wireEncodeTo(wire) }
        }
        return Native.boltffi_count_active_users(wire_writer_users.buffer)
    } finally {
        wire_writer_users.close()
    }
}

fun noop() {
    Native.boltffi_noop()
}

fun echoI32(`value`: Int): Int {
    return Native.boltffi_echo_i32(`value`)
}

fun echoF64(`value`: Double): Double {
    return Native.boltffi_echo_f64(`value`)
}

fun echoString(`value`: String): String {
    val buf = Native.boltffi_echo_string(`value`)
        ?: throw FfiException(-1, "Null buffer returned")
    return useNativeBuffer(buf) { buffer ->
        val wire = WireBuffer.fromByteBuffer(buffer)
        wire.readString(0).first
    }
}

fun add(a: Int, b: Int): Int {
    return Native.boltffi_add(a, b)
}

fun multiply(a: Double, b: Double): Double {
    return Native.boltffi_multiply(a, b)
}

fun generateString(size: Int): String {
    val buf = Native.boltffi_generate_string(size)
        ?: throw FfiException(-1, "Null buffer returned")
    return useNativeBuffer(buf) { buffer ->
        val wire = WireBuffer.fromByteBuffer(buffer)
        wire.readString(0).first
    }
}

fun generateLocations(count: Int): List<Location> {
    val buf = Native.boltffi_generate_locations(count)
        ?: throw FfiException(-1, "Null buffer returned")
    return useNativeBuffer(buf) { buffer ->
        LocationReader.readAll(buffer, 4, buffer.getInt(0))
    }
}

fun processLocations(locations: List<Location>): Int {
    val wire_writer_locations = WireWriterPool.acquire((4 + locations.size * 40))
    try {
        run {
            val wire = wire_writer_locations.writer
            wire.writeU32(locations.size.toUInt()); LocationWriter.writeAllToWire(wire, locations)
        }
        return Native.boltffi_process_locations(wire_writer_locations.buffer)
    } finally {
        wire_writer_locations.close()
    }
}

fun sumRatings(locations: List<Location>): Double {
    val wire_writer_locations = WireWriterPool.acquire((4 + locations.size * 40))
    try {
        run {
            val wire = wire_writer_locations.writer
            wire.writeU32(locations.size.toUInt()); LocationWriter.writeAllToWire(wire, locations)
        }
        return Native.boltffi_sum_ratings(wire_writer_locations.buffer)
    } finally {
        wire_writer_locations.close()
    }
}

fun generateBytes(size: Int): ByteArray {
    val buf = Native.boltffi_generate_bytes(size)
        ?: throw FfiException(-1, "Null buffer returned")
    return useNativeBuffer(buf) { buffer ->
        val wire = WireBuffer.fromByteBuffer(buffer)
        wire.readByteArray(0).first
    }
}

fun generateI32Vec(count: Int): IntArray {
    val buf = Native.boltffi_generate_i32_vec(count)
        ?: throw FfiException(-1, "Null buffer returned")
    return useNativeBuffer(buf) { buffer ->
        val wire = WireBuffer.fromByteBuffer(buffer)
        wire.readIntArray(0).first
    }
}

fun sumI32Vec(values: IntArray): Long {
    return Native.boltffi_sum_i32_vec(values)
}

@Throws(FfiException::class)
fun divide(a: Int, b: Int): Int {
    val buf = Native.boltffi_divide(a, b)
        ?: throw FfiException(-1, "Null buffer returned")
    return useNativeBuffer(buf) { buffer ->
        val wire = WireBuffer.fromByteBuffer(buffer)
        wire.readResult(0, { wire.readI32(it) to 4 }, { val (msg, s) = wire.readString(it); FfiException(-1, msg) to s }).first.getOrThrow()
    }
}

@Throws(FfiException::class)
fun parseInt(s: String): Int {
    val buf = Native.boltffi_parse_int(s)
        ?: throw FfiException(-1, "Null buffer returned")
    return useNativeBuffer(buf) { buffer ->
        val wire = WireBuffer.fromByteBuffer(buffer)
        wire.readResult(0, { wire.readI32(it) to 4 }, { val (msg, s) = wire.readString(it); FfiException(-1, msg) to s }).first.getOrThrow()
    }
}

@Throws(FfiException::class)
fun validateName(name: String): String {
    val buf = Native.boltffi_validate_name(name)
        ?: throw FfiException(-1, "Null buffer returned")
    return useNativeBuffer(buf) { buffer ->
        val wire = WireBuffer.fromByteBuffer(buffer)
        wire.readResult(0, { wire.readString(it) }, { val (msg, s) = wire.readString(it); FfiException(-1, msg) to s }).first.getOrThrow()
    }
}

@Throws(FfiException::class)
fun fetchLocation(id: Int): Location {
    val buf = Native.boltffi_fetch_location(id)
        ?: throw FfiException(-1, "Null buffer returned")
    return useNativeBuffer(buf) { buffer ->
        val wire = WireBuffer.fromByteBuffer(buffer)
        wire.readResult(0, { Location.decode(wire, it) }, { val (msg, s) = wire.readString(it); FfiException(-1, msg) to s }).first.getOrThrow()
    }
}

@Throws(FfiException::class)
fun getDirection(degrees: Int): Direction {
    val buf = Native.boltffi_get_direction(degrees)
        ?: throw FfiException(-1, "Null buffer returned")
    return useNativeBuffer(buf) { buffer ->
        val wire = WireBuffer.fromByteBuffer(buffer)
        wire.readResult(0, { Direction.fromValue(wire.readI32(it)) to 4 }, { val (msg, s) = wire.readString(it); FfiException(-1, msg) to s }).first.getOrThrow()
    }
}

@Throws(FfiException::class)
fun tryProcessValue(`value`: Int): ApiResult {
    val buf = Native.boltffi_try_process_value(`value`)
        ?: throw FfiException(-1, "Null buffer returned")
    return useNativeBuffer(buf) { buffer ->
        val wire = WireBuffer.fromByteBuffer(buffer)
        wire.readResult(0, { ApiResult.decode(wire, it) }, { val (msg, s) = wire.readString(it); FfiException(-1, msg) to s }).first.getOrThrow()
    }
}

fun generateTrades(count: Int): List<Trade> {
    val buf = Native.boltffi_generate_trades(count)
        ?: throw FfiException(-1, "Null buffer returned")
    return useNativeBuffer(buf) { buffer ->
        TradeReader.readAll(buffer, 4, buffer.getInt(0))
    }
}

fun generateParticles(count: Int): List<Particle> {
    val buf = Native.boltffi_generate_particles(count)
        ?: throw FfiException(-1, "Null buffer returned")
    return useNativeBuffer(buf) { buffer ->
        ParticleReader.readAll(buffer, 4, buffer.getInt(0))
    }
}

fun generateSensorReadings(count: Int): List<SensorReading> {
    val buf = Native.boltffi_generate_sensor_readings(count)
        ?: throw FfiException(-1, "Null buffer returned")
    return useNativeBuffer(buf) { buffer ->
        SensorReadingReader.readAll(buffer, 4, buffer.getInt(0))
    }
}

fun sumTradeVolumes(trades: List<Trade>): Long {
    val wire_writer_trades = WireWriterPool.acquire((4 + trades.size * 72))
    try {
        run {
            val wire = wire_writer_trades.writer
            wire.writeU32(trades.size.toUInt()); TradeWriter.writeAllToWire(wire, trades)
        }
        return Native.boltffi_sum_trade_volumes(wire_writer_trades.buffer)
    } finally {
        wire_writer_trades.close()
    }
}

fun sumParticleMasses(particles: List<Particle>): Double {
    val wire_writer_particles = WireWriterPool.acquire((4 + particles.size * 80))
    try {
        run {
            val wire = wire_writer_particles.writer
            wire.writeU32(particles.size.toUInt()); ParticleWriter.writeAllToWire(wire, particles)
        }
        return Native.boltffi_sum_particle_masses(wire_writer_particles.buffer)
    } finally {
        wire_writer_particles.close()
    }
}

fun avgSensorTemperature(readings: List<SensorReading>): Double {
    val wire_writer_readings = WireWriterPool.acquire((4 + readings.size * 64))
    try {
        run {
            val wire = wire_writer_readings.writer
            wire.writeU32(readings.size.toUInt()); SensorReadingWriter.writeAllToWire(wire, readings)
        }
        return Native.boltffi_avg_sensor_temperature(wire_writer_readings.buffer)
    } finally {
        wire_writer_readings.close()
    }
}

fun generateF64Vec(count: Int): DoubleArray {
    val buf = Native.boltffi_generate_f64_vec(count)
        ?: throw FfiException(-1, "Null buffer returned")
    return useNativeBuffer(buf) { buffer ->
        val wire = WireBuffer.fromByteBuffer(buffer)
        wire.readDoubleArray(0).first
    }
}

fun sumF64Vec(values: DoubleArray): Double {
    return Native.boltffi_sum_f64_vec(values)
}

fun incU64(`value`: LongArray) {
    Native.boltffi_inc_u64(`value`)
}

fun oppositeDirection(dir: Direction): Direction {
    val wire_writer_dir = WireWriterPool.acquire(4)
    try {
        run {
            val wire = wire_writer_dir.writer
            wire.writeI32(dir.value)
        }
        val buf = Native.boltffi_opposite_direction(wire_writer_dir.buffer)
            ?: throw FfiException(-1, "Null buffer returned")
        return useNativeBuffer(buf) { buffer ->
            val wire = WireBuffer.fromByteBuffer(buffer)
            Direction.fromValue(wire.readI32(0))
        }
    } finally {
        wire_writer_dir.close()
    }
}

fun directionToDegrees(dir: Direction): Int {
    val wire_writer_dir = WireWriterPool.acquire(4)
    try {
        run {
            val wire = wire_writer_dir.writer
            wire.writeI32(dir.value)
        }
        return Native.boltffi_direction_to_degrees(wire_writer_dir.buffer)
    } finally {
        wire_writer_dir.close()
    }
}

fun findEven(`value`: Int): Int? {
    val buf = Native.boltffi_find_even(`value`)
        ?: throw FfiException(-1, "Null buffer returned")
    return useNativeBuffer(buf) { buffer ->
        val wire = WireBuffer.fromByteBuffer(buffer)
        wire.readNullable(0) { wire.readI32(it) to 4 }.first
    }
}

fun findPositiveI64(`value`: Long): Long? {
    val buf = Native.boltffi_find_positive_i64(`value`)
        ?: throw FfiException(-1, "Null buffer returned")
    return useNativeBuffer(buf) { buffer ->
        val wire = WireBuffer.fromByteBuffer(buffer)
        wire.readNullable(0) { wire.readI64(it) to 8 }.first
    }
}

fun findPositiveF64(`value`: Double): Double? {
    val buf = Native.boltffi_find_positive_f64(`value`)
        ?: throw FfiException(-1, "Null buffer returned")
    return useNativeBuffer(buf) { buffer ->
        val wire = WireBuffer.fromByteBuffer(buffer)
        wire.readNullable(0) { wire.readF64(it) to 8 }.first
    }
}

fun findName(id: Int): String? {
    val buf = Native.boltffi_find_name(id)
        ?: throw FfiException(-1, "Null buffer returned")
    return useNativeBuffer(buf) { buffer ->
        val wire = WireBuffer.fromByteBuffer(buffer)
        wire.readNullable(0) { wire.readString(it) }.first
    }
}

fun findLocation(id: Int): Location? {
    val buf = Native.boltffi_find_location(id)
        ?: throw FfiException(-1, "Null buffer returned")
    return useNativeBuffer(buf) { buffer ->
        val wire = WireBuffer.fromByteBuffer(buffer)
        wire.readNullable(0) { Location.decode(wire, it) }.first
    }
}

fun findNumbers(count: Int): IntArray? {
    val buf = Native.boltffi_find_numbers(count)
        ?: throw FfiException(-1, "Null buffer returned")
    return useNativeBuffer(buf) { buffer ->
        val wire = WireBuffer.fromByteBuffer(buffer)
        wire.readNullable(0) { wire.readIntArray(it) }.first
    }
}

fun findLocations(count: Int): List<Location>? {
    val buf = Native.boltffi_find_locations(count)
        ?: throw FfiException(-1, "Null buffer returned")
    return useNativeBuffer(buf) { buffer ->
        val wire = WireBuffer.fromByteBuffer(buffer)
        wire.readNullable(0) { wire.readList(it) { Location.decode(wire, it) } }.first
    }
}

fun findDirection(id: Int): Direction? {
    val buf = Native.boltffi_find_direction(id)
        ?: throw FfiException(-1, "Null buffer returned")
    return useNativeBuffer(buf) { buffer ->
        val wire = WireBuffer.fromByteBuffer(buffer)
        wire.readNullable(0) { Direction.fromValue(wire.readI32(it)) to 4 }.first
    }
}

fun findApiResult(code: Int): ApiResult? {
    val buf = Native.boltffi_find_api_result(code)
        ?: throw FfiException(-1, "Null buffer returned")
    return useNativeBuffer(buf) { buffer ->
        val wire = WireBuffer.fromByteBuffer(buffer)
        wire.readNullable(0) { ApiResult.decode(wire, it) }.first
    }
}

fun findNames(count: Int): List<String>? {
    val buf = Native.boltffi_find_names(count)
        ?: throw FfiException(-1, "Null buffer returned")
    return useNativeBuffer(buf) { buffer ->
        val wire = WireBuffer.fromByteBuffer(buffer)
        wire.readNullable(0) { wire.readList(it) { wire.readString(it) } }.first
    }
}

fun findDirections(count: Int): List<Direction>? {
    val buf = Native.boltffi_find_directions(count)
        ?: throw FfiException(-1, "Null buffer returned")
    return useNativeBuffer(buf) { buffer ->
        val wire = WireBuffer.fromByteBuffer(buffer)
        wire.readNullable(0) { wire.readList(it) { Direction.fromValue(wire.readI32(it)) to 4 } }.first
    }
}

fun processValue(`value`: Int): ApiResult {
    val buf = Native.boltffi_process_value(`value`)
        ?: throw FfiException(-1, "Null buffer returned")
    return useNativeBuffer(buf) { buffer ->
        val wire = WireBuffer.fromByteBuffer(buffer)
        ApiResult.decode(wire, 0).first
    }
}

fun apiResultIsSuccess(result: ApiResult): Boolean {
    val wire_writer_result = WireWriterPool.acquire(result.wireEncodedSize())
    try {
        run {
            val wire = wire_writer_result.writer
            result.wireEncodeTo(wire)
        }
        return Native.boltffi_api_result_is_success(wire_writer_result.buffer)
    } finally {
        wire_writer_result.close()
    }
}

@Throws(ComputeError::class)
fun tryCompute(`value`: Int): Int {
    val buf = Native.boltffi_try_compute(`value`)
        ?: throw FfiException(-1, "Null buffer returned")
    return useNativeBuffer(buf) { buffer ->
        val wire = WireBuffer.fromByteBuffer(buffer)
        wire.readResult(0, { wire.readI32(it) to 4 }, { ComputeError.decode(wire, it) }).first.getOrThrow()
    }
}

fun createAddress(street: String, city: String, zipCode: Int): Address {
    val buf = Native.boltffi_create_address(street, city, zipCode)
        ?: throw FfiException(-1, "Null buffer returned")
    return useNativeBuffer(buf) { buffer ->
        val wire = WireBuffer.fromByteBuffer(buffer)
        Address.decode(wire, 0).first
    }
}

fun createPerson(name: String, age: Int, address: Address): Person {
    val wire_writer_address = WireWriterPool.acquire(address.wireEncodedSize())
    try {
        run {
            val wire = wire_writer_address.writer
            address.wireEncodeTo(wire)
        }
        val buf = Native.boltffi_create_person(name, age, wire_writer_address.buffer)
            ?: throw FfiException(-1, "Null buffer returned")
        return useNativeBuffer(buf) { buffer ->
            val wire = WireBuffer.fromByteBuffer(buffer)
            Person.decode(wire, 0).first
        }
    } finally {
        wire_writer_address.close()
    }
}

fun createCompany(name: String, ceo: Person, employees: List<Person>, headquarters: Address): Company {
    val wire_writer_ceo = WireWriterPool.acquire(ceo.wireEncodedSize())
    val wire_writer_employees = WireWriterPool.acquire((4 + employees.sumOf { item -> item.wireEncodedSize() }))
    val wire_writer_headquarters = WireWriterPool.acquire(headquarters.wireEncodedSize())
    try {
        run {
            val wire = wire_writer_ceo.writer
            ceo.wireEncodeTo(wire)
        }
        run {
            val wire = wire_writer_employees.writer
            wire.writeU32(employees.size.toUInt()); employees.forEach { item -> item.wireEncodeTo(wire) }
        }
        run {
            val wire = wire_writer_headquarters.writer
            headquarters.wireEncodeTo(wire)
        }
        val buf = Native.boltffi_create_company(name, wire_writer_ceo.buffer, wire_writer_employees.buffer, wire_writer_headquarters.buffer)
            ?: throw FfiException(-1, "Null buffer returned")
        return useNativeBuffer(buf) { buffer ->
            val wire = WireBuffer.fromByteBuffer(buffer)
            Company.decode(wire, 0).first
        }
    } finally {
        wire_writer_headquarters.close()
        wire_writer_employees.close()
        wire_writer_ceo.close()
    }
}

fun getCompanyEmployeeCount(company: Company): Int {
    val wire_writer_company = WireWriterPool.acquire(company.wireEncodedSize())
    try {
        run {
            val wire = wire_writer_company.writer
            company.wireEncodeTo(wire)
        }
        return Native.boltffi_get_company_employee_count(wire_writer_company.buffer)
    } finally {
        wire_writer_company.close()
    }
}

fun getCeoAddressCity(company: Company): String {
    val wire_writer_company = WireWriterPool.acquire(company.wireEncodedSize())
    try {
        run {
            val wire = wire_writer_company.writer
            company.wireEncodeTo(wire)
        }
        val buf = Native.boltffi_get_ceo_address_city(wire_writer_company.buffer)
            ?: throw FfiException(-1, "Null buffer returned")
        return useNativeBuffer(buf) { buffer ->
            val wire = WireBuffer.fromByteBuffer(buffer)
            wire.readString(0).first
        }
    } finally {
        wire_writer_company.close()
    }
}

fun createBoundingBox(minX: Double, minY: Double, maxX: Double, maxY: Double): BoundingBox {
    val buf = Native.boltffi_create_bounding_box(minX, minY, maxX, maxY)
        ?: throw FfiException(-1, "Null buffer returned")
    return useNativeBuffer(buf) { buffer ->
        val wire = WireBuffer.fromByteBuffer(buffer)
        BoundingBox.decode(wire, 0).first
    }
}

fun createRegion(name: String, bounds: BoundingBox, points: List<Coordinate>): Region {
    val wire_writer_bounds = WireWriterPool.acquire(bounds.wireEncodedSize())
    val wire_writer_points = WireWriterPool.acquire((4 + points.size * 16))
    try {
        run {
            val wire = wire_writer_bounds.writer
            bounds.wireEncodeTo(wire)
        }
        run {
            val wire = wire_writer_points.writer
            wire.writeU32(points.size.toUInt()); CoordinateWriter.writeAllToWire(wire, points)
        }
        val buf = Native.boltffi_create_region(name, wire_writer_bounds.buffer, wire_writer_points.buffer)
            ?: throw FfiException(-1, "Null buffer returned")
        return useNativeBuffer(buf) { buffer ->
            val wire = WireBuffer.fromByteBuffer(buffer)
            Region.decode(wire, 0).first
        }
    } finally {
        wire_writer_points.close()
        wire_writer_bounds.close()
    }
}

fun getRegionArea(region: Region): Double {
    val wire_writer_region = WireWriterPool.acquire(region.wireEncodedSize())
    try {
        run {
            val wire = wire_writer_region.writer
            region.wireEncodeTo(wire)
        }
        return Native.boltffi_get_region_area(wire_writer_region.buffer)
    } finally {
        wire_writer_region.close()
    }
}

fun createTask(id: Long, title: String, priority: Priority, status: TaskStatus): WorkItem {
    val wire_writer_priority = WireWriterPool.acquire(4)
    val wire_writer_status = WireWriterPool.acquire(status.wireEncodedSize())
    try {
        run {
            val wire = wire_writer_priority.writer
            wire.writeI32(priority.value)
        }
        run {
            val wire = wire_writer_status.writer
            status.wireEncodeTo(wire)
        }
        val buf = Native.boltffi_create_task(id, title, wire_writer_priority.buffer, wire_writer_status.buffer)
            ?: throw FfiException(-1, "Null buffer returned")
        return useNativeBuffer(buf) { buffer ->
            val wire = WireBuffer.fromByteBuffer(buffer)
            WorkItem.decode(wire, 0).first
        }
    } finally {
        wire_writer_status.close()
        wire_writer_priority.close()
    }
}

fun createTaskWithAssignee(id: Long, title: String, priority: Priority, status: TaskStatus, assignee: Person): WorkItem {
    val wire_writer_priority = WireWriterPool.acquire(4)
    val wire_writer_status = WireWriterPool.acquire(status.wireEncodedSize())
    val wire_writer_assignee = WireWriterPool.acquire(assignee.wireEncodedSize())
    try {
        run {
            val wire = wire_writer_priority.writer
            wire.writeI32(priority.value)
        }
        run {
            val wire = wire_writer_status.writer
            status.wireEncodeTo(wire)
        }
        run {
            val wire = wire_writer_assignee.writer
            assignee.wireEncodeTo(wire)
        }
        val buf = Native.boltffi_create_task_with_assignee(id, title, wire_writer_priority.buffer, wire_writer_status.buffer, wire_writer_assignee.buffer)
            ?: throw FfiException(-1, "Null buffer returned")
        return useNativeBuffer(buf) { buffer ->
            val wire = WireBuffer.fromByteBuffer(buffer)
            WorkItem.decode(wire, 0).first
        }
    } finally {
        wire_writer_assignee.close()
        wire_writer_status.close()
        wire_writer_priority.close()
    }
}

fun createTaskWithSubtasks(id: Long, title: String, priority: Priority, subtasks: List<WorkItem>): WorkItem {
    val wire_writer_priority = WireWriterPool.acquire(4)
    val wire_writer_subtasks = WireWriterPool.acquire((4 + subtasks.sumOf { item -> item.wireEncodedSize() }))
    try {
        run {
            val wire = wire_writer_priority.writer
            wire.writeI32(priority.value)
        }
        run {
            val wire = wire_writer_subtasks.writer
            wire.writeU32(subtasks.size.toUInt()); subtasks.forEach { item -> item.wireEncodeTo(wire) }
        }
        val buf = Native.boltffi_create_task_with_subtasks(id, title, wire_writer_priority.buffer, wire_writer_subtasks.buffer)
            ?: throw FfiException(-1, "Null buffer returned")
        return useNativeBuffer(buf) { buffer ->
            val wire = WireBuffer.fromByteBuffer(buffer)
            WorkItem.decode(wire, 0).first
        }
    } finally {
        wire_writer_subtasks.close()
        wire_writer_priority.close()
    }
}

fun countAllSubtasks(task: WorkItem): Int {
    val wire_writer_task = WireWriterPool.acquire(task.wireEncodedSize())
    try {
        run {
            val wire = wire_writer_task.writer
            task.wireEncodeTo(wire)
        }
        return Native.boltffi_count_all_subtasks(wire_writer_task.buffer)
    } finally {
        wire_writer_task.close()
    }
}

fun createProject(name: String, owner: Person, tasks: List<WorkItem>): Project {
    val wire_writer_owner = WireWriterPool.acquire(owner.wireEncodedSize())
    val wire_writer_tasks = WireWriterPool.acquire((4 + tasks.sumOf { item -> item.wireEncodedSize() }))
    try {
        run {
            val wire = wire_writer_owner.writer
            owner.wireEncodeTo(wire)
        }
        run {
            val wire = wire_writer_tasks.writer
            wire.writeU32(tasks.size.toUInt()); tasks.forEach { item -> item.wireEncodeTo(wire) }
        }
        val buf = Native.boltffi_create_project(name, wire_writer_owner.buffer, wire_writer_tasks.buffer)
            ?: throw FfiException(-1, "Null buffer returned")
        return useNativeBuffer(buf) { buffer ->
            val wire = WireBuffer.fromByteBuffer(buffer)
            Project.decode(wire, 0).first
        }
    } finally {
        wire_writer_tasks.close()
        wire_writer_owner.close()
    }
}

fun getProjectTaskCount(project: Project): Int {
    val wire_writer_project = WireWriterPool.acquire(project.wireEncodedSize())
    try {
        run {
            val wire = wire_writer_project.writer
            project.wireEncodeTo(wire)
        }
        return Native.boltffi_get_project_task_count(wire_writer_project.buffer)
    } finally {
        wire_writer_project.close()
    }
}

fun findTaskByPriority(project: Project, priority: Priority): WorkItem? {
    val wire_writer_project = WireWriterPool.acquire(project.wireEncodedSize())
    val wire_writer_priority = WireWriterPool.acquire(4)
    try {
        run {
            val wire = wire_writer_project.writer
            project.wireEncodeTo(wire)
        }
        run {
            val wire = wire_writer_priority.writer
            wire.writeI32(priority.value)
        }
        val buf = Native.boltffi_find_task_by_priority(wire_writer_project.buffer, wire_writer_priority.buffer)
            ?: throw FfiException(-1, "Null buffer returned")
        return useNativeBuffer(buf) { buffer ->
            val wire = WireBuffer.fromByteBuffer(buffer)
            wire.readNullable(0) { WorkItem.decode(wire, it) }.first
        }
    } finally {
        wire_writer_priority.close()
        wire_writer_project.close()
    }
}

fun getHighPriorityTasks(project: Project): List<WorkItem> {
    val wire_writer_project = WireWriterPool.acquire(project.wireEncodedSize())
    try {
        run {
            val wire = wire_writer_project.writer
            project.wireEncodeTo(wire)
        }
        val buf = Native.boltffi_get_high_priority_tasks(wire_writer_project.buffer)
            ?: throw FfiException(-1, "Null buffer returned")
        return useNativeBuffer(buf) { buffer ->
            val wire = WireBuffer.fromByteBuffer(buffer)
            wire.readList(0) { WorkItem.decode(wire, it) }.first
        }
    } finally {
        wire_writer_project.close()
    }
}

fun createNestedCoordinates(): List<List<Coordinate>> {
    val buf = Native.boltffi_create_nested_coordinates()
        ?: throw FfiException(-1, "Null buffer returned")
    return useNativeBuffer(buf) { buffer ->
        val wire = WireBuffer.fromByteBuffer(buffer)
        wire.readList(0) { wire.readList(it) { Coordinate.decode(wire, it) } }.first
    }
}

fun flattenCoordinates(nested: List<List<Coordinate>>): List<Coordinate> {
    val wire_writer_nested = WireWriterPool.acquire((4 + nested.sumOf { item -> (4 + item.size * 16) }))
    try {
        run {
            val wire = wire_writer_nested.writer
            wire.writeU32(nested.size.toUInt()); nested.forEach { item -> wire.writeU32(item.size.toUInt()); CoordinateWriter.writeAllToWire(wire, item) }
        }
        val buf = Native.boltffi_flatten_coordinates(wire_writer_nested.buffer)
            ?: throw FfiException(-1, "Null buffer returned")
        return useNativeBuffer(buf) { buffer ->
            CoordinateReader.readAll(buffer, 4, buffer.getInt(0))
        }
    } finally {
        wire_writer_nested.close()
    }
}

fun createOptionalPerson(name: String, age: Int, hasAddress: Boolean): Person? {
    val buf = Native.boltffi_create_optional_person(name, age, hasAddress)
        ?: throw FfiException(-1, "Null buffer returned")
    return useNativeBuffer(buf) { buffer ->
        val wire = WireBuffer.fromByteBuffer(buffer)
        wire.readNullable(0) { Person.decode(wire, it) }.first
    }
}

fun getOptionalTaskStatus(task: WorkItem?): TaskStatus? {
    val wire_writer_task = WireWriterPool.acquire((task?.let { v -> 1 + v.wireEncodedSize() } ?: 1))
    try {
        run {
            val wire = wire_writer_task.writer
            task?.let { v -> wire.writeU8(1u); v.wireEncodeTo(wire) } ?: wire.writeU8(0u)
        }
        val buf = Native.boltffi_get_optional_task_status(wire_writer_task.buffer)
            ?: throw FfiException(-1, "Null buffer returned")
        return useNativeBuffer(buf) { buffer ->
            val wire = WireBuffer.fromByteBuffer(buffer)
            wire.readNullable(0) { TaskStatus.decode(wire, it) }.first
        }
    } finally {
        wire_writer_task.close()
    }
}

fun getStatusProgress(status: TaskStatus): Int {
    val wire_writer_status = WireWriterPool.acquire(status.wireEncodedSize())
    try {
        run {
            val wire = wire_writer_status.writer
            status.wireEncodeTo(wire)
        }
        return Native.boltffi_get_status_progress(wire_writer_status.buffer)
    } finally {
        wire_writer_status.close()
    }
}

fun isStatusComplete(status: TaskStatus): Boolean {
    val wire_writer_status = WireWriterPool.acquire(status.wireEncodedSize())
    try {
        run {
            val wire = wire_writer_status.writer
            status.wireEncodeTo(wire)
        }
        return Native.boltffi_is_status_complete(wire_writer_status.buffer)
    } finally {
        wire_writer_status.close()
    }
}

fun createSuccessResponse(requestId: Long, point: DataPoint): ApiResponse {
    val wire_writer_point = WireWriterPool.acquire(point.wireEncodedSize())
    try {
        run {
            val wire = wire_writer_point.writer
            point.wireEncodeTo(wire)
        }
        val buf = Native.boltffi_create_success_response(requestId, wire_writer_point.buffer)
            ?: throw FfiException(-1, "Null buffer returned")
        return useNativeBuffer(buf) { buffer ->
            val wire = WireBuffer.fromByteBuffer(buffer)
            ApiResponse.decode(wire, 0).first
        }
    } finally {
        wire_writer_point.close()
    }
}

fun createErrorResponse(requestId: Long, error: ComputeError): ApiResponse {
    val wire_writer_error = WireWriterPool.acquire(error.wireEncodedSize())
    try {
        run {
            val wire = wire_writer_error.writer
            error.wireEncodeTo(wire)
        }
        val buf = Native.boltffi_create_error_response(requestId, wire_writer_error.buffer)
            ?: throw FfiException(-1, "Null buffer returned")
        return useNativeBuffer(buf) { buffer ->
            val wire = WireBuffer.fromByteBuffer(buffer)
            ApiResponse.decode(wire, 0).first
        }
    } finally {
        wire_writer_error.close()
    }
}

fun isResponseSuccess(response: ApiResponse): Boolean {
    val wire_writer_response = WireWriterPool.acquire(response.wireEncodedSize())
    try {
        run {
            val wire = wire_writer_response.writer
            response.wireEncodeTo(wire)
        }
        return Native.boltffi_is_response_success(wire_writer_response.buffer)
    } finally {
        wire_writer_response.close()
    }
}

fun getResponseValue(response: ApiResponse): DataPoint? {
    val wire_writer_response = WireWriterPool.acquire(response.wireEncodedSize())
    try {
        run {
            val wire = wire_writer_response.writer
            response.wireEncodeTo(wire)
        }
        val buf = Native.boltffi_get_response_value(wire_writer_response.buffer)
            ?: throw FfiException(-1, "Null buffer returned")
        return useNativeBuffer(buf) { buffer ->
            val wire = WireBuffer.fromByteBuffer(buffer)
            wire.readNullable(0) { DataPoint.decode(wire, it) }.first
        }
    } finally {
        wire_writer_response.close()
    }
}

suspend fun asyncAdd(a: Int, b: Int): Int = riffCallAsync(
    createFuture = { Native.boltffi_async_add(a, b) },
    poll = { future, contHandle -> Native.boltffi_async_add_poll(future, contHandle) },
    complete = { future ->
        Native.boltffi_async_add_complete(future)
    },
    free = { future -> Native.boltffi_async_add_free(future) },
    cancel = { future -> Native.boltffi_async_add_cancel(future) }
)

suspend fun computeHeavy(input: Int): Int = riffCallAsync(
    createFuture = { Native.boltffi_compute_heavy(input) },
    poll = { future, contHandle -> Native.boltffi_compute_heavy_poll(future, contHandle) },
    complete = { future ->
        Native.boltffi_compute_heavy_complete(future)
    },
    free = { future -> Native.boltffi_compute_heavy_free(future) },
    cancel = { future -> Native.boltffi_compute_heavy_cancel(future) }
)

@Throws(ComputeError::class)
suspend fun tryComputeAsync(`value`: Int): Int = riffCallAsync(
    createFuture = { Native.boltffi_try_compute_async(`value`) },
    poll = { future, contHandle -> Native.boltffi_try_compute_async_poll(future, contHandle) },
    complete = { future ->
        val buf = Native.boltffi_try_compute_async_complete(future) ?: throw FfiException(-1, "Null buffer returned")
        useNativeBuffer(buf) { buffer ->
            val wire = WireBuffer.fromByteBuffer(buffer)
            wire.readResult(0, { wire.readI32(it) to 4 }, { ComputeError.decode(wire, it) }).first.getOrThrow()
        }
    },
    free = { future -> Native.boltffi_try_compute_async_free(future) },
    cancel = { future -> Native.boltffi_try_compute_async_cancel(future) }
)

@Throws(FfiException::class)
suspend fun fetchData(id: Int): Int = riffCallAsync(
    createFuture = { Native.boltffi_fetch_data(id) },
    poll = { future, contHandle -> Native.boltffi_fetch_data_poll(future, contHandle) },
    complete = { future ->
        val buf = Native.boltffi_fetch_data_complete(future) ?: throw FfiException(-1, "Null buffer returned")
        useNativeBuffer(buf) { buffer ->
            val wire = WireBuffer.fromByteBuffer(buffer)
            wire.readResult(0, { wire.readI32(it) to 4 }, { val (msg, s) = wire.readString(it); FfiException(-1, msg) to s }).first.getOrThrow()
        }
    },
    free = { future -> Native.boltffi_fetch_data_free(future) },
    cancel = { future -> Native.boltffi_fetch_data_cancel(future) }
)

suspend fun asyncMakeString(`value`: Int): String = riffCallAsync(
    createFuture = { Native.boltffi_async_make_string(`value`) },
    poll = { future, contHandle -> Native.boltffi_async_make_string_poll(future, contHandle) },
    complete = { future ->
        val buf = Native.boltffi_async_make_string_complete(future) ?: throw FfiException(-1, "Null buffer returned")
        useNativeBuffer(buf) { buffer ->
            val wire = WireBuffer.fromByteBuffer(buffer)
            wire.readString(0).first
        }
    },
    free = { future -> Native.boltffi_async_make_string_free(future) },
    cancel = { future -> Native.boltffi_async_make_string_cancel(future) }
)

suspend fun asyncFetchPoint(x: Double, y: Double): DataPoint = riffCallAsync(
    createFuture = { Native.boltffi_async_fetch_point(x, y) },
    poll = { future, contHandle -> Native.boltffi_async_fetch_point_poll(future, contHandle) },
    complete = { future ->
        val buf = Native.boltffi_async_fetch_point_complete(future) ?: throw FfiException(-1, "Null buffer returned")
        useNativeBuffer(buf) { buffer ->
            DataPointReader.read(buffer, 0)
        }
    },
    free = { future -> Native.boltffi_async_fetch_point_free(future) },
    cancel = { future -> Native.boltffi_async_fetch_point_cancel(future) }
)

suspend fun asyncGetNumbers(count: Int): IntArray = riffCallAsync(
    createFuture = { Native.boltffi_async_get_numbers(count) },
    poll = { future, contHandle -> Native.boltffi_async_get_numbers_poll(future, contHandle) },
    complete = { future ->
        val buf = Native.boltffi_async_get_numbers_complete(future) ?: throw FfiException(-1, "Null buffer returned")
        useNativeBuffer(buf) { buffer ->
            val wire = WireBuffer.fromByteBuffer(buffer)
            wire.readIntArray(0).first
        }
    },
    free = { future -> Native.boltffi_async_get_numbers_free(future) },
    cancel = { future -> Native.boltffi_async_get_numbers_cancel(future) }
)

class Counter private constructor(private val handle: Long) : AutoCloseable {
    private val closed = AtomicBoolean(false)

    constructor() : this(
        Native.boltffi_counter_new()
    )

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        Native.boltffi_counter_free(handle)
    }

    
fun `set`(`value`: ULong) {
    Native.boltffi_counter_set(handle, `value`.toLong())
}

    
fun increment() {
    Native.boltffi_counter_increment(handle)
}

    
fun `get`(): ULong {
    return Native.boltffi_counter_get(handle).toULong()
}
}

class DataStore private constructor(private val handle: Long) : AutoCloseable {
    private val closed = AtomicBoolean(false)

    constructor() : this(
        Native.boltffi_data_store_new()
    )

    constructor(capacity: Int) : this(
        Native.boltffi_data_store_with_capacity(capacity)
    )

    constructor(x: Double, y: Double, timestamp: Long) : this(
        Native.boltffi_data_store_with_initial_point(x, y, timestamp)
    )

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        Native.boltffi_data_store_free(handle)
    }

    companion object {
        fun withSampleData(): DataStore {
            return DataStore(Native.boltffi_data_store_with_sample_data())
        }
    }

    
fun add(point: DataPoint) {
    val wire_writer_point = WireWriterPool.acquire(point.wireEncodedSize())
    try {
        run {
            val wire = wire_writer_point.writer
            point.wireEncodeTo(wire)
        }
        Native.boltffi_data_store_add(handle, wire_writer_point.buffer)
    } finally {
        wire_writer_point.close()
    }
}

    
fun len(): ULong {
    return Native.boltffi_data_store_len(handle).toULong()
}

    
fun isEmpty(): Boolean {
    return Native.boltffi_data_store_is_empty(handle)
}

    
fun foreach(callback: (DataPoint) -> Unit) {
    Native.boltffi_data_store_foreach(handle, DataPointCallback { buf0 -> buf0.order(java.nio.ByteOrder.nativeOrder()); callback(DataPointReader.read(buf0, 0)) })
}

    
fun sum(): Double {
    return Native.boltffi_data_store_sum(handle)
}

    
@Throws(FfiException::class)
suspend fun asyncSum(): Double = riffCallAsync(
    createFuture = { Native.boltffi_data_store_async_sum(handle) },
    poll = { future, contHandle -> Native.boltffi_data_store_async_sum_poll(handle, future, contHandle) },
    complete = { future ->
        val buf = Native.boltffi_data_store_async_sum_complete(handle, future) ?: throw FfiException(-1, "Null buffer returned")
        useNativeBuffer(buf) { buffer ->
            val wire = WireBuffer.fromByteBuffer(buffer)
            wire.readResult(0, { wire.readF64(it) to 8 }, { val (msg, s) = wire.readString(it); FfiException(-1, msg) to s }).first.getOrThrow()
        }
    },
    free = { future -> Native.boltffi_data_store_async_sum_free(handle, future) },
    cancel = { future -> Native.boltffi_data_store_async_sum_cancel(handle, future) }
)

    
@Throws(FfiException::class)
suspend fun asyncLen(): ULong = riffCallAsync(
    createFuture = { Native.boltffi_data_store_async_len(handle) },
    poll = { future, contHandle -> Native.boltffi_data_store_async_len_poll(handle, future, contHandle) },
    complete = { future ->
        val buf = Native.boltffi_data_store_async_len_complete(handle, future) ?: throw FfiException(-1, "Null buffer returned")
        useNativeBuffer(buf) { buffer ->
            val wire = WireBuffer.fromByteBuffer(buffer)
            wire.readResult(0, { wire.readU64(it) to 8 }, { val (msg, s) = wire.readString(it); FfiException(-1, msg) to s }).first.getOrThrow()
        }
    },
    free = { future -> Native.boltffi_data_store_async_len_free(handle, future) },
    cancel = { future -> Native.boltffi_data_store_async_len_cancel(handle, future) }
)
}

class Accumulator private constructor(private val handle: Long) : AutoCloseable {
    private val closed = AtomicBoolean(false)

    constructor() : this(
        Native.boltffi_accumulator_new()
    )

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        Native.boltffi_accumulator_free(handle)
    }

    
fun add(amount: Long) {
    Native.boltffi_accumulator_add(handle, amount)
}

    
fun `get`(): Long {
    return Native.boltffi_accumulator_get(handle)
}

    
fun reset() {
    Native.boltffi_accumulator_reset(handle)
}
}

class SensorMonitor private constructor(private val handle: Long) : AutoCloseable {
    private val closed = AtomicBoolean(false)

    constructor() : this(
        Native.boltffi_sensor_monitor_new()
    )

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        Native.boltffi_sensor_monitor_free(handle)
    }

    
fun emitReading(sensorId: Int, timestampMs: Long, `value`: Double) {
    Native.boltffi_sensor_monitor_emit_reading(handle, sensorId, timestampMs, `value`)
}

    
fun subscriberCount(): ULong {
    return Native.boltffi_sensor_monitor_subscriber_count(handle).toULong()
}
}

class DataConsumer private constructor(private val handle: Long) : AutoCloseable {
    private val closed = AtomicBoolean(false)

    constructor() : this(
        Native.boltffi_data_consumer_new()
    )

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        Native.boltffi_data_consumer_free(handle)
    }

    
fun setProvider(provider: DataProvider) {
    Native.boltffi_data_consumer_set_provider(handle, DataProviderBridge.create(provider))
}

    
fun computeSum(): ULong {
    return Native.boltffi_data_consumer_compute_sum(handle).toULong()
}
}

interface DataProvider {
    fun getCount(): UInt
}

private object DataProviderHandleMap {
    private val map = java.util.concurrent.ConcurrentHashMap<Long, DataProvider>()
    private val counter = java.util.concurrent.atomic.AtomicLong(1L)

    fun insert(obj: DataProvider): Long {
        val handle = counter.getAndAdd(2L)
        map[handle] = obj
        return handle
    }

    fun get(handle: Long): DataProvider? = map[handle]

    fun remove(handle: Long): DataProvider? = map.remove(handle)

    fun clone(handle: Long): Long {
        val obj = map[handle] ?: return 0L
        return insert(obj)
    }
}

object DataProviderCallbacks {
    @JvmStatic
    fun free(handle: Long) {
        DataProviderHandleMap.remove(handle)
    }

    @JvmStatic
    fun clone(handle: Long): Long {
        return DataProviderHandleMap.clone(handle)
    }

    @JvmStatic
    fun get_count(handle: Long): Int {
        val impl = DataProviderHandleMap.get(handle) ?: return 0
        return impl.getCount().toInt()
    }
}

object DataProviderBridge {
    private val registered = java.util.concurrent.atomic.AtomicBoolean(false)

    fun register() {
        if (!registered.compareAndSet(false, true)) return
    }

    fun create(impl: DataProvider): Long {
        register()
        return DataProviderHandleMap.insert(impl)
    }
}

interface AsyncDataFetcher {
    suspend fun fetchValue(key: UInt): ULong
}

private object AsyncDataFetcherHandleMap {
    private val map = java.util.concurrent.ConcurrentHashMap<Long, AsyncDataFetcher>()
    private val counter = java.util.concurrent.atomic.AtomicLong(1L)

    fun insert(obj: AsyncDataFetcher): Long {
        val handle = counter.getAndAdd(2L)
        map[handle] = obj
        return handle
    }

    fun get(handle: Long): AsyncDataFetcher? = map[handle]

    fun remove(handle: Long): AsyncDataFetcher? = map.remove(handle)

    fun clone(handle: Long): Long {
        val obj = map[handle] ?: return 0L
        return insert(obj)
    }
}

object AsyncDataFetcherCallbacks {
    @JvmStatic
    fun free(handle: Long) {
        AsyncDataFetcherHandleMap.remove(handle)
    }

    @JvmStatic
    fun clone(handle: Long): Long {
        return AsyncDataFetcherHandleMap.clone(handle)
    }

    @JvmStatic
    fun fetch_value(handle: Long, key: Int, callbackPtr: Long, callbackData: Long) {
        val impl = AsyncDataFetcherHandleMap.get(handle) ?: run {
            Native.invokeAsyncCallbackI64(callbackPtr, callbackData, 0L)
            return
        }
        @OptIn(DelicateCoroutinesApi::class)
        GlobalScope.launch {
            val result = impl.fetchValue(key.toUInt())
            Native.invokeAsyncCallbackI64(callbackPtr, callbackData, result.toLong())
        }
    }
}

object AsyncDataFetcherBridge {
    private val registered = java.util.concurrent.atomic.AtomicBoolean(false)

    fun register() {
        if (!registered.compareAndSet(false, true)) return
    }

    fun create(impl: AsyncDataFetcher): Long {
        register()
        return AsyncDataFetcherHandleMap.insert(impl)
    }
}

@Suppress("FunctionName")
private object Native {
    init {
        System.loadLibrary("bench_boltffi_jni")
    }

    @JvmStatic external fun boltffi_free_string(ptr: Long)
    @JvmStatic external fun boltffi_free_native_buffer(buffer: ByteBuffer)
    @JvmStatic external fun boltffi_noop(): Unit
    @JvmStatic external fun boltffi_echo_i32(`value`: Int): Int
    @JvmStatic external fun boltffi_echo_f64(`value`: Double): Double
    @JvmStatic external fun boltffi_add(a: Int, b: Int): Int
    @JvmStatic external fun boltffi_async_add(a: Int, b: Int): Long
    @JvmStatic external fun boltffi_async_add_poll(future: Long, contHandle: Long)
    @JvmStatic external fun boltffi_async_add_complete(future: Long): Int
    @JvmStatic external fun boltffi_async_add_cancel(future: Long)
    @JvmStatic external fun boltffi_async_add_free(future: Long)
    @JvmStatic external fun boltffi_multiply(a: Double, b: Double): Double
    @JvmStatic external fun boltffi_compute_heavy(input: Int): Long
    @JvmStatic external fun boltffi_compute_heavy_poll(future: Long, contHandle: Long)
    @JvmStatic external fun boltffi_compute_heavy_complete(future: Long): Int
    @JvmStatic external fun boltffi_compute_heavy_cancel(future: Long)
    @JvmStatic external fun boltffi_compute_heavy_free(future: Long)
    @JvmStatic external fun boltffi_try_compute_async(`value`: Int): Long
    @JvmStatic external fun boltffi_try_compute_async_poll(future: Long, contHandle: Long)
    @JvmStatic external fun boltffi_try_compute_async_complete(future: Long): ByteBuffer?
    @JvmStatic external fun boltffi_try_compute_async_cancel(future: Long)
    @JvmStatic external fun boltffi_try_compute_async_free(future: Long)
    @JvmStatic external fun boltffi_fetch_data(id: Int): Long
    @JvmStatic external fun boltffi_fetch_data_poll(future: Long, contHandle: Long)
    @JvmStatic external fun boltffi_fetch_data_complete(future: Long): ByteBuffer?
    @JvmStatic external fun boltffi_fetch_data_cancel(future: Long)
    @JvmStatic external fun boltffi_fetch_data_free(future: Long)
    @JvmStatic external fun boltffi_async_make_string(`value`: Int): Long
    @JvmStatic external fun boltffi_async_make_string_poll(future: Long, contHandle: Long)
    @JvmStatic external fun boltffi_async_make_string_complete(future: Long): ByteBuffer?
    @JvmStatic external fun boltffi_async_make_string_cancel(future: Long)
    @JvmStatic external fun boltffi_async_make_string_free(future: Long)
    @JvmStatic external fun boltffi_async_fetch_point(x: Double, y: Double): Long
    @JvmStatic external fun boltffi_async_fetch_point_poll(future: Long, contHandle: Long)
    @JvmStatic external fun boltffi_async_fetch_point_complete(future: Long): ByteBuffer?
    @JvmStatic external fun boltffi_async_fetch_point_cancel(future: Long)
    @JvmStatic external fun boltffi_async_fetch_point_free(future: Long)
    @JvmStatic external fun boltffi_async_get_numbers(count: Int): Long
    @JvmStatic external fun boltffi_async_get_numbers_poll(future: Long, contHandle: Long)
    @JvmStatic external fun boltffi_async_get_numbers_complete(future: Long): ByteBuffer?
    @JvmStatic external fun boltffi_async_get_numbers_cancel(future: Long)
    @JvmStatic external fun boltffi_async_get_numbers_free(future: Long)
    @JvmStatic external fun boltffi_generate_user_profiles(count: Int): ByteBuffer?
    @JvmStatic external fun boltffi_sum_user_scores(users: ByteBuffer): Double
    @JvmStatic external fun boltffi_count_active_users(users: ByteBuffer): Int
    @JvmStatic external fun boltffi_echo_string(`value`: String): ByteBuffer?
    @JvmStatic external fun boltffi_generate_string(size: Int): ByteBuffer?
    @JvmStatic external fun boltffi_generate_locations(count: Int): ByteBuffer?
    @JvmStatic external fun boltffi_process_locations(locations: ByteBuffer): Int
    @JvmStatic external fun boltffi_sum_ratings(locations: ByteBuffer): Double
    @JvmStatic external fun boltffi_generate_bytes(size: Int): ByteBuffer?
    @JvmStatic external fun boltffi_generate_i32_vec(count: Int): ByteBuffer?
    @JvmStatic external fun boltffi_sum_i32_vec(values: IntArray): Long
    @JvmStatic external fun boltffi_divide(a: Int, b: Int): ByteBuffer?
    @JvmStatic external fun boltffi_parse_int(s: String): ByteBuffer?
    @JvmStatic external fun boltffi_validate_name(name: String): ByteBuffer?
    @JvmStatic external fun boltffi_fetch_location(id: Int): ByteBuffer?
    @JvmStatic external fun boltffi_get_direction(degrees: Int): ByteBuffer?
    @JvmStatic external fun boltffi_try_process_value(`value`: Int): ByteBuffer?
    @JvmStatic external fun boltffi_generate_trades(count: Int): ByteBuffer?
    @JvmStatic external fun boltffi_generate_particles(count: Int): ByteBuffer?
    @JvmStatic external fun boltffi_generate_sensor_readings(count: Int): ByteBuffer?
    @JvmStatic external fun boltffi_sum_trade_volumes(trades: ByteBuffer): Long
    @JvmStatic external fun boltffi_sum_particle_masses(particles: ByteBuffer): Double
    @JvmStatic external fun boltffi_avg_sensor_temperature(readings: ByteBuffer): Double
    @JvmStatic external fun boltffi_generate_f64_vec(count: Int): ByteBuffer?
    @JvmStatic external fun boltffi_sum_f64_vec(values: DoubleArray): Double
    @JvmStatic external fun boltffi_inc_u64(`value`: LongArray): Unit
    @JvmStatic external fun boltffi_opposite_direction(dir: ByteBuffer): ByteBuffer?
    @JvmStatic external fun boltffi_direction_to_degrees(dir: ByteBuffer): Int
    @JvmStatic external fun boltffi_find_even(`value`: Int): ByteBuffer?
    @JvmStatic external fun boltffi_find_positive_i64(`value`: Long): ByteBuffer?
    @JvmStatic external fun boltffi_find_positive_f64(`value`: Double): ByteBuffer?
    @JvmStatic external fun boltffi_find_name(id: Int): ByteBuffer?
    @JvmStatic external fun boltffi_find_location(id: Int): ByteBuffer?
    @JvmStatic external fun boltffi_find_numbers(count: Int): ByteBuffer?
    @JvmStatic external fun boltffi_find_locations(count: Int): ByteBuffer?
    @JvmStatic external fun boltffi_find_direction(id: Int): ByteBuffer?
    @JvmStatic external fun boltffi_find_api_result(code: Int): ByteBuffer?
    @JvmStatic external fun boltffi_find_names(count: Int): ByteBuffer?
    @JvmStatic external fun boltffi_find_directions(count: Int): ByteBuffer?
    @JvmStatic external fun boltffi_process_value(`value`: Int): ByteBuffer?
    @JvmStatic external fun boltffi_api_result_is_success(result: ByteBuffer): Boolean
    @JvmStatic external fun boltffi_try_compute(`value`: Int): ByteBuffer?
    @JvmStatic external fun boltffi_create_address(street: String, city: String, zipCode: Int): ByteBuffer?
    @JvmStatic external fun boltffi_create_person(name: String, age: Int, address: ByteBuffer): ByteBuffer?
    @JvmStatic external fun boltffi_create_company(name: String, ceo: ByteBuffer, employees: ByteBuffer, headquarters: ByteBuffer): ByteBuffer?
    @JvmStatic external fun boltffi_get_company_employee_count(company: ByteBuffer): Int
    @JvmStatic external fun boltffi_get_ceo_address_city(company: ByteBuffer): ByteBuffer?
    @JvmStatic external fun boltffi_create_bounding_box(minX: Double, minY: Double, maxX: Double, maxY: Double): ByteBuffer?
    @JvmStatic external fun boltffi_create_region(name: String, bounds: ByteBuffer, points: ByteBuffer): ByteBuffer?
    @JvmStatic external fun boltffi_get_region_area(region: ByteBuffer): Double
    @JvmStatic external fun boltffi_create_task(id: Long, title: String, priority: ByteBuffer, status: ByteBuffer): ByteBuffer?
    @JvmStatic external fun boltffi_create_task_with_assignee(id: Long, title: String, priority: ByteBuffer, status: ByteBuffer, assignee: ByteBuffer): ByteBuffer?
    @JvmStatic external fun boltffi_create_task_with_subtasks(id: Long, title: String, priority: ByteBuffer, subtasks: ByteBuffer): ByteBuffer?
    @JvmStatic external fun boltffi_count_all_subtasks(task: ByteBuffer): Int
    @JvmStatic external fun boltffi_create_project(name: String, owner: ByteBuffer, tasks: ByteBuffer): ByteBuffer?
    @JvmStatic external fun boltffi_get_project_task_count(project: ByteBuffer): Int
    @JvmStatic external fun boltffi_find_task_by_priority(project: ByteBuffer, priority: ByteBuffer): ByteBuffer?
    @JvmStatic external fun boltffi_get_high_priority_tasks(project: ByteBuffer): ByteBuffer?
    @JvmStatic external fun boltffi_create_nested_coordinates(): ByteBuffer?
    @JvmStatic external fun boltffi_flatten_coordinates(nested: ByteBuffer): ByteBuffer?
    @JvmStatic external fun boltffi_create_optional_person(name: String, age: Int, hasAddress: Boolean): ByteBuffer?
    @JvmStatic external fun boltffi_get_optional_task_status(task: ByteBuffer): ByteBuffer?
    @JvmStatic external fun boltffi_get_status_progress(status: ByteBuffer): Int
    @JvmStatic external fun boltffi_is_status_complete(status: ByteBuffer): Boolean
    @JvmStatic external fun boltffi_create_success_response(requestId: Long, point: ByteBuffer): ByteBuffer?
    @JvmStatic external fun boltffi_create_error_response(requestId: Long, error: ByteBuffer): ByteBuffer?
    @JvmStatic external fun boltffi_is_response_success(response: ByteBuffer): Boolean
    @JvmStatic external fun boltffi_get_response_value(response: ByteBuffer): ByteBuffer?
    @JvmStatic external fun boltffi_counter_new(): Long
    @JvmStatic external fun boltffi_counter_free(handle: Long)
    @JvmStatic external fun boltffi_counter_set(handle: Long, `value`: Long): Unit
    @JvmStatic external fun boltffi_counter_increment(handle: Long): Unit
    @JvmStatic external fun boltffi_counter_get(handle: Long): Long
    @JvmStatic external fun boltffi_data_store_new(): Long
    @JvmStatic external fun boltffi_data_store_free(handle: Long)
    @JvmStatic external fun boltffi_data_store_with_sample_data(): Long
    @JvmStatic external fun boltffi_data_store_with_capacity(capacity: Int): Long
    @JvmStatic external fun boltffi_data_store_with_initial_point(x: Double, y: Double, timestamp: Long): Long
    @JvmStatic external fun boltffi_data_store_async_sum(handle: Long): Long
    @JvmStatic external fun boltffi_data_store_async_sum_poll(handle: Long, future: Long, contHandle: Long)
    @JvmStatic external fun boltffi_data_store_async_sum_complete(handle: Long, future: Long): ByteBuffer?
    @JvmStatic external fun boltffi_data_store_async_sum_cancel(handle: Long, future: Long)
    @JvmStatic external fun boltffi_data_store_async_sum_free(handle: Long, future: Long)
    @JvmStatic external fun boltffi_data_store_async_len(handle: Long): Long
    @JvmStatic external fun boltffi_data_store_async_len_poll(handle: Long, future: Long, contHandle: Long)
    @JvmStatic external fun boltffi_data_store_async_len_complete(handle: Long, future: Long): ByteBuffer?
    @JvmStatic external fun boltffi_data_store_async_len_cancel(handle: Long, future: Long)
    @JvmStatic external fun boltffi_data_store_async_len_free(handle: Long, future: Long)
    @JvmStatic external fun boltffi_data_store_add(handle: Long, point: ByteBuffer): Unit
    @JvmStatic external fun boltffi_data_store_len(handle: Long): Long
    @JvmStatic external fun boltffi_data_store_is_empty(handle: Long): Boolean
    @JvmStatic external fun boltffi_data_store_foreach(handle: Long, callback: DataPointCallback): Unit
    @JvmStatic external fun boltffi_data_store_sum(handle: Long): Double
    @JvmStatic external fun boltffi_accumulator_new(): Long
    @JvmStatic external fun boltffi_accumulator_free(handle: Long)
    @JvmStatic external fun boltffi_accumulator_add(handle: Long, amount: Long): Unit
    @JvmStatic external fun boltffi_accumulator_get(handle: Long): Long
    @JvmStatic external fun boltffi_accumulator_reset(handle: Long): Unit
    @JvmStatic external fun boltffi_sensor_monitor_new(): Long
    @JvmStatic external fun boltffi_sensor_monitor_free(handle: Long)
    @JvmStatic external fun boltffi_sensor_monitor_emit_reading(handle: Long, sensorId: Int, timestampMs: Long, `value`: Double): Unit
    @JvmStatic external fun boltffi_sensor_monitor_subscriber_count(handle: Long): Long
    @JvmStatic external fun boltffi_data_consumer_new(): Long
    @JvmStatic external fun boltffi_data_consumer_free(handle: Long)
    @JvmStatic external fun boltffi_data_consumer_set_provider(handle: Long, provider: Long): Unit
    @JvmStatic external fun boltffi_data_consumer_compute_sum(handle: Long): Long
    @JvmStatic external fun invokeAsyncCallbackI64(callbackPtr: Long, callbackData: Long, result: Long)
}
