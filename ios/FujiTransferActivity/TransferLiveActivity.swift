import ActivityKit
import AppIntents
import SwiftUI
import WidgetKit

@main
struct FujiTransferWidgets: WidgetBundle {
    var body: some Widget {
        TransferLiveActivity()
    }
}

/// The app's orange accent and inks (see Film in the app; custom fonts aren't loaded here).
private enum Ink {
    static let accent = Color(red: 1, green: 0x9D / 255, blue: 0x28 / 255)
    static let onAccent = Color(red: 0x14 / 255, green: 0x0C / 255, blue: 0x02 / 255)
}

/// Progress of a download in the Dynamic Island and on the Lock Screen, with a Stop button.
struct TransferLiveActivity: Widget {
    var body: some WidgetConfiguration {
        ActivityConfiguration(for: TransferAttributes.self) { context in
            LockScreenView(attributes: context.attributes, state: context.state)
                .activityBackgroundTint(Color.black.opacity(0.85))
                .activitySystemActionForegroundColor(Ink.accent)
        } dynamicIsland: { context in
            let s = context.state
            return DynamicIsland {
                DynamicIslandExpandedRegion(.leading) {
                    Label {
                        Text(context.attributes.cameraName).lineLimit(1)
                    } icon: {
                        Image(systemName: "camera.fill").foregroundStyle(Ink.accent)
                    }
                    .font(.system(size: 14, weight: .semibold))
                    .padding(.leading, 4)
                }
                DynamicIslandExpandedRegion(.trailing) {
                    Text(countText(s))
                        .font(.system(size: 14, weight: .semibold, design: .monospaced))
                        .foregroundStyle(Ink.accent)
                        .padding(.trailing, 4)
                }
                DynamicIslandExpandedRegion(.bottom) {
                    VStack(alignment: .leading, spacing: 8) {
                        ProgressView(value: s.progress).tint(Ink.accent)
                        HStack(alignment: .center) {
                            Text(detailText(s))
                                .font(.system(size: 13, design: .monospaced))
                                .foregroundStyle(.secondary)
                                .lineLimit(1)
                            Spacer(minLength: 8)
                            if s.summary == nil { StopButton() }
                        }
                    }
                    .padding(.horizontal, 4)
                }
            } compactLeading: {
                ProgressRing(state: s)
            } compactTrailing: {
                Text(countText(s))
                    .font(.system(size: 13, weight: .semibold, design: .monospaced))
                    .foregroundStyle(Ink.accent)
            } minimal: {
                ProgressRing(state: s)
            }
            .keylineTint(Ink.accent)
        }
    }
}

private struct LockScreenView: View {
    let attributes: TransferAttributes
    let state: TransferAttributes.ContentState

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                Image(systemName: state.summary == nil ? "camera.fill" : "checkmark.circle.fill")
                    .foregroundStyle(Ink.accent)
                Text(state.summary ?? "Đang tải từ \(attributes.cameraName)")
                    .font(.system(size: 15, weight: .semibold))
                    .foregroundStyle(.white)
                    .lineLimit(1)
                Spacer()
                Text(countText(state))
                    .font(.system(size: 15, weight: .semibold, design: .monospaced))
                    .foregroundStyle(Ink.accent)
            }
            ProgressView(value: state.progress).tint(Ink.accent)
            HStack {
                Text(detailText(state))
                    .font(.system(size: 13, design: .monospaced))
                    .foregroundStyle(.white.opacity(0.7))
                    .lineLimit(1)
                Spacer(minLength: 8)
                if state.summary == nil { StopButton() }
            }
        }
        .padding(16)
    }
}

/// The whole transfer as an orange ring around the camera (a check once it's over).
private struct ProgressRing: View {
    let state: TransferAttributes.ContentState

    var body: some View {
        ZStack {
            Circle().stroke(Ink.accent.opacity(0.25), lineWidth: 2.5)
            Circle()
                .trim(from: 0, to: state.summary == nil ? state.progress : 1)
                .stroke(Ink.accent, style: StrokeStyle(lineWidth: 2.5, lineCap: .round))
                .rotationEffect(.degrees(-90))
            Image(systemName: state.summary == nil ? "camera.fill" : "checkmark")
                .font(.system(size: 9, weight: .bold))
                .foregroundStyle(Ink.accent)
        }
        .frame(width: 22, height: 22)
    }
}

private struct StopButton: View {
    var body: some View {
        Button(intent: StopTransferIntent()) {
            Text("Dừng")
                .font(.system(size: 13, weight: .semibold))
                .foregroundStyle(Ink.onAccent)
                .padding(.horizontal, 12)
                .padding(.vertical, 5)
                .background(Capsule().fill(Ink.accent))
        }
        .buttonStyle(.plain)
    }
}

private func countText(_ s: TransferAttributes.ContentState) -> String {
    let shown = s.summary == nil ? min(s.done + 1, s.total) : s.done
    return "\(shown)/\(s.total)"
}

private func detailText(_ s: TransferAttributes.ContentState) -> String {
    if let summary = s.summary { return summary }
    if s.fileName.isEmpty { return "Đang chuẩn bị…" }
    let speed = s.bytesPerSecond > 0 ? String(format: " · %.1f MB/s", s.bytesPerSecond / 1_048_576) : ""
    let failed = s.failed > 0 ? " · \(s.failed) lỗi" : ""
    return s.fileName + speed + failed
}
