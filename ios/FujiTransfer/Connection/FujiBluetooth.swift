import CoreBluetooth
import Foundation

/// The camera's access point, as read over Bluetooth.
struct CameraAp: Codable {
    let ssid: String
    let password: String
}

struct BleError: LocalizedError {
    let message: String
    init(_ message: String) { self.message = message }
    var errorDescription: String? { message }
}

/// Wakes the Wi-Fi of newer bodies (X-S20, X-T5, X-H2, X100VI…), which only start their access
/// point when a paired phone asks over Bluetooth LE. Photos never travel over Bluetooth.
/// The same steps as the Android app's `FujiBluetooth.kt` (after gkoh/furble, petabyt/libfuji
/// and Th0rgal/fuji-bridge).
///
/// CoreBluetooth answers through delegate callbacks on [queue]; each step below starts one
/// request and waits for its callback (or its timeout) — one request at a time.
final class FujiBluetooth: NSObject {
    static let companyId: UInt16 = 0x04D8
    static let clientName = "Fuji Transfer"

    static let secureStatus = CBUUID(string: "f557d96b-8284-4667-8793-b971c1deca2a")
    static let basicToken = CBUUID(string: "aba356eb-9633-4e60-b73f-f52516dbd671")
    static let identity = CBUUID(string: "85b9163e-62d1-49ff-a6f5-054b4630d4a1")
    static let indication1 = CBUUID(string: "a68e3f66-0fcc-4395-8d4c-aa980b5877fa")
    static let ssid = CBUUID(string: "bf6dc9cf-3606-4ec9-a4c8-d77576e93ea4")
    static let password = CBUUID(string: "e809256a-915c-4967-92e8-53b7d4cad213")
    /// FUNCTION_LAUNCH_REQUEST: 3 = in-camera viewing / image import.
    static let wifiWake = CBUUID(string: "600655e6-3637-42f1-8fb2-44efc5c63b13")
    static let importFunction = Data([0x03, 0x00])

    /// What XApp listens to; a body may wait for its client to subscribe before serving Wi-Fi.
    static let subscriptions: [CBUUID] = [
        indication1,
        CBUUID(string: "bd17ba04-b76b-4892-a545-b73ba1f74dae"),
        CBUUID(string: "f9150137-5d40-4801-a8dc-f7fc5b01da50"),
        CBUUID(string: "ad06c7b7-f41a-46f4-a29a-712055319122"),
        CBUUID(string: "7170fd5a-56d9-4c19-b043-7a7047d8e1a0"),
        ssid,
        CBUUID(string: "75823784-fbb7-4b71-abae-cd9a34072e3c"),
        CBUUID(string: "e6692c5c-b7cd-44f4-95fc-eda07ce32560"),
        CBUUID(string: "aab609c4-94dd-4d89-bc60-665d5090b828"),
        CBUUID(string: "2a125640-706d-4dd1-b420-c0f4ab93c361"),
        CBUUID(string: "82a9f452-c5ce-4ef5-8203-3fc9a47f8171"),
        CBUUID(string: "deef7187-3f43-4364-9e22-11a8c8a15951"),
        CBUUID(string: "c95d91ae-b247-4d6d-8661-7dd5d6a0f85b"),
    ]

    private enum Op {
        case power, connect, discover, write, notify
        case read(CBUUID)
    }

    private let log: (String) -> Void
    private let queue = DispatchQueue(label: "ble")
    private var central: CBCentralManager!
    private var peripheral: CBPeripheral?
    private var characteristics: [CBUUID: CBCharacteristic] = [:]
    private var pendingServices = 0

    /// The request waiting for its callback. Touched only on [queue].
    private var pending: (op: Op, id: Int, c: CheckedContinuation<Data, Error>)?
    private var opId = 0
    /// Scanning: called for every Fujifilm advert.
    private var found: ((CBPeripheral, Data?) -> Void)?
    /// Answers on INDICATION1 (access point state), oldest first.
    private var wifiAnswers: [Data] = []

    private static let rememberedKey = "ble.peripheral"

    init(log: @escaping (String) -> Void) {
        self.log = log
        super.init()
        central = CBCentralManager(delegate: self, queue: queue)
    }

    /// Finds the camera, says hello, asks it to start Wi-Fi and returns the network to join.
    func wakeWifi(firstScan: TimeInterval = 25, onPairing: @escaping () -> Void) async throws -> CameraAp {
        try await poweredOn()
        var lastError: Error = BleError("Không tìm thấy máy ảnh Fujifilm qua Bluetooth.")
        // A body drops the link right after a new pairing is confirmed, then waits for the bonded phone.
        for attempt in 0..<3 {
            let (p, token) = try await scan(timeout: attempt == 0 ? firstScan : 15)
            log("BLE: found \(p.name ?? p.identifier.uuidString) (attempt \(attempt + 1))")
            do {
                try await connect(p)
                let ap = try await handshake(token: token, onPairing: onPairing)
                UserDefaults.standard.set(p.identifier.uuidString, forKey: Self.rememberedKey)
                return ap
            } catch {
                lastError = error
                log("BLE: \(error.localizedDescription)")
                close()
                if attempt < 2 { try await Task.sleep(nanoseconds: 2_000_000_000) }
            }
        }
        throw lastError
    }

    /// Keep the link until the Wi-Fi session answers; then let it go.
    func close() {
        queue.async {
            if let p = self.peripheral { self.central.cancelPeripheralConnection(p) }
            self.peripheral = nil
            self.characteristics = [:]
        }
    }

    // MARK: - One request at a time

    /// Starts a request on [queue] and waits for its callback, or fails with [error] after [timeout].
    @discardableResult
    private func run(_ op: Op, timeout: TimeInterval, _ error: Error, start: @escaping () -> Void) async throws -> Data {
        try await withCheckedThrowingContinuation { c in
            queue.async {
                self.opId += 1
                let id = self.opId
                self.pending = (op, id, c)
                start()
                self.queue.asyncAfter(deadline: .now() + timeout) {
                    if let p = self.pending, p.id == id {
                        self.pending = nil
                        p.c.resume(throwing: error)
                    }
                }
            }
        }
    }

    /// Completes the pending request if [matches] says it is the one this callback answers.
    private func complete(_ matches: (Op) -> Bool, _ result: Result<Data, Error>) {
        guard let p = pending, matches(p.op) else { return }
        pending = nil
        p.c.resume(with: result)
    }

    private func characteristic(_ uuid: CBUUID) -> CBCharacteristic? { queue.sync { characteristics[uuid] } }

    // MARK: - Steps

    private func poweredOn() async throws {
        let state = queue.sync { central.state }
        if state == .unsupported { throw BleError("Điện thoại này không có Bluetooth.") }
        if state == .unauthorized { throw BleError("Hãy cho phép Fuji Transfer dùng Bluetooth trong Cài đặt.") }
        if state == .poweredOn { return }
        try await run(.power, timeout: 5, BleError("Bluetooth đang tắt. Hãy bật Bluetooth rồi thử lại.")) {
            if self.central.state == .poweredOn { self.complete({ if case .power = $0 { return true }; return false }, .success(Data())) }
        }
    }

    private func scan(timeout: TimeInterval) async throws -> (CBPeripheral, Data?) {
        let remembered = UserDefaults.standard.string(forKey: Self.rememberedKey)
        log("BLE: scanning for Fujifilm cameras…")
        let result: (CBPeripheral, Data?)? = await withCheckedContinuation { c in
            queue.async {
                let started = Date()
                var done = false
                var firstSeen: (CBPeripheral, Data?)?
                func finish(_ r: (CBPeripheral, Data?)?) {
                    guard !done else { return }
                    done = true
                    self.found = nil
                    self.central.stopScan()
                    c.resume(returning: r)
                }
                self.found = { p, token in
                    if firstSeen == nil { firstSeen = (p, token) }
                    // Prefer the body we paired with last time, but take any Fujifilm after a moment.
                    if remembered == nil || p.identifier.uuidString == remembered || token != nil
                        || Date().timeIntervalSince(started) > 6 {
                        finish((p, token))
                    }
                }
                self.central.scanForPeripherals(withServices: nil, options: [CBCentralManagerScanOptionAllowDuplicatesKey: false])
                self.queue.asyncAfter(deadline: .now() + 6) { if let f = firstSeen { finish(f) } }
                self.queue.asyncAfter(deadline: .now() + timeout) { finish(nil) }
            }
        }
        guard let result else {
            throw BleError("Không tìm thấy máy ảnh Fujifilm qua Bluetooth. Máy ảnh đã bật và đã bật Bluetooth trong cài đặt chưa?")
        }
        return result
    }

    private func connect(_ p: CBPeripheral) async throws {
        try await run(.connect, timeout: 15, BleError("Không kết nối được qua Bluetooth. Nếu máy ảnh đang ghép đôi với điện thoại khác, hãy ghép đôi lại (máy ảnh: ĐĂNG KÝ GHÉP ĐÔI).")) {
            self.peripheral = p
            p.delegate = self
            self.central.connect(p)
        }
        try await Task.sleep(nanoseconds: 300_000_000)
        try await run(.discover, timeout: 15, BleError("Không đọc được dịch vụ Bluetooth của máy ảnh.")) {
            self.characteristics = [:]
            p.discoverServices(nil)
        }
        log("BLE: \(queue.sync { characteristics.count }) characteristics")
    }

    private func read(_ uuid: CBUUID, timeout: TimeInterval = 15) async throws -> Data {
        guard let ch = characteristic(uuid) else { throw BleError("Máy ảnh không có đặc tính Bluetooth \(uuid)") }
        return try await run(.read(uuid), timeout: timeout, BleError("Máy ảnh không trả lời qua Bluetooth.")) {
            self.peripheral?.readValue(for: ch)
        }
    }

    private func write(_ uuid: CBUUID, _ value: Data) async throws {
        guard let ch = characteristic(uuid) else { throw BleError("Máy ảnh không có đặc tính Bluetooth \(uuid)") }
        try await run(.write, timeout: 15, BleError("Máy ảnh không trả lời qua Bluetooth.")) {
            self.peripheral?.writeValue(value, for: ch, type: .withResponse)
        }
    }

    private func subscribe(_ uuid: CBUUID) async {
        guard let ch = characteristic(uuid), ch.properties.contains(.notify) || ch.properties.contains(.indicate) else { return }
        _ = try? await run(.notify, timeout: 5, BleError("subscribe")) {
            self.peripheral?.setNotifyValue(true, for: ch)
        }
    }

    private func handshake(token: Data?, onPairing: @escaping () -> Void) async throws -> CameraAp {
        if characteristic(Self.secureStatus) != nil {
            // Secure bodies: the first encrypted read makes iOS show its pairing dialog; iOS
            // repeats the read itself once the bond is made, so give it time.
            let note = Task {
                try await Task.sleep(nanoseconds: 2_500_000_000)
                self.log("BLE: pairing needed — confirm on the phone and the camera")
                onPairing()
            }
            var status: Data?
            for _ in 0..<20 {
                do {
                    status = try await read(Self.secureStatus, timeout: 60)
                    break
                } catch let e as CBATTError where e.code == .insufficientAuthentication || e.code == .insufficientEncryption {
                    try await Task.sleep(nanoseconds: 2_000_000_000)
                }
            }
            note.cancel()
            guard var s = status else { throw BleError("Ghép đôi chưa hoàn tất.") }
            log("BLE: secure status \(hex(s))")
            if s.count == 4 { s[s.startIndex + 3] = 0x20 }
            try await write(Self.secureStatus, s)
        } else if let token {
            try await write(Self.basicToken, token)
            log("BLE: pairing token sent")
        }
        try await write(Self.identity, Data(Self.clientName.utf8))
        log("BLE: identified as \"\(Self.clientName)\"")

        for uuid in Self.subscriptions { await subscribe(uuid) }

        let ssid = text(try await read(Self.ssid))
        log("BLE: camera Wi-Fi \"\(ssid)\"")
        queue.sync { wifiAnswers.removeAll() } // answers from before the wake

        try await write(Self.wifiWake, Self.importFunction)
        log("BLE: asked the camera to start Wi-Fi (03 00)")
        let password = (try? text(await read(Self.password))) ?? ""
        log("BLE: password \(password.isEmpty ? "none" : "\(password.count) characters")")

        // AP_STATE: 01 = access point up, 00 = not (yet). An X-S20 answers 00 within milliseconds
        // and 01 about 7 s later, so 00 means "keep waiting". Ask again every 5 s.
        let deadline = Date().addingTimeInterval(25)
        var lastAsk = Date()
        var sawAnswer = false
        waiting: while true {
            if Date() > deadline {
                if sawAnswer { throw BleError("Máy ảnh báo đang bận và không bật Wi-Fi. Hãy thoát menu/chế độ xem ảnh trên máy ảnh rồi thử lại.") }
                log("BLE: no Wi-Fi answer in 25 s, trying anyway")
                break
            }
            let answers: [Data] = queue.sync {
                let a = wifiAnswers
                wifiAnswers.removeAll()
                return a
            }
            for a in answers {
                sawAnswer = true
                log("BLE: Wi-Fi answer \(hex(a))")
                if let first = a.first, first != 0 { break waiting }
            }
            if Date().timeIntervalSince(lastAsk) >= 5 {
                lastAsk = Date()
                log("BLE: Wi-Fi not up yet, asking again")
                try await write(Self.wifiWake, Self.importFunction)
            }
            try await Task.sleep(nanoseconds: 200_000_000)
        }
        guard !ssid.isEmpty else { throw BleError("Máy ảnh không gửi tên Wi-Fi.") }
        return CameraAp(ssid: ssid, password: password)
    }

    private func text(_ d: Data) -> String {
        String(decoding: d.prefix(while: { $0 != 0 }), as: UTF8.self).trimmingCharacters(in: .whitespaces)
    }

    private func hex(_ d: Data) -> String { d.map { String(format: "%02x", $0) }.joined(separator: " ") }
}

extension FujiBluetooth: CBCentralManagerDelegate, CBPeripheralDelegate {
    func centralManagerDidUpdateState(_ central: CBCentralManager) {
        if central.state == .poweredOn { complete({ if case .power = $0 { return true }; return false }, .success(Data())) }
    }

    func centralManager(_ central: CBCentralManager, didDiscover p: CBPeripheral, advertisementData: [String: Any], rssi: NSNumber) {
        // Manufacturer data: company id (LE), type byte, then (type 0x02) the 4-byte pairing token.
        guard let data = advertisementData[CBAdvertisementDataManufacturerDataKey] as? Data, data.count >= 2 else { return }
        let bytes = [UInt8](data)
        guard UInt16(bytes[0]) | UInt16(bytes[1]) << 8 == Self.companyId else { return }
        let token: Data? = bytes.count >= 7 && bytes[2] == 0x02 ? Data(bytes[3..<7]) : nil
        log("BLE: advert \(p.name ?? "?") rssi \(rssi) data \(hex(data))")
        found?(p, token)
    }

    func centralManager(_ central: CBCentralManager, didConnect p: CBPeripheral) {
        complete({ if case .connect = $0 { return true }; return false }, .success(Data()))
    }

    func centralManager(_ central: CBCentralManager, didFailToConnect p: CBPeripheral, error: Error?) {
        complete({ if case .connect = $0 { return true }; return false },
                 .failure(error ?? BleError("Không kết nối được qua Bluetooth.")))
    }

    func centralManager(_ central: CBCentralManager, didDisconnectPeripheral p: CBPeripheral, error: Error?) {
        log("BLE: disconnected")
        // Whatever was waiting will never be answered now.
        complete({ _ in true }, .failure(BleError("Máy ảnh ngắt Bluetooth.")))
    }

    func peripheral(_ p: CBPeripheral, didDiscoverServices error: Error?) {
        let services = p.services ?? []
        pendingServices = services.count
        if services.isEmpty { complete({ if case .discover = $0 { return true }; return false }, .success(Data())) }
        services.forEach { p.discoverCharacteristics(nil, for: $0) }
    }

    func peripheral(_ p: CBPeripheral, didDiscoverCharacteristicsFor service: CBService, error: Error?) {
        service.characteristics?.forEach { characteristics[$0.uuid] = $0 }
        pendingServices -= 1
        if pendingServices <= 0 { complete({ if case .discover = $0 { return true }; return false }, .success(Data())) }
    }

    func peripheral(_ p: CBPeripheral, didUpdateValueFor ch: CBCharacteristic, error: Error?) {
        let isRead: (Op) -> Bool = { if case .read(let u) = $0 { return u == ch.uuid }; return false }
        if let pending, isRead(pending.op) {
            complete(isRead, error == nil ? .success(ch.value ?? Data()) : .failure(error!))
            return
        }
        // Not a read we asked for: a notification.
        if ch.uuid == Self.indication1, let v = ch.value { wifiAnswers.append(v) }
    }

    func peripheral(_ p: CBPeripheral, didWriteValueFor ch: CBCharacteristic, error: Error?) {
        complete({ if case .write = $0 { return true }; return false }, error == nil ? .success(Data()) : .failure(error!))
    }

    func peripheral(_ p: CBPeripheral, didUpdateNotificationStateFor ch: CBCharacteristic, error: Error?) {
        complete({ if case .notify = $0 { return true }; return false }, .success(Data()))
    }
}
