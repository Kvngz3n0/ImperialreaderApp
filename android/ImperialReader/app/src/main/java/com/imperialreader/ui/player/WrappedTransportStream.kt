package com.imperialreader.ui.player

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

internal object WrappedTransportStream {
    private const val PACKET_SIZE = 188
    private const val SYNC_COUNT = 4
    private const val MAX_PREFIX = 64 * 1024
    private val pngSignature = byteArrayOf(
        0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a
    )

    fun isWrapped(prefix: ByteArray, length: Int): Boolean =
        length >= pngSignature.size && pngSignature.indices.all { prefix[it] == pngSignature[it] }

    suspend fun unwrap(input: InputStream, output: OutputStream) {
        val source = if (input is BufferedInputStream) input else BufferedInputStream(input, MAX_PREFIX + PACKET_SIZE * SYNC_COUNT)
        val packet = ByteArray(PACKET_SIZE)
        val probe = ByteArray(MAX_PREFIX + PACKET_SIZE * SYNC_COUNT)
        var packets = 0L
        while (true) {
            if (packets % 1024L == 0L) currentCoroutineContext().ensureActive()
            source.mark(packet.size)
            val count = readUpTo(source, packet, packet.size)
            if (count == 0) break
            if (isWrapped(packet, count)) {
                source.reset()
                skipWrapper(source, probe)
                continue
            }
            if (count != PACKET_SIZE || packet[0] != 0x47.toByte()) {
                throw IOException("The downloaded transport stream contains an invalid packet")
            }
            output.write(packet)
            packets++
        }
        if (packets == 0L) throw IOException("The downloaded file contains no transport stream packets")
    }

    private fun skipWrapper(source: BufferedInputStream, probe: ByteArray) {
        source.mark(probe.size)
        val length = readUpTo(source, probe, probe.size)
        val limit = minOf(MAX_PREFIX, length - PACKET_SIZE * (SYNC_COUNT - 1))
        var offset = -1
        for (i in pngSignature.size until limit) {
            if ((0 until SYNC_COUNT).all { probe[i + it * PACKET_SIZE] == 0x47.toByte() }) {
                offset = i
                break
            }
        }
        if (offset < 0) throw IOException("The downloaded image-wrapped segment contains no valid transport stream")
        source.reset()
        var remaining = offset
        while (remaining > 0) {
            val skipped = source.skip(remaining.toLong()).toInt()
            if (skipped <= 0) throw IOException("Could not read the downloaded segment")
            remaining -= skipped
        }
    }

    private fun readUpTo(input: InputStream, buffer: ByteArray, limit: Int): Int {
        var total = 0
        while (total < limit) {
            val count = input.read(buffer, total, limit - total)
            if (count < 0) break
            if (count == 0) throw IOException("Could not read the downloaded file")
            total += count
        }
        return total
    }
}
