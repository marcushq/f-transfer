package dev.fujitransfer.protocol

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import javax.net.SocketFactory
import kotlin.random.Random

/** Camera-side resizing (XApp's "Resize"): D22E picks the size, D226 = 1 turns it on. */
class ResizeTest {
    /** A fake JPEG: random body ending in the FF D9 end marker. */
    private fun jpeg(size: Int, seed: Int) = Random(seed).nextBytes(size).also {
        it[0] = 0xFF.toByte(); it[1] = 0xD8.toByte()
        it[size - 2] = 0xFF.toByte(); it[size - 1] = 0xD9.toByte()
    }

    private val original = jpeg(2_400_000, 1)
    private val small = jpeg(300_123, 2) // not a multiple of 512, shorter than one 1 MB window
    private val raf = Random(3).nextBytes(50_000)
    private val cam = FakeCamera(listOf("DSCF0001.JPG" to original, "DSCF0002.RAF" to raf), xapp = true).apply {
        resized[1] = small
        objectSizeUnsupported = true
        datePropUnsupported = true
        thumbNeedsObjectInfo = true
        partialNeedsObjectInfo = true
        partialNeedsOriginals = true
        start()
    }

    @After fun tearDown() = cam.stop()

    private fun connect() = FujiCamera({ println(it) }, "127.0.0.1", cam.server.localPort).apply {
        connect(SocketFactory.getDefault(), "Fuji Transfer", viaBluetooth = true) {}
    }

    @Test fun smallUsesD802Length() {
        val camera = connect()
        val out = ByteArrayOutputStream()
        val got = camera.download(1, out, { false }, TransferSize.SMALL) { _, _ -> }
        assertArrayEquals(small, out.toByteArray())
        assertEquals(small.size.toLong(), got.size)
        assertEquals("S", 1, cam.props[Fuji.DPC_RESIZE_RATE])
        assertEquals("resize on", 1, cam.props[Fuji.DPC_COMPRESS_SMALL])
        assertEquals("D227 back to 0", 0, cam.props[Fuji.DPC_CORRECT_FILE_SIZE])
        camera.close()
    }

    /** No 0xD802 and the original's length announced: must stop at the JPEG's end, not keep the echo. */
    @Test fun extraSmallWithoutD802StopsAtJpegEnd() {
        cam.compressedSizeKnown = false
        val camera = connect()
        val out = ByteArrayOutputStream()
        camera.download(1, out, { false }, TransferSize.EXTRA_SMALL) { _, _ -> }
        assertArrayEquals(small, out.toByteArray())
        assertEquals("XS", 0, cam.props[Fuji.DPC_RESIZE_RATE])
        camera.close()
    }

    @Test fun rafAndOriginalStayFullSize() {
        val camera = connect()
        val rafOut = ByteArrayOutputStream()
        camera.download(2, rafOut, { false }, TransferSize.SMALL) { _, _ -> }
        assertArrayEquals("RAF is never resized", raf, rafOut.toByteArray())
        assertEquals("originals for RAF", 2, cam.props[Fuji.DPC_COMPRESS_SMALL])

        // Switching back to originals for a JPEG.
        val out = ByteArrayOutputStream()
        camera.download(1, out, { false }, TransferSize.ORIGINAL) { _, _ -> }
        assertArrayEquals(original, out.toByteArray())
        assertTrue(cam.props[Fuji.DPC_COMPRESS_SMALL] == 2)
        camera.close()
    }
}
