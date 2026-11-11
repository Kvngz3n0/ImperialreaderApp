package com.imperialreader.ui.player

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException

class WrappedTransportStreamTest {
    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)

    private fun packets(count: Int, start: Int): ByteArray =
        ByteArray(count * 188) { index ->
            if (index % 188 == 0) 0x47 else ((index + start) % 251).toByte()
        }

    @Test
    fun removesImagePrefixesAtEverySegmentBoundary() = runBlocking {
        val first = packets(20, 1)
        val second = packets(13, 7)
        val wrapper = png + ByteArray(244)
        val output = ByteArrayOutputStream()
        WrappedTransportStream.unwrap(ByteArrayInputStream(wrapper + first + wrapper + second), output)
        assertArrayEquals(first + second, output.toByteArray())
    }

    @Test
    fun identifiesPngWithoutTreatingPlainTransportStreamAsWrapped() {
        assertTrue(WrappedTransportStream.isWrapped(png, png.size))
        assertFalse(WrappedTransportStream.isWrapped(png, 7))
        assertFalse(WrappedTransportStream.isWrapped(packets(1, 0), 8))
    }

    @Test
    fun preservesPlainTransportStream() = runBlocking {
        val bytes = packets(8, 3)
        val output = ByteArrayOutputStream()
        WrappedTransportStream.unwrap(ByteArrayInputStream(bytes), output)
        assertArrayEquals(bytes, output.toByteArray())
    }

    @Test(expected = IOException::class)
    fun rejectsTruncatedWrappedSegment() = runBlocking {
        WrappedTransportStream.unwrap(ByteArrayInputStream(png + ByteArray(50)), ByteArrayOutputStream())
    }

    @Test(expected = IOException::class)
    fun rejectsTruncatedPacket() = runBlocking {
        WrappedTransportStream.unwrap(ByteArrayInputStream(packets(5, 0) + byteArrayOf(0x47)), ByteArrayOutputStream())
    }
}
