package dev.fujitransfer

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Network
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.fujitransfer.protocol.ExifOrientation
import dev.fujitransfer.protocol.FujiCamera
import dev.fujitransfer.protocol.ObjectInfo
import dev.fujitransfer.protocol.TransferSize
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

enum class Phase { Idle, Bluetooth, Pairing, Joining, Connecting, WaitingForOk, PreparingCard, Connected }

enum class ConnectMode {
    /** Newer bodies (X-S20, X-T5, X-H2, X100VI…): Bluetooth wakes the Wi-Fi. */
    Bluetooth,
    /** Older bodies (X-S10, X-T4…): WIRELESS COMMUNICATION menu, app joins the Wi-Fi. */
    WifiAuto,
    /** The phone is already on the camera's Wi-Fi. */
    WifiCurrent,
}

data class Photo(
    val handle: Int,
    val info: ObjectInfo? = null,
    val thumb: ImageBitmap? = null,
    val downloaded: Boolean = false,
    /** A thumbnail load was attempted (it may have failed). */
    val attempted: Boolean = false,
    /** Details read and the thumbnail turned the way the photo was shot. */
    val oriented: Boolean = false,
) {
    /** "yyyyMMdd" of the capture date, or null while unknown. */
    val day: String? get() = info?.captureDate?.take(8)?.takeIf { it.length == 8 && it.all(Char::isDigit) }
}

/** Progress of reading every photo's details and thumbnail into the cache. */
data class Preparing(
    val done: Int,
    val total: Int,
    /** Seconds left at the current pace, or null while there isn't one yet. */
    val secondsLeft: Int?,
    /** The last few thumbnails read, for the screen to show something happening. */
    val recent: List<ImageBitmap>,
)

/** Grid filters; kept outside [UiState] so they survive reconnects. */
data class Filters(
    /** "yyyyMMdd", or null for every day. */
    val day: String? = null,
    val hideDownloaded: Boolean = false,
)

/** The photos the grid shows (and "select all" acts on) under [f]. */
fun visiblePhotos(photos: List<Photo>, f: Filters): List<Photo> = photos.filter { p ->
    (f.day == null || p.day == f.day) && !(f.hideDownloaded && p.downloaded)
}

data class Transfer(
    val index: Int,
    val total: Int,
    val fileName: String,
    val fileDone: Long,
    val fileTotal: Long,
    val bytesPerSecond: Double,
    val succeeded: Int,
    val failed: Int,
)

data class UiState(
    val phase: Phase = Phase.Idle,
    val status: String = "",
    val error: String? = null,
    val cameraName: String = "",
    /** The photos loaded so far (newest first); more come in as the grid scrolls. */
    val photos: List<Photo> = emptyList(),
    /** How many photos the card has in all. */
    val total: Int = 0,
    val selected: Set<Int> = emptySet(),
    val transfer: Transfer? = null,
    val notice: String? = null,
    /** "yyyyMMdd" of the day "select whole day" is still collecting, or null. */
    val selectingDay: String? = null,
    /** Set while the first-time preparation of the card runs (see [MainViewModel.prepareCard]). */
    val preparing: Preparing? = null,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui

    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log

    /** Every camera call runs on this one thread, so commands never interleave. */
    private val cameraThread = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val camera = FujiCamera(::appendLog)
    private val wifi = CameraWifi(app)
    private val ble = FujiBluetooth(app, ::appendLog)
    private val store = PhotoStore(app)

    private val _convertHeif = MutableStateFlow(store.convertHeif)
    /** Save Fujifilm HEIF photos as JPEG so every phone and app can open them. */
    val convertHeif: StateFlow<Boolean> = _convertHeif

    private val _rotateThumbs = MutableStateFlow(store.rotateThumbs)
    /** Rotate grid thumbnails to how the photo was held. */
    val rotateThumbs: StateFlow<Boolean> = _rotateThumbs

    private val _filters = MutableStateFlow(Filters(hideDownloaded = store.hideDownloaded))
    val filters: StateFlow<Filters> = _filters

    /** Every handle on the card, newest first; [UiState.photos] is a prefix of it. */
    @Volatile private var allHandles: List<Int> = emptyList()

    /** Thumbnails shown unrotated, waiting for their EXIF orientation (handle → camera JPEG). */
    private val pendingRotate = LinkedHashMap<Int, ByteArray>()

    private val _transferSize = MutableStateFlow(store.transferSize)
    /** Original files, or JPEGs the camera resizes before sending. */
    val transferSize: StateFlow<TransferSize> = _transferSize

    /** Result of the one-time check for cut-short JPEGs, shown once the gallery opens. */
    @Volatile private var repairNotice: String? = null

    init {
        if (!store.cutShortChecked) {
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    val cut = store.findCutShortJpegs()
                    store.cutShortChecked = true
                    if (cut.isNotEmpty()) {
                        appendLog("Kiểm tra ảnh đã lưu: ${cut.size} ảnh JPEG bị thiếu phần cuối (bản cũ): " + cut.take(20).joinToString())
                        repairNotice = "${cut.size} ảnh tải bằng bản cũ bị thiếu phần cuối. Bấm \"Chọn ảnh mới\" để tải lại; bản lỗi sẽ được thay thế."
                    } else {
                        appendLog("Kiểm tra ảnh đã lưu: không có ảnh JPEG nào bị thiếu phần cuối")
                    }
                } catch (e: Exception) {
                    appendLog("Không kiểm tra được ảnh đã lưu: ${e.message}")
                }
            }
        }
    }

    /** Photos whose details the background scan has asked for this session. */
    private val scanTried = mutableSetOf<Int>()

    /** Names/dates/orientations from earlier sessions with this camera. */
    @Volatile private var meta: MetadataCache? = null
    private var cacheHits = 0
    private var cacheMisses = 0
    private var scanSummaryLogged = false

    private var sessionJob: Job? = null
    private var transferJob: Job? = null
    @Volatile private var cancelTransfer = false
    @Volatile private var transferring = false
    @Volatile private var heifKeptOriginal = 0
    private var thumbFailuresLogged = 0

    /** Handles the grid wants thumbnails for; newest request is served first. */
    private val wanted = ArrayDeque<Int>()

    private val timeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    fun appendLog(line: String) {
        val stamped = "${timeFmt.format(Date())} $line"
        _log.update { (it + stamped).takeLast(3000) }
    }

    fun dismissNotice() = _ui.update { it.copy(notice = null, error = null) }

    /**
     * [ConnectMode.WifiAuto] asks Android to join an SSID starting with [ssidPrefix];
     * [ConnectMode.Bluetooth] learns the exact SSID and password from the camera.
     */
    fun connect(mode: ConnectMode, ssidPrefix: String = "", password: String = "") {
        if (sessionJob?.isActive == true) return
        sessionJob = viewModelScope.launch {
            appendLog("Bắt đầu kết nối (${mode.name}), Android ${android.os.Build.VERSION.RELEASE}")
            val network: Network?
            when (mode) {
                ConnectMode.Bluetooth -> {
                    _ui.update { UiState(phase = Phase.Bluetooth, status = "Đang tìm máy ảnh qua Bluetooth…") }
                    val ap = try {
                        // With a known network to fall back on, give up on Bluetooth sooner.
                        ble.wakeWifi(firstScanMs = if (store.lastAp != null) 12_000 else 25_000, onPairing = {
                            _ui.update { it.copy(phase = Phase.Pairing, status = "Xác nhận mã ghép đôi trên điện thoại và máy ảnh") }
                        }).also { store.lastAp = it }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        appendLog("LỖI Bluetooth: ${e.javaClass.simpleName}: ${e.message}")
                        ble.close()
                        // A body whose Wi-Fi is already up (e.g. after a dropped session) stops
                        // advertising over Bluetooth; join the network it gave us last time instead.
                        val saved = store.lastAp
                        if (saved == null) {
                            fail(e.message ?: "Lỗi Bluetooth")
                            return@launch
                        }
                        appendLog("Thử vào thẳng Wi-Fi đã biết \"${saved.ssid}\"")
                        saved
                    }
                    _ui.update { it.copy(phase = Phase.Joining, status = "Đang vào Wi-Fi ${ap.ssid}…") }
                    appendLog("Yêu cầu Android kết nối \"${ap.ssid}\"")
                    network = wifi.requestExact(ap.ssid, ap.password.ifBlank { null })
                }
                ConnectMode.WifiAuto -> {
                    _ui.update { UiState(phase = Phase.Joining, status = "Đang tìm Wi-Fi của máy ảnh…") }
                    appendLog("Yêu cầu Android kết nối SSID bắt đầu bằng \"$ssidPrefix\"")
                    network = wifi.request(ssidPrefix, password.ifBlank { null })
                }
                ConnectMode.WifiCurrent -> {
                    _ui.update { UiState(phase = Phase.Joining, status = "Đang tìm Wi-Fi của máy ảnh…") }
                    network = wifi.currentWifi()
                }
            }
            if (network == null) {
                ble.close()
                fail(
                    when (mode) {
                        ConnectMode.WifiCurrent -> "Điện thoại chưa kết nối Wi-Fi nào. Hãy vào Cài đặt › Wi-Fi và chọn mạng FUJIFILM-…"
                        ConnectMode.Bluetooth -> "Không vào được Wi-Fi của máy ảnh. Hãy bật máy ảnh (Bluetooth BẬT), thử lại, và chọn mạng của máy ảnh khi Android hỏi."
                        ConnectMode.WifiAuto -> "Không tìm thấy Wi-Fi của máy ảnh. Hãy bật chế độ kết nối không dây trên máy ảnh rồi thử lại. Với X-S20, X-T5, X-H2, X100VI… hãy dùng chế độ Bluetooth."
                    }
                )
                return@launch
            }
            _ui.update { it.copy(phase = Phase.Connecting, status = "Đang bắt tay với máy ảnh…") }
            camera.onPreparingCard = { waited ->
                _ui.update {
                    it.copy(
                        phase = Phase.PreparingCard,
                        status = "Máy ảnh đang đọc thẻ nhớ… (${waited / 1000} s)",
                    )
                }
            }
            try {
                val photos = withContext(cameraThread) {
                    camera.connect(network.socketFactory, FujiBluetooth.CLIENT_NAME, mode == ConnectMode.Bluetooth) {
                        _ui.update { it.copy(phase = Phase.WaitingForOk, status = "Bấm OK trên máy ảnh để cho phép kết nối") }
                    }
                    val handles = camera.handles().also { allHandles = it }
                    val m = MetadataCache.forCamera(File(getApplication<Application>().filesDir, "meta"), camera.cameraName)
                    meta = m
                    cacheTrusted = verifyCache(m, handles)
                    // Everything the cache already knows is shown at once (newest first, up to the
                    // first photo it doesn't know), at least as many as last time.
                    val cachedRun = if (!cacheTrusted) 0 else handles.indexOfFirst { m.get(it)?.date.isNullOrEmpty() }.let { if (it < 0) handles.size else it }
                    val count = maxOf(FIRST_PAGE, store.shownCount(camera.cameraName), cachedRun).coerceAtMost(handles.size)
                    handles.take(count).map(::photoFromCache)
                }
                // Wi-Fi is up and answering; the Bluetooth link was only needed for the wake.
                ble.close()
                _ui.update {
                    it.copy(
                        phase = Phase.Connected,
                        status = "",
                        cameraName = camera.cameraName.ifEmpty { "Máy ảnh Fujifilm" },
                        photos = photos,
                        total = allHandles.size,
                    )
                }
                thumbFailuresLogged = 0
                prefetchCursor = 0
                scanTried.clear()
                completeDays.clear()
                repairNotice?.let { msg -> repairNotice = null; _ui.update { it.copy(notice = msg) } }
                cacheHits = 0
                cacheMisses = 0
                scanSummaryLogged = false
                appendLog(
                    "Bộ nhớ đệm: đã biết ${meta?.size ?: 0} ảnh của ${camera.cameraName}" +
                        if (cacheTrusted) " — dùng ngay, ${photos.count { it.info != null }}/${photos.size} ảnh hiện có sẵn ngày" else ""
                )
                _filters.update { it.copy(day = null) }
                val missing = missingFromCache()
                if (missing.size >= PREPARE_THRESHOLD) prepareCard(missing)
                runBackgroundLoop()
            } catch (e: CancellationException) {
                throw e // The user pressed Cancel / Disconnect; disconnect() cleans up.
            } catch (e: Exception) {
                appendLog("LỖI: ${e.javaClass.simpleName}: ${e.message}" + (camera.stalledCommand?.let { " — máy ảnh không trả lời lệnh $it" } ?: ""))
                withContext(cameraThread) { camera.close() }
                ble.close()
                fail(friendly(e))
            }
        }
    }

    private fun fail(message: String) {
        saveMeta()
        prepareJob?.cancel()
        wifi.release()
        _ui.update { it.copy(phase = Phase.Idle, status = "", error = message, transfer = null, preparing = null) }
    }

    private fun friendly(e: Exception): String = when (e) {
        is java.net.SocketTimeoutException -> "Máy ảnh không phản hồi. Hãy kiểm tra máy ảnh vẫn đang ở màn hình kết nối không dây."
        is java.net.ConnectException, is java.net.NoRouteToHostException ->
            "Không kết nối được tới máy ảnh (192.168.0.1). Điện thoại có đang ở Wi-Fi của máy ảnh không?"
        is java.io.IOException -> "Mất kết nối với máy ảnh: ${e.message}"
        else -> e.message ?: e.javaClass.simpleName
    }

    /** Loads thumbnails on demand and keeps the session alive with event polls. */
    private suspend fun runBackgroundLoop() {
        var lastPoll = System.currentTimeMillis()
        while (viewModelScope.isActive && _ui.value.phase == Phase.Connected) {
            if (transferring || prepareJob?.isActive == true) {
                delay(300)
                continue
            }
            val handle = synchronized(wanted) { wanted.removeLastOrNull() }
            try {
                if (handle != null) {
                    if (!loadThumb(handle)) {
                        // Camera busy: put the photo back and give the camera room instead of hammering it.
                        synchronized(wanted) { if (handle !in wanted) wanted.addFirst(handle) }
                        delay(2_000)
                    }
                } else if (System.currentTimeMillis() - lastPoll > 8_000) {
                    lastPoll = System.currentTimeMillis()
                    val before = camera.objectCount
                    withContext(cameraThread) { camera.pollEvents() }
                    val guessed = camera.xappMode && !camera.cardReady
                    if ((camera.objectCount != before && camera.objectCount > 0) || guessed) refreshList()
                } else if (!rotateNext() && !scanNextDetails() && !prefetchNext()) {
                    delay(150)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                appendLog("LỖI nền: ${e.javaClass.simpleName}: ${e.message}" + (camera.stalledCommand?.let { " — máy ảnh không trả lời lệnh $it" } ?: ""))
                val stalledOnExif = camera.orientationInFlight
                withContext(cameraThread) { camera.close() }
                if (stalledOnExif) {
                    // Don't let thumbnail rotation keep killing sessions on a body that won't serve it.
                    setRotateThumbs(false)
                    appendLog("Máy ảnh không trả lời khi đọc EXIF → đã tắt xoay ảnh thu nhỏ")
                    fail(friendly(e) + "\nĐã tắt \"Xoay ảnh thu nhỏ\" vì máy ảnh không trả lời khi đọc hướng ảnh. Hãy kết nối lại.")
                } else {
                    fail(friendly(e))
                }
                return
            }
        }
    }

    private suspend fun refreshList() {
        val wasGuessed = camera.xappMode && !camera.cardReady
        val handles = withContext(cameraThread) { camera.handles() }
        // Still only a guess from D222: nothing new to show.
        if (wasGuessed && !camera.cardReady && handles == _ui.value.photos.map { it.handle }) return
        appendLog("Danh sách ảnh cập nhật → ${handles.size}" + if (camera.cardReady && wasGuessed) " (thẻ nhớ đã sẵn sàng)" else "")
        allHandles = handles
        synchronized(wanted) { wanted.clear() }
        scanTried.clear()
        completeDays.clear()
        // Keep what is already known about photos that are still there, and as many as were shown.
        _ui.update { s ->
            val old = s.photos.associateBy { it.handle }
            val shown = handles.take(maxOf(FIRST_PAGE, s.photos.size)).map { old[it] ?: photoFromCache(it) }
            s.copy(photos = shown, total = handles.size, selected = s.selected intersect shown.map { it.handle }.toSet())
        }
    }

    /** Appends the next [count] photos to the grid (called as it scrolls near the end). */
    fun loadMore(count: Int = PAGE) {
        _ui.update { s ->
            val all = allHandles
            if (s.photos.size >= all.size) s
            else s.copy(photos = s.photos + all.subList(s.photos.size, minOf(all.size, s.photos.size + count)).map(::photoFromCache))
        }
        store.setShownCount(camera.cameraName, _ui.value.photos.size)
    }

    /**
     * The cache was checked against this card on connect: its names and dates can be shown
     * without asking the camera again. Downloads still read the camera's own details.
     */
    @Volatile private var cacheTrusted = false

    /** A grid entry with name, date and "downloaded" already filled in when the cache knows the photo. */
    private fun photoFromCache(handle: Int): Photo {
        if (!cacheTrusted) return Photo(handle)
        val e = meta?.get(handle)?.takeIf { it.date.isNotEmpty() } ?: return Photo(handle)
        val info = ObjectInfo(handle, e.name, 0, e.date)
        return Photo(handle, info = info, downloaded = store.isDownloaded(store.key(e.name, e.date)))
    }

    /**
     * Is this still the card the cache was built from? Asks the camera for the names of three
     * known photos (newest, middle, oldest) — ~30 ms on an X-S20 — instead of checking every one.
     */
    private fun verifyCache(m: MetadataCache, handles: List<Int>): Boolean {
        val known = handles.filter { m.get(it) != null }
        if (known.isEmpty()) return false
        val samples = listOf(known.first(), known[known.size / 2], known.last()).distinct()
        for (h in samples) {
            val name = camera.fileName(h) ?: camera.describe(h)?.fileName
            if (name != m.get(h)?.name) {
                appendLog("Bộ nhớ đệm không khớp ảnh #$h (${m.get(h)?.name} ≠ $name) — kiểm tra từng ảnh")
                return false
            }
        }
        return true
    }

    /** Forgets every name, date and thumbnail kept for this camera. */
    fun clearCache() {
        val m = meta ?: MetadataCache.forCamera(File(getApplication<Application>().filesDir, "meta"), camera.cameraName)
        viewModelScope.launch(Dispatchers.IO) {
            m.clear()
            cacheTrusted = false
            store.setShownCount(camera.cameraName, 0)
            appendLog("Đã xoá bộ nhớ đệm ảnh")
            _ui.update { it.copy(notice = "Đã xoá bộ nhớ đệm. Lần kết nối sau ảnh sẽ được đọc lại từ máy ảnh.") }
        }
    }

    /** Size of the cache on the phone, for the menu. */
    fun cacheBytes(): Long = meta?.diskBytes() ?: 0L

    /**
     * Rotates one thumbnail that was shown unrotated: reads its EXIF only after every visible
     * thumbnail has loaded, so rotation never slows the grid down. False when nothing waits.
     */
    private suspend fun rotateNext(): Boolean {
        val shown = _ui.value.photos.associateBy { it.handle }
        val next = synchronized(pendingRotate) {
            pendingRotate.keys.retainAll(shown.keys)
            val last = pendingRotate.entries.lastOrNull()
            if (last != null) pendingRotate.remove(last.key)
            last?.let { it.key to it.value }
        } ?: return false
        val (handle, bytes) = next
        val name = shown[handle]?.info?.fileName ?: return true
        if (!_rotateThumbs.value) return true
        val o = withContext(cameraThread) { camera.orientation(handle, name) } ?: ExifOrientation.NORMAL
        meta?.setOrientation(handle, name, o)
        val degrees = ExifOrientation.degrees(o)
        val bmp = if (degrees != 0) decodeThumb(bytes, degrees) else null
        updatePhoto(handle) { it.copy(thumb = bmp ?: it.thumb, oriented = true) }
        return true
    }

    /**
     * When the grid wants nothing, reads name/date/size of the next photo nobody has looked at,
     * so the date filter and "hide transferred" cover the whole card, not just what was scrolled.
     * Returns false when there was nothing left to scan.
     */
    private suspend fun scanNextDetails(): Boolean {
        val next = _ui.value.photos.firstOrNull { it.info == null && it.handle !in scanTried }
        if (next == null) {
            if (!scanSummaryLogged && (cacheHits + cacheMisses) > 0) {
                scanSummaryLogged = true
                appendLog("Đã đọc xong ngày chụp: dùng lại $cacheHits ảnh từ bộ nhớ đệm, đọc mới $cacheMisses ảnh")
                saveMeta()
            }
            return false
        }
        scanTried += next.handle
        val cached = meta?.get(next.handle)
        val info = withContext(cameraThread) {
            if (cached != null && cached.date.isNotEmpty()) {
                // Seen before: one fast name check instead of a slow GetObjectInfo.
                val name = camera.fileName(next.handle)
                if (name != null && name == cached.name) {
                    cacheHits++
                    return@withContext ObjectInfo(next.handle, name, 0, cached.date)
                }
                if (name == null && camera.lastWasBusy) return@withContext null
            }
            camera.describe(next.handle)?.also { cacheMisses++ }
        }
        if (info != null) remember(info)
        if (info == null) {
            if (camera.lastWasBusy) {
                scanTried -= next.handle
                delay(2_000)
            }
            return true
        }
        val done = store.isDownloaded(store.key(info.fileName, info.captureDate))
        updatePhoto(next.handle) { if (it.info == null) it.copy(info = info, downloaded = done) else it }
        return true
    }

    /** Returns false when the camera was busy and the photo should be tried again later. */
    private suspend fun loadThumb(handle: Int): Boolean {
        val current = _ui.value.photos.firstOrNull { it.handle == handle } ?: return true
        if (current.thumb != null) return true
        // Seen before: show the thumbnail kept on the phone, no camera round trip at all.
        val cachedInfo = current.info
        if (cachedInfo != null) {
            val saved = withContext(Dispatchers.IO) { meta?.thumb(handle, cachedInfo.fileName) }
            if (saved != null) {
                val known = meta?.get(handle)?.takeIf { it.name == cachedInfo.fileName }?.orientation
                val bmp = decodeThumb(saved, ExifOrientation.degrees(if (_rotateThumbs.value) known else null))
                if (bmp != null) {
                    if (known == null && _rotateThumbs.value) synchronized(pendingRotate) { pendingRotate[handle] = saved }
                    updatePhoto(handle) { it.copy(thumb = bmp, attempted = true) }
                    return true
                }
            }
        }
        var busy = false
        var orientation: Int? = null
        val (info, bytes) = withContext(cameraThread) {
            // Classic bodies block GetThumb until GetObjectInfo was asked for the same handle,
            // which describe() does for them.
            val info = current.info ?: camera.describe(handle)
            if (info == null && camera.lastWasBusy) {
                busy = true
                return@withContext null to null
            }
            // Thumbnail first: it must follow GetObjectInfo directly on newer bodies.
            val thumb = camera.thumbnail(handle)
            if (thumb == null && camera.lastWasBusy) busy = true
            // The camera's thumbnail is never rotated. Its own EXIF may say how the photo was held
            // (free); otherwise read the file's EXIF.
            if (_rotateThumbs.value && !busy && info != null) {
                orientation = meta?.get(handle)?.takeIf { it.name == info.fileName }?.orientation
                    ?: thumb?.let(ExifOrientation::parse)?.also { meta?.setOrientation(handle, info.fileName, it) }
            }
            info to thumb
        }
        if (busy) {
            if (info != null) updatePhoto(handle) { it.copy(info = info) }
            return false
        }
        if (info != null) remember(info)
        if (info != null && bytes != null) {
            val m = meta
            viewModelScope.launch(Dispatchers.IO) { m?.saveThumb(handle, info.fileName, bytes) }
        }
        val bmp = bytes?.let { decodeThumb(it, ExifOrientation.degrees(orientation)) }
        // Not known yet: show it now as the camera sent it, rotate later (see rotateNext).
        if (bytes != null && bmp != null && orientation == null && info != null && _rotateThumbs.value) {
            synchronized(pendingRotate) { pendingRotate[handle] = bytes }
        }
        if ((info == null || bmp == null) && thumbFailuresLogged < 5) {
            thumbFailuresLogged++
            appendLog(
                "Ảnh #$handle: " + (if (info == null) "không đọc được thông tin" else info.fileName) +
                    ", thumbnail " + (if (bytes == null) "không có" else if (bmp == null) "không giải mã được (${bytes.size} B)" else "OK")
            )
        }
        val done = info?.let { store.isDownloaded(store.key(it.fileName, it.captureDate)) } ?: false
        updatePhoto(handle) { it.copy(info = info, thumb = bmp, downloaded = done, attempted = true, oriented = orientation != null) }
        return true
    }

    /** Shows the thumbnail kept on the phone, turned as the cache says. */
    private fun showCachedThumb(m: MetadataCache, handle: Int, name: String) {
        val bytes = m.thumb(handle, name) ?: return
        val orientation = if (_rotateThumbs.value) m.get(handle)?.takeIf { it.name == name }?.orientation else null
        val bmp = decodeThumb(bytes, ExifOrientation.degrees(orientation)) ?: return
        if (orientation == null && _rotateThumbs.value) synchronized(pendingRotate) { pendingRotate[handle] = bytes }
        updatePhoto(handle) { if (it.info?.fileName == name) it.copy(thumb = bmp, attempted = true, oriented = orientation != null) else it }
    }

    private var prepareJob: Job? = null

    /** Handles whose name, date, thumbnail (and orientation, if rotating) aren't in the cache yet. */
    private fun missingFromCache(): List<Int> {
        val m = meta ?: return allHandles
        if (!cacheTrusted) return allHandles
        val rotate = _rotateThumbs.value
        return allHandles.filter { !m.isComplete(it, rotate) }
    }

    /**
     * First time with a card (or many new photos): read every photo's details, thumbnail and
     * orientation once and keep them on the phone, so every later visit opens instantly.
     * Shows a progress screen; [skipPreparing] leaves it and lets idle time finish the rest.
     */
    private fun prepareCard(targets: List<Int>) {
        if (prepareJob?.isActive == true) return
        val m = meta ?: return
        appendLog("Chuẩn bị thẻ nhớ: ${targets.size} ảnh chưa có trong bộ nhớ đệm")
        prepareJob = viewModelScope.launch {
            val started = System.nanoTime()
            val recent = ArrayDeque<ImageBitmap>()
            var done = 0
            _ui.update { it.copy(preparing = Preparing(0, targets.size, null, emptyList())) }
            try {
                for (h in targets) {
                    var item: FujiCamera.IndexedPhoto? = null
                    for (attempt in 0 until 10) {
                        item = withContext(cameraThread) { camera.indexPhoto(h, _rotateThumbs.value) }
                        if (item != null || !camera.lastWasBusy) break
                        delay(2_000)
                    }
                    item?.let { storeIndexed(m, it)?.let { bmp -> recent.addLast(bmp); if (recent.size > 6) recent.removeFirst() } }
                    done++
                    val perPhoto = (System.nanoTime() - started) / 1e9 / done
                    _ui.update {
                        it.copy(preparing = Preparing(done, targets.size, ((targets.size - done) * perPhoto).toInt().takeIf { done >= 3 }, recent.toList()))
                    }
                    if (done % 50 == 0) saveMeta()
                }
                cacheTrusted = true
                saveMeta()
                val secs = ((System.nanoTime() - started) / 1e9).toInt()
                appendLog("Chuẩn bị xong ${targets.size} ảnh trong ${secs / 60} phút ${secs % 60} giây")
                // Everything is known now: show the whole card from the cache.
                _ui.update { s ->
                    val old = s.photos.associateBy { it.handle }
                    s.copy(photos = allHandles.map { old[it]?.takeIf { p -> p.info != null } ?: photoFromCache(it) }, preparing = null,
                        notice = "Đã chuẩn bị xong. Lần sau thư viện sẽ hiện ra ngay.")
                }
                store.setShownCount(camera.cameraName, allHandles.size)
            } catch (e: CancellationException) {
                saveMeta()
                throw e
            } catch (e: Exception) {
                appendLog("LỖI khi chuẩn bị: ${e.javaClass.simpleName}: ${e.message}" + (camera.stalledCommand?.let { " — máy ảnh không trả lời lệnh $it" } ?: ""))
                saveMeta()
                val stalledOnExif = camera.orientationInFlight
                withContext(cameraThread) { camera.close() }
                if (stalledOnExif) setRotateThumbs(false)
                fail(friendly(e) + if (stalledOnExif) "\nĐã tắt \"Xoay ảnh thu nhỏ\" vì máy ảnh không trả lời khi đọc hướng ảnh. Hãy kết nối lại." else "")
            }
        }
    }

    /** Leaves the preparing screen; what is left is read in idle moments instead. */
    fun skipPreparing() {
        prepareJob?.cancel()
        _ui.update { it.copy(preparing = null) }
        appendLog("Bỏ qua bước chuẩn bị; phần còn lại sẽ được đọc khi rảnh")
    }

    /** Puts one indexed photo into the cache and the grid; returns its thumbnail for display. */
    private fun storeIndexed(m: MetadataCache, item: FujiCamera.IndexedPhoto): ImageBitmap? {
        val info = item.info
        val h = info.handle
        m.put(h, info.fileName, info.captureDate)
        item.thumb?.let { m.saveThumb(h, info.fileName, it) }
        item.orientation?.let { m.setOrientation(h, info.fileName, it) }
        val bmp = item.thumb?.let { decodeThumb(it, ExifOrientation.degrees(if (_rotateThumbs.value) item.orientation else null)) }
        val done = store.isDownloaded(store.key(info.fileName, info.captureDate))
        updatePhoto(h) { it.copy(info = info, thumb = bmp ?: it.thumb, downloaded = done, attempted = true, oriented = item.orientation != null) }
        return bmp
    }

    /** Idle time: read one more photo of the card into the cache (newest first). */
    private var prefetchCursor = 0
    private suspend fun prefetchNext(): Boolean {
        val m = meta ?: return false
        val rotate = _rotateThumbs.value
        val all = allHandles
        while (prefetchCursor < all.size && m.isComplete(all[prefetchCursor], rotate)) prefetchCursor++
        if (prefetchCursor >= all.size) return false
        val h = all[prefetchCursor]
        val item = withContext(cameraThread) { camera.indexPhoto(h, rotate) }
        if (item == null) {
            if (camera.lastWasBusy) delay(2_000) else prefetchCursor++
            return true
        }
        storeIndexed(m, item)
        prefetchCursor++
        if (prefetchCursor % 50 == 0) saveMeta()
        return true
    }

    /** Keeps name/date for next time; writes the cache out every ~100 changes. */
    private fun remember(info: ObjectInfo) {
        val m = meta ?: return
        if (m.put(info.handle, info.fileName, info.captureDate)) saveMeta()
    }

    private fun saveMeta() {
        val m = meta ?: return
        viewModelScope.launch(Dispatchers.IO) { m.save() }
    }

    private fun decodeThumb(bytes: ByteArray, degrees: Int): ImageBitmap? {
        // RGB_565: half the memory of ARGB, invisible at thumbnail size; a whole card fits.
        val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 }
        // The camera letterboxes non-4:3 photos inside its 4:3 thumbnail; drop the bands.
        val raw = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)?.let(::trimBlackBars) ?: return null
        if (degrees == 0) return raw.asImageBitmap()
        val m = Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true).asImageBitmap()
    }

    private fun updatePhoto(handle: Int, f: (Photo) -> Photo) = _ui.update { s ->
        s.copy(photos = s.photos.map { if (it.handle == handle) f(it) else it })
    }

    /** Thumbnails decoded from the phone in parallel, never waiting behind camera work. */
    private val diskDecoder = Dispatchers.IO.limitedParallelism(4)
    private val decoding = HashSet<Int>()

    fun requestThumb(handle: Int) {
        val info = _ui.value.photos.firstOrNull { it.handle == handle }?.info
        val m = meta
        if (info != null && m != null && m.hasThumb(handle, info.fileName)) {
            if (synchronized(decoding) { decoding.add(handle) }) {
                viewModelScope.launch(diskDecoder) {
                    try { showCachedThumb(m, handle, info.fileName) } finally { synchronized(decoding) { decoding.remove(handle) } }
                }
            }
            return
        }
        synchronized(wanted) {
            wanted.remove(handle)
            wanted.addLast(handle)
            while (wanted.size > 60) wanted.removeFirst()
        }
    }

    fun toggle(handle: Int) = _ui.update {
        it.copy(selected = if (handle in it.selected) it.selected - handle else it.selected + handle)
    }

    /** "Select whole roll": selects all of [handles], or clears them when all are already selected. */
    private var dayJob: Job? = null

    /**
     * Selects every photo shot on [day] — also the ones not loaded into the grid yet. Handles
     * follow shooting order, so a day's photos sit next to each other: starting from one of
     * them, walk outwards (reading dates that aren't known yet, loading more of the card when
     * the day runs past what's shown) until the date changes. Tapping again clears the day.
     */
    fun selectWholeDay(day: String) {
        if (dayJob?.isActive == true || transferJob?.isActive == true) return
        val shownOfDay = _ui.value.photos.filter { it.day == day }.map { it.handle }
        if (shownOfDay.isNotEmpty() && shownOfDay.all { it in _ui.value.selected } && dayComplete(day)) {
            _ui.update { it.copy(selected = it.selected - shownOfDay.toSet()) }
            return
        }
        dayJob = viewModelScope.launch {
            _ui.update { it.copy(selectingDay = day) }
            try {
                val start = _ui.value.photos.indexOfFirst { it.day == day }
                if (start < 0) return@launch
                // Newer side (towards the top of the grid).
                var i = start - 1
                while (i >= 0 && dayAt(i) == day) i--
                // Older side, loading more of the card as needed.
                var j = start + 1
                while (true) {
                    if (j >= _ui.value.photos.size) {
                        if (_ui.value.photos.size >= allHandles.size) break
                        loadMore(PAGE)
                    }
                    if (dayAt(j) != day) break
                    j++
                }
                val handles = _ui.value.photos.subList(i + 1, j).filter { it.day == day }.map { it.handle }
                _ui.update { it.copy(selected = it.selected + handles) }
                completeDays += day
                appendLog("Chọn cả ngày ${day.substring(6, 8)}/${day.substring(4, 6)}: ${handles.size} ảnh")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                appendLog("LỖI khi chọn cả ngày: ${e.javaClass.simpleName}: ${e.message}")
            } finally {
                _ui.update { it.copy(selectingDay = null) }
            }
        }
    }

    /** Days whose every photo has been found by [selectWholeDay] this session. */
    private val completeDays = mutableSetOf<String>()
    private fun dayComplete(day: String) = day in completeDays

    /** The day of the photo at [index], reading its details from the camera if needed. */
    private suspend fun dayAt(index: Int): String? {
        val p = _ui.value.photos.getOrNull(index) ?: return null
        p.day?.let { return it }
        repeat(15) {
            val info = withContext(cameraThread) { camera.describe(p.handle) }
            if (info != null) {
                remember(info)
                val done = store.isDownloaded(store.key(info.fileName, info.captureDate))
                updatePhoto(p.handle) { if (it.info == null) it.copy(info = info, downloaded = done) else it }
                return _ui.value.photos.getOrNull(index)?.day
            }
            if (!camera.lastWasBusy) return null
            delay(2_000)
        }
        return null
    }

    fun toggleRoll(handles: List<Int>) = _ui.update { s ->
        s.copy(selected = if (handles.all { it in s.selected }) s.selected - handles.toSet() else s.selected + handles)
    }

    /** Selects every photo the grid currently shows (all of a day when a date is picked). */
    fun selectAll() = _ui.update { s ->
        s.copy(selected = visiblePhotos(s.photos, _filters.value).map { it.handle }.toSet())
    }

    fun clearSelection() = _ui.update { it.copy(selected = emptySet()) }

    /** Selects every shown photo that isn't on the phone yet. */
    fun selectNotDownloaded() = _ui.update { s ->
        s.copy(selected = visiblePhotos(s.photos, _filters.value).filter { !it.downloaded }.map { it.handle }.toSet())
    }

    /** Shows only [day] ("yyyyMMdd"), or every day for null. Selection outside the day is dropped. */
    fun setDayFilter(day: String?) {
        _filters.update { it.copy(day = day) }
        dropHiddenSelection()
    }

    fun setHideDownloaded(hide: Boolean) {
        store.hideDownloaded = hide
        _filters.update { it.copy(hideDownloaded = hide) }
        dropHiddenSelection()
    }

    private fun dropHiddenSelection() = _ui.update { s ->
        val shown = visiblePhotos(s.photos, _filters.value).map { it.handle }.toSet()
        s.copy(selected = s.selected intersect shown)
    }

    /**
     * Downloads the selection. [includeDownloaded] = false skips photos already saved on this
     * phone (the grid asks first when the selection has any).
     */
    fun downloadSelected(includeDownloaded: Boolean = true) {
        val s = _ui.value
        if (s.selected.isEmpty() || transferJob?.isActive == true) return
        val queue = s.photos.filter { it.handle in s.selected && (includeDownloaded || !it.downloaded) }.map { it.handle }
        if (queue.isEmpty()) {
            _ui.update { it.copy(selected = emptySet(), notice = "Các ảnh đã chọn đều đã có trong điện thoại") }
            return
        }
        cancelTransfer = false
        transferring = true
        // Keeps going with the app in the background; shows progress in a notification.
        try {
            TransferService.start(getApplication())
        } catch (e: Exception) {
            appendLog("Không chạy được dịch vụ nền: ${e.message}")
        }
        transferJob = viewModelScope.launch {
            heifKeptOriginal = 0
            var ok = 0
            var failed = 0
            for ((i, handle) in queue.withIndex()) {
                if (cancelTransfer) break
                val name = _ui.value.photos.firstOrNull { it.handle == handle }?.info?.fileName ?: "#$handle"
                _ui.update { it.copy(transfer = Transfer(i, queue.size, name, 0, 0, 0.0, ok, failed)) }
                try {
                    withContext(cameraThread) { downloadOne(handle, i, queue.size, ok, failed) }
                    ok++
                    updatePhoto(handle) { it.copy(downloaded = true) }
                    _ui.update { it.copy(selected = it.selected - handle) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: InterruptedException) {
                    break
                } catch (e: Exception) {
                    failed++
                    appendLog("LỖI tải #$handle: ${e.javaClass.simpleName}: ${e.message}" + (camera.stalledCommand?.let { " — máy ảnh không trả lời lệnh $it" } ?: ""))
                    if (e is java.io.IOException) {
                        // The socket is gone; the rest of the queue cannot succeed.
                        withContext(cameraThread) { camera.close() }
                        transferring = false
                        fail(friendly(e) + "\nĐã tải xong $ok ảnh trước khi mất kết nối.")
                        return@launch
                    }
                }
            }
            transferring = false
            val msg = buildString {
                append("Đã lưu $ok ảnh vào Thư viện › Fuji Transfer")
                if (failed > 0) append(", $failed ảnh lỗi")
                if (heifKeptOriginal > 0) append(". $heifKeptOriginal ảnh HEIF giữ nguyên vì điện thoại không đọc được")
                if (cancelTransfer) append(" (đã dừng)")
            }
            _ui.update { it.copy(transfer = null, notice = msg) }
        }
        // The window was closed mid-transfer: hang up once the photos are in.
        transferJob?.invokeOnCompletion { if (uiGone) viewModelScope.launch { disconnect() } }
    }

    fun setTransferSize(size: TransferSize) {
        store.transferSize = size
        _transferSize.value = size
    }

    fun setRotateThumbs(on: Boolean) {
        store.rotateThumbs = on
        _rotateThumbs.value = on
    }

    fun setConvertHeif(on: Boolean) {
        store.convertHeif = on
        _convertHeif.value = on
    }

    private fun downloadOne(handle: Int, index: Int, total: Int, ok: Int, failed: Int) {
        // Ask for the name first so the file in the gallery gets the camera's own name.
        var found = camera.describe(handle)
        // A busy camera answers again in a moment; give it up to ~30 s per photo.
        var waits = 0
        while (found == null && camera.lastWasBusy && waits++ < 15 && !cancelTransfer) {
            Thread.sleep(2_000)
            found = camera.describe(handle)
        }
        val info = found ?: throw IllegalStateException(
            "Không đọc được ảnh #$handle" + if (camera.lastWasBusy) " (máy ảnh đang bận)" else ""
        )
        // The grid may show cached details; the camera's own word wins. A mismatch means the
        // cache no longer fits this card, so stop trusting it for the rest of the session.
        val shown = _ui.value.photos.firstOrNull { it.handle == handle }?.info
        if (shown != null && (shown.fileName != info.fileName || shown.captureDate != info.captureDate)) {
            appendLog("Bộ nhớ đệm sai cho #$handle (${shown.fileName} → ${info.fileName}) — sẽ kiểm tra lại từng ảnh")
            cacheTrusted = false
            remember(info)
            updatePhoto(handle) { it.copy(info = info, thumb = null, attempted = false) }
        }
        val started = System.nanoTime()
        var lastUi = 0L
        val progress: (Long, Long) -> Unit = { done, size ->
            val now = System.nanoTime()
            if (now - lastUi > 150_000_000 || done == size) {
                lastUi = now
                val speed = done / ((now - started) / 1e9).coerceAtLeast(0.001)
                _ui.update { it.copy(transfer = Transfer(index, total, info.fileName, done, size, speed, ok, failed)) }
            }
        }
        val isHeif = info.fileName.endsWith(".HIF", ignoreCase = true)
        if (isHeif && store.convertHeif) {
            downloadAndConvert(handle, info.fileName, progress)
            return
        }
        val isJpeg = info.fileName.endsWith(".JPG", ignoreCase = true) || info.fileName.endsWith(".JPEG", ignoreCase = true)
        val size = if (isJpeg) _transferSize.value else TransferSize.ORIGINAL
        // A resized copy gets its own name so it never looks like the original.
        val saveAs = if (size == TransferSize.ORIGINAL) info.fileName
        else info.fileName.substringBeforeLast('.') + (if (size == TransferSize.SMALL) "_S." else "_XS.") + info.fileName.substringAfterLast('.')
        val pending = store.begin(saveAs)
        try {
            val real = camera.download(handle, pending.stream, { cancelTransfer }, size, progress)
            store.commit(pending, store.key(real.fileName, real.captureDate))
            val secs = (System.nanoTime() - started) / 1e9
            appendLog("Đã lưu ${store.savedName(real.fileName)} (${real.size / 1024} KB, %.1f s)".format(secs))
        } catch (e: Throwable) {
            store.abort(pending)
            throw e
        }
    }

    /** HEIF → JPEG on the phone. Keeps the original .HEIF if the phone can't decode it. */
    private fun downloadAndConvert(handle: Int, cameraName: String, progress: (Long, Long) -> Unit) {
        val dir = File(getApplication<Application>().cacheDir, "convert").apply { mkdirs() }
        val heif = File(dir, "src.heif")
        val jpeg = File(dir, "out.jpg")
        try {
            val real = heif.outputStream().buffered(1 shl 20).use { out ->
                camera.download(handle, out, { cancelTransfer }, TransferSize.ORIGINAL, progress)
            }
            val key = store.key(real.fileName, real.captureDate)
            _ui.update { s -> s.copy(transfer = s.transfer?.copy(fileName = "$cameraName → JPEG…")) }
            val result = HeifConverter.toJpeg(heif, jpeg, ::appendLog)
            if (result != null) {
                val name = cameraName.dropLast(3) + "JPG"
                store.saveFile(name, jpeg, key)
                appendLog("Đã chuyển $cameraName → $name (${result.note}, ${jpeg.length() / 1024} KB)")
            } else {
                store.saveFile(cameraName, heif, key)
                heifKeptOriginal++
                appendLog("Giữ nguyên ${store.savedName(cameraName)} vì không chuyển được sang JPEG")
            }
        } finally {
            heif.delete()
            jpeg.delete()
        }
    }

    /** No window shows this session any more (its activity finished). */
    @Volatile private var uiGone = false

    /**
     * The app's window was closed. Without a transfer running, hang up as before; during one,
     * let it finish in the background ([TransferService]) and hang up then.
     */
    fun uiClosed() {
        uiGone = true
        if (transferJob?.isActive != true && _ui.value.phase != Phase.Idle) disconnect()
    }

    /** A window shows this session again. */
    fun uiOpened() {
        uiGone = false
    }

    fun cancelTransfer() {
        cancelTransfer = true
    }

    fun disconnect() {
        cancelTransfer = true
        prepareJob?.cancel()
        sessionJob?.cancel()
        viewModelScope.launch {
            withContext(cameraThread) { camera.close() }
            wifi.release()
            ble.close()
            transferring = false
            synchronized(wanted) { wanted.clear() }
            saveMeta()
            _ui.value = UiState()
            appendLog("Đã ngắt kết nối")
        }
    }

    /** Writes the log to a shareable file for bug reports. */
    fun exportLog(): android.net.Uri {
        val ctx = getApplication<Application>()
        val dir = File(ctx.cacheDir, "logs").apply { mkdirs() }
        val file = File(dir, "fuji-transfer-log.txt")
        file.writeText(
            "Fuji Transfer ${BuildConfigInfo.version}\n" +
                "Android ${android.os.Build.VERSION.RELEASE} / ${android.os.Build.MODEL}\n\n" +
                _log.value.joinToString("\n")
        )
        return FileProvider.getUriForFile(ctx, ctx.packageName + ".files", file)
    }

    override fun onCleared() {
        // viewModelScope is gone by now; save on the camera thread instead.
        val m = meta
        cameraThread.executor.execute { m?.close(); camera.close() }
        wifi.release()
        ble.close()
    }
}

/** Photos shown right after connecting, and how many each scroll to the end adds. */
const val FIRST_PAGE = 100
const val PAGE = 10

/** On connect, this many photos missing from the cache opens the preparing screen. */
const val PREPARE_THRESHOLD = 30

object BuildConfigInfo {
    const val version = "0.20.0"
}
