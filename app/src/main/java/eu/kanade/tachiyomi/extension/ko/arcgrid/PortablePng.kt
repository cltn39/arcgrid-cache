package eu.kanade.tachiyomi.extension.ko.arcgrid

import java.io.ByteArrayOutputStream
import kotlin.math.floor

/**
 * E-book style, phone-readable Korean text renderer (18 columns, 19 rows).
 * The iOS extension runtime may not support android.graphics or java.awt, so the original
 * Nanum bitmap glyphs are resampled to larger glyphs, encoded as 4-bit grayscale PNGs.
 * No Android graphics, AWT, or java.util.zip dependency is introduced.
 */
internal object PortablePng {
    const val WIDTH = 744
    const val HEIGHT = 1144
    const val MAX_COLUMNS = 18
    const val MAX_ROWS = 19
    private const val FONT_WIDTH = 34
    private const val FONT_HEIGHT = 40
    private const val LETTER_ADVANCE = 36
    private const val LINE_ADVANCE = 56
    private const val START_X = (WIDTH - ((MAX_COLUMNS - 1) * LETTER_ADVANCE + FONT_WIDTH)) / 2
    private const val START_Y = 48
    // At 390pt-wide phone display: about 18pt glyphs, 25pt margins and 29pt line pitch.
    private const val PITCH = WIDTH / 2 // 4-bit grayscale: two pixels per byte

    private val crcTable = IntArray(256) { value ->
        var c = value
        repeat(8) { c = if ((c and 1) != 0) (c ushr 1) xor 0xedb88320.toInt() else c ushr 1 }
        c
    }

    fun render(lines: List<String>): ByteArray {
        require(lines.size <= MAX_ROWS) { "More lines than the PNG page can display" }
        val bitmap = ByteArray(PITCH * HEIGHT) { 0xff.toByte() }
        val glyphCache = HashMap<Char, ByteArray>()
        for ((rowIndex, line) in lines.withIndex()) {
            val y0 = START_Y + rowIndex * LINE_ADVANCE
            for ((column, c) in line.take(MAX_COLUMNS).withIndex()) {
                if (c == ' ') continue
                val glyph = glyphCache.getOrPut(c) { rasterize(c) }
                val x0 = START_X + column * LETTER_ADVANCE
                for (y in 0 until FONT_HEIGHT) {
                    val base = (y0 + y) * PITCH
                    for (x in 0 until FONT_WIDTH) {
                        val tone = glyph[y * FONT_WIDTH + x].toInt() and 15
                        if (tone == 15) continue
                        val xx = x0 + x
                        val pos = base + xx / 2
                        val old = bitmap[pos].toInt() and 255
                        // Preserve the darker tone where adjacent glyph regions overlap.
                        val oldTone = if (xx % 2 == 0) old ushr 4 else old and 15
                        val newTone = minOf(oldTone, tone)
                        bitmap[pos] = (if (xx % 2 == 0)
                            ((newTone shl 4) or (old and 15))
                        else
                            ((old and 240) or newTone)).toByte()
                    }
                }
            }
        }

        val scanlines = ByteArray((PITCH + 1) * HEIGHT)
        for (y in 0 until HEIGHT) {
            System.arraycopy(bitmap, y * PITCH, scanlines, y * (PITCH + 1) + 1, PITCH)
        }
        val output = ByteArrayOutputStream()
        output.write(byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10))
        val header = ByteArrayOutputStream()
        writeInt(header, WIDTH)
        writeInt(header, HEIGHT)
        header.write(byteArrayOf(4, 0, 0, 0, 0)) // grayscale, 4-bit, no interlace
        chunk(output, "IHDR", header.toByteArray())
        chunk(output, "IDAT", storeDeflate(scanlines))
        chunk(output, "IEND", byteArrayOf())
        return output.toByteArray()
    }

    /** Bilinear upscaling adds gray edge pixels rather than making larger jagged black squares. */
    private fun rasterize(c: Char): ByteArray {
        val result = ByteArray(FONT_WIDTH * FONT_HEIGHT)
        for (y in 0 until FONT_HEIGHT) {
            val sy = (y + 0.5f) * GlyphMatrix.height / FONT_HEIGHT - 0.5f
            val y0 = floor(sy.toDouble()).toInt()
            val fy = sy - y0
            for (x in 0 until FONT_WIDTH) {
                val sx = (x + 0.5f) * GlyphMatrix.width / FONT_WIDTH - 0.5f
                val x0 = floor(sx.toDouble()).toInt()
                val fx = sx - x0
                fun ink(dx: Int, dy: Int): Float {
                    val px = x0 + dx
                    val py = y0 + dy
                    if (px !in 0 until GlyphMatrix.width || py !in 0 until GlyphMatrix.height) return 0f
                    return if (GlyphMatrix.readBit(c.code, px, py)) 1f else 0f
                }
                val upper = ink(0, 0) * (1f - fx) + ink(1, 0) * fx
                val lower = ink(0, 1) * (1f - fx) + ink(1, 1) * fx
                val coverage = (upper * (1f - fy) + lower * fy).coerceIn(0f, 1f)
                result[y * FONT_WIDTH + x] = (15 - (15f * coverage + 0.5f).toInt()).coerceIn(0, 15).toByte()
            }
        }
        return result
    }

    // zlib stream with uncompressed DEFLATE stored blocks: interoperable with iOS image decoders.
    private fun storeDeflate(data: ByteArray): ByteArray {
        val output = ByteArrayOutputStream(data.size + 64)
        output.write(0x78)
        output.write(0x01)
        var i = 0
        while (i < data.size) {
            val n = minOf(65535, data.size - i)
            output.write(if (i + n == data.size) 1 else 0)
            output.write(n and 255)
            output.write(n ushr 8)
            output.write((n xor 65535) and 255)
            output.write((n xor 65535) ushr 8)
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
