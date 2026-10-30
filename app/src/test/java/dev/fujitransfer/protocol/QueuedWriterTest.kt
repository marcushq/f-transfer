package dev.fujitransfer.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream

/** Saving on its own thread must still write every byte, in order, and report failures. */
class QueuedWriterTest {
    @Test fun writesEverythingInOrder() {
        val out = ByteArrayOutputStream()
        val w = QueuedWriter(out, depth = 2)
        val expected = ByteArrayOutputStream()
        for (i in 0 until 50) {
            val chunk = ByteArray(1000 + i) { (it + i).toByte() }
            // Only the first `len` bytes count, like a short read from the camera.
            val len = chunk.size - i
            expected.write(chunk, 0, len)
            w.write(chunk, len)
        }
        w.finish()
        assertArrayEquals(expected.toByteArray(), out.toByteArray())
    }

    @Test fun reportsAFailedWrite() {
        val broken = object : OutputStream() {
            override fun write(b: Int) = throw IOException("Hết dung lượng")
            override fun write(b: ByteArray, off: Int, len: Int) = throw IOException("Hết dung lượng")
        }
        val w = QueuedWriter(broken, depth = 1)
        try {
            repeat(20) { w.write(ByteArray(10), 10) }
            w.finish()
            fail("the storage error should surface")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("dung lượng"))
        }
    }

    @Test fun abortStopsWithoutHanging() {
        val slow = object : OutputStream() {
            override fun write(b: Int) = Thread.sleep(50)
            override fun write(b: ByteArray, off: Int, len: Int) = Thread.sleep(50)
        }
        val w = QueuedWriter(slow, depth = 2)
        repeat(3) { w.write(ByteArray(10), 10) }
        val t0 = System.currentTimeMillis()
        w.abort()
        assertTrue(System.currentTimeMillis() - t0 < 1_000)
    }
}
