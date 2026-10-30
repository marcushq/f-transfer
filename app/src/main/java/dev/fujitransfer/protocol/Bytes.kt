package dev.fujitransfer.protocol

import java.io.ByteArrayOutputStream

/** Little-endian helpers; everything on the wire is LE. */
object LE {
    fun u16(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)

    fun u32(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or
            ((b[off + 1].toInt() and 0xFF) shl 8) or
            ((b[off + 2].toInt() and 0xFF) shl 16) or
            ((b[off + 3].toInt() and 0xFF) shl 24)

    fun u16Bytes(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte())
    fun u32Bytes(v: Int) =
        byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())
}

class LEWriter {
    private val out = ByteArrayOutputStream()
    fun u16(v: Int) = apply { out.write(LE.u16Bytes(v)) }
    fun u32(v: Int) = apply { out.write(LE.u32Bytes(v)) }
    fun bytes(b: ByteArray) = apply { out.write(b) }
    fun toByteArray(): ByteArray = out.toByteArray()
}

/** PTP string: u8 char count (including the terminating NUL), then UTF-16LE. */
object PtpString {
    /** Returns the decoded string and the number of bytes it occupied. */
    fun decode(b: ByteArray, off: Int): Pair<String, Int> {
        if (off >= b.size) return "" to 0
        val count = b[off].toInt() and 0xFF
        if (count == 0) return "" to 1
        val sb = StringBuilder()
        for (i in 0 until count) {
            val p = off + 1 + i * 2
            if (p + 1 >= b.size) break
            val c = LE.u16(b, p)
            if (c == 0) break
            sb.append(c.toChar())
        }
        return sb.toString() to (1 + count * 2)
    }
}

fun ByteArray.hexDump(max: Int = 64): String =
    take(max).joinToString(" ") { "%02x".format(it) } + if (size > max) " … (${size} B)" else ""
