import Foundation

struct ObjectInfo {
    let handle: UInt32
    let fileName: String
    /// Only trustworthy while D227 (correct file size) is 1.
    var size: Int
    /// "YYYYMMDDThhmmss" as the camera reports it, or empty.
    let captureDate: String
    /// True when [size] came from ObjectSize rather than ObjectInfo's 100 KB placeholder.
    var sizeTrusted = false
}

/// Wi-Fi session with a Fujifilm X camera. A port of the Android app's `FujiCamera.kt`,
/// including what the X-S20 taught it (see the comments there and here).
///
/// Every method blocks and must be called from one thread at a time (`Session` runs them on
/// one serial queue).
final class FujiCamera {
    private let log: (String) -> Void
    private let host: String
    private let cmdPort: UInt16
    private let link: PtpLink
    private var sideSockets: [PtpLink] = []

    private(set) var cameraName = ""
    private(set) var cameraState = -1
    private(set) var objectCount = -1
    private(set) var remoteVersion = -1
    private(set) var remoteImageViewVersion = -1
    /// The session runs the XApp gallery protocol (bodies woken over Bluetooth).
    private(set) var xappMode = false

    private var importHandles: [UInt32] = []
    /// Current D226 so it is only sent when it changes; the body switches modes on every write.
    private var compressionMode = 0
    /// ObjectSize (0xDC04) is refused but the other object properties work.
    private var sizePropUnsupported = false
    /// The body didn't answer object properties; use GetObjectInfo for the rest of the session.
    private var infoOnly = false
    /// How long to wait for a freshly inserted card to be indexed before guessing handles.
    private let cardIndexTimeout: TimeInterval = 180
    /// Called about every 2 s while the body is still indexing a freshly inserted card.
    var onPreparingCard: (TimeInterval) -> Void = { _ in }

    /// Set while an EXIF read runs and left set when it throws, so the caller can tell a stall
    /// came from reading orientations.
    private(set) var orientationInFlight = false

    /// The last command answered Device Busy / Store Not Available.
    var lastWasBusy: Bool { Fuji.isTransient(link.lastCode) }
    /// The command a timeout interrupted, e.g. "0x100A(#6044)".
    var stalledCommand: String? { link.inFlight }
    var isConnected: Bool { link.isOpen }
    /// Newer bodies number handles oldest-first; older ones newest-first.
    private var handlesOldestFirst: Bool { remoteImageViewVersion >= 5 }

    init(log: @escaping (String) -> Void, host: String = Fuji.cameraHost, cmdPort: UInt16 = Fuji.cmdPort) {
        self.log = log
        self.host = host
        self.cmdPort = cmdPort
        link = PtpLink(log: log)
    }

    // MARK: - Handshake

    /// Runs the whole handshake. [onWaitingForOk] is called while the camera shows its
    /// "accept connection" prompt. [viaBluetooth]: the Wi-Fi was started by a Bluetooth wake,
    /// so a new enough body expects the XApp protocol rather than Camera Remote.
    func connect(clientName: String, viaBluetooth: Bool, onWaitingForOk: () -> Void) throws {
        xappMode = false
        importHandles = []
        compressionMode = 0
        sizePropUnsupported = false
        infoOnly = false
        log("Kết nối TCP \(host):\(cmdPort)")
        try link.open(host: host, port: cmdPort)

        try handshake(clientName)
        Thread.sleep(forTimeInterval: 0.06) // OpenSession sent within ~50 ms of the ack gets silence.

        link.tid = 1
        let open = try link.transaction(Fuji.ocOpenSession, [1])
        if !open.ok && open.code != Fuji.rcSessionAlreadyOpen {
            throw PtpError.camera("Không mở được phiên (OpenSession \(Fuji.hex(open.code)))", code: open.code)
        }
        log("Đã mở phiên PTP")

        cameraState = -1
        objectCount = -1
        try pollEvents()
        if cameraState == Fuji.stateWaitForAccess || cameraState == -1 {
            log("Đang chờ bấm OK trên máy ảnh…")
            onWaitingForOk()
            let deadline = Date().addingTimeInterval(120)
            while cameraState == Fuji.stateWaitForAccess || cameraState == -1 {
                if Date() > deadline { throw PtpError.camera("Hết thời gian chờ xác nhận trên máy ảnh") }
                Thread.sleep(forTimeInterval: 0.3)
                try pollEvents()
            }
        }
        log("Trạng thái máy ảnh DF00 = \(cameraState)")

        let getObjectVersion = try getPropInt(Fuji.dpcGetObjectVersion)
        remoteImageViewVersion = try getPropInt(Fuji.dpcRemoteGetObjectVersion)
        let imageGetVersion = try getPropInt(Fuji.dpcImageGetVersion)
        remoteVersion = try getPropInt(Fuji.dpcRemoteVersion)
        log("Phiên bản: DF22=\(hexOrNone(getObjectVersion)) DF25=\(hexOrNone(remoteImageViewVersion)) " +
            "DF21=\(hexOrNone(imageGetVersion)) DF24=\(hexOrNone(remoteVersion))")

        // libfuji: after a Bluetooth hand-off, bodies older than remote version 0x20007 still speak Camera Remote.
        if viaBluetooth && remoteVersion >= 0x20007 {
            xappMode = true
            try configXappGallery()
            log("Sẵn sàng (XApp). Số ảnh: \(importHandles.isEmpty ? objectCount : importHandles.count)")
            return
        }

        try configInitMode()
        if cameraState == Fuji.stateMultipleTransfer {
            log("Máy ảnh đang ở chế độ gửi ảnh đã chọn")
            return
        }
        try configVersion()
        if remoteVersion != -1 && cameraState == Fuji.stateRemoteAccess {
            // Fuji's own client opens and closes remote mode once before the gallery;
            // without it some bodies stop answering.
            log("Cấu hình chế độ remote")
            try configRemoteOnce()
            log("Cấu hình thư viện ảnh")
            try configImageGallery()
        }
        log("Sẵn sàng. Số ảnh: \(objectCount)")
    }

    private func handshake(_ clientName: String) throws {
        for attempt in 0..<3 {
            var name = [UInt8](repeating: 0, count: 54)
            for (i, c) in clientName.utf16.prefix(26).enumerated() {
                name[i * 2] = UInt8(c & 0xFF)
                name[i * 2 + 1] = UInt8(c >> 8)
            }
            var w = LEWriter()
            w.u32(0x52)
            w.u32(Fuji.initCommandReq)
            w.u32(Fuji.protocolVersion)
            w.u32(0x5d48_a5ad); w.u32(0x0b7f_b287); w.u32(0xd0de_d5d3); w.u32(0)
            w.append(name)
            try link.write(w.bytes)
            let reply = try link.readPacket()
            if reply.u32(4) == Fuji.initFail {
                log("Init bị từ chối (lần \(attempt + 1)), thử lại")
                Thread.sleep(forTimeInterval: 0.3)
                continue
            }
            cameraName = reply.count > 28 ? decodeUtf16(reply, 28) : ""
            log("Init OK: \"\(cameraName)\"")
            return
        }
        throw PtpError.camera("Máy ảnh từ chối kết nối (Init Fail)")
    }

    private func decodeUtf16(_ b: [UInt8], _ off: Int) -> String {
        var units: [UInt16] = []
        var p = off
        while p + 1 < b.count {
            let c = UInt16(b.u16(p))
            if c == 0 { break }
            units.append(c)
            p += 2
        }
        return String(decoding: units, as: UTF16.self)
    }

    /// DF01: tells the camera which kind of client we are. Mirrors libfuji's choice.
    private func configInitMode() throws {
        let mode: Int
        if remoteVersion != -1 || cameraState == Fuji.stateRemoteAccess { mode = Fuji.clientRemoteMode }
        else if cameraState == Fuji.stateMultipleTransfer { mode = Fuji.clientViewMultiple }
        else if cameraState == Fuji.statePcAutoSave { mode = Fuji.clientOldRemote }
        else { mode = Fuji.clientViewAllImgs }
        log("Đặt chế độ client DF01 = \(mode)")
        // Newer bodies may show a second Yes/No dialog here, so give them time.
        link.setReadTimeout(120)
        defer { link.setReadTimeout(15) }
        try requireOk(setProp16(Fuji.dpcClientState, mode), "đặt chế độ client")
        try pollEvents()
    }

    private func configVersion() throws {
        if remoteVersion == -1 {
            guard let raw = try getPropRaw(Fuji.dpcGetObjectVersion) else { return }
            try requireOk(setPropRaw(Fuji.dpcGetObjectVersion, raw), "đặt DF22")
        } else {
            try requireOk(setProp32(Fuji.dpcRemoteVersion, Fuji.camConnectRemoteVer), "đặt DF24")
        }
    }

    private func configRemoteOnce() throws {
        let openTid = link.tid
        try requireOk(link.transaction(Fuji.ocInitiateOpenCapture, [0, 0]), "vào chế độ remote")
        try pollEvents()
        Thread.sleep(forTimeInterval: 0.05)
        // The camera expects the event and live view sockets to be opened in remote mode.
        for port in [Fuji.eventPort, Fuji.liveviewPort] {
            let side = PtpLink(log: log)
            do {
                try side.open(host: host, port: port)
                sideSockets.append(side)
            } catch {
                log("Không mở được cổng \(port): \(error.localizedDescription)")
            }
        }
        try pollEvents()
        let t = try link.transaction(Fuji.ocTerminateOpenCapture, [openTid])
        if !t.ok { log("TerminateOpenCapture trả về \(Fuji.hex(t.code))") }
    }

    private func configImageGallery() throws {
        guard remoteImageViewVersion != -1 else { return }
        try pollEvents()
        try requireOk(setProp16(Fuji.dpcCameraState, Fuji.stateRemoteAccess), "đặt DF00")
        try pollEvents()
        try pollEvents()
        try requireOk(setProp16(Fuji.dpcClientState, Fuji.clientRemoteImgView), "đặt DF01 = 11")
        try pollEvents()
        // X-S10 and X-H1 want 4 or higher; Fuji's app sets 5.
        if try getPropRaw(Fuji.dpcRemoteGetObjectVersion) != nil {
            try requireOk(setProp32(Fuji.dpcRemoteGetObjectVersion, 5), "đặt DF25")
        }
        try pollEvents()
    }

    /// XApp's gallery setup, in XApp's order (as recorded by libfuji and fuji-bridge). The body
    /// answers some of these with errors on some firmware; none of them is fatal.
    private func configXappGallery() throws {
        log("Đặt chế độ client DF01 = \(Fuji.clientRemoteImgViewXapp) (XApp)")
        link.setReadTimeout(120)
        let r = try setProp16(Fuji.dpcClientState, Fuji.clientRemoteImgViewXapp)
        link.setReadTimeout(15)
        if !r.ok { log("DF01 = 20 bị từ chối (\(Fuji.hex(r.code))), vẫn tiếp tục") }
        if let raw = try getPropRaw(Fuji.dpcRemotePhotoViewEx) { try setPropRaw(Fuji.dpcRemotePhotoViewEx, raw) }
        try setProp16(Fuji.dpcCompressSmall, 0)
        try setProp16(Fuji.dpcCorrectFileSize, 0)
        try link.transaction(Fuji.ocGetExtensionObjectInfo, [0x1000_0001])
        try link.transaction(Fuji.ocGetExtensionThumb, [0x1000_0001])

        // Right after a card goes in, the body is still indexing it: the import calls answer
        // Device Busy / Store Not Available and every handle is invalid until it finishes.
        let started = Date()
        while true {
            let folders = try link.transaction(Fuji.ocGetImageImportFolders)
            _ = try getPropRaw(Fuji.dpcUnknownD22B)
            let dates = try (Fuji.isTransient(folders.code) ? folders : link.transaction(Fuji.ocGetImageImportDates, [0, 30000]))
            var rawHandles: [UInt8]?
            if !Fuji.isTransient(dates.code) {
                _ = try getPropRaw(Fuji.dpcImageImportCount)
                rawHandles = try getPropRaw(Fuji.dpcImageImportHandles)
                importHandles = rawHandles.map(parseHandleArray) ?? []
            }
            let waited = Date().timeIntervalSince(started)
            if !importHandles.isEmpty {
                log("D621: \(importHandles.count) ảnh (sau \(Int(waited)) s)")
                break
            }
            let stillIndexing = Fuji.isTransient(folders.code) || Fuji.isTransient(dates.code) || rawHandles == nil
            if !stillIndexing || waited > cardIndexTimeout {
                log("D621 trống hoặc không đọc được" + (stillIndexing ? " — hết thời gian chờ, đoán số ảnh từ D222" : ""))
                break
            }
            onPreparingCard(waited)
            Thread.sleep(forTimeInterval: 2)
            try pollEvents()
        }
        try pollEvents()
    }

    /// u32 count, then that many u32 handles.
    private func parseHandleArray(_ b: [UInt8]) -> [UInt32] {
        guard b.count >= 4 else { return [] }
        let count = Int(b.u32(0))
        guard count > 0, count <= 20_000, b.count >= 4 + count * 4 else { return [] }
        return (0..<count).map { b.u32(4 + $0 * 4) }
    }

    // MARK: - Properties

    /// Reads the D212 change list and updates state and object count.
    @discardableResult
    func pollEvents() throws -> [UInt32: UInt32] {
        guard let raw = try getPropRaw(Fuji.dpcEventsList), raw.count >= 2 else { return [:] }
        var events: [UInt32: UInt32] = [:]
        for i in 0..<raw.u16(0) {
            let off = 2 + i * 6
            if off + 6 > raw.count { break }
            events[UInt32(raw.u16(off))] = raw.u32(off + 2)
        }
        if let s = events[Fuji.dpcCameraState] { cameraState = Int(s) }
        if let c = events[Fuji.dpcObjectCount] { objectCount = Int(c) }
        if !events.isEmpty {
            log("  sự kiện: " + events.map { "\(Fuji.hex($0.key))=\($0.value)" }.joined(separator: ", "))
        }
        return events
    }

    func getPropRaw(_ code: UInt32) throws -> [UInt8]? {
        let r = try link.transaction(Fuji.ocGetPropValue, [code])
        return r.ok ? r.payload : nil
    }

    private func getPropInt(_ code: UInt32) throws -> Int {
        guard let raw = try getPropRaw(code) else { return -1 }
        if raw.count >= 4 { return Int(raw.u32(0)) }
        if raw.count >= 2 { return raw.u16(0) }
        if raw.count == 1 { return Int(raw[0]) }
        return -1
    }

    @discardableResult
    private func setPropRaw(_ code: UInt32, _ value: [UInt8]) throws -> PtpResponse {
        try link.transaction(Fuji.ocSetPropValue, [code], data: value)
    }

    @discardableResult
    private func setProp16(_ code: UInt32, _ value: Int) throws -> PtpResponse { try setPropRaw(code, LEWriter.u16(value)) }

    @discardableResult
    private func setProp32(_ code: UInt32, _ value: UInt32) throws -> PtpResponse { try setPropRaw(code, LEWriter.u32(value)) }

    private func requireOk(_ r: PtpResponse, _ what: String) throws {
        if !r.ok { throw PtpError.camera("Máy ảnh từ chối \(what) (\(Fuji.hex(r.code)))", code: r.code) }
    }

    private func hexOrNone(_ v: Int) -> String { v == -1 ? "—" : Fuji.hex(v) }

    // MARK: - Photos

    /// All photo handles, newest first.
    func handles() throws -> [UInt32] {
        if xappMode {
            // Re-read D621 so photos taken during the session show up. Newest (highest handle) first.
            if let raw = try getPropRaw(Fuji.dpcImageImportHandles) {
                let h = parseHandleArray(raw)
                if !h.isEmpty { importHandles = h }
            }
            if !importHandles.isEmpty { return importHandles.sorted(by: >) }
            if objectCount > 0 { return (1...objectCount).reversed().map { UInt32($0) } }
        }
        if objectCount > 0 {
            let list = (1...objectCount).map { UInt32($0) }
            return handlesOldestFirst ? list.reversed() : list
        }
        // Count unknown (e.g. "send selected images" mode): probe until a handle fails.
        var found: [UInt32] = []
        for h in UInt32(1)...999 {
            guard try objectInfo(h) != nil else { break }
            found.append(h)
        }
        return found
    }

    func objectInfo(_ handle: UInt32) throws -> ObjectInfo? {
        let r = try link.transaction(Fuji.ocGetObjectInfo, [handle])
        guard r.ok, r.payload.count >= 53 else { return nil }
        let b = r.payload
        // Fuji's ObjectInfo moves fields around: compressed size is the unaligned word at 13,
        // the variable part (filename, dates) starts at 52 like the ISO layout.
        let size = Int(b.u32(13))
        let (name, used) = PtpString.decode(b, 52)
        let (date, _) = PtpString.decode(b, 52 + used)
        return ObjectInfo(handle: handle, fileName: name.isEmpty ? "IMG_\(handle)" : name, size: size, captureDate: date)
    }

    private func objectProp(_ handle: UInt32, _ code: UInt32) throws -> [UInt8]? {
        let r = try link.transaction(Fuji.ocGetObjectPropValue, [handle, code])
        return r.ok ? r.payload : nil
    }

    /// ObjectSize through GetObjectPropValue, the way XApp learns a file's real length.
    private func objectSize(_ handle: UInt32) throws -> Int? {
        guard let b = try objectProp(handle, Fuji.opcObjectSize) else { return nil }
        let v: Int
        if b.count >= 8 { v = Int(b.u32(0)) | Int(b.u32(4)) << 32 }
        else if b.count >= 4 { v = Int(b.u32(0)) }
        else { return nil }
        return v > 0 ? v : nil
    }

    /// Name, size and date of one photo. XApp bodies get them from object properties (~10 ms
    /// each) as XApp does; an X-S20 refuses ObjectSize (0x2006) and its DateCreated sometimes
    /// never answers, so on the first refusal this switches to GetObjectInfo for the session.
    func describe(_ handle: UInt32) throws -> ObjectInfo? {
        if xappMode && !infoOnly {
            let size = try (sizePropUnsupported ? nil : objectSize(handle))
            if size == nil {
                if lastWasBusy || link.lastCode == Fuji.rcInvalidObjectHandle { return nil }
                sizePropUnsupported = true
                infoOnly = true
                log("Máy ảnh không hỗ trợ ObjectSize (\(Fuji.hex(link.lastCode))); đọc tên/ngày bằng GetObjectInfo")
                return try objectInfo(handle)
            }
            if let rawName = try objectProp(handle, Fuji.opcObjectFileName) {
                let name = baseName(PtpString.decode(rawName, 0).0)
                if !name.isEmpty {
                    if let rawDate = try objectProp(handle, Fuji.opcDateCreated) {
                        return ObjectInfo(handle: handle, fileName: name, size: size ?? 0,
                                          captureDate: PtpString.decode(rawDate, 0).0, sizeTrusted: size != nil)
                    }
                    if lastWasBusy { return nil }
                    log("Máy ảnh không hỗ trợ DateCreated (\(Fuji.hex(link.lastCode))); đọc ngày bằng GetObjectInfo")
                    infoOnly = true
                    return try objectInfo(handle)
                }
            }
            if lastWasBusy || link.lastCode == Fuji.rcInvalidObjectHandle { return nil }
            infoOnly = true
        }
        return try objectInfo(handle)
    }

    private func baseName(_ s: String) -> String {
        String(s.split(whereSeparator: { $0 == "/" || $0 == "\\" }).last ?? Substring(s))
    }

    /// The camera's thumbnail (a small JPEG). Newer bodies never answer GetThumb unless
    /// GetObjectInfo for the same handle came right before (a 15 s stall on an X-S20).
    func thumbnail(_ handle: UInt32) throws -> [UInt8]? {
        let needsInfo = xappMode || remoteVersion > 0x20006
        if needsInfo && !link.lastWas(Fuji.ocGetObjectInfo, handle) {
            _ = try objectInfo(handle)
            if lastWasBusy { return nil }
        }
        let r = try link.transaction(Fuji.ocGetThumb, [handle])
        return r.ok && r.payload.count > 100 ? r.payload : nil
    }

    /// Up to [length] bytes of the file from [offset], or nil if the camera refuses.
    private func readHead(_ handle: UInt32, _ offset: Int, _ length: Int) throws -> [UInt8]? {
        let r = try link.transaction(Fuji.ocGetPartialObject, [handle, UInt32(offset), UInt32(length)])
        return r.ok && !r.payload.isEmpty ? r.payload : nil
    }

    /// EXIF orientation of a photo, read from the start of the file (the camera's thumbnail is
    /// never rotated). RAF files carry a JPEG whose offset is a big-endian u32 at byte 84.
    func orientation(_ handle: UInt32, fileName: String) throws -> Int? {
        let ext = fileName.split(separator: ".").last.map { $0.lowercased() } ?? ""
        guard ["jpg", "jpeg", "hif", "heif", "heic", "raf"].contains(ext) else { return nil }
        // An X-S20 never answers GetPartialObject unless it is set up exactly as for a download:
        // D226 ≠ 0, D227 = 1, then GetObjectInfo for the same handle right before the read.
        try requestOriginals()
        orientationInFlight = true
        // Fail fast: a stalled read costs the session either way.
        link.setReadTimeout(8)
        let sizeFix = try xappMode && setProp16(Fuji.dpcCorrectFileSize, 1).ok
        defer {
            if sizeFix && !orientationInFlight { _ = try? setProp16(Fuji.dpcCorrectFileSize, 0) }
            link.setReadTimeout(15)
        }
        if xappMode && !link.lastWas(Fuji.ocGetObjectInfo, handle) {
            guard try objectInfo(handle) != nil else {
                orientationInFlight = false
                return nil
            }
        }
        let result = try readOrientation(handle, ext)
        orientationInFlight = false
        return result
    }

    private func readOrientation(_ handle: UInt32, _ ext: String) throws -> Int? {
        switch ext {
        case "jpg", "jpeg":
            return try readHead(handle, 0, 4096).flatMap(ExifOrientation.parse)
        case "hif", "heif", "heic":
            if let o = try readHead(handle, 0, 4096).flatMap(ExifOrientation.parse) { return o }
            return try readHead(handle, 0, 65536).flatMap(ExifOrientation.parse)
        case "raf":
            guard let header = try readHead(handle, 0, 512), header.count >= 92 else { return nil }
            let jpegOffset = Int(header[84]) << 24 | Int(header[85]) << 16 | Int(header[86]) << 8 | Int(header[87])
            guard jpegOffset > 0 else { return nil }
            // Unaligned reads are very slow on Fuji bodies, so start at a 512-byte boundary.
            let aligned = jpegOffset - jpegOffset % 512
            return try readHead(handle, aligned, 4096 + (jpegOffset - aligned)).flatMap(ExifOrientation.parse)
        default:
            return nil
        }
    }

    /// D226 = 2: send originals. File reads need D226 ≠ 0 on an X-S20.
    private func requestOriginals() throws {
        guard xappMode, compressionMode != 2 else { return }
        let r = try setProp16(Fuji.dpcCompressSmall, 2)
        if !r.ok { log("D226 = 2 bị từ chối (\(Fuji.hex(r.code)))") }
        compressionMode = 2
    }

    // MARK: - Download

    /// Streams one file into [write] in chunks of up to 1 MB. [progress] gets (done, total).
    /// Returns the file's info with its real size. Never returns half a JPEG.
    func download(
        _ handle: UInt32,
        isCancelled: () -> Bool,
        write: ([UInt8], Int) throws -> Void,
        progress: (Int, Int) -> Void
    ) throws -> ObjectInfo {
        link.setReadTimeout(30)
        var correctSizeOn = false
        defer {
            if !xappMode { _ = try? pollEvents() }
            if correctSizeOn { _ = try? setProp16(Fuji.dpcCorrectFileSize, 0) }
            link.setReadTimeout(15)
        }
        var info: ObjectInfo
        if xappMode {
            try requestOriginals()
            guard let base = try describe(handle) else { throw PtpError.camera("Không đọc được thông tin ảnh #\(handle)") }
            info = base
            if !base.sizeTrusted {
                let size = try (sizePropUnsupported ? nil : objectSize(handle))
                if let size {
                    info.size = size
                } else {
                    // Body without ObjectSize: libfuji's D227 = 1 for a truthful ObjectInfo, which
                    // is also the GetObjectInfo an X-S20 needs right before the first read.
                    try setProp16(Fuji.dpcCorrectFileSize, 1)
                    correctSizeOn = true
                    info.size = try objectInfo(handle)?.size ?? base.size
                }
            }
        } else {
            try pollEvents()
            // With D227 = 0 the camera reports ~100 KB for every file.
            try setProp16(Fuji.dpcCorrectFileSize, 1)
            correctSizeOn = true
            guard let i = try objectInfo(handle) else { throw PtpError.camera("Không đọc được thông tin ảnh #\(handle)") }
            info = i
        }
        log("Tải \(info.fileName): \(info.size) B")

        var total = info.size
        var done = 0
        var shortEnd = -1
        var lastTwo = 0
        let jpeg = info.fileName.lowercased().hasSuffix(".jpg") || info.fileName.lowercased().hasSuffix(".jpeg")
        func tail(_ b: [UInt8], _ len: Int) -> Int {
            if len >= 2 { return Int(b[len - 2]) << 8 | Int(b[len - 1]) }
            if len == 1 { return (lastTwo & 0xFF) << 8 | Int(b[0]) }
            return lastTwo
        }
        while true {
            if done >= total {
                // A JPEG that hasn't reached FF D9 at the announced length: the length was
                // rounded down, so keep reading until the camera has nothing more. An X-S20 has
                // announced 4+ MB too little, so the only cap is a size no JPEG reaches.
                if jpeg && lastTwo != 0xFFD9 && done > 0 && done < Fuji.maxJpegBytes {
                    total = done + Fuji.maxPartial
                } else {
                    break
                }
            }
            if isCancelled() { throw PtpError.cancelled }
            var ask = min(Fuji.maxPartial, total - done)
            var r = try link.transaction(Fuji.ocGetPartialObject, [handle, UInt32(done), UInt32(ask)])
            if r.code == Fuji.rcInvalidParameter && done > 0 {
                // An X-S20 announces a rounded-up length and refuses a read that runs past the
                // real end. Halve the window until it answers; when even 1 byte is refused, the
                // file ended at [done].
                while r.code == Fuji.rcInvalidParameter && ask > 1 {
                    ask /= 2
                    r = try link.transaction(Fuji.ocGetPartialObject, [handle, UInt32(done), UInt32(ask)])
                }
                if r.code == Fuji.rcInvalidParameter {
                    total = done
                    break
                }
            }
            guard r.ok else { throw PtpError.camera("Lỗi tải \(info.fileName) (\(Fuji.hex(r.code)))", code: r.code) }
            if r.payload.isEmpty {
                // Nothing past the end while probing past the announced length for FF D9.
                if done > 0 && done >= info.size { total = done; break }
                throw PtpError.camera("Máy ảnh trả về dữ liệu rỗng")
            }
            var take = r.payload.count
            if take < ask && done + take < total {
                // Same short answer at the same place twice: the object really ends here.
                if shortEnd == done + take {
                    try write(r.payload, take)
                    done += take
                    total = done
                    lastTwo = tail(r.payload, take)
                    break
                }
                shortEnd = done + take
                // Reads from an offset that isn't 512-aligned are up to 40× slower on Fuji bodies,
                // so after a short window drop the tail past the last 512-byte boundary.
                if take >= 512 { take -= take % 512 }
            }
            try write(r.payload, take)
            lastTwo = tail(r.payload, take)
            done += take
            progress(done, total)
        }
        if done != info.size {
            log("\(info.fileName): nhận \(done) B, máy ảnh báo \(info.size) B" +
                (jpeg ? (lastTwo == 0xFFD9 ? " — JPEG kết thúc đúng" : " — JPEG THIẾU phần cuối") : ""))
        }
        // Never keep half a picture: a JPEG must end with FF D9.
        if jpeg && done != info.size && lastTwo != 0xFFD9 {
            throw PtpError.camera("Ảnh tải về không hoàn chỉnh (\(info.fileName), \(done)/\(info.size) B)")
        }
        progress(done, done)
        info.size = done
        return info
    }

    func close() {
        if link.isOpen { _ = try? link.transaction(Fuji.ocCloseSession) }
        link.close()
        sideSockets.forEach { $0.close() }
        sideSockets.removeAll()
    }
}
