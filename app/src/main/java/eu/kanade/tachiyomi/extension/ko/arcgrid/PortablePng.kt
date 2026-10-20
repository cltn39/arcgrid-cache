package eu.kanade.tachiyomi.extension.ko.arcgrid

import java.io.ByteArrayOutputStream

/** 1-bit grayscale PNG writer with a pure Kotlin DEFLATE stored-block stream.
 *  Deliberately uses NO android.graphics, java.awt or java.util.zip calls.
 */
internal object PortablePng {
    const val WIDTH = 744
    const val HEIGHT = 1144
    private const val PITCH = WIDTH / 8
    private val crcTable = IntArray(256) { value ->
        var c = value
        repeat(8) { c = if ((c and 1) != 0) (c ushr 1) xor 0xedb88320.toInt() else c ushr 1 }
        c
    }

    fun render(lines: List<String>): ByteArray {
        val bitmap = ByteArray(PITCH * HEIGHT) { 0xff.toByte() } // 1 => white
        for ((lineIndex, line) in lines.withIndex()) {
            val y0 = 57 + lineIndex * 38
            if (y0 + GlyphMatrix.height >= HEIGHT) break
            for ((column, c) in line.take(28).withIndex()) {
                if (c == ' ') continue
                val x0 = 42 + column * 25
                for (y in 0 until GlyphMatrix.height) {
                    for (x in 0 until GlyphMatrix.width) {
                        if (!GlyphMatrix.readBit(c.code, x, y)) continue
                        val xx = x0 + x
                        val yy = y0 + y
                        val pos = yy * PITCH + xx / 8
                        bitmap[pos] = (bitmap[pos].toInt() and (0x80 ushr (xx % 8)).inv()).toByte()
                    }
                }
            }
        }
        // PNG scanlines: filter byte 0 followed by packed 1-bit grayscale pixels.
        val scanlines = ByteArray((PITCH + 1) * HEIGHT)
        for (y in 0 until HEIGHT) {
            System.arraycopy(bitmap, y * PITCH, scanlines, y * (PITCH + 1) + 1, PITCH)
        }
        val output = ByteArrayOutputStream()
        output.write(byteArrayOf(137.toByte(),80,78,71,13,10,26,10))
        val head = ByteArrayOutputStream()
        writeInt(head, WIDTH); writeInt(head, HEIGHT)
        head.write(byteArrayOf(1,0,0,0,0)) // grayscale, 1-bit, no interlace
        chunk(output, "IHDR", head.toByteArray())
        chunk(output, "IDAT", storeDeflate(scanlines))
        chunk(output, "IEND", byteArrayOf())
        return output.toByteArray()
    }

    private fun storeDeflate(data: ByteArray): ByteArray {
        val output = ByteArrayOutputStream(data.size + 64)
        output.write(0x78); output.write(0x01) // zlib header, no compression
        var i = 0
        while (i < data.size) {
            val n = minOf(65535, data.size - i)
            output.write(if (i + n == data.size) 1 else 0) // final, stored block
            output.write(n and 255); output.write(n ushr 8)
            output.write((n xor 65535) and 255); output.write((n xor 65535) ushr 8)
            output.write(data, i, n)
            i += n
        }
        var a = 1
        var b = 0
        for (byte in data) {
            a = (a + (byte.toInt() and 255)) % 65521
            b = (b + a) % 65521
        }
        writeInt(output, (b shl 16) or a)
        return output.toByteArray()
    }

    private fun chunk(out: ByteArrayOutputStream, type: String, body: ByteArray) {
        writeInt(out, body.size)
        val typeBytes = type.toByteArray(Charsets.US_ASCII)
        out.write(typeBytes)
        out.write(body)
        var crc = -1
        for (part in arrayOf(typeBytes, body)) for (byte in part) {
            crc = crcTable[(crc xor (byte.toInt() and 255)) and 255] xor (crc ushr 8)
        }
        writeInt(out, crc.inv())
    }

    private fun writeInt(out: ByteArrayOutputStream, v: Int) {
        out.write((v ushr 24) and 255)
        out.write((v ushr 16) and 255)
        out.write((v ushr 8) and 255)
        out.write(v and 255)
    }
}
