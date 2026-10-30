package dev.fujitransfer.protocol

import dev.fujitransfer.Filters
import dev.fujitransfer.Photo
import dev.fujitransfer.visiblePhotos
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayOutputStream
import javax.net.SocketFactory

/** Minimal JPEG start: SOI + APP1 "Exif\0\0" + TIFF with IFD0 holding Orientation (and one more tag). */
fun exifJpeg(orientation: Int, bigEndian: Boolean = false, padTo: Int = 8_000): ByteArray {
    fun u16(v: Int) = if (bigEndian) byteArrayOf((v shr 8).toByte(), v.toByte()) else LE.u16Bytes(v)
    fun u32(v: Int) = if (bigEndian) byteArrayOf((v shr 24).toByte(), (v shr 16).toByte(), (v shr 8).toByte(), v.toByte()) else LE.u32Bytes(v)
    val tiff = ByteArrayOutputStream().apply {
        write(if (bigEndian) byteArrayOf(0x4D, 0x4D, 0, 0x2A) else byteArrayOf(0x49, 0x49, 0x2A, 0))
        write(u32(8))
        write(u16(2))
        // Make (0x010F) first so the parser has to walk the entries.
        write(u16(0x010F)); write(u16(2)); write(u32(4)); write(u32(0))
        write(u16(0x0112)); write(u16(3)); write(u32(1)); write(u16(orientation)); write(u16(0))
        write(u32(0))
    }.toByteArray()
    val app1 = byteArrayOf(0x45, 0x78, 0x69, 0x66, 0, 0) + tiff
    val out = ByteArrayOutputStream()
    out.write(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE1.toByte()))
    out.write(byteArrayOf(((app1.size + 2) shr 8).toByte(), (app1.size + 2).toByte()))
    out.write(app1)
    while (out.size() < padTo) out.write(0)
    return out.toByteArray()
}

class ExifOrientationTest {
    @Test fun readsLittleAndBigEndian() {
        assertEquals(6, ExifOrientation.parse(exifJpeg(6)))
        assertEquals(8, ExifOrientation.parse(exifJpeg(8, bigEndian = true)))
        assertEquals(1, ExifOrientation.parse(exifJpeg(1)))
    }

    @Test fun findsExifInsideHeifLikeData() {
        // HEIF keeps EXIF in an item somewhere after the boxes; only the marker matters.
        val heif = ByteArray(3_000) { 0x11 } + exifJpeg(6).copyOfRange(4, 200)
        assertEquals(6, ExifOrientation.parse(heif))
    }

    @Test fun noExifIsNull() {
        assertNull(ExifOrientation.parse(ByteArray(4096)))
        assertNull(ExifOrientation.parse(exifJpeg(6).copyOf(20))) // truncated before IFD0
    }

    @Test fun degrees() {
        assertEquals(90, ExifOrientation.degrees(6))
        assertEquals(270, ExifOrientation.degrees(8))
        assertEquals(180, ExifOrientation.degrees(3))
        assertEquals(0, ExifOrientation.degrees(null))
    }
}

class OrientationOverWifiTest {
    private val portraitJpeg = exifJpeg(6, padTo = 20_000)
    private val raf: ByteArray = run {
        // RAF: big-endian JPEG offset at byte 84; put the embedded JPEG at an unaligned offset.
        val jpegAt = 1_000
        val b = ByteArray(jpegAt) { 0 }
        "FUJIFILMCCD-RAW ".forEachIndexed { i, c -> b[i] = c.code.toByte() }
        b[84] = (jpegAt shr 24).toByte(); b[85] = (jpegAt shr 16).toByte(); b[86] = (jpegAt shr 8).toByte(); b[87] = jpegAt.toByte()
        b + exifJpeg(8, bigEndian = true, padTo = 10_000)
    }
    private val cam = FakeCamera(listOf("DSCF0001.JPG" to portraitJpeg, "DSCF0002.RAF" to raf), xapp = true).also { it.start() }

    @After fun tearDown() = cam.stop()

    @Test fun readsOrientationFromJpegAndRaf() {
        val camera = FujiCamera({ println(it) }, "127.0.0.1", cam.server.localPort)
        camera.connect(SocketFactory.getDefault(), "Fuji Transfer", viaBluetooth = true) {}
        assertEquals(6, camera.orientation(1, "DSCF0001.JPG"))
        assertEquals(8, camera.orientation(2, "DSCF0002.RAF"))
        camera.close()
    }

    /**
     * Everything the X-S20 logs of 27 Oct showed: no ObjectSize, no DateCreated, GetThumb
     * stalls unless GetObjectInfo for the same handle came right before, and file reads stall
     * until D226 = 2. A regression here shows up as a 15 s read timeout.
     */
    @Test fun xs20FullProfile() {
        cam.partialNeedsOriginals = true
        cam.objectSizeUnsupported = true
        cam.datePropUnsupported = true
        cam.thumbNeedsObjectInfo = true
        cam.partialNeedsObjectInfo = true
        val camera = FujiCamera({ println(it) }, "127.0.0.1", cam.server.localPort)
        camera.connect(SocketFactory.getDefault(), "Fuji Transfer", viaBluetooth = true) {}

        // The grid's order: details, thumbnail, then orientation.
        val info = camera.describe(1)
        assertEquals("DSCF0001.JPG", info?.fileName)
        assertEquals("date from GetObjectInfo", "20261027T101500", info?.captureDate)
        org.junit.Assert.assertNotNull(camera.thumbnail(1))
        // Thumbnail of another photo straight after: must not stall either.
        org.junit.Assert.assertNotNull(camera.thumbnail(2))
        assertEquals(6, camera.orientation(1, "DSCF0001.JPG"))
        assertNull("nothing in flight after a clean run", camera.stalledCommand)

        val out = ByteArrayOutputStream()
        val real = camera.download(1, out, { false }) { _, _ -> }
        assertEquals(portraitJpeg.size.toLong(), real.size)
        org.junit.Assert.assertArrayEquals(portraitJpeg, out.toByteArray())
        camera.close()
    }

    /** What the X-S20 log of 27 Oct showed: no ObjectSize, and file reads stall until D226 = 2. */
    /**
     * 28 Oct: DateCreated sometimes never answers on an X-S20. A body that refuses ObjectSize
     * must not be asked for DateCreated at all; GetObjectInfo has the date.
     */
    @Test fun xs20NeverAsksDateCreated() {
        cam.partialNeedsOriginals = true
        cam.objectSizeUnsupported = true
        cam.dateHangs = true
        val camera = FujiCamera({ println(it) }, "127.0.0.1", cam.server.localPort)
        camera.connect(SocketFactory.getDefault(), "Fuji Transfer", viaBluetooth = true) {}

        val info = camera.describe(1)
        assertEquals("DSCF0001.JPG", info?.fileName)
        assertEquals("20261027T101500", info?.captureDate)
        assertEquals(false, info?.sizeTrusted)
        assertEquals("20261027T101500", camera.describe(2)?.captureDate)
        assertNull(camera.stalledCommand)

        // Would hang (and time out) if D226 = 2 weren't sent before the first file read.
        assertEquals(6, camera.orientation(1, "DSCF0001.JPG"))
        assertEquals(false, camera.orientationInFlight)

        val out = ByteArrayOutputStream()
        val real = camera.download(1, out, { false }) { _, _ -> }
        assertEquals("size from ObjectInfo with D227 = 1", portraitJpeg.size.toLong(), real.size)
        org.junit.Assert.assertArrayEquals(portraitJpeg, out.toByteArray())
        camera.close()
    }
}

class FiltersTest {
    private fun photo(h: Int, date: String?, done: Boolean = false) = Photo(
        handle = h,
        info = date?.let { ObjectInfo(h, "DSCF$h.JPG", 1, it) },
        downloaded = done,
    )

    private val photos = listOf(
        photo(1, "20261027T101500"),
        photo(2, "20261027T183000", done = true),
        photo(3, "20261026T090000"),
        photo(4, null),
    )

    @Test fun byDay() {
        assertEquals(listOf(1, 2), visiblePhotos(photos, Filters(day = "20261027")).map { it.handle })
    }

    @Test fun hideTransferred() {
        assertEquals(listOf(1, 3, 4), visiblePhotos(photos, Filters(hideDownloaded = true)).map { it.handle })
    }

    @Test fun both() {
        assertEquals(listOf(1), visiblePhotos(photos, Filters(day = "20261027", hideDownloaded = true)).map { it.handle })
    }
}
