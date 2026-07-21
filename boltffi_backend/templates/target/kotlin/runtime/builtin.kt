private fun WireReader.readDuration(): java.time.Duration {
    val seconds = readI64()
    val nanos = readI32().toLong()
    require(seconds >= 0L) { "Duration out of range" }
    require(nanos >= 0L) { "Duration nanos out of range" }
    return java.time.Duration.ofSeconds(seconds, nanos)
}

private fun WireReader.readInstant(): java.time.Instant {
    val seconds = readI64()
    val nanos = readI32()
    require(nanos >= 0L) { "Instant nanos out of range" }
    return java.time.Instant.ofEpochSecond(seconds, nanos.toLong())
}

private fun WireReader.readUuid(): java.util.UUID = java.util.UUID(readI64(), readI64())

private fun WireReader.readUri(): java.net.URI = java.net.URI.create(readString())

private fun WireWriter.writeDuration(value: java.time.Duration) {
    require(value.seconds >= 0L) { "Invalid duration, must be non-negative" }
    require(value.nano >= 0) { "Invalid duration nanos" }
    writeI64(value.seconds)
    writeI32(value.nano)
}

private fun WireWriter.writeInstant(value: java.time.Instant) {
    writeI64(value.epochSecond)
    writeI32(value.nano)
}

private fun WireWriter.writeUuid(value: java.util.UUID) {
    writeI64(value.mostSignificantBits)
    writeI64(value.leastSignificantBits)
}

private fun WireWriter.writeUri(value: java.net.URI) {
    writeString(value.toString())
}
