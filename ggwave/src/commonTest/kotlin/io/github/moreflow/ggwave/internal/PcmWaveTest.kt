package io.github.moreflow.ggwave.internal

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class PcmWaveTest {
    @Test
    fun convertsSamplesToLittleEndianBytes() {
        val samples = shortArrayOf(0x1234, -2)

        assertContentEquals(
            byteArrayOf(0x34, 0x12, 0xFE.toByte(), 0xFF.toByte()),
            samples.toLittleEndianByteArray(),
        )
    }

    @Test
    fun convertsLittleEndianBytesToSamples() {
        val bytes = byteArrayOf(0x34, 0x12, 0xFE.toByte(), 0xFF.toByte(), 0x7F)

        assertContentEquals(shortArrayOf(0x1234, -2), bytes.toLittleEndianShortArray(byteCount = 4))
        assertContentEquals(shortArrayOf(0x1234, -2), bytes.toLittleEndianShortArray())
    }

    @Test
    fun createsValidPcm16MonoWaveHeader() {
        val dataLength = 1_024
        val header = createPcm16MonoWaveHeader(dataLength, sampleRate = 48_000)

        assertEquals(44, header.size)
        assertEquals("RIFF", header.ascii(0, 4))
        assertEquals(dataLength + 36, header.littleEndianInt(4))
        assertEquals("WAVE", header.ascii(8, 4))
        assertEquals("fmt ", header.ascii(12, 4))
        assertEquals(48_000, header.littleEndianInt(24))
        assertEquals(96_000, header.littleEndianInt(28))
        assertEquals("data", header.ascii(36, 4))
        assertEquals(dataLength, header.littleEndianInt(40))
    }
}

private fun ByteArray.ascii(offset: Int, length: Int): String =
    decodeToString(offset, offset + length)

private fun ByteArray.littleEndianInt(offset: Int): Int =
    (this[offset].toInt() and 0xFF) or
        ((this[offset + 1].toInt() and 0xFF) shl 8) or
        ((this[offset + 2].toInt() and 0xFF) shl 16) or
        ((this[offset + 3].toInt() and 0xFF) shl 24)
