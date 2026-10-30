package dev.fujitransfer.protocol

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.net.ServerSocket
import java.net.Socket
import javax.net.SocketFactory
import kotlin.concurrent.thread
import kotlin.random.Random

/**
 * A loopback body that answers like an X-S10 in Wireless Communication mode:
 * Fuji init, "press OK" for the first event polls, remote version 0x2000A, the 100 KB
 * size lie while D227 is 0, and 1 MB GetPartialObject windows.
 */
class FakeCamera(private val files: List<Pair<String, ByteArray>>, private val xapp: Boolean = false) {
    val server = ServerSocket(0)
    val props = mutableMapOf(
        Fuji.DPC_CAMERA_STATE to 0,
        Fuji.DPC_CLIENT_STATE to 0,
        Fuji.DPC_CORRECT_FILE_SIZE to 0,
        Fuji.DPC_GET_OBJECT_VERSION to 4,
        Fuji.DPC_REMOTE_GET_OBJECT_VERSION to 4,
        Fuji.DPC_IMAGE_GET_VERSION to 2,
        Fuji.DPC_REMOTE_VERSION to if (xapp) 0x20010 else 0x2000A,
    ).apply {
        if (xapp) {
            put(Fuji.DPC_REMOTE_PHOTO_VIEW_EX, 3)
            put(Fuji.DPC_COMPRESS_SMALL, 0)
            put(Fuji.DPC_UNKNOWN_D22B, 0)
            put(Fuji.DPC_IMAGE_IMPORT_COUNT, files.size)
        }
    }
    val received = mutableListOf<Int>()
    private var polls = 0
    @Volatile var initFailsLeft = 1
    /** X-S20 hypothesis: GetObjectInfo refused, object properties answered. */
    @Volatile var refuseObjectInfo = false
    @Volatile var answerObjectProps = true
    /** New-card behaviour seen on an X-S20: busy / store-not-available until indexing ends. */
    @Volatile var indexingPollsLeft = 0
    /** X-S20: GetPartialObject gets no answer while D226 is still 0 (XApp sets it before any file read). */
    @Volatile var partialNeedsOriginals = false
    /** X-S20: ObjectSize (0xDC04) answers 0x2006, name and date still work. */
    @Volatile var objectSizeUnsupported = false
    /** X-S20: DateCreated (0xDC08) answers 0x2006 as well. */
    @Volatile var datePropUnsupported = false
    /** X-S20 / libfuji: GetThumb never answers unless GetObjectInfo for that handle came right before. */
    @Volatile var thumbNeedsObjectInfo = false
    /**
     * X-S20 on 28 Oct: GetPartialObject never answers unless GetObjectInfo for that handle came
     * right before (or it continues a run of reads of the same handle, as a download does).
     */
    @Volatile var partialNeedsObjectInfo = false
    /**
     * Resized copies served while D226 = 1 (JPEG only). Like the X100VI in fuji-bridge's notes,
     * ObjectInfo keeps announcing the original's length and reads past the end echo the last
     * 257 bytes. [compressedSizeKnown] controls whether 0xD802 gives the real length.
     */
    val resized = HashMap<Int, ByteArray>()
    @Volatile var compressedSizeKnown = true
    /**
     * X-S20 on 28 Oct: with D227 = 1 ObjectInfo gives a rounded-up length (always …2B9 modulo
     * 16 KB) and GetPartialObject answers 0x201D for any read that runs past the real end.
     */
    @Volatile var roundedSizes = false
    /** X-S20: DateCreated sometimes never answers. */
    @Volatile var dateHangs = false
    /** Round the announced length down instead of up (it isn't known which way the X-S20 goes). */
    @Volatile var roundDown = false
    /** Announce this many bytes less than the real length (DSCF0868: 4.2 MB on 29 Oct). */
    @Volatile var announceShortBy = 0
    private fun announced(size: Int) = when {
        !roundedSizes -> size
        announceShortBy > 0 -> size - announceShortBy
        roundDown -> (size / 0x4000) * 0x4000 - 0x4000 + 0x2B9
        else -> ((size + 0x3FFF) / 0x4000) * 0x4000 + 0x2B9
    }
    private fun body(handle: Int): ByteArray {
        val f = files[handle - 1].second
        return if (props[Fuji.DPC_COMPRESS_SMALL] == 1) resized[handle] ?: f else f
    }
    private var prevOp = 0
    private var prevParam = 0
    private var prevOk = false
    private val indexing get() = indexingPollsLeft > 0

    fun start() = thread(isDaemon = true) {
        while (!server.isClosed) {
            val s = try { server.accept() } catch (_: Exception) { return@thread }
            thread(isDaemon = true) { serve(s) }
        }
    }

    private fun serve(s: Socket) {
        val inp = DataInputStream(s.getInputStream())
        val out = s.getOutputStream()
        fun read(): ByteArray {
            val head = ByteArray(4); inp.readFully(head)
            val len = LE.u32(head, 0)
            val p = ByteArray(len); System.arraycopy(head, 0, p, 0, 4); inp.readFully(p, 4, len - 4)
            return p
        }
        fun container(type: Int, code: Int, tid: Int, body: ByteArray = ByteArray(0)) =
            LEWriter().u32(12 + body.size).u16(type).u16(code).u32(tid).bytes(body).toByteArray()

        try {
            // Init handshake
            while (true) {
                val init = read()
                assertEquals(0x52, init.size)
                assertEquals(Fuji.PROTOCOL_VERSION, LE.u32(init, 8))
                if (initFailsLeft-- > 0) {
                    out.write(LEWriter().u32(12).u32(Fuji.INIT_FAIL).u32(0).toByteArray()); continue
                }
                val ack = ByteArray(82)
                System.arraycopy(LE.u32Bytes(0x52), 0, ack, 0, 4)
                System.arraycopy(LE.u32Bytes(2), 0, ack, 4, 4)
                "X-S10".forEachIndexed { i, c -> ack[28 + i * 2] = c.code.toByte() }
                out.write(ack); break
            }
            while (true) {
                val cmd = read()
                val code = LE.u16(cmd, 6)
                val tid = LE.u32(cmd, 8)
                val params = IntArray((cmd.size - 12) / 4) { LE.u32(cmd, 12 + it * 4) }
                received += code
                var data: ByteArray? = null
                var rc = Fuji.RC_OK
                var silent = false
                when (code) {
                    Fuji.OC_OPEN_SESSION, Fuji.OC_CLOSE_SESSION,
                    Fuji.OC_INITIATE_OPEN_CAPTURE, Fuji.OC_TERMINATE_OPEN_CAPTURE -> {}
                    Fuji.OC_GET_IMAGE_IMPORT_FOLDERS ->
                        if (!xapp) rc = 0x2005
                        else if (indexing) { indexingPollsLeft--; rc = Fuji.RC_DEVICE_BUSY }
                        else data = ByteArray(8)
                    Fuji.OC_GET_IMAGE_IMPORT_DATES ->
                        if (!xapp) rc = 0x2005
                        else if (indexing) rc = Fuji.RC_STORE_NOT_AVAILABLE
                        else data = ByteArray(8)
                    Fuji.OC_GET_EXTENSION_OBJECT_INFO, Fuji.OC_GET_EXTENSION_THUMB ->
                        if (!xapp) rc = 0x2005 else data = ByteArray(8)
                    Fuji.OC_GET_OBJECT_PROP_VALUE -> {
                        val f = files.getOrNull(params[0] - 1)
                        if (indexing) rc = Fuji.RC_INVALID_OBJECT_HANDLE
                        else if (!xapp || !answerObjectProps || f == null) rc = 0x200A
                        else when (params[1]) {
                            Fuji.OPC_OBJECT_SIZE ->
                                if (objectSizeUnsupported) rc = 0x2006
                                else data = LE.u32Bytes(f.second.size) + LE.u32Bytes(0)
                            Fuji.OPC_OBJECT_FILE_NAME -> data = ptpString(f.first)
                            Fuji.OPC_COMPRESSED_SIZE ->
                                if (!compressedSizeKnown || resized[params[0]] == null) rc = 0x2006
                                else data = LE.u32Bytes(resized[params[0]]!!.size) + LE.u32Bytes(0)
                            Fuji.OPC_DATE_CREATED ->
                                if (dateHangs) silent = true
                                else if (datePropUnsupported) rc = 0x2006
                                else data = ptpString("20261027T101500")
                            else -> rc = 0x200A
                        }
                    }
                    Fuji.OC_GET_PROP_VALUE -> {
                        val prop = params[0]
                        if (prop == Fuji.DPC_EVENTS_LIST) {
                            polls++
                            if (polls == 3) props[Fuji.DPC_CAMERA_STATE] = Fuji.STATE_REMOTE_ACCESS
                            data = LEWriter().u16(2)
                                .u16(Fuji.DPC_CAMERA_STATE).u32(props[Fuji.DPC_CAMERA_STATE]!!)
                                .u16(Fuji.DPC_OBJECT_COUNT).u32(files.size)
                                .toByteArray()
                        } else if (prop == Fuji.DPC_IMAGE_IMPORT_HANDLES && xapp && indexing) {
                            rc = Fuji.RC_GENERAL_ERROR
                        } else if (prop == Fuji.DPC_IMAGE_IMPORT_HANDLES && xapp) {
                            // X100VI-style: newest first.
                            data = LEWriter().u32(files.size)
                                .apply { (files.size downTo 1).forEach { u32(it) } }.toByteArray()
                        } else {
                            val v = props[prop]
                            if (v == null) rc = 0x200A else data = LE.u32Bytes(v)
                        }
                    }
                    Fuji.OC_SET_PROP_VALUE -> {
                        val d = read()
                        assertEquals(Fuji.TYPE_DATA, LE.u16(d, 4))
                        val body = d.copyOfRange(12, d.size)
                        props[params[0]] = if (body.size >= 4) LE.u32(body, 0) else LE.u16(body, 0)
                    }
                    Fuji.OC_GET_OBJECT_INFO -> {
                        val f = files.getOrNull(params[0] - 1)
                        if (indexing) rc = Fuji.RC_DEVICE_BUSY
                        else if (refuseObjectInfo) rc = 0x2005
                        else if (f == null) rc = 0x2009 else {
                            val head = ByteArray(52)
                            val size = if (props[Fuji.DPC_CORRECT_FILE_SIZE] == 1) announced(f.second.size) else 102_400
                            System.arraycopy(LE.u32Bytes(size), 0, head, 13, 4)
                            data = head + ptpString(f.first) + ptpString("20261027T101500")
                        }
                    }
                    Fuji.OC_GET_THUMB -> if (thumbNeedsObjectInfo && !(prevOp == Fuji.OC_GET_OBJECT_INFO && prevParam == params[0] && prevOk)) {
                        silent = true
                    } else if (indexing) rc = Fuji.RC_INVALID_OBJECT_HANDLE else data = ByteArray(500) { 7 }
                    Fuji.OC_GET_PARTIAL_OBJECT -> if (
                        (partialNeedsOriginals && (props[Fuji.DPC_COMPRESS_SMALL] ?: 0) == 0) ||
                        (partialNeedsObjectInfo && !(prevParam == params[0] && (
                            (prevOp == Fuji.OC_GET_OBJECT_INFO && prevOk) || prevOp == Fuji.OC_GET_PARTIAL_OBJECT)))
                    ) {
                        silent = true
                    } else {
                        val f = body(params[0])
                        val off = params[1]; val len = minOf(params[2], Fuji.MAX_PARTIAL)
                        if (roundedSizes && off.toLong() + len > f.size) rc = Fuji.RC_INVALID_PARAMETER
                        else data = if (off >= f.size && f !== files[params[0] - 1].second) {
                            f.copyOfRange(maxOf(0, f.size - 257), f.size)
                        } else {
                            f.copyOfRange(minOf(off, f.size), minOf(f.size, off + len))
                        }
                    }
                    else -> rc = 0x2005
                }
                prevOp = code; prevParam = params.firstOrNull() ?: 0; prevOk = rc == Fuji.RC_OK
                if (silent) continue
                var reply = ByteArray(0)
                if (data != null) reply += container(Fuji.TYPE_DATA, code, tid, data)
                reply += container(Fuji.TYPE_RESPONSE, rc, tid)
                out.write(reply)
            }
        } catch (_: Exception) {
            s.close()
        }
    }

    private fun ptpString(s: String): ByteArray {
        val w = ByteArrayOutputStream()
        w.write(s.length + 1)
        s.forEach { w.write(LE.u16Bytes(it.code)) }
        w.write(LE.u16Bytes(0))
        return w.toByteArray()
    }

    fun stop() = server.close()
}

class FakeCameraTest {
    private val big = Random(1).nextBytes(2_500_000) // spans three 1 MB windows
    private val small = Random(2).nextBytes(40_000)
    private lateinit var cam: FakeCamera
    private val log = mutableListOf<String>()

    @Before fun setUp() {
        cam = FakeCamera(listOf("DSCF0002.JPG" to big, "DSCF0001.RAF" to small))
        cam.start()
    }

    @After fun tearDown() = cam.stop()

    @Test fun connectListAndDownload() {
        val camera = FujiCamera({ log += it; println(it) }, "127.0.0.1", cam.server.localPort)
        var askedForOk = false
        camera.connect(SocketFactory.getDefault(), "Fuji Transfer") { askedForOk = true }

        assertTrue("should wait for OK on the camera", askedForOk)
        assertEquals("X-S10", camera.cameraName)
        assertEquals(Fuji.STATE_REMOTE_ACCESS, camera.cameraState)
        // Remote-capable body: DF01 ends on the remote gallery, DF24 and DF25 raised like Fuji's app.
        assertEquals(Fuji.CLIENT_REMOTE_IMG_VIEW, cam.props[Fuji.DPC_CLIENT_STATE])
        assertEquals(Fuji.CAM_CONNECT_REMOTE_VER, cam.props[Fuji.DPC_REMOTE_VERSION])
        assertEquals(5, cam.props[Fuji.DPC_REMOTE_GET_OBJECT_VERSION])

        assertEquals(listOf(1, 2), camera.handles())
        val info = camera.objectInfo(1)
        assertNotNull(info)
        assertEquals("DSCF0002.JPG", info!!.fileName)
        assertEquals("20261027T101500", info.captureDate)
        assertNotNull(camera.thumbnail(1))

        val out = ByteArrayOutputStream()
        var lastProgress = 0L
        val real = camera.download(1, out, { false }) { done, _ -> lastProgress = done }
        assertEquals(big.size.toLong(), real.size)
        assertEquals(big.size.toLong(), lastProgress)
        assertArrayEquals(big, out.toByteArray())
        assertEquals("D227 must go back to 0 after a download", 0, cam.props[Fuji.DPC_CORRECT_FILE_SIZE])

        val out2 = ByteArrayOutputStream()
        camera.download(2, out2, { false }) { _, _ -> }
        assertArrayEquals(small, out2.toByteArray())

        camera.close()
        assertTrue(Fuji.OC_CLOSE_SESSION in cam.received)
    }
}

class FakeXappCameraTest {
    private val big = Random(3).nextBytes(1_300_000)
    private val small = Random(4).nextBytes(30_000)
    private lateinit var cam: FakeCamera

    @Before fun setUp() {
        cam = FakeCamera(listOf("DSCF0001.JPG" to small, "DSCF0002.HIF" to big), xapp = true)
        cam.start()
    }

    @After fun tearDown() = cam.stop()

    @Test fun bluetoothWokenBodyUsesXappProtocol() {
        val camera = FujiCamera({ println(it) }, "127.0.0.1", cam.server.localPort)
        camera.connect(SocketFactory.getDefault(), "Fuji Transfer", viaBluetooth = true) {}

        assertTrue(camera.xappMode)
        assertEquals(Fuji.CLIENT_REMOTE_IMG_VIEW_XAPP, cam.props[Fuji.DPC_CLIENT_STATE])
        assertTrue(Fuji.OC_GET_IMAGE_IMPORT_DATES in cam.received)
        // No Camera Remote steps on an XApp body.
        assertTrue(Fuji.OC_INITIATE_OPEN_CAPTURE !in cam.received)
        assertEquals(listOf(2, 1), camera.handles())

        // ObjectInfo still lies (D227 = 0); the real size must come from ObjectSize.
        val out = ByteArrayOutputStream()
        val real = camera.download(2, out, { false }) { _, _ -> }
        assertEquals(big.size.toLong(), real.size)
        assertArrayEquals(big, out.toByteArray())
        assertEquals("originals requested", 2, cam.props[Fuji.DPC_COMPRESS_SMALL])
        assertEquals(0, cam.props[Fuji.DPC_CORRECT_FILE_SIZE])
        camera.close()
    }

    @Test fun oldBodyWokenOverBluetoothStaysOnCameraRemote() {
        cam.props[Fuji.DPC_REMOTE_VERSION] = 0x20006
        val camera = FujiCamera({ println(it) }, "127.0.0.1", cam.server.localPort)
        camera.connect(SocketFactory.getDefault(), "Fuji Transfer", viaBluetooth = true) {}
        assertTrue(!camera.xappMode)
        assertEquals(Fuji.CLIENT_REMOTE_IMG_VIEW, cam.props[Fuji.DPC_CLIENT_STATE])
        camera.close()
    }

    @Test fun bodyThatRefusesObjectInfoStillListsAndSaves() {
        cam.refuseObjectInfo = true
        val camera = FujiCamera({ println(it) }, "127.0.0.1", cam.server.localPort)
        camera.connect(SocketFactory.getDefault(), "Fuji Transfer", viaBluetooth = true) {}

        val info = camera.describe(2)
        assertNotNull("name from object properties", info)
        assertEquals("DSCF0002.HIF", info!!.fileName)
        assertEquals(big.size.toLong(), info.size)
        assertNotNull(camera.thumbnail(2))

        val out = ByteArrayOutputStream()
        camera.download(2, out, { false }) { _, _ -> }
        assertArrayEquals(big, out.toByteArray())
        camera.close()
    }

    @Test fun bodyWithoutObjectPropsFallsBackToObjectInfo() {
        cam.answerObjectProps = false
        val camera = FujiCamera({ println(it) }, "127.0.0.1", cam.server.localPort)
        camera.connect(SocketFactory.getDefault(), "Fuji Transfer", viaBluetooth = true) {}

        assertEquals("DSCF0001.JPG", camera.describe(1)?.fileName)
        val out = ByteArrayOutputStream()
        // No ObjectSize either: D227 = 1 must make ObjectInfo report the real size.
        val real = camera.download(1, out, { false }) { _, _ -> }
        assertEquals(small.size.toLong(), real.size)
        assertArrayEquals(small, out.toByteArray())
        camera.close()
    }

    @Test fun newCardWaitsForIndexingInsteadOfGuessingHandles() {
        cam.indexingPollsLeft = 4
        var preparing = 0
        val camera = FujiCamera({ println(it) }, "127.0.0.1", cam.server.localPort, cardIndexTimeoutMs = 10_000, cardPollMs = 20)
        camera.onPreparingCard = { preparing++ }
        camera.connect(SocketFactory.getDefault(), "Fuji Transfer", viaBluetooth = true) {}

        assertTrue("UI was told the card is being read", preparing > 0)
        assertTrue(camera.cardReady)
        assertEquals(listOf(2, 1), camera.handles())
        assertEquals("DSCF0002.HIF", camera.describe(2)?.fileName)
        camera.close()
    }

    @Test fun busyCameraDoesNotDisableObjectProperties() {
        cam.indexingPollsLeft = 1_000_000
        val camera = FujiCamera({ println(it) }, "127.0.0.1", cam.server.localPort, cardIndexTimeoutMs = 100, cardPollMs = 20)
        camera.connect(SocketFactory.getDefault(), "Fuji Transfer", viaBluetooth = true) {}

        assertTrue(!camera.cardReady)
        assertEquals("falls back to D222 after the timeout", listOf(2, 1), camera.handles())
        // While indexing, lookups fail softly and are retryable.
        assertEquals(null, camera.describe(2))
        assertEquals(null, camera.thumbnail(2))

        // Indexing finishes: the next list refresh picks up D621 and properties still work.
        cam.indexingPollsLeft = 0
        assertEquals(listOf(2, 1), camera.handles())
        val info = camera.describe(2)
        assertEquals("DSCF0002.HIF", info?.fileName)
        assertTrue("size from ObjectSize, not GetObjectInfo", info!!.sizeTrusted)
        camera.close()
    }
}
