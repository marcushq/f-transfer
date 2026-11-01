import SwiftUI

/// The connection log, for bug reports: newest at the bottom, shareable as text.
struct LogView: View {
    @EnvironmentObject var session: Session
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            ScrollViewReader { proxy in
                ScrollView {
                    LazyVStack(alignment: .leading, spacing: 1) {
                        if session.log.isEmpty {
                            Text("Chưa có gì.").font(Film.sans(14)).foregroundStyle(Film.inkSoft)
                        }
                        ForEach(Array(session.log.enumerated()), id: \.offset) { i, line in
                            Text(line)
                                .font(.system(size: 11, design: .monospaced))
                                .foregroundStyle(Film.ink)
                                .textSelection(.enabled)
                                .id(i)
                        }
                    }
                    .padding(16)
                }
                .onAppear { proxy.scrollTo(session.log.count - 1, anchor: .bottom) }
            }
            .background(Film.paper)
            .navigationTitle("Nhật ký kết nối")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Đóng") { dismiss() } }
                ToolbarItem(placement: .primaryAction) {
                    ShareLink(item: "Fuji Transfer iOS\n\n" + session.log.joined(separator: "\n")) {
                        Text("Chia sẻ")
                    }
                }
            }
        }
    }
}
