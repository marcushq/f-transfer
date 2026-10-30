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
