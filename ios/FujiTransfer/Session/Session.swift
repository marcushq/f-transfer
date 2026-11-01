import Foundation
import OSLog
import UIKit

enum ConnectMode: String {
    /// Newer bodies (X-S20, X-T5, X-H2, X100VI…): Bluetooth wakes the Wi-Fi.
    case bluetooth
    /// The phone is already on the camera's Wi-Fi (joined in Settings).
    case wifiCurrent
}

enum Phase { case idle, bluetooth, pairing, joining, connecting, waitingForOk, preparingCard, connected }

struct Photo: Identifiable {
    let handle: UInt32
    var id: UInt32 { handle }
    var info: ObjectInfo?
    var thumb: UIImage?
    /// A thumbnail load was attempted (it may have failed).
    var attempted = false
    /// Reading the details was attempted (they may be missing).
    var infoTried = false
    var downloaded = false
    /// EXIF orientation (1–8) once read; the camera's thumbnails are never rotated.
    var orientation: Int?

    /// The thumbnail, turned upright when [rotate] and the orientation is known.
    func shownThumb(rotate: Bool) -> UIImage? {
        guard let thumb, rotate, let cg = thumb.cgImage else { return thumb }
        switch ExifOrientation.degrees(orientation) {
        case 90: return UIImage(cgImage: cg, scale: thumb.scale, orientation: .right)
        case 180: return UIImage(cgImage: cg, scale: thumb.scale, orientation: .down)
        case 270: return UIImage(cgImage: cg, scale: thumb.scale, orientation: .left)
        default: return thumb
        }
    }

    /// Shooting day as "yyyyMMdd", once the details are read.
    var day: String? {
        guard let d = info?.captureDate.prefix(8), d.count == 8, d.allSatisfy(\.isNumber) else { return nil }
        return String(d)
    }
}

/// The photos of one shooting day, in the grid's order; [day] is nil while dates are unread.
struct DayGroup: Identifiable {
    let day: String?
    var photos: [Photo]
    var id: String { day ?? "unknown" }
}

struct Transfer {
    var index: Int
    var total: Int
    var fileName: String
    var fileDone = 0
    var fileTotal = 0
    var bytesPerSecond = 0.0
    var failed = 0

    /// Whole files done plus the share of the current one.
    var overall: Double {
        let part = fileTotal > 0 ? Double(fileDone) / Double(fileTotal) : 0
        return total > 0 ? min(1, (Double(index) + part) / Double(total)) : 0
    }
}

struct SessionError: LocalizedError {
    let message: String
    init(_ message: String) { self.message = message }
    var errorDescription: String? { message }
}

/// A flag the camera thread reads while the UI sets it.
final class Flag {
    private let lock = NSLock()
    private var v = false
    var value: Bool {
        get { lock.lock(); defer { lock.unlock() }; return v }
        set { lock.lock(); v = newValue; lock.unlock() }
    }
}

/// One camera session: connecting, the photo grid and downloads. Camera commands run one at a
/// time on [cameraQueue]; everything the UI shows lives here on the main actor.
@MainActor
final class Session: ObservableObject {
    @Published var phase: Phase = .idle
    @Published var status = ""
    @Published var error: String?
    @Published var notice: String?
    @Published var cameraName = ""
    @Published var photos: [Photo] = []
    @Published var selected: Set<UInt32> = []
    /// Only this day ("yyyyMMdd") is shown, or every day for nil.
    @Published var dayFilter: String? {
        didSet { dropHiddenSelection() }
    }
    /// Photos already on this phone are left out of the grid (remembered between launches).
    @Published var hideDownloaded = UserDefaults.standard.bool(forKey: "hideDownloaded") {
        didSet {
            UserDefaults.standard.set(hideDownloaded, forKey: "hideDownloaded")
            dropHiddenSelection()
        }
    }
    /// The day "Chọn cả ngày" is still reading from the camera, or nil.
    @Published var selectingDay: String?
    /// Portrait shots are shown upright in the grid (reads each file's EXIF; on by default).
    @Published var rotateThumbs = UserDefaults.standard.object(forKey: "rotateThumbs") as? Bool ?? true {
        didSet {
            UserDefaults.standard.set(rotateThumbs, forKey: "rotateThumbs")
            if rotateThumbs {
                pendingRotate = photos.filter { $0.thumb != nil && $0.orientation == nil }.map(\.handle).reversed()
            }
        }
    }
    @Published var transfer: Transfer? {
        didSet { if let transfer { TransferProgress.shared.update(transfer) } }
    }
    @Published var log: [String] = []

    private let cameraQueue = DispatchQueue(label: "camera")
    private var camera: FujiCamera!
    private var ble: FujiBluetooth!
    private var index: [UInt32: Int] = [:]
    private var sessionTask: Task<Void, Never>?
    private var thumbTask: Task<Void, Never>?
    /// Tiles that came on screen, newest last: their thumbnails load first.
    private var wanted: [UInt32] = []
    /// Thumbnails shown unrotated, waiting for their EXIF orientation; the last one goes first.
    private var pendingRotate: [UInt32] = []
    /// Names, dates, orientations and thumbnails from earlier sessions with this camera.
    private var meta: MetadataCache?
    private let cancelTransfer = Flag()
    private var cancelled = false

    private static let timeFormat: DateFormatter = {
        let f = DateFormatter()
        f.dateFormat = "HH:mm:ss.SSS"
        return f
    }()

    init() {
        let log: (String) -> Void = { [weak self] line in
            Task { @MainActor in self?.appendLog(line) }
        }
        camera = FujiCamera(log: log)
        ble = FujiBluetooth(log: log)
        TransferProgress.shared.log = log
        TransferProgress.shared.onExpire = { [weak self] in self?.stopTransfer() }
        // The Stop button in the Dynamic Island / Lock Screen.
        NotificationCenter.default.addObserver(forName: .stopTransfer, object: nil, queue: .main) { [weak self] _ in
            Task { @MainActor in
                self?.appendLog("Dừng tải từ Live Activity")
                self?.stopTransfer()
            }
        }
    }

    /// The same lines in the system log (Console.app, `log stream`), for debugging off the phone.
    private static let osLog = Logger(subsystem: "dev.fujitransfer", category: "session")

    func appendLog(_ line: String) {
        Self.osLog.info("\(line, privacy: .public)")
        log.append(Self.timeFormat.string(from: Date()) + " " + line)
        if log.count > 3000 { log.removeFirst(log.count - 3000) }
    }

    /// Runs [body] on the camera thread and waits for it.
    private func onCamera<T>(_ body: @escaping () throws -> T) async throws -> T {
        try await withCheckedThrowingContinuation { c in
            cameraQueue.async { c.resume(with: Result { try body() }) }
        }
    }

    // MARK: - Connecting

    func connect(_ mode: ConnectMode) {
        guard sessionTask == nil else { return }
        error = nil
        notice = nil
        cancelled = false
        sessionTask = Task {
            await runConnect(mode)
            sessionTask = nil
        }
    }

    /// Without the Hotspot Configuration capability (free Apple ID) iOS won't join for us: copy
    /// the password, let the user pick the network in Settings › Wi-Fi, and carry on by itself
    /// once the phone is on it.
    private func waitForManualJoin(_ ap: CameraAp) async throws {
        if !ap.password.isEmpty { UIPasteboard.general.string = ap.password }
        phase = .joining
        status = "Vào Cài đặt › Wi-Fi, chọn \"\(ap.ssid)\"" +
            (ap.password.isEmpty ? "" : " (mật khẩu đã được sao chép: \(ap.password))") +
            ", rồi quay lại đây. Giữ máy ảnh ở màn hình đang kết nối."
        appendLog("Chờ vào Wi-Fi \"\(ap.ssid)\" trong Cài đặt")
        let deadline = Date().addingTimeInterval(180)
        while !CameraWifi.onCameraNetwork {
            if cancelled { return }
            if Date() > deadline {
                throw SessionError("Chưa thấy iPhone vào Wi-Fi \"\(ap.ssid)\" sau 3 phút. Bấm Kết nối để thử lại.")
            }
            try await Task.sleep(nanoseconds: 1_000_000_000)
        }
        appendLog("Đã vào Wi-Fi \"\(ap.ssid)\"")
    }

    private func runConnect(_ mode: ConnectMode) async {
        appendLog("Bắt đầu kết nối (\(mode.rawValue)), iOS \(UIDevice.current.systemVersion)")
        do {
            if mode == .bluetooth && CameraWifi.onCameraNetwork {
                // Joined in Settings after an earlier wake: the body no longer advertises, skip the scan.
                appendLog("Đã ở sẵn trong Wi-Fi của máy ảnh, bỏ qua Bluetooth")
            } else if mode == .bluetooth {
                phase = .bluetooth
                status = "Đang tìm máy ảnh qua Bluetooth…"
                var ap: CameraAp
                do {
                    // With a known network to fall back on, give up on Bluetooth sooner.
                    ap = try await ble.wakeWifi(firstScan: CameraWifi.lastAp != nil ? 12 : 25) { [weak self] in
                        Task { @MainActor in
                            self?.phase = .pairing
                            self?.status = "Xác nhận ghép đôi trên iPhone và trên máy ảnh"
                        }
                    }
                    CameraWifi.lastAp = ap
                } catch {
                    appendLog("LỖI Bluetooth: \(error.localizedDescription)")
                    ble.close()
                    // A body whose Wi-Fi is already up stops advertising over Bluetooth; join the
                    // network it gave us last time instead.
                    guard let saved = CameraWifi.lastAp, !cancelled else { throw error }
                    appendLog("Thử vào thẳng Wi-Fi đã biết \"\(saved.ssid)\"")
                    ap = saved
                }
                if cancelled { return }
                phase = .joining
                status = "Đang vào Wi-Fi \(ap.ssid)…"
                if CameraWifi.onCameraNetwork {
                    appendLog("Đã ở sẵn trong Wi-Fi của máy ảnh")
                } else {
                    do {
                        try await CameraWifi.join(ap)
                        appendLog("Đã vào Wi-Fi \"\(ap.ssid)\"")
                    } catch {
                        appendLog("Không tự vào được Wi-Fi: \(error.localizedDescription)")
                        try await waitForManualJoin(ap)
                    }
                }
            }
            if cancelled { return }
            phase = .connecting
            status = "Đang bắt tay với máy ảnh…"
            camera.onPreparingCard = { [weak self] waited in
                Task { @MainActor in
                    self?.phase = .preparingCard
                    self?.status = "Máy ảnh đang đọc thẻ nhớ… (\(Int(waited)) s)"
                }
            }
            let viaBluetooth = mode == .bluetooth
            let handles: [UInt32] = try await onCamera { [camera, weak self] in
                // The camera's access point may need a few seconds before it accepts TCP.
                var attempt = 0
                while true {
                    do {
                        try camera!.connect(clientName: FujiBluetooth.clientName, viaBluetooth: viaBluetooth) {
                            Task { @MainActor in
                                self?.phase = .waitingForOk
                                self?.status = "Bấm OK trên máy ảnh để cho phép kết nối"
                            }
                        }
                        return try camera!.handles()
                    } catch let e as PtpError {
                        attempt += 1
                        var network = false
                        if case .io = e { network = true }
                        if case .timeout = e { network = true }
                        guard network && attempt < 6 else { throw e }
                        camera!.close()
                        Thread.sleep(forTimeInterval: 1.5)
                    }
                }
            }
            ble.close() // Wi-Fi is up and answering; Bluetooth was only needed for the wake.
            if cancelled { return }
            cameraName = camera.cameraName.isEmpty ? "Máy ảnh Fujifilm" : camera.cameraName
            photos = handles.map { Photo(handle: $0) }
            index = Dictionary(handles.enumerated().map { ($1, $0) }, uniquingKeysWith: { first, _ in first })
            let cache = MetadataCache(camera: cameraName)
            meta = cache
            if try await verifyCache(cache, handles) { prefill(from: cache) }
            selected = []
            dayFilter = nil
            phase = .connected
            status = ""
            appendLog("Đã kết nối \(cameraName): \(handles.count) ảnh")
            startThumbnails()
        } catch {
            if cancelled { return }
            appendLog("LỖI: \(error.localizedDescription)" + (camera.stalledCommand.map { " — máy ảnh không trả lời lệnh \($0)" } ?? ""))
            let cam = camera!
            cameraQueue.async { cam.close() }
            ble.close()
            fail(error.localizedDescription)
        }
    }

    /// Is this still the card the cache was built from? Asks the camera for the names of three
    /// known photos (newest, middle, oldest) instead of checking every one.
    private func verifyCache(_ m: MetadataCache, _ handles: [UInt32]) async throws -> Bool {
        let known = handles.filter { m.entry($0)?.date.isEmpty == false }
        guard !known.isEmpty else { return false }
        for h in Set([known[0], known[known.count / 2], known[known.count - 1]]) {
            let name = try await onCamera { [camera] in try camera!.describe(h)?.fileName }
            if name != m.entry(h)?.name {
                appendLog("Bộ nhớ đệm không khớp ảnh #\(h) (\(m.entry(h)?.name ?? "—") ≠ \(name ?? "—")) — đọc lại từ máy ảnh")
                return false
            }
        }
        return true
    }

    /// Lays out the grid from the cache straight away: names, dates, orientations.
    private func prefill(from m: MetadataCache) {
        var n = 0
        for i in photos.indices {
            guard let e = m.entry(photos[i].handle), !e.date.isEmpty else { continue }
            let info = ObjectInfo(handle: photos[i].handle, fileName: e.name, size: 0, captureDate: e.date)
            photos[i].info = info
            photos[i].infoTried = true
            photos[i].downloaded = Downloaded.contains(info)
            photos[i].orientation = e.orientation
            n += 1
        }
        appendLog("Bộ nhớ đệm: đã có \(n)/\(photos.count) ảnh")
    }

    /// Writes what was learned about the card (also when the app leaves the screen).
    func saveCache() { meta?.save() }

    /// Forgets every name, date and thumbnail kept on the phone.
    func clearCache() {
        MetadataCache.clearAll()
        if phase == .connected { meta = MetadataCache(camera: cameraName) }
        appendLog("Đã xoá bộ nhớ đệm ảnh")
        notice = "Đã xoá bộ nhớ đệm. Lần kết nối sau ảnh sẽ được đọc lại từ máy ảnh."
    }

    private func fail(_ message: String) {
        meta?.save()
        thumbTask?.cancel()
        phase = .idle
        status = ""
        error = message
        transfer = nil
    }

    func disconnect() {
        cancelled = true
        cancelTransfer.value = true
        sessionTask?.cancel()
        sessionTask = nil
        thumbTask?.cancel()
        let cam = camera!
        cameraQueue.async { cam.close() }
        ble.close()
        phase = .idle
        status = ""
        photos = []
        selected = []
        dayFilter = nil
        transfer = nil
        wanted = []
        pendingRotate = []
        meta?.save()
        meta = nil
        appendLog("Đã ngắt kết nối")
    }

    // MARK: - Thumbnails

    func requestThumb(_ handle: UInt32) {
        guard let i = index[handle], !photos[i].attempted else { return }
        wanted.removeAll { $0 == handle }
        wanted.append(handle)
        if wanted.count > 60 { wanted.removeFirst(wanted.count - 60) }
    }

    /// Loads details and thumbnails: tiles on screen first (from the phone's cache when it has
    /// them), then their EXIF orientations, then every photo's details (dates for the day groups
    /// and filters; cheap), then the remaining thumbnails in order. Pauses while photos are
    /// downloading (the camera does one thing at a time).
    private func startThumbnails() {
        thumbTask?.cancel()
        thumbTask = Task {
            var infoCursor = 0
            var thumbCursor = 0
            while !Task.isCancelled && phase == .connected {
                if transfer != nil {
                    try? await Task.sleep(nanoseconds: 300_000_000)
                    continue
                }
                var next: UInt32?
                var wantThumb = true
                while let h = wanted.popLast() {
                    if let i = index[h], !photos[i].attempted { next = h; break }
                }
                if next == nil, rotateThumbs, let h = popRotate() {
                    guard await rotate(h) else { return }
                    continue
                }
                if next == nil {
                    while infoCursor < photos.count && photos[infoCursor].infoTried { infoCursor += 1 }
                    if infoCursor < photos.count { next = photos[infoCursor].handle; wantThumb = false }
                }
                if next == nil {
                    while thumbCursor < photos.count && photos[thumbCursor].attempted { thumbCursor += 1 }
                    if thumbCursor < photos.count { next = photos[thumbCursor].handle }
                }
                guard let h = next else {
                    try? await Task.sleep(nanoseconds: 300_000_000)
                    continue
                }
                guard await load(h, wantThumb: wantThumb) else { return }
            }
        }
    }

    /// Reads [h]'s details and, if [wantThumb], its thumbnail. False when the session is lost.
    private func load(_ h: UInt32, wantThumb: Bool) async -> Bool {
        guard let i = index[h] else { return true }
        var info = photos[i].info
        do {
            if !photos[i].infoTried {
                let (got, busy) = try await onCamera { [camera] in (try camera!.describe(h), camera!.lastWasBusy) }
                if got == nil && busy {
                    try? await Task.sleep(nanoseconds: 2_000_000_000) // still reading the card
                    return true
                }
                guard let j = index[h] else { return true }
                setInfo(j, got)
                info = got
            }
            guard wantThumb else { return true }
            guard let info else {
                if let j = index[h] { photos[j].attempted = true }
                return true
            }
            // From the phone: no camera command at all.
            if let data = await meta?.thumb(h, name: info.fileName) {
                showThumb(h, data)
                return true
            }
            let (thumb, busy) = try await onCamera { [camera] in (try camera!.thumbnail(h), camera!.lastWasBusy) }
            if thumb == nil && busy {
                try? await Task.sleep(nanoseconds: 2_000_000_000)
                return true
            }
            let data = thumb.map { Data($0) }
            if let data {
                meta?.saveThumb(h, name: info.fileName, data)
                if let o = ExifOrientation.parse(Array(data)), let j = index[h] {
                    photos[j].orientation = o
                    meta?.setOrientation(h, name: info.fileName, o)
                }
            }
            showThumb(h, data)
        } catch {
            appendLog("LỖI ảnh #\(h): \(error.localizedDescription)")
            if case PtpError.io = error {
                fail("Mất kết nối với máy ảnh. Hãy kết nối lại.")
                return false
            }
            if let j = index[h] {
                photos[j].infoTried = true
                if wantThumb { photos[j].attempted = true }
            }
        }
        return true
    }

    private func showThumb(_ h: UInt32, _ data: Data?) {
        guard let i = index[h] else { return }
        photos[i].thumb = data.flatMap { UIImage(data: $0) }.map(trimBlackBars)
        photos[i].attempted = true
        if photos[i].thumb != nil && photos[i].orientation == nil { pendingRotate.append(h) }
    }

    private func popRotate() -> UInt32? {
        while let h = pendingRotate.popLast() {
            if let i = index[h], photos[i].orientation == nil, photos[i].info != nil { return h }
        }
        return nil
    }

    /// Reads [h]'s EXIF orientation from the file. A camera that stalls on it loses the session
    /// either way, so rotation is then switched off. False when the session is lost.
    private func rotate(_ h: UInt32) async -> Bool {
        guard let i = index[h], let name = photos[i].info?.fileName else { return true }
        do {
            let o = try await onCamera { [camera] in try camera!.orientation(h, fileName: name) } ?? ExifOrientation.normal
            guard let j = index[h] else { return true }
            photos[j].orientation = o
            meta?.setOrientation(h, name: name, o)
        } catch {
            let stalled = camera.orientationInFlight
            appendLog("LỖI đọc hướng ảnh #\(h): \(error.localizedDescription)")
            if stalled {
                rotateThumbs = false
                appendLog("Máy ảnh không trả lời khi đọc EXIF → đã tắt xoay ảnh thu nhỏ")
                let cam = camera!
                cameraQueue.async { cam.close() }
                fail("Mất kết nối với máy ảnh.\nĐã tắt \"Xoay ảnh thu nhỏ\" vì máy ảnh không trả lời khi đọc hướng ảnh. Hãy kết nối lại.")
                return false
            }
            if case PtpError.io = error {
                fail("Mất kết nối với máy ảnh. Hãy kết nối lại.")
                return false
            }
            if let j = index[h] { photos[j].orientation = ExifOrientation.normal }
        }
        return true
    }

    private func setInfo(_ i: Int, _ info: ObjectInfo?) {
        photos[i].info = info
        photos[i].infoTried = true
        guard let info else { return }
        photos[i].downloaded = Downloaded.contains(info)
        meta?.put(photos[i].handle, name: info.fileName, date: info.captureDate)
        if let o = meta?.entry(photos[i].handle)?.orientation { photos[i].orientation = o }
    }

    /// The shooting day of the photo at [i], reading its details from the camera if needed.
    private func dayAt(_ i: Int) async -> String? {
        guard i >= 0, i < photos.count else { return nil }
        if photos[i].infoTried { return photos[i].day }
        let h = photos[i].handle
        guard let info = try? await onCamera({ [camera] in try camera!.describe(h) }) else { return nil }
        guard let j = index[h] else { return nil }
        setInfo(j, info)
        return photos[j].day
    }

    // MARK: - Selection

    func toggle(_ handle: UInt32) {
        guard transfer == nil else { return }
        if selected.contains(handle) { selected.remove(handle) } else { selected.insert(handle) }
    }

    // MARK: - Days and filters

    /// The photos the filters let through, in the grid's order.
    var visiblePhotos: [Photo] {
        photos.filter { p in
            (dayFilter == nil || p.day == dayFilter) && !(hideDownloaded && p.downloaded)
        }
    }

    /// Shooting days on the card, newest first.
    var days: [String] {
        Array(Set(photos.compactMap(\.day))).sorted(by: >)
    }

    /// The visible photos by shooting day; photos whose date isn't read yet come last.
    var dayGroups: [DayGroup] {
        var groups: [DayGroup] = []
        var at: [String: Int] = [:]
        var unknown: [Photo] = []
        for p in visiblePhotos {
            guard let d = p.day else { unknown.append(p); continue }
            if let g = at[d] {
                groups[g].photos.append(p)
            } else {
                at[d] = groups.count
                groups.append(DayGroup(day: d, photos: [p]))
            }
        }
        if !unknown.isEmpty { groups.append(DayGroup(day: nil, photos: unknown)) }
        return groups
    }

    /// Photos whose details (and date) are still unread.
    var unreadCount: Int { photos.reduce(0) { $0 + ($1.infoTried ? 0 : 1) } }

    /// A selection only keeps what the grid shows.
    private func dropHiddenSelection() {
        guard !selected.isEmpty else { return }
        selected.formIntersection(visiblePhotos.map(\.handle))
    }

    /// Selects a whole day, also its photos whose dates aren't read yet: a day's photos sit next
    /// to each other on the card, so it reads outwards from the ones shown until the date
    /// changes. Tapping a fully selected day clears it; [day] nil selects the undated group.
    func toggleDay(_ day: String?) {
        guard transfer == nil, selectingDay == nil else { return }
        let shown = visiblePhotos.filter { $0.day == day }.map(\.handle)
        if !shown.isEmpty && shown.allSatisfy(selected.contains) {
            selected.subtract(shown)
            return
        }
        guard let day else {
            selected.formUnion(shown)
            return
        }
        selectingDay = day
        Task {
            defer { selectingDay = nil }
            if let first = photos.firstIndex(where: { $0.day == day }),
               let last = photos.lastIndex(where: { $0.day == day }) {
                var i = first - 1
                while await dayAt(i) == day { i -= 1 }
                var j = last + 1
                while await dayAt(j) == day { j += 1 }
            }
            guard transfer == nil else { return }
            let picked = visiblePhotos.filter { $0.day == day }.map(\.handle)
            selected.formUnion(picked)
            appendLog("Chọn cả ngày \(day.suffix(2))/\(day.dropFirst(4).prefix(2)): \(picked.count) ảnh")
        }
    }

    /// Selects every photo the grid shows.
    func selectAll() {
        guard transfer == nil else { return }
        selected = Set(visiblePhotos.map(\.handle))
    }

    /// Selects the shown photos that aren't on this phone yet.
    func selectNew() {
        guard transfer == nil else { return }
        selected = Set(visiblePhotos.filter { $0.info != nil && !$0.downloaded }.map(\.handle))
        if selected.isEmpty {
            notice = unreadCount > 0 ? "Chưa có ảnh mới trong các ảnh đã đọc xong" : "Mọi ảnh đã ở trong điện thoại"
        }
    }

    func clearSelection() { selected = [] }

    // MARK: - Downloading

    func downloadSelected() {
        guard transfer == nil, !selected.isEmpty else { return }
        let queue = photos.filter { selected.contains($0.handle) }.map(\.handle)
        cancelTransfer.value = false
        Task { await runDownload(queue) }
    }

    func stopTransfer() { cancelTransfer.value = true }

    #if DEBUG
    /// A fake 12-photo transfer that drives the Live Activity without a camera
    /// (launch with -demoTransfer).
    func demoTransfer() {
        cameraName = "X-S20"
        cancelTransfer.value = false
        let flag = cancelTransfer
        Task {
            let total = 12
            TransferProgress.shared.start(cameraName: cameraName, total: total)
            var done = 0
            for i in 0..<total where !flag.value {
                transfer = Transfer(index: i, total: total, fileName: String(format: "DSCF%04d.JPG", 1200 + i))
                for step in 1...10 where !flag.value {
                    try? await Task.sleep(nanoseconds: 300_000_000)
                    transfer?.fileDone = step * 1_200_000
                    transfer?.fileTotal = 12_000_000
                    transfer?.bytesPerSecond = 4_000_000
                }
                done += 1
            }
            transfer = nil
            TransferProgress.shared.finish(summary: "Đã lưu \(done) ảnh" + (flag.value ? " (đã dừng)" : ""), success: !flag.value)
            appendLog("Demo: xong \(done) ảnh")
        }
    }

    /// A fake card of 40 photos over four days (no camera), to look at the grid; the last
    /// ten have no date yet, like a card still being read.
    func demoGallery() {
        cameraName = "X-S20"
        let cal = Calendar.current
        let f = DateFormatter()
        f.dateFormat = "yyyyMMdd"
        let colors: [UIColor] = [.systemOrange, .systemTeal, .systemIndigo, .systemPink, .systemGreen]
        photos = (0..<40).map { n in
            var p = Photo(handle: UInt32(40 - n))
            p.infoTried = n < 30
            if n < 30 {
                let day = f.string(from: cal.date(byAdding: .day, value: -[0, 1, 3, 9][min(n / 8, 3)], to: Date())!)
                p.info = ObjectInfo(handle: p.handle, fileName: String(format: "DSCF%04d.%@", 2000 - n, n % 7 == 0 ? "RAF" : "JPG"),
                                    size: 0, captureDate: day + "T120000")
                p.downloaded = n % 3 == 0
            }
            // Every fifth photo was shot upright: its arrow points up once the tile is rotated.
            if n % 5 == 2 { p.orientation = 6 }
            p.thumb = UIGraphicsImageRenderer(size: CGSize(width: 80, height: 80)).image { ctx in
                colors[n % colors.count].withAlphaComponent(0.4 + Double(n % 4) * 0.15).setFill()
                ctx.fill(CGRect(x: 0, y: 0, width: 80, height: 80))
                let arrow = UIImage(systemName: n % 5 == 2 ? "arrow.left" : "arrow.up")!
                arrow.withTintColor(.white).draw(in: CGRect(x: 25, y: 25, width: 30, height: 30))
            }
            p.attempted = true
            return p
        }
        index = Dictionary(photos.enumerated().map { ($1.handle, $0) }, uniquingKeysWith: { a, _ in a })
        phase = .connected
    }
    #endif

    private func runDownload(_ queue: [UInt32]) async {
        guard await PhotoSaver.authorize() else {
            error = "Hãy cho phép Fuji Transfer thêm ảnh vào ứng dụng Ảnh (Cài đặt › Fuji Transfer › Ảnh)."
            return
        }
        // Keep the screen (and with it Wi-Fi) on; if the app is left, keep going in the
        // background (iOS 26+) and show progress in the Dynamic Island.
        TransferProgress.shared.start(cameraName: cameraName, total: queue.count)
        UIApplication.shared.isIdleTimerDisabled = true
        var bg = UIBackgroundTaskIdentifier.invalid
        bg = UIApplication.shared.beginBackgroundTask(withName: "transfer") {
            UIApplication.shared.endBackgroundTask(bg)
        }
        defer {
            UIApplication.shared.isIdleTimerDisabled = false
            if bg != .invalid { UIApplication.shared.endBackgroundTask(bg) }
        }

        var ok = 0
        var failed = 0
        let flag = cancelTransfer
        for (i, h) in queue.enumerated() {
            if flag.value { break }
            let name = index[h].flatMap { photos[$0].info?.fileName } ?? "#\(h)"
            transfer = Transfer(index: i, total: queue.count, fileName: name, failed: failed)
            do {
                let dir = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
                try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
                let part = dir.appendingPathComponent("download.part")
                FileManager.default.createFile(atPath: part.path, contents: nil)
                let file = try FileHandle(forWritingTo: part)
                let started = Date()
                let info: ObjectInfo = try await onCamera { [camera, weak self] in
                    defer { try? file.close() }
                    var lastUI = Date.distantPast
                    return try camera!.download(h, isCancelled: { flag.value }, write: { bytes, count in
                        file.write(Data(bytes[0..<count]))
                    }, progress: { done, total in
                        let now = Date()
                        guard now.timeIntervalSince(lastUI) > 0.2 || done == total else { return }
                        lastUI = now
                        let speed = Double(done) / max(now.timeIntervalSince(started), 0.001)
                        Task { @MainActor in
                            self?.transfer?.fileDone = done
                            self?.transfer?.fileTotal = total
                            self?.transfer?.bytesPerSecond = speed
                        }
                    })
                }
                // Photos keeps the file's name and type from its extension.
                let named = dir.appendingPathComponent(info.fileName)
                try FileManager.default.moveItem(at: part, to: named)
                try await PhotoSaver.save(named, name: info.fileName)
                try? FileManager.default.removeItem(at: dir)
                Downloaded.add(info)
                if let idx = index[h] { photos[idx].downloaded = true }
                selected.remove(h)
                ok += 1
                let seconds = Date().timeIntervalSince(started)
                appendLog(String(format: "Đã lưu %@ (%.1f MB, %.1f s, %.1f MB/s)", info.fileName,
                                 Double(info.size) / 1_048_576, seconds, Double(info.size) / 1_048_576 / max(seconds, 0.001)))
            } catch PtpError.cancelled {
                break
            } catch {
                failed += 1
                appendLog("LỖI tải #\(h): \(error.localizedDescription)" + (camera.stalledCommand.map { " — máy ảnh không trả lời lệnh \($0)" } ?? ""))
                if case PtpError.io = error {
                    TransferProgress.shared.finish(summary: "Mất kết nối · đã lưu \(ok) ảnh", success: false)
                    fail("Mất kết nối với máy ảnh. Đã lưu \(ok) ảnh trước khi mất kết nối.")
                    return
                }
            }
        }
        transfer = nil
        let tail = (failed > 0 ? ", \(failed) ảnh lỗi" : "") + (flag.value ? " (đã dừng)" : "")
        TransferProgress.shared.finish(summary: "Đã lưu \(ok) ảnh" + tail, success: failed == 0 && !flag.value)
        notice = "Đã lưu \(ok) ảnh vào ứng dụng Ảnh" + tail
    }
}

/// Photos already saved from the camera, by name and shooting time, so the grid can mark them.
enum Downloaded {
    private static let key = "downloaded"

    private static func id(_ info: ObjectInfo) -> String { info.fileName + "|" + info.captureDate }

    private static var all = Set(UserDefaults.standard.stringArray(forKey: key) ?? [])

    static func contains(_ info: ObjectInfo) -> Bool { all.contains(id(info)) }

    static func add(_ info: ObjectInfo) {
        guard all.insert(id(info)).inserted else { return }
        UserDefaults.standard.set(Array(all), forKey: key)
    }

    /// Every saved photo as (file name, "YYYYMMDDThhmmss").
    static var entries: [(name: String, date: String)] {
        all.compactMap { e in
            let p = e.split(separator: "|", maxSplits: 1).map(String.init)
            return p.count == 2 ? (p[0], p[1]) : nil
        }
    }

    /// The photo named [name] was deleted from the phone: "Chọn ảnh mới" offers it again.
    static func forget(name: String) {
        let before = all.count
        all = all.filter { !$0.hasPrefix(name + "|") }
        if all.count != before { UserDefaults.standard.set(Array(all), forKey: key) }
    }
}
