import Foundation
import NetworkExtension

/// Joins the camera's access point with NEHotspotConfiguration (iOS asks the user once).
///
/// This needs the "Hotspot Configuration" capability, which only paid Apple Developer
/// accounts can sign. Without it [join] fails and the app asks the user to pick the camera's
/// network in Settings › Wi-Fi instead — the transfer itself works the same either way.
enum CameraWifi {
    private static let lastApKey = "wifi.lastAp"

    /// The network the camera gave over Bluetooth last time; joined directly when the body's
    /// Wi-Fi is already up and it no longer advertises over Bluetooth.
    static var lastAp: CameraAp? {
        get {
            guard let d = UserDefaults.standard.data(forKey: lastApKey) else { return nil }
            return try? JSONDecoder().decode(CameraAp.self, from: d)
        }
        set {
            UserDefaults.standard.set(newValue.flatMap { try? JSONEncoder().encode($0) }, forKey: lastApKey)
        }
    }

    /// True while the phone holds an address on the camera's network (192.168.0.x beside the
    /// camera's 192.168.0.1). Needs no permission, unlike reading the current SSID.
    static var onCameraNetwork: Bool {
        var head: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&head) == 0, let first = head else { return false }
        defer { freeifaddrs(head) }
        for p in sequence(first: first, next: { $0.pointee.ifa_next }) {
            guard let sa = p.pointee.ifa_addr, sa.pointee.sa_family == UInt8(AF_INET),
                  String(cString: p.pointee.ifa_name).hasPrefix("en") else { continue }
            let addr = sa.withMemoryRebound(to: sockaddr_in.self, capacity: 1) { $0.pointee.sin_addr.s_addr }
            let b = withUnsafeBytes(of: addr) { Array($0) } // network byte order
            if b[0] == 192 && b[1] == 168 && b[2] == 0 && b[3] != 1 { return true }
        }
        return false
    }

    static func join(_ ap: CameraAp) async throws {
        let config = ap.password.isEmpty
            ? NEHotspotConfiguration(ssid: ap.ssid)
            : NEHotspotConfiguration(ssid: ap.ssid, passphrase: ap.password, isWEP: false)
        // Keep it joined after the app goes to the background mid-transfer.
        config.joinOnce = false
        do {
            try await NEHotspotConfigurationManager.shared.apply(config)
        } catch let e as NSError where e.domain == NEHotspotConfigurationErrorDomain
            && e.code == NEHotspotConfigurationError.alreadyAssociated.rawValue {
            // Already on it: fine.
        }
    }
}
