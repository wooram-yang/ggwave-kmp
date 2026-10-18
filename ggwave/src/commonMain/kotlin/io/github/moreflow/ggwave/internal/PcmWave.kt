package io.github.moreflow.ggwave.internal

internal fun ShortArray.toLittleEndianByteArray(): ByteArray {
    val result = ByteArray(size * Short.SIZE_BYTES)
    forEachIndexed { index, sample ->
        val offset = index * Short.SIZE_BYTES
        result[offset] = (sample.toInt() and 0xFF).toByte()
        result[offset + 1] = ((sample.toInt() ushr 8) and 0xFF).toByte()
    }
    return result
}

internal fun ByteArray.toLittleEndianShortArray(byteCount: Int = size): ShortArray =
    ShortArray(byteCount / Short.SIZE_BYTES) { index ->
        val offset = index * Short.SIZE_BYTES
        val low = this[offset].toInt() and 0xFF
        val high = this[offset + 1].toInt() shl 8
        (low or high).toShort()
    }

internal fun createPcm16MonoWaveHeader(
    dataLength: Int,
    sampleRate: Int,
): ByteArray {
    require(dataLength >= 0) { "dataLength must not be negative" }
    require(sampleRate > 0) { "sampleRate must be positive" }

    val header = ByteArray(WAVE_HEADER_SIZE)
    header.writeAscii(0, "RIFF")
    header.writeLittleEndianInt(4, dataLength + WAVE_HEADER_SIZE - 8)
    header.writeAscii(8, "WAVE")
    header.writeAscii(12, "fmt ")
    header.writeLittleEndianInt(16, PCM_FORMAT_CHUNK_SIZE)
    header.writeLittleEndianShort(20, PCM_FORMAT)
    header.writeLittleEndianShort(22, MONO_CHANNELS)
    header.writeLittleEndianInt(24, sampleRate)
    header.writeLittleEndianInt(28, sampleRate * MONO_CHANNELS * BYTES_PER_SAMPLE)
    header.writeLittleEndianShort(32, MONO_CHANNELS * BYTES_PER_SAMPLE)
    header.writeLittleEndianShort(34, BITS_PER_SAMPLE)
    header.writeAscii(36, "data")
    header.writeLittleEndianInt(40, dataLength)
    return header
}

private fun ByteArray.writeAscii(offset: Int, value: String) {
    value.forEachIndexed { index, character -> this[offset + index] = character.code.toByte() }
}

private fun ByteArray.writeLittleEndianInt(offset: Int, value: Int) {
    repeat(Int.SIZE_BYTES) { byteIndex ->
        this[offset + byteIndex] = (value ushr (byteIndex * Byte.SIZE_BITS)).toByte()
    }
}

private fun ByteArray.writeLittleEndianShort(offset: Int, value: Int) {
    repeat(Short.SIZE_BYTES) { byteIndex ->
        this[offset + byteIndex] = (value ushr (byteIndex * Byte.SIZE_BITS)).toByte()
    }
}

private const val WAVE_HEADER_SIZE = 44
private const val PCM_FORMAT_CHUNK_SIZE = 16
private const val PCM_FORMAT = 1
private const val MONO_CHANNELS = 1
private const val BITS_PER_SAMPLE = 16
private const val BYTES_PER_SAMPLE = BITS_PER_SAMPLE / Byte.SIZE_BITS
