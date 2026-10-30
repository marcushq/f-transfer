package dev.fujitransfer

import dev.fujitransfer.protocol.FakeCamera
import dev.fujitransfer.protocol.FujiCamera
import dev.fujitransfer.protocol.exifJpeg
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import javax.net.SocketFactory
import kotlin.random.Random

/** The one-file thumbnail pack and the full-card preparation it serves. */
class ThumbPackTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun thumb(seed: Int, size: Int = 12_000) = Random(seed).nextBytes(size)

    @Test fun manyThumbnailsOneFileAndBackAfterRestart() {
        val dir = tmp.newFolder()
        val c = MetadataCache.forCamera(dir, "X-S20")
        for (h in 1..300) {
            c.put(h, "DSCF%04d.JPG".format(h), "20261027T1015%02d".format(h % 60))
            c.saveThumb(h, "DSCF%04d.JPG".format(h), thumb(h))
            c.setOrientation(h, "DSCF%04d.JPG".format(h), if (h % 3 == 0) 6 else 1)
        }
        c.close()
        // Two files, whatever the number of photos.
        assertEquals(setOf("meta-X-S20.tsv", "meta-X-S20.pack"), dir.listFiles()!!.map { it.name }.toSet())

        val again = MetadataCache.forCamera(dir, "X-S20")
        for (h in listOf(1, 150, 300)) {
            assertArrayEquals(thumb(h), again.thumb(h, "DSCF%04d.JPG".format(h)))
            assertTrue(again.isComplete(h, needOrientation = true))
        }
        assertEquals(6, again.get(150)?.orientation)
        again.close()
    }

    @Test fun completenessNeedsEverything() {
        val c = MetadataCache.forCamera(tmp.newFolder(), "X-S20")
        c.put(1, "DSCF0001.JPG", "20261027T101500")
        assertFalse("no thumbnail yet", c.isComplete(1, needOrientation = false))
        c.saveThumb(1, "DSCF0001.JPG", thumb(1))
        assertTrue(c.isComplete(1, needOrientation = false))
        assertFalse("orientation not read", c.isComplete(1, needOrientation = true))
        c.setOrientation(1, "DSCF0001.JPG", 1)
        assertTrue(c.isComplete(1, needOrientation = true))
        // Another photo on the same handle (new card): nothing is complete any more.
        c.put(1, "DSCF0900.JPG", "20261101T080000")
        assertFalse(c.isComplete(1, needOrientation = false))
        assertNull(c.thumb(1, "DSCF0001.JPG"))
        c.close()
    }

    @Test fun oldThumbnailFolderIsImportedOnce() {
        val dir = tmp.newFolder()
        File(dir, "meta-X-S20.tsv").writeText("6044\tDSCF1091.JPG\t20261027T101500\t6\n")
        val legacy = File(dir, "meta-X-S20-thumbs").apply { mkdirs() }
        File(legacy, "6044_DSCF1091.JPG.jpg").writeBytes(thumb(7))
        File(legacy, "6043_DSCF1090.JPG.jpg").writeBytes(thumb(8)) // no entry: dropped

        val c = MetadataCache.forCamera(dir, "X-S20")
        assertArrayEquals(thumb(7), c.thumb(6044, "DSCF1091.JPG"))
        assertFalse("folder removed after import", legacy.exists())
        c.close()
        assertArrayEquals(thumb(7), MetadataCache.forCamera(dir, "X-S20").thumb(6044, "DSCF1091.JPG"))
    }

    @Test fun packCutShortByACrashLosesOnlyTheLostThumbnails() {
        val dir = tmp.newFolder()
        MetadataCache.forCamera(dir, "X-S20").apply {
            put(1, "A.JPG", "20261027T101500"); saveThumb(1, "A.JPG", thumb(1))
            put(2, "B.JPG", "20261027T101501"); saveThumb(2, "B.JPG", thumb(2))
            close()
        }
        val pack = File(dir, "meta-X-S20.pack")
        java.io.RandomAccessFile(pack, "rw").use { it.setLength(pack.length() - 100) }
        val c = MetadataCache.forCamera(dir, "X-S20")
        assertNotNull(c.thumb(1, "A.JPG"))
        assertNull("its bytes are gone", c.thumb(2, "B.JPG"))
        assertEquals("still knows the date", "20261027T101501", c.get(2)?.date)
        c.close()
    }

    @Test fun newCardCompactsThePack() {
        val dir = tmp.newFolder()
        val c = MetadataCache.forCamera(dir, "X-S20")
        // 1,000 thumbnails of 12 KB (≈12 MB), then a "new card" replaces every one of them.
        for (h in 1..1000) { c.put(h, "OLD$h.JPG", "20261001T000000"); c.saveThumb(h, "OLD$h.JPG", thumb(h)) }
        c.save()
        for (h in 1..1000) { c.put(h, "NEW$h.JPG", "20261101T000000"); c.saveThumb(h, "NEW$h.JPG", thumb(h + 5000)) }
        c.close()
        val pack = File(dir, "meta-X-S20.pack")
        assertTrue("old thumbnails dropped: ${pack.length()}", pack.length() <= 1000L * 12_000)
        val again = MetadataCache.forCamera(dir, "X-S20")
        assertArrayEquals(thumb(5500), again.thumb(500, "NEW500.JPG"))
        again.close()
    }

    /** One pass per photo on the strictest simulated X-S20: details, thumbnail, orientation. */
    @Test fun indexPhotoOnXs20() {
        val portrait = exifJpeg(6, padTo = 20_000)
        val cam = FakeCamera(listOf("DSCF0001.JPG" to portrait, "DSCF0002.JPG" to exifJpeg(1, padTo = 20_000)), xapp = true).apply {
            objectSizeUnsupported = true
            datePropUnsupported = true
            dateHangs = true
            thumbNeedsObjectInfo = true
            partialNeedsObjectInfo = true
            partialNeedsOriginals = true
            start()
        }
        try {
            val camera = FujiCamera({ println(it) }, "127.0.0.1", cam.server.localPort)
            camera.connect(SocketFactory.getDefault(), "Fuji Transfer", viaBluetooth = true) {}
            for (h in listOf(1, 2, 1)) {
                val p = camera.indexPhoto(h, withOrientation = true)
                assertNotNull(p)
                assertNotNull("thumbnail #$h", p!!.thumb)
                assertEquals("20261027T101500", p.info.captureDate)
                assertEquals(if (h == 1) 6 else 1, p.orientation)
            }
            assertNull(camera.stalledCommand)
            camera.close()
        } finally {
            cam.stop()
        }
    }
}
