package dev.fujitransfer.protocol

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import javax.net.SocketFactory
import kotlin.random.Random

/**
 * The 0x201D failures of 28 Oct: the X-S20 announces a rounded-up length and refuses reads
 * that run past the real end. Sizes taken from that log so the last window lands the same way.
 */
class RoundedSizeTest {
    private fun jpeg(size: Int, seed: Int) = Random(seed).nextBytes(size).also {
        it[0] = 0xFF.toByte(); it[1] = 0xD8.toByte()
        it[size - 2] = 0xFF.toByte(); it[size - 1] = 0xD9.toByte()
    }

    // DSCF0957 was announced as 11,502,265 B; a real size a little below that.
    private val a = jpeg(11_489_001, 1)
    // A size whose real end sits at an odd, unaligned offset.
    private val b = jpeg(5_554_001, 2)
    // A file whose real length is exactly a multiple of the 1 MB window.
    private val c = jpeg(3 * 0x100000, 3)
    private val broken = Random(4).nextBytes(2_000_000) // a "JPEG" that doesn't end with FF D9

    private val cam = FakeCamera(
        listOf("DSCF0957.JPG" to a, "DSCF4736.JPG" to b, "DSCF0001.JPG" to c, "DSCF0002.JPG" to broken),
        xapp = true,
    ).apply {
        roundedSizes = true
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

    @Test fun findsTheRealEnd() {
        val camera = connect()
        for ((handle, expected) in listOf(1 to a, 2 to b, 3 to c)) {
            val out = ByteArrayOutputStream()
            val got = camera.download(handle, out, { false }) { _, _ -> }
            assertArrayEquals("handle $handle", expected, out.toByteArray())
            assertEquals(expected.size.toLong(), got.size)
        }
        assertEquals("D227 back to 0", 0, cam.props[Fuji.DPC_CORRECT_FILE_SIZE])
        camera.close()
    }

    /** Announced length below the real one: must keep reading to the end, not save a cut-off JPEG. */
    @Test fun lengthRoundedDownStillGetsWholeFile() {
        cam.roundDown = true
        val camera = connect()
        for ((handle, expected) in listOf(1 to a, 2 to b, 3 to c)) {
            val out = ByteArrayOutputStream()
            camera.download(handle, out, { false }) { _, _ -> }
            assertArrayEquals("handle $handle", expected, out.toByteArray())
        }
        camera.close()
    }

    /** DSCF0868 on 29 Oct: real file 4+ MB longer than announced. Must not stop at a fixed margin. */
    @Test fun lengthFarTooSmallStillGetsWholeFile() {
        cam.announceShortBy = 4_194_304 + 100_001
        val camera = connect()
        val out = ByteArrayOutputStream()
        val got = camera.download(1, out, { false }) { _, _ -> }
        assertArrayEquals(a, out.toByteArray())
        assertEquals(a.size.toLong(), got.size)
        camera.close()
    }

    @Test fun incompleteJpegIsNotKept() {
        val camera = connect()
        try {
            camera.download(4, ByteArrayOutputStream(), { false }) { _, _ -> }
            fail("a JPEG without its end marker must be refused")
        } catch (e: PtpException) {
            assertTrue(e.message!!.contains("không hoàn chỉnh"))
        }
        // The session survives and the next photo still downloads.
        val out = ByteArrayOutputStream()
        camera.download(2, out, { false }) { _, _ -> }
        assertArrayEquals(b, out.toByteArray())
        camera.close()
    }
}
