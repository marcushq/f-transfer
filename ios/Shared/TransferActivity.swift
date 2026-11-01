import ActivityKit
import AppIntents
import Foundation

/// A download shown in the Dynamic Island and on the Lock Screen. Built into both the app and
/// the FujiTransferActivity extension.
struct TransferAttributes: ActivityAttributes {
    struct ContentState: Codable, Hashable {
        /// Files finished so far (saved or failed).
        var done: Int
        var total: Int
        var fileName: String
        /// Whole transfer, 0…1.
        var progress: Double
        var bytesPerSecond: Double
        var failed: Int
        /// Set on the last update: what to show once the transfer has ended.
        var summary: String?
    }

    var cameraName: String
}

/// The Stop button in the Live Activity. Runs in the app's process, which stops the transfer.
struct StopTransferIntent: LiveActivityIntent {
    static var title: LocalizedStringResource = "Dừng tải ảnh"

    init() {}

    func perform() async throws -> some IntentResult {
        NotificationCenter.default.post(name: .stopTransfer, object: nil)
        return .result()
    }
}

extension Notification.Name {
    static let stopTransfer = Notification.Name("dev.fujitransfer.stopTransfer")
}
