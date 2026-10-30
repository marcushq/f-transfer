package dev.fujitransfer

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream

/** Letterbox trimming of camera thumbnails, and the preview inside a RAF. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class ThumbsTest {
    /** A 160×120 thumbnail with the picture in [x, y, w, h] and black around it. */
    private fun boxed(x: Int, y: Int, w: Int, h: Int, picture: Int = Color.rgb(140, 180, 220)): Bitmap {
        val b = Bitmap.createBitmap(160, 120, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        c.drawColor(Color.BLACK)
        c.drawRect(x.toFloat(), y.toFloat(), (x + w).toFloat(), (y + h).toFloat(), Paint().apply { color = picture })
        return b
    }

    @Test fun cutsBandsOffALandscape3by2() {
        // 3:2 in 4:3: 160×107, 6–7 px of black top and bottom.
        val t = trimBlackBars(boxed(0, 7, 160, 106))
        assertEquals(160, t.width)
        assertEquals(104, t.height)
        assertEquals(Color.rgb(140, 180, 220), t.getPixel(80, 0))
    }

    @Test fun cutsBandsOffAnUprightPhoto() {
        // Portrait 2:3 rotated into the 4:3 frame: 80×120 in the middle.
        val t = trimBlackBars(boxed(40, 0, 80, 120))
        assertEquals(120, t.height)
        assertEquals(78, t.width)
    }

    @Test fun keepsAFullFramePhoto() {
        val b = boxed(0, 0, 160, 120)
        assertEquals(160, trimBlackBars(b).width)
        assertEquals(120, trimBlackBars(b).height)
    }

    @Test fun keepsANightShotDarkAtOneEdge() {
        // Black sky on top only: not a letterbox.
        val t = trimBlackBars(boxed(0, 30, 160, 90))
        assertEquals(120, t.height)
    }

    private fun jpeg(): ByteArray {
        val b = Bitmap.createBitmap(1920, 1280, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(200, 120, 60)) }
        return ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.JPEG, 80, it) }.toByteArray()
    }

    private fun raf(preview: ByteArray, at: Int = 148): ByteArray {
        val head = ByteArray(at)
        "FUJIFILMCCD-RAW 0201FF383501".toByteArray().copyInto(head)
        fun put(v: Int, i: Int) { head[i] = (v ushr 24).toByte(); head[i + 1] = (v ushr 16).toByte(); head[i + 2] = (v ushr 8).toByte(); head[i + 3] = v.toByte() }
        put(at, 84)
        put(preview.size, 88)
        return head + preview + ByteArray(4096) // raw data after it
    }

    @Test fun findsThePreviewInARaf() {
        val preview = jpeg()
        assertArrayEquals(preview, RafPreview.jpeg(raf(preview).inputStream()))
        val bmp = RafPreview.decode(raf(preview).inputStream(), 900)
        assertNotNull(bmp)
        assertEquals(960, bmp!!.width) // halved once: still at least the 900 px asked for
    }

    @Test fun ignoresFilesThatAreNotRaf() {
        assertNull(RafPreview.jpeg(jpeg().inputStream()))
    }
}
