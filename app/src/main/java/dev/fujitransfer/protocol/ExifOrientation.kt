package dev.fujitransfer.protocol

/**
 * Finds the EXIF Orientation tag (0x0112) in the first few KB of a file. Fujifilm's own
 * thumbnails are never rotated, so the grid needs this to show portrait shots upright.
 *
 * Works on JPEG (APP1 at the start), on HEIF (the Exif item carries the same "Exif\0\0"
 * marker) and on the JPEG embedded in a RAF.
 */
object ExifOrientation {
    const val NORMAL = 1

    /** 1–8 as in EXIF, or null when no orientation was found in [b]. */
    fun parse(b: ByteArray): Int? {
        val tiff = findTiff(b) ?: return null
        val little = b[tiff] == 'I'.code.toByte()
        fun u16(o: Int) = if (little) LE.u16(b, o) else ((b[o].toInt() and 0xFF) shl 8) or (b[o + 1].toInt() and 0xFF)
        fun u32(o: Int) = if (little) LE.u32(b, o) else (u16(o) shl 16) or u16(o + 2)
        if (tiff + 8 > b.size) return null
        val ifd0 = tiff + u32(tiff + 4)
        if (ifd0 < tiff || ifd0 + 2 > b.size) return null
        val count = u16(ifd0)
        for (i in 0 until count) {
            val e = ifd0 + 2 + i * 12
            if (e + 12 > b.size) return null
            if (u16(e) == 0x0112) return u16(e + 8).takeIf { it in 1..8 }
        }
        return null
    }

    /** Offset of the TIFF header ("II*\0" / "MM\0*") that follows an "Exif\0\0" marker. */
    private fun findTiff(b: ByteArray): Int? {
        val marker = byteArrayOf(0x45, 0x78, 0x69, 0x66, 0, 0) // "Exif\0\0"
        var i = 0
        while (i + marker.size + 4 <= b.size) {
            if (matches(b, i, marker)) {
                val t = i + marker.size
                if (isTiff(b, t)) return t
            }
            i++
        }
        return null
    }

    private fun isTiff(b: ByteArray, o: Int) =
        (b[o] == 0x49.toByte() && b[o + 1] == 0x49.toByte() && b[o + 2] == 0x2A.toByte() && b[o + 3] == 0.toByte()) ||
            (b[o] == 0x4D.toByte() && b[o + 1] == 0x4D.toByte() && b[o + 2] == 0.toByte() && b[o + 3] == 0x2A.toByte())

    private fun matches(b: ByteArray, at: Int, m: ByteArray): Boolean {
        for (k in m.indices) if (b[at + k] != m[k]) return false
        return true
    }

    /** Degrees to rotate clockwise so the image shows upright. Mirrored variants are not produced by cameras. */
    fun degrees(orientation: Int?): Int = when (orientation) {
        3, 4 -> 180
        5, 6 -> 90
        7, 8 -> 270
        else -> 0
    }
}
