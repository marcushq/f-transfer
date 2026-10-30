import Foundation
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
    var downloaded = false
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
    @Published var transfer: Transfer?
    @Published var log: [String] = []

    private let cameraQueue = DispatchQueue(label: "camera")
    private var camera: FujiCamera!
    private var ble: FujiBluetooth!
    private var index: [UInt32: Int] = [:]
    private var sessionTask: Task<Void, Never>?
    private var thumbTask: Task<Void, Never>?
    /// Tiles that came on screen, newest last: their thumbnails load first.
    private var wanted: [UInt32] = []
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
    }

    func appendLog(_ line: String) {
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

    private func runConnect(_ mode: ConnectMode) async {
        appendLog("Bắt đầu kết nối (\(mode.rawValue)), iOS \(UIDevice.current.systemVersion)")
        do {
            if mode == .bluetooth {
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
                do {
                    try await CameraWifi.join(ap)
                    appendLog("Đã vào Wi-Fi \"\(ap.ssid)\"")
                } catch {
                    appendLog("Không tự vào được Wi-Fi: \(error.localizedDescription)")
                    throw SessionError(
                        "Không tự vào được Wi-Fi \"\(ap.ssid)\". Hãy vào Cài đặt › Wi-Fi, chọn mạng này" +
                            (ap.password.isEmpty ? "" : " (mật khẩu: \(ap.password))") +
                            ", rồi quay lại app, chọn \"Đã vào Wi-Fi\" và bấm Kết nối. Giữ máy ảnh ở màn hình đang kết nối."
                    )
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
            selected = []
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

    private func fail(_ message: String) {
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
        transfer = nil
        wanted = []
        appendLog("Đã ngắt kết nối")
    }

    // MARK: - Thumbnails

    func requestThumb(_ handle: UInt32) {
        guard let i = index[handle], !photos[i].attempted else { return }
        wanted.removeAll { $0 == handle }
        wanted.append(handle)
        if wanted.count > 60 { wanted.removeFirst(wanted.count - 60) }
    }

    /// Loads details and thumbnails: tiles on screen first, then the rest in order. Pauses while
    /// photos are downloading (the camera does one thing at a time).
    private func startThumbnails() {
        thumbTask?.cancel()
        thumbTask = Task {
            var cursor = 0
            while !Task.isCancelled && phase == .connected {
                if transfer != nil {
                    try? await Task.sleep(nanoseconds: 300_000_000)
                    continue
                }
                var next: UInt32?
                while let h = wanted.popLast() {
                    if let i = index[h], !photos[i].attempted { next = h; break }
                }
                if next == nil {
                    while cursor < photos.count && photos[cursor].attempted { cursor += 1 }
                    if cursor < photos.count { next = photos[cursor].handle }
                }
                guard let h = next else {
                    try? await Task.sleep(nanoseconds: 300_000_000)
                    continue
                }
                do {
                    let (info, thumb, busy): (ObjectInfo?, [UInt8]?, Bool) = try await onCamera { [camera] in
                        let info = try camera!.describe(h)
                        var thumb: [UInt8]?
                        if info != nil { thumb = try camera!.thumbnail(h) }
                        return (info, thumb, camera!.lastWasBusy)
                    }
                    if info == nil && busy {
                        try? await Task.sleep(nanoseconds: 2_000_000_000) // still reading the card
                        continue
                    }
                    guard let i = index[h] else { continue }
                    photos[i].info = info
                    photos[i].thumb = thumb.flatMap { UIImage(data: Data($0)) }.map(trimBlackBars)
                    photos[i].attempted = true
                    if let info { photos[i].downloaded = Downloaded.contains(info) }
                } catch {
                    appendLog("LỖI ảnh thu nhỏ #\(h): \(error.localizedDescription)")
                    if case PtpError.io = error {
                        fail("Mất kết nối với máy ảnh. Hãy kết nối lại.")
                        return
                    }
                    if let i = index[h] { photos[i].attempted = true }
                }
            }
        }
    }

    // MARK: - Selection

    func toggle(_ handle: UInt32) {
        guard transfer == nil else { return }
        if selected.contains(handle) { selected.remove(handle) } else { selected.insert(handle) }
    }

    /// Selects every photo whose details are known and that isn't on this phone yet.
    func selectNew() {
        selected = Set(photos.filter { $0.info != nil && !$0.downloaded }.map(\.handle))
        if selected.isEmpty { notice = "Không có ảnh mới trong các ảnh đã hiện" }
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

    private func runDownload(_ queue: [UInt32]) async {
        guard await PhotoSaver.authorize() else {
            error = "Hãy cho phép Fuji Transfer thêm ảnh vào ứng dụng Ảnh (Cài đặt › Fuji Transfer › Ảnh)."
            return
        }
        // Keep the screen (and with it Wi-Fi) on, and ask iOS for time if the app is left.
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
                    fail("Mất kết nối với máy ảnh. Đã lưu \(ok) ảnh trước khi mất kết nối.")
                    return
                }
            }
        }
        transfer = nil
        notice = "Đã lưu \(ok) ảnh vào ứng dụng Ảnh" + (failed > 0 ? ", \(failed) ảnh lỗi" : "") + (flag.value ? " (đã dừng)" : "")
    }
}

/// Photos already saved from the camera, by name and shooting time, so the grid can mark them.
enum Downloaded {
    private static let key = "downloaded"

    private static func id(_ info: ObjectInfo) -> String { info.fileName + "|" + info.captureDate }

    static func contains(_ info: ObjectInfo) -> Bool {
        (UserDefaults.standard.stringArray(forKey: key) ?? []).contains(id(info))
    }

    static func add(_ info: ObjectInfo) {
        var all = UserDefaults.standard.stringArray(forKey: key) ?? []
        if !all.contains(id(info)) { all.append(id(info)) }
        UserDefaults.standard.set(all, forKey: key)
    }
}
