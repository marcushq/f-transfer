import ActivityKit
import BackgroundTasks
import Foundation

/// Keeps a download running after the app leaves the screen and shows its progress in the
/// Dynamic Island and on the Lock Screen.
///
/// - iOS 26+: a BGContinuedProcessingTask lets the user-started transfer finish in the
///   background (iOS shows its own progress and may still stop it under pressure).
/// - Otherwise (older iOS, or the request is refused) a Live Activity shows the progress with a
///   Stop button.
@MainActor
final class TransferProgress {
    static let shared = TransferProgress()
    nonisolated static let taskId = (Bundle.main.bundleIdentifier ?? "dev.fujitransfer.ios") + ".transfer"

    /// Called when iOS ends the background time or the user stops it from the system UI.
    var onExpire: (() -> Void)?
    var log: ((String) -> Void)?

    private var activity: Activity<TransferAttributes>?
    /// The BGContinuedProcessingTask while one runs (typed AnyObject for the iOS 17 build).
    private var task: AnyObject?
    private var state: TransferAttributes.ContentState?
    private var lastPush = Date.distantPast
    private var title = ""

    /// Registers the background task handler; must run before the app finishes launching.
    nonisolated static func register() {
        guard #available(iOS 26, *) else { return }
        BGTaskScheduler.shared.register(forTaskWithIdentifier: taskId, using: nil) { task in
            guard let task = task as? BGContinuedProcessingTask else { return }
            task.progress.totalUnitCount = 1000
            task.expirationHandler = {
                Task { @MainActor in
                    shared.log?("iOS dừng thời gian chạy nền")
                    shared.onExpire?()
                }
            }
            Task { @MainActor in shared.attach(task) }
        }
    }

    func start(cameraName: String, total: Int) {
        title = "Tải \(total) ảnh từ \(cameraName)"
        let first = TransferAttributes.ContentState(done: 0, total: total, fileName: "", progress: 0, bytesPerSecond: 0, failed: 0)
        state = first
        lastPush = .distantPast

        // iOS shows its own progress for a continued-processing task; a Live Activity on top
        // would show the same transfer twice, so it is only the fallback.
        if #available(iOS 26, *) {
            let request = BGContinuedProcessingTaskRequest(identifier: Self.taskId, title: title, subtitle: "Đang chuẩn bị…")
            request.strategy = .fail // start now or not at all: the user is waiting for it
            do {
                try BGTaskScheduler.shared.submit(request)
                return
            } catch {
                log?("Không xin được chạy nền: \(error.localizedDescription)")
            }
        }

        if ActivityAuthorizationInfo().areActivitiesEnabled {
            do {
                activity = try Activity.request(
                    attributes: TransferAttributes(cameraName: cameraName),
                    content: ActivityContent(state: first, staleDate: nil)
                )
            } catch {
                log?("Không mở được Live Activity: \(error.localizedDescription)")
            }
        }
    }

    func update(_ t: Transfer) {
        guard var s = state else { return }
        let newFile = t.index != s.done || t.fileName != s.fileName
        s.done = t.index
        s.total = t.total
        s.fileName = t.fileName
        s.progress = t.overall
        s.bytesPerSecond = t.bytesPerSecond
        s.failed = t.failed
        state = s

        if #available(iOS 26, *), let task = task as? BGContinuedProcessingTask {
            task.progress.completedUnitCount = Int64(t.overall * 1000)
        }
        // iOS throttles Live Activity updates; once a second is plenty, plus each new file.
        guard newFile || Date().timeIntervalSince(lastPush) >= 1 else { return }
        lastPush = Date()
        if #available(iOS 26, *), let task = task as? BGContinuedProcessingTask {
            task.updateTitle(title, subtitle: "Ảnh \(t.index + 1)/\(t.total) · \(t.fileName)")
        }
        let content = ActivityContent(state: s, staleDate: nil)
        if let activity { Task { await activity.update(content) } }
    }

    func finish(summary: String, success: Bool) {
        if #available(iOS 26, *), let task = task as? BGContinuedProcessingTask {
            task.progress.completedUnitCount = task.progress.totalUnitCount
            task.setTaskCompleted(success: success)
        }
        task = nil
        if let activity, var s = state {
            s.summary = summary
            if success { s.progress = 1 }
            let content = ActivityContent(state: s, staleDate: nil)
            Task { await activity.end(content, dismissalPolicy: .after(Date().addingTimeInterval(300))) }
        }
        activity = nil
        state = nil
    }

    @available(iOS 26, *)
    private func attach(_ task: BGContinuedProcessingTask) {
        // The transfer may already be over by the time iOS hands the task over.
        guard state != nil else {
            task.setTaskCompleted(success: true)
            return
        }
        self.task = task
        if let s = state { task.progress.completedUnitCount = Int64(s.progress * 1000) }
        log?("Đã được phép chạy nền")
    }
}
