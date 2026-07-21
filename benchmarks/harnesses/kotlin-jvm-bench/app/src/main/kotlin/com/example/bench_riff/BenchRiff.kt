package com.example.bench_boltffi

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

class FfiException(val code: Int, message: String) : Exception(message)

class BoltFFIException(val errorBuffer: ByteBuffer) : Exception("Structured error") {
    init {
        errorBuffer.order(ByteOrder.nativeOrder())
    }
}

private fun checkStatus(code: Int) {
    if (code != 0) {
        val message = Native.boltffi_last_error_message() ?: "FFI error"
        Native.boltffi_clear_last_error()
        throw FfiException(code, message)
    }
}

private inline fun <T> useNativeBuffer(buffer: ByteBuffer, block: (ByteBuffer) -> T): T {
    return try {
        block(buffer)
    } finally {
        Native.boltffi_free_native_buffer(buffer)
    }
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
        val tag = buf.getInt(TAG_OFFSET)
        return when (tag) {
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
        val tag = buf.getInt(TAG_OFFSET)
        return when (tag) {
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

data class Location(
    val id: Long,
    val lat: Double,
    val lng: Double,
    val rating: Double,
    val reviewCount: Int,
    val isOpen: Boolean
)

private object LocationReader {
    const val STRUCT_SIZE = 40
    const val OFFSET_ID = 0
    const val OFFSET_LAT = 8
    const val OFFSET_LNG = 16
    const val OFFSET_RATING = 24
    const val OFFSET_REVIEW_COUNT = 32
    const val OFFSET_IS_OPEN = 36

    fun read(buf: ByteBuffer, index: Int): Location {
        val base = index * STRUCT_SIZE
        return Location(
            id = buf.getLong(base + OFFSET_ID),
            lat = buf.getDouble(base + OFFSET_LAT),
            lng = buf.getDouble(base + OFFSET_LNG),
            rating = buf.getDouble(base + OFFSET_RATING),
            reviewCount = buf.getInt(base + OFFSET_REVIEW_COUNT),
            isOpen = buf.get(base + OFFSET_IS_OPEN) != 0.toByte()
        )
    }

    fun readAll(buf: ByteBuffer, count: Int): List<Location> {
        return (0 until count).map { read(buf, it) }
    }
}

private object LocationWriter {
    const val STRUCT_SIZE = 40
    const val OFFSET_ID = 0
    const val OFFSET_LAT = 8
    const val OFFSET_LNG = 16
    const val OFFSET_RATING = 24
    const val OFFSET_REVIEW_COUNT = 32
    const val OFFSET_IS_OPEN = 36

    fun pack(items: List<Location>): ByteArray {
        val bytes = ByteArray(items.size * STRUCT_SIZE)
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.nativeOrder())
        items.forEachIndexed { index, item ->
            val base = index * STRUCT_SIZE
            buf.putLong(base + OFFSET_ID, item.id)
            buf.putDouble(base + OFFSET_LAT, item.lat)
            buf.putDouble(base + OFFSET_LNG, item.lng)
            buf.putDouble(base + OFFSET_RATING, item.rating)
            buf.putInt(base + OFFSET_REVIEW_COUNT, item.reviewCount)
            buf.put(base + OFFSET_IS_OPEN, (if (item.isOpen) 1 else 0).toByte())
        }
        return bytes
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
)

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

    fun read(buf: ByteBuffer, index: Int): Trade {
        val base = index * STRUCT_SIZE
        return Trade(
            id = buf.getLong(base + OFFSET_ID),
            symbolId = buf.getInt(base + OFFSET_SYMBOL_ID),
            price = buf.getDouble(base + OFFSET_PRICE),
            quantity = buf.getLong(base + OFFSET_QUANTITY),
            bid = buf.getDouble(base + OFFSET_BID),
            ask = buf.getDouble(base + OFFSET_ASK),
            volume = buf.getLong(base + OFFSET_VOLUME),
            timestamp = buf.getLong(base + OFFSET_TIMESTAMP),
            isBuy = buf.get(base + OFFSET_IS_BUY) != 0.toByte()
        )
    }

    fun readAll(buf: ByteBuffer, count: Int): List<Trade> {
        return (0 until count).map { read(buf, it) }
    }
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

    fun pack(items: List<Trade>): ByteArray {
        val bytes = ByteArray(items.size * STRUCT_SIZE)
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.nativeOrder())
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
        return bytes
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
)

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

    fun read(buf: ByteBuffer, index: Int): Particle {
        val base = index * STRUCT_SIZE
        return Particle(
            id = buf.getLong(base + OFFSET_ID),
            x = buf.getDouble(base + OFFSET_X),
            y = buf.getDouble(base + OFFSET_Y),
            z = buf.getDouble(base + OFFSET_Z),
            vx = buf.getDouble(base + OFFSET_VX),
            vy = buf.getDouble(base + OFFSET_VY),
            vz = buf.getDouble(base + OFFSET_VZ),
            mass = buf.getDouble(base + OFFSET_MASS),
            charge = buf.getDouble(base + OFFSET_CHARGE),
            active = buf.get(base + OFFSET_ACTIVE) != 0.toByte()
        )
    }

    fun readAll(buf: ByteBuffer, count: Int): List<Particle> {
        return (0 until count).map { read(buf, it) }
    }
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

    fun pack(items: List<Particle>): ByteArray {
        val bytes = ByteArray(items.size * STRUCT_SIZE)
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.nativeOrder())
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
        return bytes
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
)

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

    fun read(buf: ByteBuffer, index: Int): SensorReading {
        val base = index * STRUCT_SIZE
        return SensorReading(
            sensorId = buf.getLong(base + OFFSET_SENSOR_ID),
            timestamp = buf.getLong(base + OFFSET_TIMESTAMP),
            temperature = buf.getDouble(base + OFFSET_TEMPERATURE),
            humidity = buf.getDouble(base + OFFSET_HUMIDITY),
            pressure = buf.getDouble(base + OFFSET_PRESSURE),
            light = buf.getDouble(base + OFFSET_LIGHT),
            battery = buf.getDouble(base + OFFSET_BATTERY),
            signalStrength = buf.getInt(base + OFFSET_SIGNAL_STRENGTH),
            isValid = buf.get(base + OFFSET_IS_VALID) != 0.toByte()
        )
    }

    fun readAll(buf: ByteBuffer, count: Int): List<SensorReading> {
        return (0 until count).map { read(buf, it) }
    }
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

    fun pack(items: List<SensorReading>): ByteArray {
        val bytes = ByteArray(items.size * STRUCT_SIZE)
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.nativeOrder())
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
        return bytes
    }
}

data class DataPoint(
    val x: Double,
    val y: Double,
    val timestamp: Long
)

data class StreamReading(
    val sensorId: Int,
    val timestampMs: Long,
    val `value`: Double
)

fun noop() {
    val status = Native.boltffi_noop()
    checkStatus(status)
}

fun echoI32(`value`: Int): Int {
    return Native.boltffi_echo_i32(`value`)
}

fun echoF64(`value`: Double): Double {
    return Native.boltffi_echo_f64(`value`)
}

fun echoString(`value`: String): String {
    return Native.boltffi_echo_string(`value`) ?: throw FfiException(-1, "Null string returned")
}

fun add(a: Int, b: Int): Int {
    return Native.boltffi_add(a, b)
}

fun multiply(a: Double, b: Double): Double {
    return Native.boltffi_multiply(a, b)
}

fun generateString(size: Int): String {
    return Native.boltffi_generate_string(size) ?: throw FfiException(-1, "Null string returned")
}

fun generateLocations(count: Int): List<Location> {
    val buf = Native.boltffi_generate_locations(count)
    return useNativeBuffer(buf) { buffer ->
        buffer.order(ByteOrder.nativeOrder())
        LocationReader.readAll(buffer, buffer.capacity() / LocationReader.STRUCT_SIZE)
    }
}

fun processLocations(locations: List<Location>): Int {
    return Native.boltffi_process_locations(LocationWriter.pack(locations))
}

fun sumRatings(locations: List<Location>): Double {
    return Native.boltffi_sum_ratings(LocationWriter.pack(locations))
}

fun generateBytes(size: Int): List<UByte> {
    return Native.boltffi_generate_bytes(size).map { it.toUByte() }
}

fun generateI32Vec(count: Int): List<Int> {
    return Native.boltffi_generate_i32_vec(count).toList()
}

fun sumI32Vec(values: List<Int>): Long {
    return Native.boltffi_sum_i32_vec(values.toIntArray())
}

@Throws(FfiException::class)
fun divide(a: Int, b: Int): Int {
    try {
        return Native.boltffi_divide(a, b)
    } catch (e: BoltFFIException) {
        throw FfiException(-1, e.message ?: "Unknown error")
    }
}

@Throws(FfiException::class)
fun parseInt(s: String): Int {
    try {
        return Native.boltffi_parse_int(s)
    } catch (e: BoltFFIException) {
        throw FfiException(-1, e.message ?: "Unknown error")
    }
}

@Throws(FfiException::class)
fun validateName(name: String): String {
    try {
        return Native.boltffi_validate_name(name) ?: throw FfiException(-1, "Null string returned")
    } catch (e: BoltFFIException) {
        throw FfiException(-1, e.message ?: "Unknown error")
    }
}

@Throws(FfiException::class)
fun fetchLocation(id: Int): Location {
    try {
        val buf = Native.boltffi_fetch_location(id) ?: throw FfiException(-1, "Null buffer returned")
        return useNativeBuffer(buf) { buffer ->
            buffer.order(ByteOrder.nativeOrder())
            LocationReader.read(buffer, 0)
        }
    } catch (e: BoltFFIException) {
        throw FfiException(-1, e.message ?: "Unknown error")
    }
}

@Throws(FfiException::class)
fun getDirection(degrees: Int): Direction {
    try {
        return Direction.fromValue(Native.boltffi_get_direction(degrees))
    } catch (e: BoltFFIException) {
        throw FfiException(-1, e.message ?: "Unknown error")
    }
}

@Throws(FfiException::class)
fun tryProcessValue(`value`: Int): ApiResult {
    try {
        val buf = Native.boltffi_try_process_value(`value`) ?: throw FfiException(-1, "Null buffer returned")
        return useNativeBuffer(buf) { buffer ->
            buffer.order(ByteOrder.nativeOrder())
            ApiResultCodec.read(buffer)
        }
    } catch (e: BoltFFIException) {
        throw FfiException(-1, e.message ?: "Unknown error")
    }
}

fun generateTrades(count: Int): List<Trade> {
    val buf = Native.boltffi_generate_trades(count)
    return useNativeBuffer(buf) { buffer ->
        buffer.order(ByteOrder.nativeOrder())
        TradeReader.readAll(buffer, buffer.capacity() / TradeReader.STRUCT_SIZE)
    }
}

fun generateParticles(count: Int): List<Particle> {
    val buf = Native.boltffi_generate_particles(count)
    return useNativeBuffer(buf) { buffer ->
        buffer.order(ByteOrder.nativeOrder())
        ParticleReader.readAll(buffer, buffer.capacity() / ParticleReader.STRUCT_SIZE)
    }
}

fun generateSensorReadings(count: Int): List<SensorReading> {
    val buf = Native.boltffi_generate_sensor_readings(count)
    return useNativeBuffer(buf) { buffer ->
        buffer.order(ByteOrder.nativeOrder())
        SensorReadingReader.readAll(buffer, buffer.capacity() / SensorReadingReader.STRUCT_SIZE)
    }
}

fun sumTradeVolumes(trades: List<Trade>): Long {
    return Native.boltffi_sum_trade_volumes(TradeWriter.pack(trades))
}

fun sumParticleMasses(particles: List<Particle>): Double {
    return Native.boltffi_sum_particle_masses(ParticleWriter.pack(particles))
}

fun avgSensorTemperature(readings: List<SensorReading>): Double {
    return Native.boltffi_avg_sensor_temperature(SensorReadingWriter.pack(readings))
}

fun generateF64Vec(count: Int): List<Double> {
    return Native.boltffi_generate_f64_vec(count).toList()
}

fun sumF64Vec(values: List<Double>): Double {
    return Native.boltffi_sum_f64_vec(values.toDoubleArray())
}

fun oppositeDirection(dir: Direction): Direction {
    return Direction.fromValue(Native.boltffi_opposite_direction(dir.value))
}

fun directionToDegrees(dir: Direction): Int {
    return Native.boltffi_direction_to_degrees(dir.value)
}

fun findEven(`value`: Int): Int? {
    val packed = Native.boltffi_find_even(`value`)
    return if ((packed shr 32) != 0L) packed.toInt() else null
}

fun processValue(`value`: Int): ApiResult {
    val buf = Native.boltffi_process_value(`value`)
    return useNativeBuffer(buf) { buffer ->
        buffer.order(ByteOrder.nativeOrder())
        ApiResultCodec.read(buffer)
    }
}

fun apiResultIsSuccess(result: ApiResult): Boolean {
    return Native.boltffi_api_result_is_success(ApiResultCodec.pack(result))
}

@Throws(ComputeError::class)
fun tryCompute(`value`: Int): Int {
    try {
        return Native.boltffi_try_compute(`value`)
    } catch (e: BoltFFIException) {
        throw ComputeErrorCodec.read(e.errorBuffer)
    }
}

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
        val status = Native.boltffi_counter_set(handle, `value`.toLong())
        checkStatus(status)
    }

    fun increment() {
        val status = Native.boltffi_counter_increment(handle)
        checkStatus(status)
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

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        Native.boltffi_data_store_free(handle)
    }

    fun len(): Long {
        return Native.boltffi_data_store_len(handle)
    }

    fun isEmpty(): Boolean {
        return Native.boltffi_data_store_is_empty(handle)
    }

    fun sum(): Double {
        return Native.boltffi_data_store_sum(handle)
    }
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
        val status = Native.boltffi_accumulator_add(handle, amount)
        checkStatus(status)
    }

    fun `get`(): Long {
        return Native.boltffi_accumulator_get(handle)
    }

    fun reset() {
        val status = Native.boltffi_accumulator_reset(handle)
        checkStatus(status)
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
        val status = Native.boltffi_sensor_monitor_emit_reading(handle, sensorId, timestampMs, `value`)
        checkStatus(status)
    }

    fun subscriberCount(): Long {
        return Native.boltffi_sensor_monitor_subscriber_count(handle)
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

    fun computeSum(): ULong {
        return Native.boltffi_data_consumer_compute_sum(handle).toULong()
    }
}

@Suppress("FunctionName")
private object Native {
    init {
        System.loadLibrary("bench_boltffi_jni")
    }

    @JvmStatic external fun boltffi_free_string(ptr: Long)
    @JvmStatic external fun boltffi_last_error_message(): String?
    @JvmStatic external fun boltffi_clear_last_error()
    @JvmStatic external fun boltffi_free_native_buffer(buffer: ByteBuffer)
    @JvmStatic external fun boltffi_noop(): Int
    @JvmStatic external fun boltffi_echo_i32(`value`: Int): Int
    @JvmStatic external fun boltffi_echo_f64(`value`: Double): Double
    @JvmStatic external fun boltffi_echo_string(`value`: String): String?
    @JvmStatic external fun boltffi_add(a: Int, b: Int): Int
    @JvmStatic external fun boltffi_multiply(a: Double, b: Double): Double
    @JvmStatic external fun boltffi_generate_string(size: Int): String?
    @JvmStatic external fun boltffi_generate_locations(count: Int): ByteBuffer
    @JvmStatic external fun boltffi_process_locations(locations: ByteArray): Int
    @JvmStatic external fun boltffi_sum_ratings(locations: ByteArray): Double
    @JvmStatic external fun boltffi_generate_bytes(size: Int): ByteArray
    @JvmStatic external fun boltffi_generate_i32_vec(count: Int): IntArray
    @JvmStatic external fun boltffi_sum_i32_vec(values: IntArray): Long
    @JvmStatic external fun boltffi_divide(a: Int, b: Int): Int
    @JvmStatic external fun boltffi_parse_int(s: String): Int
    @JvmStatic external fun boltffi_validate_name(name: String): String?
    @JvmStatic external fun boltffi_fetch_location(id: Int): ByteBuffer?
    @JvmStatic external fun boltffi_get_direction(degrees: Int): Int
    @JvmStatic external fun boltffi_try_process_value(`value`: Int): ByteBuffer?
    @JvmStatic external fun boltffi_generate_trades(count: Int): ByteBuffer
    @JvmStatic external fun boltffi_generate_particles(count: Int): ByteBuffer
    @JvmStatic external fun boltffi_generate_sensor_readings(count: Int): ByteBuffer
    @JvmStatic external fun boltffi_sum_trade_volumes(trades: ByteArray): Long
    @JvmStatic external fun boltffi_sum_particle_masses(particles: ByteArray): Double
    @JvmStatic external fun boltffi_avg_sensor_temperature(readings: ByteArray): Double
    @JvmStatic external fun boltffi_generate_f64_vec(count: Int): DoubleArray
    @JvmStatic external fun boltffi_sum_f64_vec(values: DoubleArray): Double
    @JvmStatic external fun boltffi_inc_u64(`value`: LongArray): Int
    @JvmStatic external fun boltffi_opposite_direction(dir: Int): Int
    @JvmStatic external fun boltffi_direction_to_degrees(dir: Int): Int
    @JvmStatic external fun boltffi_find_even(`value`: Int): Long
    @JvmStatic external fun boltffi_process_value(`value`: Int): ByteBuffer
    @JvmStatic external fun boltffi_api_result_is_success(result: ByteArray): Boolean
    @JvmStatic external fun boltffi_try_compute(`value`: Int): Int
    @JvmStatic external fun boltffi_compute_heavy(input: Int): Int
    @JvmStatic external fun boltffi_try_compute_async(`value`: Int): Int
    @JvmStatic external fun boltffi_fetch_data(id: Int): Int
    @JvmStatic external fun boltffi_async_make_string(`value`: Int): String?
    @JvmStatic external fun boltffi_async_fetch_point(x: Double, y: Double): DataPoint
    @JvmStatic external fun boltffi_async_get_numbers(count: Int): IntArray
    @JvmStatic external fun boltffi_counter_new(): Long
    @JvmStatic external fun boltffi_counter_free(handle: Long)
    @JvmStatic external fun boltffi_counter_set(handle: Long, `value`: Long): Int
    @JvmStatic external fun boltffi_counter_increment(handle: Long): Int
    @JvmStatic external fun boltffi_counter_get(handle: Long): Long
    @JvmStatic external fun boltffi_data_store_new(): Long
    @JvmStatic external fun boltffi_data_store_free(handle: Long)
    @JvmStatic external fun boltffi_data_store_len(handle: Long): Long
    @JvmStatic external fun boltffi_data_store_is_empty(handle: Long): Boolean
    @JvmStatic external fun boltffi_data_store_sum(handle: Long): Double
    @JvmStatic external fun boltffi_accumulator_new(): Long
    @JvmStatic external fun boltffi_accumulator_free(handle: Long)
    @JvmStatic external fun boltffi_accumulator_add(handle: Long, amount: Long): Int
    @JvmStatic external fun boltffi_accumulator_get(handle: Long): Long
    @JvmStatic external fun boltffi_accumulator_reset(handle: Long): Int
    @JvmStatic external fun boltffi_sensor_monitor_new(): Long
    @JvmStatic external fun boltffi_sensor_monitor_free(handle: Long)
    @JvmStatic external fun boltffi_sensor_monitor_emit_reading(handle: Long, sensorId: Int, timestampMs: Long, `value`: Double): Int
    @JvmStatic external fun boltffi_sensor_monitor_subscriber_count(handle: Long): Long
    @JvmStatic external fun boltffi_data_consumer_new(): Long
    @JvmStatic external fun boltffi_data_consumer_free(handle: Long)
    @JvmStatic external fun boltffi_data_consumer_compute_sum(handle: Long): Long
}
