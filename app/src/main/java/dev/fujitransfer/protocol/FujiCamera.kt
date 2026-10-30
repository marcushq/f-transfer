package dev.fujitransfer.protocol

import java.io.IOException
import java.io.OutputStream
import javax.net.SocketFactory

data class ObjectInfo(
    val handle: Int,
    val fileName: String,
    /** Only trustworthy while D227 (correct file size) is 1. */
    val size: Long,
    /** "YYYYMMDDThhmmss" as the camera reports it, or empty. */
    val captureDate: String,
    /** True when [size] came from ObjectSize rather than ObjectInfo's 100 KB placeholder. */
    val sizeTrusted: Boolean = false,
)

/**
 * Wi-Fi session with a Fujifilm X camera in "Wireless communication" mode
 * (the Camera Remote protocol used by X-T4, X-S10, X100V, X-T3 and similar bodies).
 *
 * Every method blocks and must be called from one thread at a time.
 */
class FujiCamera(
    private val log: (String) -> Unit,
    private val host: String = Fuji.CAMERA_HOST,
    private val cmdPort: Int = Fuji.CMD_PORT,
    /** How long to wait for a freshly inserted card to be indexed before guessing handles. */
    private val cardIndexTimeoutMs: Long = 180_000,
    private val cardPollMs: Long = 2_000,
) {
    private val link = PtpLink(log)
    private var factory: SocketFactory = SocketFactory.getDefault()
    private val sideSockets = mutableListOf<PtpLink>()

    var cameraName = ""
        private set
    var cameraState = -1
        private set
    var objectCount = -1
        private set
    var remoteVersion = -1
        private set
    var remoteImageViewVersion = -1
        private set

    /** True when the session runs the XApp gallery protocol (bodies woken over Bluetooth). */
    var xappMode = false
        private set

    /** XApp bodies list their images in D621. */
    private var importHandles: List<Int> = emptyList()
    /** Current D226 (0 after the gallery setup) and D22E, so they are only sent when they change. */
    private var compressionMode = 0
    private var resizeRate = -1
    /** ObjectSize (0xDC04) is refused but the other object properties work. */
    private var sizePropUnsupported = false
    private var namePropUnsupported = false

    /** True while an EXIF read is waiting on the camera; tells a timeout's cause apart. */
    @Volatile var orientationInFlight = false
        private set
    /** Called about every 2 s while the body is still indexing a freshly inserted card. */
    var onPreparingCard: (waitedMs: Long) -> Unit = {}

    /** D621 gave the real handle list (XApp bodies). */
    var cardReady = false
        private set

    /** The last command answered Device Busy / Store Not Available. */
    val lastWasBusy get() = Fuji.isTransient(link.lastCode)

    /** The command a timeout interrupted, e.g. "0x100A(#6044)". */
    val stalledCommand: String? get() = link.inFlight

    /** The body didn't answer object properties; use GetObjectInfo for the rest of the session. */
    private var infoOnly = false

    /** Newer bodies number handles oldest-first; older ones newest-first. */
    val handlesOldestFirst get() = remoteImageViewVersion >= 5

    val isConnected get() = link.isOpen

    /**
     * Runs the whole handshake. [onWaitingForOk] is called while the camera shows its
     * "accept connection" prompt. [viaBluetooth]: the Wi-Fi was started by a Bluetooth wake,
     * so a new enough body expects the XApp protocol rather than Camera Remote.
     */
    fun connect(
        socketFactory: SocketFactory,
        clientName: String,
        viaBluetooth: Boolean = false,
        onWaitingForOk: () -> Unit,
    ) {
        xappMode = false
        importHandles = emptyList()
        compressionMode = 0
        resizeRate = -1
        sizePropUnsupported = false
        namePropUnsupported = false
        orientationInFlight = false
        infoOnly = false
        cardReady = false
        factory = socketFactory
        log("Kết nối TCP $host:$cmdPort")
        link.open(factory, host, cmdPort)

        handshake(clientName)
        Thread.sleep(60) // OpenSession sent within ~50 ms of the ack gets silence.

        link.tid = 1
        val open = link.transaction(Fuji.OC_OPEN_SESSION, intArrayOf(1))
        if (!open.ok && open.code != Fuji.RC_SESSION_ALREADY_OPEN) {
            throw PtpException("Không mở được phiên (OpenSession ${Fuji.hex(open.code)})", open.code)
        }
        log("Đã mở phiên PTP")

        cameraState = -1
        objectCount = -1
        pollEvents()
        if (cameraState == Fuji.STATE_WAIT_FOR_ACCESS || cameraState == -1) {
            log("Đang chờ bấm OK trên máy ảnh…")
            onWaitingForOk()
            val deadline = System.currentTimeMillis() + 120_000
            while (cameraState == Fuji.STATE_WAIT_FOR_ACCESS || cameraState == -1) {
                if (System.currentTimeMillis() > deadline) {
                    throw PtpException("Hết thời gian chờ xác nhận trên máy ảnh")
                }
                Thread.sleep(300)
                pollEvents()
            }
        }
        log("Trạng thái máy ảnh DF00 = $cameraState")

        val getObjectVersion = getPropInt(Fuji.DPC_GET_OBJECT_VERSION)
        remoteImageViewVersion = getPropInt(Fuji.DPC_REMOTE_GET_OBJECT_VERSION)
        val imageGetVersion = getPropInt(Fuji.DPC_IMAGE_GET_VERSION)
        remoteVersion = getPropInt(Fuji.DPC_REMOTE_VERSION)
        log(
            "Phiên bản: DF22=${hexOrNone(getObjectVersion)} DF25=${hexOrNone(remoteImageViewVersion)} " +
                "DF21=${hexOrNone(imageGetVersion)} DF24=${hexOrNone(remoteVersion)}"
        )

        // libfuji: after a Bluetooth hand-off, bodies older than remote version 0x20007
        // still speak Camera Remote.
        if (viaBluetooth && remoteVersion >= 0x20007) {
            xappMode = true
            configXappGallery()
            log("Sẵn sàng (XApp). Số ảnh: ${importHandles.size.takeIf { it > 0 } ?: objectCount}")
            return
        }

        configInitMode()

        if (cameraState == Fuji.STATE_MULTIPLE_TRANSFER) {
            log("Máy ảnh đang ở chế độ gửi ảnh đã chọn")
            return
        }

        configVersion()

        if (remoteVersion != -1 && cameraState == Fuji.STATE_REMOTE_ACCESS) {
            // Fuji's own client opens and closes remote mode once before the gallery;
            // without it some bodies stop answering.
            log("Cấu hình chế độ remote")
            configRemoteOnce()
            log("Cấu hình thư viện ảnh")
            configImageGallery()
        }
        log("Sẵn sàng. Số ảnh: $objectCount")
    }

    private fun handshake(clientName: String) {
        repeat(3) { attempt ->
            val name = ByteArray(54)
            val chars = clientName.take(26)
            chars.forEachIndexed { i, c ->
                name[i * 2] = c.code.toByte()
                name[i * 2 + 1] = (c.code shr 8).toByte()
            }
            val init = LEWriter()
                .u32(0x52)
                .u32(Fuji.INIT_COMMAND_REQ)
                .u32(Fuji.PROTOCOL_VERSION)
                .u32(0x5d48a5ad).u32(0x0b7fb287).u32(0xd0ded5d3.toInt()).u32(0)
                .bytes(name)
                .toByteArray()
            link.write(init)
            val reply = link.readPacket()
            val type = LE.u32(reply, 4)
            if (type == Fuji.INIT_FAIL) {
                log("Init bị từ chối (lần ${attempt + 1}), thử lại")
                Thread.sleep(300)
                return@repeat
            }
            cameraName = if (reply.size > 28) decodeUtf16(reply, 28) else ""
            log("Init OK: \"$cameraName\" (loại gói $type)")
            return
        }
        throw PtpException("Máy ảnh từ chối kết nối (Init Fail)")
    }

    private fun decodeUtf16(b: ByteArray, off: Int): String {
        val sb = StringBuilder()
        var p = off
        while (p + 1 < b.size) {
            val c = LE.u16(b, p)
            if (c == 0) break
            sb.append(c.toChar())
            p += 2
        }
        return sb.toString()
    }

    /** DF01: tells the camera which kind of client we are. Mirrors libfuji's choice. */
    private fun configInitMode() {
        val mode = when {
            remoteVersion != -1 || cameraState == Fuji.STATE_REMOTE_ACCESS -> Fuji.CLIENT_REMOTE_MODE
            cameraState == Fuji.STATE_MULTIPLE_TRANSFER -> Fuji.CLIENT_VIEW_MULTIPLE
            cameraState == Fuji.STATE_PC_AUTO_SAVE -> Fuji.CLIENT_OLD_REMOTE
            else -> Fuji.CLIENT_VIEW_ALL_IMGS
        }
        log("Đặt chế độ client DF01 = $mode")
        // Newer bodies may show a second Yes/No dialog here, so give them time.
        link.setReadTimeout(120_000)
        try {
            requireOk(setProp16(Fuji.DPC_CLIENT_STATE, mode), "đặt chế độ client")
        } finally {
            link.setReadTimeout(15_000)
        }
        pollEvents()
    }

    private fun configVersion() {
        if (remoteVersion == -1) {
            val raw = getPropRaw(Fuji.DPC_GET_OBJECT_VERSION) ?: return
            requireOk(setPropRaw(Fuji.DPC_GET_OBJECT_VERSION, raw), "đặt DF22")
        } else {
            requireOk(setProp32(Fuji.DPC_REMOTE_VERSION, Fuji.CAM_CONNECT_REMOTE_VER), "đặt DF24")
        }
    }

    private fun configRemoteOnce() {
        val openTid = link.tid
        val r = link.transaction(Fuji.OC_INITIATE_OPEN_CAPTURE, intArrayOf(0, 0))
        requireOk(r, "vào chế độ remote")
        pollEvents()
        Thread.sleep(50)
        // The camera expects the event and live view sockets to be opened in remote mode.
        for (port in intArrayOf(Fuji.EVENT_PORT, Fuji.LIVEVIEW_PORT)) {
            try {
                val side = PtpLink(log)
                side.open(factory, host, port)
                sideSockets += side
            } catch (e: IOException) {
                log("Không mở được cổng $port: ${e.message}")
            }
        }
        pollEvents()
        val t = link.transaction(Fuji.OC_TERMINATE_OPEN_CAPTURE, intArrayOf(openTid))
        if (!t.ok) log("TerminateOpenCapture trả về ${Fuji.hex(t.code)}")
    }

    private fun configImageGallery() {
        if (remoteImageViewVersion == -1) return
        pollEvents()
        requireOk(setProp16(Fuji.DPC_CAMERA_STATE, Fuji.STATE_REMOTE_ACCESS), "đặt DF00")
        pollEvents()
        pollEvents()
        requireOk(setProp16(Fuji.DPC_CLIENT_STATE, Fuji.CLIENT_REMOTE_IMG_VIEW), "đặt DF01 = 11")
        pollEvents()
        // X-S10 and X-H1 want 4 or higher; Fuji's app sets 5.
        val raw = getPropRaw(Fuji.DPC_REMOTE_GET_OBJECT_VERSION)
        if (raw != null) {
            requireOk(setProp32(Fuji.DPC_REMOTE_GET_OBJECT_VERSION, 5), "đặt DF25")
        }
        pollEvents()
    }

    /**
     * XApp's gallery setup, in XApp's order (as recorded by libfuji and fuji-bridge). The body
     * answers some of these with errors on some firmware; none of them is fatal.
     */
    private fun configXappGallery() {
        log("Đặt chế độ client DF01 = ${Fuji.CLIENT_REMOTE_IMG_VIEW_XAPP} (XApp)")
        link.setReadTimeout(120_000)
        try {
            val r = setProp16(Fuji.DPC_CLIENT_STATE, Fuji.CLIENT_REMOTE_IMG_VIEW_XAPP)
            if (!r.ok) log("DF01 = 20 bị từ chối (${Fuji.hex(r.code)}), vẫn tiếp tục")
        } finally {
            link.setReadTimeout(15_000)
        }
        getPropRaw(Fuji.DPC_REMOTE_PHOTO_VIEW_EX)?.let { setPropRaw(Fuji.DPC_REMOTE_PHOTO_VIEW_EX, it) }
        setProp16(Fuji.DPC_COMPRESS_SMALL, 0)
        setProp16(Fuji.DPC_CORRECT_FILE_SIZE, 0)
        link.transaction(Fuji.OC_GET_EXTENSION_OBJECT_INFO, intArrayOf(0x10000001))
        link.transaction(Fuji.OC_GET_EXTENSION_THUMB, intArrayOf(0x10000001))

        // Right after a card goes in, the body is still indexing it: the import calls answer
        // Device Busy / Store Not Available and every handle is invalid until it finishes.
        // Wait for it rather than guessing handles (seen on an X-S20 with 1823 photos).
        val started = System.currentTimeMillis()
        var lastNote = 0L
        while (true) {
            val folders = link.transaction(Fuji.OC_GET_IMAGE_IMPORT_FOLDERS)
            getPropRaw(Fuji.DPC_UNKNOWN_D22B)
            val dates = if (Fuji.isTransient(folders.code)) folders
            else link.transaction(Fuji.OC_GET_IMAGE_IMPORT_DATES, intArrayOf(0, 30000))
            var rawHandles: ByteArray? = null
            if (!Fuji.isTransient(dates.code)) {
                getPropRaw(Fuji.DPC_IMAGE_IMPORT_COUNT)
                rawHandles = getPropRaw(Fuji.DPC_IMAGE_IMPORT_HANDLES)
                importHandles = rawHandles?.let(::parseHandleArray) ?: emptyList()
            }
            val waited = System.currentTimeMillis() - started
            if (importHandles.isNotEmpty()) {
                log("D621: ${importHandles.size} ảnh (sau ${waited / 1000} s), mẫu " +
                    importHandles.take(5).joinToString() + if (importHandles.size > 5) " …" else "")
                cardReady = true
                break
            }
            val stillIndexing = Fuji.isTransient(folders.code) || Fuji.isTransient(dates.code) || rawHandles == null
            if (!stillIndexing || waited > cardIndexTimeoutMs) {
                log("D621 trống hoặc không đọc được: ${rawHandles?.hexDump() ?: "bị từ chối"}" +
                    if (stillIndexing) " — hết thời gian chờ, đoán số ảnh từ D222" else "")
                break
            }
            if (waited - lastNote > 5_000) {
                lastNote = waited
                log("Máy ảnh đang đọc thẻ nhớ (${waited / 1000} s)…")
            }
            onPreparingCard(waited)
            Thread.sleep(cardPollMs)
            pollEvents()
        }
        log("D621: ${importHandles.size} ảnh, D222 = $objectCount")
        pollEvents()
    }

    /** u32 count, then that many u32 handles. */
    private fun parseHandleArray(b: ByteArray): List<Int> {
        if (b.size < 4) return emptyList()
        val count = LE.u32(b, 0)
        if (count <= 0 || count > 20_000 || b.size < 4 + count * 4) return emptyList()
        return List(count) { LE.u32(b, 4 + it * 4) }
    }

    /** ObjectSize through GetObjectPropValue, the way XApp learns a file's real length. */
    private fun objectSize(handle: Int, prop: Int = Fuji.OPC_OBJECT_SIZE): Long? {
        val r = link.transaction(Fuji.OC_GET_OBJECT_PROP_VALUE, intArrayOf(handle, prop))
        if (!r.ok) return null
        val b = r.payload
        return when {
            b.size >= 8 -> (LE.u32(b, 0).toLong() and 0xFFFFFFFFL) or (LE.u32(b, 4).toLong() shl 32)
            b.size >= 4 -> LE.u32(b, 0).toLong() and 0xFFFFFFFFL
            else -> null
        }?.takeIf { it > 0 }
    }

    /** Reads the D212 change list and updates state, object count and so on. */
    fun pollEvents(): Map<Int, Int> {
        val raw = getPropRaw(Fuji.DPC_EVENTS_LIST) ?: return emptyMap()
        if (raw.size < 2) return emptyMap()
        val count = LE.u16(raw, 0)
        val events = LinkedHashMap<Int, Int>()
        for (i in 0 until count) {
            val off = 2 + i * 6
            if (off + 6 > raw.size) break
            events[LE.u16(raw, off)] = LE.u32(raw, off + 2)
        }
        events[Fuji.DPC_CAMERA_STATE]?.let { cameraState = it }
        events[Fuji.DPC_OBJECT_COUNT]?.let { objectCount = it }
        if (events.isNotEmpty()) {
            log("  sự kiện: " + events.entries.joinToString { "${Fuji.hex(it.key)}=${it.value}" })
        }
        return events
    }

    fun getPropRaw(code: Int): ByteArray? {
        val r = link.transaction(Fuji.OC_GET_PROP_VALUE, intArrayOf(code))
        return if (r.ok) r.payload else null
    }

    private fun getPropInt(code: Int): Int {
        val raw = getPropRaw(code) ?: return -1
        return when {
            raw.size >= 4 -> LE.u32(raw, 0)
            raw.size >= 2 -> LE.u16(raw, 0)
            raw.size == 1 -> raw[0].toInt() and 0xFF
            else -> -1
        }
    }

    private fun setPropRaw(code: Int, value: ByteArray) =
        link.transaction(Fuji.OC_SET_PROP_VALUE, intArrayOf(code), value)

    private fun setProp16(code: Int, value: Int) = setPropRaw(code, LE.u16Bytes(value))
    private fun setProp32(code: Int, value: Int) = setPropRaw(code, LE.u32Bytes(value))

    private fun requireOk(r: PtpResponse, what: String) {
        if (!r.ok) throw PtpException("Máy ảnh từ chối $what (${Fuji.hex(r.code)})", r.code)
    }

    private fun hexOrNone(v: Int) = if (v == -1) "—" else Fuji.hex(v)

    /** Handles are 1..objectCount over Wi-Fi; GetObjectHandles is not supported. */
    fun handles(): List<Int> {
        if (xappMode) {
            // Re-read D621 so photos taken during the session show up. Newest (highest handle) first.
            getPropRaw(Fuji.DPC_IMAGE_IMPORT_HANDLES)?.let(::parseHandleArray)
                ?.takeIf { it.isNotEmpty() }?.let { importHandles = it }
            if (importHandles.isNotEmpty()) return importHandles.sortedDescending()
            if (objectCount > 0) return (objectCount downTo 1).toList()
        }
        if (objectCount > 0) {
            val list = (1..objectCount).toList()
            return if (handlesOldestFirst) list.reversed() else list
        }
        // Count unknown (e.g. "send selected images" mode): probe until a handle fails.
        val found = mutableListOf<Int>()
        for (h in 1..999) {
            if (objectInfo(h) == null) break
            found += h
        }
        return found
    }

    fun objectInfo(handle: Int): ObjectInfo? {
        val r = link.transaction(Fuji.OC_GET_OBJECT_INFO, intArrayOf(handle))
        if (!r.ok || r.payload.size < 53) return null
        val b = r.payload
        // Fuji's ObjectInfo moves fields around: compressed size is the unaligned word at 13,
        // the variable part (filename, dates) starts at 52 like the ISO layout.
        val size = LE.u32(b, 13).toLong() and 0xFFFFFFFFL
        val (name, used) = PtpString.decode(b, 52)
        val (date, _) = PtpString.decode(b, 52 + used)
        return ObjectInfo(handle, name.ifEmpty { "IMG_$handle" }, size, date)
    }

    private fun objectProp(handle: Int, code: Int): ByteArray? {
        val r = link.transaction(Fuji.OC_GET_OBJECT_PROP_VALUE, intArrayOf(handle, code))
        return if (r.ok) r.payload else null
    }

    /**
     * Name, size and date of one photo. XApp bodies get them from three object properties
     * (~10 ms each), as XApp does; GetObjectInfo is slow there (~500 ms) and may be refused.
     * Falls back to GetObjectInfo when the body doesn't answer the properties.
     */
    fun describe(handle: Int): ObjectInfo? {
        if (xappMode && !infoOnly) {
            val size = if (sizePropUnsupported) null else objectSize(handle)
            if (size == null) {
                // Busy or a bad handle says nothing about whether the body supports the properties.
                if (lastWasBusy || link.lastCode == Fuji.RC_INVALID_OBJECT_HANDLE) return null
                // An X-S20 answers 0x2006 for ObjectSize, and its DateCreated sometimes answers
                // 0x2006 and sometimes never answers at all (a 15 s stall on 28 Oct). GetObjectInfo
                // has name and date and has never stalled, and the thumbnail needs it anyway.
                sizePropUnsupported = true
                infoOnly = true
                log("Máy ảnh không hỗ trợ ObjectSize (${Fuji.hex(link.lastCode)}); đọc tên/ngày bằng GetObjectInfo")
                return objectInfo(handle)
            }
            val name = objectProp(handle, Fuji.OPC_OBJECT_FILE_NAME)
                ?.let { PtpString.decode(it, 0).first.substringAfterLast('/').substringAfterLast('\\') }
            if (!name.isNullOrBlank()) {
                val rawDate = objectProp(handle, Fuji.OPC_DATE_CREATED)
                if (rawDate != null) {
                    val date = PtpString.decode(rawDate, 0).first
                    return ObjectInfo(handle, name, size ?: 0, date, sizeTrusted = size != null)
                }
                if (lastWasBusy) return null
                // An X-S20 refuses DateCreated (0x2006) too. Without a date the day filter is
                // useless, and GetObjectInfo has it, so use that from now on.
                log("Máy ảnh không hỗ trợ DateCreated (${Fuji.hex(link.lastCode)}); đọc ngày bằng GetObjectInfo")
                infoOnly = true
                return objectInfo(handle)
            }
            if (lastWasBusy || link.lastCode == Fuji.RC_INVALID_OBJECT_HANDLE) return null
            log("Máy ảnh không trả về thuộc tính ảnh #$handle (size=$size, name=$name, ${Fuji.hex(link.lastCode)}), chuyển sang GetObjectInfo")
            infoOnly = true
        }
        return objectInfo(handle)
    }

    /**
     * Just the file name, via the fast ObjectFileName property (~10 ms). Used to confirm a
     * cached entry still belongs to this handle. Null when busy or not supported.
     */
    fun fileName(handle: Int): String? {
        if (!xappMode || namePropUnsupported) return null
        val raw = objectProp(handle, Fuji.OPC_OBJECT_FILE_NAME)
        if (raw == null) {
            if (!lastWasBusy && link.lastCode != Fuji.RC_INVALID_OBJECT_HANDLE) {
                namePropUnsupported = true
                log("Máy ảnh không hỗ trợ ObjectFileName (${Fuji.hex(link.lastCode)}); không dùng được bộ nhớ đệm")
            }
            return null
        }
        return PtpString.decode(raw, 0).first.substringAfterLast('/').substringAfterLast('\\').ifBlank { null }
    }

    class IndexedPhoto(val info: ObjectInfo, val thumb: ByteArray?, val orientation: Int?)

    /**
     * Everything the grid needs about one photo, in an order the X-S20 accepts: details →
     * thumbnail (GetThumb right after GetObjectInfo), then — when [withOrientation] and the
     * thumbnail itself carries no EXIF — the file's EXIF the same way a download reads it.
     * Null when the camera is busy (try again later) or doesn't know the handle.
     */
    fun indexPhoto(handle: Int, withOrientation: Boolean): IndexedPhoto? {
        val info = describe(handle) ?: return null
        val thumb = thumbnail(handle)
        if (thumb == null && lastWasBusy) return null
        val orientation = if (!withOrientation) null
        else thumb?.let(ExifOrientation::parse) ?: orientation(handle, info.fileName) ?: ExifOrientation.NORMAL
        return IndexedPhoto(info, thumb, orientation)
    }

    /** Up to [length] bytes of the file from [offset], or null if the camera refuses. */
    private fun readHead(handle: Int, offset: Int, length: Int): ByteArray? {
        val r = link.transaction(Fuji.OC_GET_PARTIAL_OBJECT, intArrayOf(handle, offset, length))
        return if (r.ok && r.payload.isNotEmpty()) r.payload else null
    }

    /**
     * EXIF orientation of a photo, read from the start of the file (the camera's thumbnail
     * is never rotated). RAF files carry a JPEG whose offset is a big-endian u32 at byte 84.
     */
    fun orientation(handle: Int, fileName: String): Int? {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        if (ext !in setOf("jpg", "jpeg", "hif", "heif", "heic", "raf")) return null
        // An X-S20 never answers GetPartialObject unless it is set up exactly as for a download:
        // D226 ≠ 0, D227 = 1, then GetObjectInfo for the same handle right before the read
        // (stalls on 28 Oct with only D226 set; every download, which does all three, worked).
        requestOriginals()
        // Left set when the read throws, so the caller can tell the stall came from here.
        orientationInFlight = true
        // Fail fast: a stalled read costs the session either way.
        link.setReadTimeout(8_000)
        val sizeFix = xappMode && setProp16(Fuji.DPC_CORRECT_FILE_SIZE, 1).ok
        try {
            if (xappMode && !link.lastWas(Fuji.OC_GET_OBJECT_INFO, handle)) {
                objectInfo(handle) ?: return null.also { orientationInFlight = false }
            }
            val result = readOrientation(handle, ext)
            orientationInFlight = false
            return result
        } finally {
            if (sizeFix && !orientationInFlight) setProp16(Fuji.DPC_CORRECT_FILE_SIZE, 0)
            link.setReadTimeout(15_000)
        }
    }

    /** File reads need D226 ≠ 0; anything already set (original or resized) will do. */
    private fun requestOriginals() {
        if (compressionMode <= 0) setTransferSize(TransferSize.ORIGINAL)
    }

    /**
     * D226 = 2 sends originals, D226 = 1 resized copies at D22E's size (XApp's Resize).
     * Only sent when it changes; the body switches modes on every write.
     */
    private fun setTransferSize(size: TransferSize) {
        if (!xappMode) return
        val rate = size.resizeRate
        if (rate != null && resizeRate != rate) {
            val r = setProp16(Fuji.DPC_RESIZE_RATE, rate)
            if (!r.ok) log("D22E = $rate bị từ chối (${Fuji.hex(r.code)})")
            resizeRate = rate
        }
        val mode = if (rate == null) 2 else 1
        if (compressionMode != mode) {
            val r = setProp16(Fuji.DPC_COMPRESS_SMALL, mode)
            if (!r.ok) log("D226 = $mode bị từ chối (${Fuji.hex(r.code)})")
            compressionMode = mode
        }
    }

    private fun readOrientation(handle: Int, ext: String): Int? {
        return when (ext) {
            "jpg", "jpeg" -> readHead(handle, 0, 4096)?.let(ExifOrientation::parse)
            "hif", "heif", "heic" ->
                readHead(handle, 0, 4096)?.let(ExifOrientation::parse)
                    ?: readHead(handle, 0, 65536)?.let(ExifOrientation::parse)
            "raf" -> {
                val header = readHead(handle, 0, 512) ?: return null
                if (header.size < 92) return null
                val jpegOffset = ((header[84].toInt() and 0xFF) shl 24) or ((header[85].toInt() and 0xFF) shl 16) or
                    ((header[86].toInt() and 0xFF) shl 8) or (header[87].toInt() and 0xFF)
                if (jpegOffset <= 0) return null
                // Unaligned reads are very slow on Fuji bodies, so start at a 512-byte boundary.
                val aligned = jpegOffset - jpegOffset % 512
                readHead(handle, aligned, 4096 + (jpegOffset - aligned))?.let(ExifOrientation::parse)
            }
            else -> null
        }
    }

    fun thumbnail(handle: Int): ByteArray? {
        // libfuji: newer bodies never answer GetThumb unless GetObjectInfo for the same handle
        // came right before (seen as a 15 s stall on an X-S20 when the name came from properties).
        val needsInfo = xappMode || remoteVersion > 0x20006
        if (needsInfo && !link.lastWas(Fuji.OC_GET_OBJECT_INFO, handle)) {
            objectInfo(handle)
            if (lastWasBusy) return null
        }
        val r = link.transaction(Fuji.OC_GET_THUMB, intArrayOf(handle))
        return if (r.ok && r.payload.size > 100) r.payload else null
    }

    /**
     * Streams one file into [out]. [onProgress] gets (bytesDone, totalBytes).
     * Returns the file's info as reported with the real size.
     */
    fun download(
        handle: Int,
        out: OutputStream,
        isCancelled: () -> Boolean,
        size: TransferSize = TransferSize.ORIGINAL,
        onProgress: (Long, Long) -> Unit,
    ): ObjectInfo {
        link.setReadTimeout(30_000)
        var correctSizeOn = false
        // Saving overlaps the next read from the camera.
        val sink = QueuedWriter(out)
        try {
            val info: ObjectInfo
            var resized = false
            if (xappMode && size != TransferSize.ORIGINAL && isJpeg(handle)) {
                // The camera makes the smaller JPEG itself. D227 = 1 so ObjectInfo has a real
                // length; that may still be the original's, so ask 0xD802 like XApp, and rely on
                // the end-of-file checks below either way.
                setTransferSize(size)
                setProp16(Fuji.DPC_CORRECT_FILE_SIZE, 1)
                correctSizeOn = true
                // 0xD802 first: GetObjectInfo must come right before the first file read.
                val compressed = objectSize(handle, Fuji.OPC_COMPRESSED_SIZE)
                val base = objectInfo(handle) ?: throw PtpException("Không đọc được thông tin ảnh #$handle")
                val small = compressed?.takeIf { it < base.size || base.size == 0L }
                info = base.copy(size = small ?: base.size)
                resized = true
            } else if (xappMode) {
                setTransferSize(TransferSize.ORIGINAL)
                val base = describe(handle) ?: throw PtpException("Không đọc được thông tin ảnh #$handle")
                val size = if (base.sizeTrusted) base.size else (if (sizePropUnsupported) null else objectSize(handle)) ?: run {
                    // Body without ObjectSize: fall back to libfuji's D227 = 1 for a truthful ObjectInfo.
                    setProp16(Fuji.DPC_CORRECT_FILE_SIZE, 1)
                    correctSizeOn = true
                    objectInfo(handle)?.size ?: base.size
                }
                info = base.copy(size = size)
            } else {
                pollEvents()
                // With D227 = 0 the camera reports ~100 KB for every file.
                setProp16(Fuji.DPC_CORRECT_FILE_SIZE, 1)
                correctSizeOn = true
                info = objectInfo(handle) ?: throw PtpException("Không đọc được thông tin ảnh #$handle")
            }
            log("Tải ${info.fileName}: ${info.size} B" + if (resized) " (máy ảnh thu nhỏ: ${size.name})" else "")
            var total = info.size
            var done = 0L
            var shortEnd = -1L
            var lastTwo = 0
            val jpeg = info.fileName.endsWith(".JPG", ignoreCase = true) || info.fileName.endsWith(".JPEG", ignoreCase = true)
            while (true) {
                if (done >= total) {
                    // A JPEG that hasn't reached FF D9 at the announced length: the length was
                    // rounded down, so keep reading until the camera has nothing more (the reads
                    // past the end are refused or come back short). An X-S20 has announced 4+ MB too little
                    // (DSCF0868, 29 Oct), so the only cap is a size no JPEG from these bodies reaches.
                    if (jpeg && !resized && lastTwo != 0xFFD9 && done > 0 && done < Fuji.MAX_JPEG_BYTES) {
                        total = done + Fuji.MAX_PARTIAL
                    } else {
                        break
                    }
                }
                if (isCancelled()) throw InterruptedException("Đã huỷ")
                var ask = minOf(Fuji.MAX_PARTIAL.toLong(), total - done).toInt()
                var r = link.transaction(Fuji.OC_GET_PARTIAL_OBJECT, intArrayOf(handle, done.toInt(), ask))
                if (r.code == Fuji.RC_INVALID_PARAMETER && done > 0) {
                    // An X-S20 announces a rounded-up length (every size it gave on 28 Oct ended in
                    // …2B9 modulo 16 KB) and refuses a read that runs past the real end. Halve the
                    // window until it answers; when even 1 byte is refused, the file ended at [done].
                    while (r.code == Fuji.RC_INVALID_PARAMETER && ask > 1) {
                        ask /= 2
                        r = link.transaction(Fuji.OC_GET_PARTIAL_OBJECT, intArrayOf(handle, done.toInt(), ask))
                    }
                    if (r.code == Fuji.RC_INVALID_PARAMETER) {
                        total = done
                        break
                    }
                }
                if (!r.ok) throw PtpException("Lỗi tải ${info.fileName} (${Fuji.hex(r.code)})", r.code)
                if (r.payload.isEmpty()) {
                    // Nothing past the end: of a resized copy (shorter than announced), or while
                    // probing past the announced length for a JPEG's end marker.
                    if (done > 0 && (resized || done >= info.size)) { total = done; break }
                    throw PtpException("Máy ảnh trả về dữ liệu rỗng")
                }
                var take = r.payload.size
                if (resized && take < ask && done + take < total && tail(r.payload, take, lastTwo) == 0xFFD9) {
                    // The camera announced the original's length but the resized JPEG ended here.
                    // Stop now: past the end an X100VI keeps echoing the last bytes.
                    sink.write(r.payload, take)
                    done += take
                    total = done
                    lastTwo = 0xFFD9
                    break
                }
                if (take < ask && done + take < total) {
                    // Same short answer at the same place twice: the object really ends here
                    // (fuji-bridge saw a resized file asked for its last bytes 1,510 times).
                    if (shortEnd == done + take) {
                        sink.write(r.payload, take)
                        done += take
                        total = done
                        lastTwo = tail(r.payload, take, lastTwo)
                        break
                    }
                    shortEnd = done + take
                    // Reads from an offset that isn't 512-aligned are up to 40× slower on Fuji bodies,
                    // so after a short window drop the tail past the last 512-byte boundary.
                    if (take >= 512) take -= take % 512
                }
                sink.write(r.payload, take)
                lastTwo = tail(r.payload, take, lastTwo)
                done += take
                onProgress(done, total)
            }
            if (done != info.size) {
                log("${info.fileName}: nhận $done B, máy ảnh báo ${info.size} B" +
                    if (jpeg) (if (lastTwo == 0xFFD9) " — JPEG kết thúc đúng" else " — JPEG THIẾU phần cuối") else "")
            }
            // Never keep half a picture: a JPEG must end with FF D9.
            if ((resized || (jpeg && done != info.size)) && lastTwo != 0xFFD9) {
                throw PtpException(
                    (if (resized) "Ảnh thu nhỏ từ máy ảnh không hoàn chỉnh" else "Ảnh tải về không hoàn chỉnh") +
                        " (${info.fileName}, $done/${info.size} B)"
                )
            }
            sink.finish()
            onProgress(done, done)
            return info.copy(size = done)
        } finally {
            sink.abort()
            try {
                if (!xappMode) pollEvents()
                if (correctSizeOn) setProp16(Fuji.DPC_CORRECT_FILE_SIZE, 0)
            } catch (_: Exception) {
            }
            link.setReadTimeout(15_000)
        }
    }

    /** The last two bytes written so far, as a u16. */
    private fun tail(b: ByteArray, len: Int, prev: Int): Int = when {
        len >= 2 -> ((b[len - 2].toInt() and 0xFF) shl 8) or (b[len - 1].toInt() and 0xFF)
        len == 1 -> ((prev and 0xFF) shl 8) or (b[0].toInt() and 0xFF)
        else -> prev
    }

    /** Resizing only applies to JPEG; uses the cached name when the grid already has it. */
    private fun isJpeg(handle: Int): Boolean {
        val name = fileName(handle) ?: objectInfo(handle)?.fileName ?: return false
        return name.endsWith(".JPG", ignoreCase = true) || name.endsWith(".JPEG", ignoreCase = true)
    }

    fun close() {
        try {
            if (link.isOpen) link.transaction(Fuji.OC_CLOSE_SESSION)
        } catch (_: Exception) {
        }
        link.close()
        sideSockets.forEach { it.close() }
        sideSockets.clear()
    }
}
