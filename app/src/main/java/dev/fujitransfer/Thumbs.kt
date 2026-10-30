package dev.fujitransfer

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.InputStream
import kotlin.math.abs
import kotlin.math.max

/**
 * Camera thumbnails are always 4:3 (160×120), so a 3:2 or 16:9 photo comes letterboxed with
 * black bands top and bottom, and an upright one with bands left and right. Cuts those bands
 * off so the grid's square crop shows only picture.
 *
 * A band is a run of rows (or columns) from an edge where every pixel is near black, and the
 * bands on opposite edges must match in size — a night shot that is just dark at one edge
 * keeps its pixels.
 */
fun trimBlackBars(b: Bitmap): Bitmap {
    val w = b.width
    val h = b.height
    if (w < 8 || h < 8) return b
    val row = IntArray(w)
    val col = IntArray(h)
    fun dark(px: IntArray, n: Int): Boolean {
        var i = 0
        while (i < n) {
            val c = px[i]
            // JPEG ringing leaves bars a few levels above zero.
            if (Color.red(c) > 40 || Color.green(c) > 40 || Color.blue(c) > 40) return false
            i += 1
        }
        return true
    }
    fun rowDark(y: Int): Boolean { b.getPixels(row, 0, w, 0, y, w, 1); return dark(row, w) }
    fun colDark(x: Int, top: Int, bottom: Int): Boolean {
        val n = bottom - top
        b.getPixels(col, 0, 1, x, top, 1, n)
        return dark(col, n)
    }

    var top = 0
    while (top < h / 3 && rowDark(top)) top++
    var bottom = 0
    while (bottom < h / 3 && rowDark(h - 1 - bottom)) bottom++
    if (!balanced(top, bottom, h)) { top = 0; bottom = 0 }

    val y0 = top
    val y1 = h - bottom
    var left = 0
    while (left < w / 3 && colDark(left, y0, y1)) left++
    var right = 0
    while (right < w / 3 && colDark(w - 1 - right, y0, y1)) right++
    if (!balanced(left, right, w)) { left = 0; right = 0 }

    if (top + bottom + left + right == 0) return b
    // One more pixel in from each trimmed edge: the band's soft JPEG edge.
    val x = if (left > 0) left + 1 else 0
    val y = if (top > 0) top + 1 else 0
    val cw = w - x - (if (right > 0) right + 1 else 0)
    val ch = h - y - (if (bottom > 0) bottom + 1 else 0)
    if (cw < w / 3 || ch < h / 3) return b
    return Bitmap.createBitmap(b, x, y, cw, ch)
}

/** Bands on opposite edges of about the same size, and worth trimming. */
private fun balanced(a: Int, b: Int, size: Int): Boolean =
    max(a, b) >= 2 && abs(a - b) <= max(2, size / 30)

/**
 * The JPEG preview inside a Fujifilm RAF. The header ("FUJIFILMCCD-RAW ") gives the preview's
 * offset and length at bytes 84 and 88, big-endian. Recent bodies store a preview of about
 * 1920 px or more — far sharper than the system's RAW thumbnail.
 */
object RafPreview {
    private val MAGIC = "FUJIFILMCCD-RAW".toByteArray()

    fun jpeg(input: InputStream): ByteArray? {
        val d = DataInputStream(input.buffered())
        val head = ByteArray(92)
        try {
            d.readFully(head)
        } catch (_: Exception) {
            return null
        }
        if (!head.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) return null
        val offset = int(head, 84)
        val length = int(head, 88)
        if (offset < head.size || length < 1024 || length > 64 shl 20) return null
        var skip = (offset - head.size).toLong()
        while (skip > 0) {
            val n = d.skip(skip)
            if (n <= 0) return null
            skip -= n
        }
        val out = ByteArray(length)
        return try {
            d.readFully(out)
            if (out[0] == 0xFF.toByte() && out[1] == 0xD8.toByte()) out else null
        } catch (_: Exception) {
            null
        }
    }

    /** The preview decoded no larger than about [maxSide] px, turned upright. */
    fun decode(input: InputStream, maxSide: Int): Bitmap? {
        val bytes = jpeg(input) ?: return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return null
        val degrees = try {
            ExifInterface(ByteArrayInputStream(bytes)).rotationDegrees
        } catch (_: Exception) {
            0
        }
        if (degrees == 0) return bmp
        return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(degrees.toFloat()) }, true)
    }

    private fun int(b: ByteArray, at: Int) =
        ((b[at].toInt() and 0xFF) shl 24) or ((b[at + 1].toInt() and 0xFF) shl 16) or
            ((b[at + 2].toInt() and 0xFF) shl 8) or (b[at + 3].toInt() and 0xFF)
}
