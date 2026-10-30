import SwiftUI

/// The camera's photos, newest first: tap to select, then download.
struct GalleryView: View {
    @EnvironmentObject var session: Session
    @Binding var showLog: Bool

    private let columns = Array(repeating: GridItem(.flexible(), spacing: 2), count: 3)

    var body: some View {
        ZStack(alignment: .bottom) {
            ScrollView {
                VStack(alignment: .leading, spacing: 14) {
                    header.padding(.horizontal, 20)
                    if let notice = session.notice {
                        Text(notice)
                            .font(Film.sans(14))
                            .foregroundStyle(Film.ink)
                            .padding(12)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .background(RoundedRectangle(cornerRadius: 14).fill(Film.card))
                            .padding(.horizontal, 20)
                            .onTapGesture { session.notice = nil }
                    }
                    LazyVGrid(columns: columns, spacing: 2) {
                        ForEach(session.photos) { photo in
                            Tile(photo: photo, selected: session.selected.contains(photo.handle))
                                .onAppear { session.requestThumb(photo.handle) }
                                .onTapGesture { session.toggle(photo.handle) }
                        }
                    }
                    Spacer(minLength: 110)
                }
                .padding(.top, 14)
            }
            bottomBar.padding(.horizontal, 20).padding(.bottom, 12)
        }
        .background(Film.paper.ignoresSafeArea())
    }

    private var header: some View {
        HStack(alignment: .top) {
            VStack(alignment: .leading, spacing: 6) {
                Text(session.cameraName)
                    .font(Film.sans(30, .bold))
                    .tracking(-0.6)
                    .foregroundStyle(Film.ink)
                HStack(spacing: 6) {
                    Circle().fill(Film.accent).frame(width: 7, height: 7)
                    Text(session.selected.isEmpty ? "ĐÃ KẾT NỐI · \(session.photos.count) ẢNH" : "ĐÃ CHỌN \(session.selected.count) ẢNH")
                        .font(Film.mono(11))
                        .tracking(0.6)
                        .foregroundStyle(Film.inkSoft)
                }
            }
            Spacer()
            if !session.selected.isEmpty && session.transfer == nil {
                Button("Bỏ chọn") { session.clearSelection() }
                    .font(Film.sans(15, .semibold))
                    .foregroundStyle(Film.accentText)
                    .padding(.top, 10)
            }
            Menu {
                Button("Chọn ảnh mới (chưa tải)") { session.selectNew() }
                Button("Nhật ký kết nối") { showLog = true }
                Button("Ngắt kết nối", role: .destructive) { session.disconnect() }
            } label: {
                Image(systemName: "ellipsis")
                    .font(.system(size: 18, weight: .semibold))
                    .foregroundStyle(Film.ink)
                    .frame(width: 44, height: 44)
                    .background(Circle().fill(Film.card))
                    .overlay(Circle().stroke(Film.hairline, lineWidth: 1))
            }
        }
    }

    @ViewBuilder
    private var bottomBar: some View {
        if let t = session.transfer {
            VStack(alignment: .leading, spacing: 8) {
                HStack(alignment: .center) {
                    VStack(alignment: .leading, spacing: 3) {
                        Text("ĐANG TẢI \(t.index + 1)/\(t.total)")
                            .font(Film.mono(11)).tracking(0.6).foregroundStyle(Film.accentText)
                        Text(t.fileName + (t.bytesPerSecond > 0 ? String(format: " · %.1f MB/s", t.bytesPerSecond / 1_048_576) : ""))
                            .font(Film.sans(16, .semibold)).foregroundStyle(Film.ink).lineLimit(1)
                    }
                    Spacer()
                    Button("Dừng") { session.stopTransfer() }
                        .font(Film.sans(15, .semibold))
                        .foregroundStyle(Film.ink)
                        .padding(.horizontal, 18)
                        .frame(height: 40)
                        .overlay(Capsule().stroke(Film.hairline, lineWidth: 1))
                }
                ProgressView(value: t.overall).tint(Film.accent)
            }
            .padding(16)
            .background(RoundedRectangle(cornerRadius: 24).fill(Film.card).shadow(color: .black.opacity(0.15), radius: 12, y: 4))
        } else if !session.selected.isEmpty {
            Button { session.downloadSelected() } label: {
                HStack {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("Tải \(session.selected.count) ảnh về máy").font(Film.sans(18, .bold))
                        Text("ẢNH GỐC").font(Film.mono(11)).tracking(0.6)
                    }
                    Spacer()
                    Image(systemName: "arrow.down.to.line")
                        .font(.system(size: 18, weight: .bold))
                        .foregroundStyle(Film.accent)
                        .frame(width: 44, height: 44)
                        .background(Circle().fill(Film.onAccent))
                }
                .foregroundStyle(Film.onAccent)
                .padding(.leading, 22)
                .padding(.trailing, 8)
                .frame(height: 64)
                .background(Capsule().fill(Film.accent))
            }
            .buttonStyle(.plain)
        }
    }
}

/// One square photo: the camera's thumbnail, a ring and tick when selected, and badges.
private struct Tile: View {
    let photo: Photo
    let selected: Bool

    var body: some View {
        Film.frameEmpty
            .aspectRatio(1, contentMode: .fit)
            .overlay {
                if let thumb = photo.thumb {
                    Image(uiImage: thumb).resizable().scaledToFill()
                }
            }
            .clipped()
            .overlay(alignment: .bottomLeading) {
                if let ext = photo.info?.fileName.split(separator: ".").last?.uppercased(), ext != "JPG" {
                    badge(String(ext))
                }
            }
            .overlay(alignment: .bottomTrailing) {
                if photo.downloaded { badge("ĐÃ LƯU") }
            }
            .overlay {
                if selected {
                    Rectangle().stroke(Film.accent, lineWidth: 3)
                }
            }
            .overlay(alignment: .topTrailing) {
                if selected {
                    Image(systemName: "checkmark")
                        .font(.system(size: 12, weight: .bold))
                        .foregroundStyle(Film.onAccent)
                        .frame(width: 24, height: 24)
                        .background(Circle().fill(Film.accent))
                        .padding(6)
                }
            }
            .contentShape(Rectangle())
    }

    private func badge(_ text: String) -> some View {
        Text(text)
            .font(Film.mono(10))
            .foregroundStyle(Color(hex: 0xF2EFEA))
            .padding(.horizontal, 6)
            .padding(.vertical, 2)
            .background(RoundedRectangle(cornerRadius: 6).fill(Color.black.opacity(0.72)))
            .padding(6)
    }
}
