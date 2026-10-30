package dev.fujitransfer

import dev.fujitransfer.protocol.FakeCamera
import dev.fujitransfer.protocol.FujiCamera
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import javax.net.SocketFactory

class MetadataCacheTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun survivesRestart() {
        val dir = tmp.newFolder()
        MetadataCache.forCamera(dir, "X-S20").apply {
            put(6044, "DSCF1091.JPG", "20261027T101500")
            setOrientation(6044, "DSCF1091.JPG", 6)
            put(6043, "DSCF1090.RAF", "20261026T090000")
            save()
        }
        val again = MetadataCache.forCamera(dir, "X-S20")
        assertEquals(2, again.size)
        assertEquals(MetadataCache.Entry("DSCF1091.JPG", "20261027T101500", 6), again.get(6044))
        assertNull(again.get(6043)?.orientation)
        assertEquals("one file per camera", 0, MetadataCache.forCamera(dir, "X-T5").size)
    }

    @Test fun orientationReadBeforeTheDateIsKept() {
        val c = MetadataCache.forCamera(tmp.newFolder(), "X-S20")
        // The grid reads orientation inside the camera call, the date is stored right after.
        c.setOrientation(10, "DSCF0010.JPG", 8)
        c.put(10, "DSCF0010.JPG", "20261027T120000")
        assertEquals(MetadataCache.Entry("DSCF0010.JPG", "20261027T120000", 8), c.get(10))
    }

    @Test fun otherFileOnSameHandleDropsOrientation() {
        val c = MetadataCache.forCamera(tmp.newFolder(), "X-S20")
        c.put(10, "DSCF0010.JPG", "20261027T120000")
        c.setOrientation(10, "DSCF0010.JPG", 6)
        // New card: handle 10 is a different photo now.
        c.put(10, "DSCF0500.JPG", "20261101T080000")
        assertNull(c.get(10)?.orientation)
    }

    @Test fun thumbnailsSurviveRestartAndNeverShowForAnotherPhoto() {
        val dir = tmp.newFolder()
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2, 3, 0xFF.toByte(), 0xD9.toByte())
        MetadataCache.forCamera(dir, "X-S20").apply {
            put(6044, "DSCF1091.JPG", "20261027T101500")
            saveThumb(6044, "DSCF1091.JPG", jpeg)
            save()
        }
        val again = MetadataCache.forCamera(dir, "X-S20")
        org.junit.Assert.assertArrayEquals(jpeg, again.thumb(6044, "DSCF1091.JPG"))
        assertNull("same handle, other photo", again.thumb(6044, "DSCF0001.JPG"))
        assertEquals(mapOf(6044 to MetadataCache.Entry("DSCF1091.JPG", "20261027T101500", null)), again.known(listOf(6044, 6043)))

        // New card: handle 6044 is another photo now; its old thumbnail goes away.
        again.put(6044, "DSCF0001.JPG", "20261101T080000")
        assertNull(again.thumb(6044, "DSCF1091.JPG"))
    }

    @Test fun clearForgetsEverything() {
        val dir = tmp.newFolder()
        val c = MetadataCache.forCamera(dir, "X-S20")
        c.put(1, "DSCF0001.JPG", "20261027T101500")
        c.saveThumb(1, "DSCF0001.JPG", ByteArray(100))
        c.save()
        assertTrue(c.diskBytes() > 100)
        c.clear()
        assertEquals(0, c.size)
        assertEquals(0L, c.diskBytes())
        assertEquals(0, MetadataCache.forCamera(dir, "X-S20").size)
    }

    @Test fun brokenFileIsIgnored() {
        val dir = tmp.newFolder()
        java.io.File(dir, "meta-X-S20.tsv").writeText("garbage\nnot\ta\tnumber\n")
        assertEquals(0, MetadataCache.forCamera(dir, "X-S20").size)
    }

    /** The quick check the cache relies on: the X-S20 answers ObjectFileName even without size/date. */
    @Test fun fileNameCheckOnXs20() {
        val cam = FakeCamera(listOf("DSCF0001.JPG" to ByteArray(5000), "DSCF0002.JPG" to ByteArray(5000)), xapp = true)
        cam.objectSizeUnsupported = true
        cam.datePropUnsupported = true
        cam.start()
        try {
            val camera = FujiCamera({ println(it) }, "127.0.0.1", cam.server.localPort)
            camera.connect(SocketFactory.getDefault(), "Fuji Transfer", viaBluetooth = true) {}
            val before = cam.received.count { it == dev.fujitransfer.protocol.Fuji.OC_GET_OBJECT_INFO }
            assertEquals("DSCF0002.JPG", camera.fileName(2))
            assertEquals("no slow GetObjectInfo for a cache check", before,
                cam.received.count { it == dev.fujitransfer.protocol.Fuji.OC_GET_OBJECT_INFO })
            camera.close()
        } finally {
            cam.stop()
        }
    }
}
