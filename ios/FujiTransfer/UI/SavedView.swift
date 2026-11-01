import Photos
import SwiftUI

private let monthKey: DateFormatter = {
    let f = DateFormatter()
    f.dateFormat = "yyyyMM"
    return f
}()

private let dayKey: DateFormatter = {
    let f = DateFormatter()
    f.dateFormat = "yyyyMMdd"
    return f
}()

/// "Tháng 10, 2026".
private func monthTitle(_ d: Date) -> String {
    let c = Calendar.current.dateComponents([.month, .year], from: d)
    return "Tháng \(c.month ?? 0), \(c.year ?? 0)"
}

/// "Hôm nay", "Hôm qua", else "27 Th10".
private func dayName(_ d: Date) -> String {
    let cal = Calendar.current
    if cal.isDateInToday(d) { return "Hôm nay" }
    if cal.isDateInYesterday(d) { return "Hôm qua" }
    let c = cal.dateComponents([.day, .month], from: d)
    return "\(c.day ?? 0) Th\(c.month ?? 0)"
}

/// One month of [SavedLibrary.shown], each photo with its index there (for the viewer).
private struct Month: Identifiable {
    let id: String
    let photos: [(index: Int, photo: SavedPhoto)]
}

/// The "Đã lưu" tab: photos already on the phone, newest first, without a camera.
struct SavedView: View {
    @EnvironmentObject var library: SavedLibrary
    @State private var viewing: Int?

    private let columns = Array(repeating: GridItem(.flexible(), spacing: 2), count: 4)

    var body: some View {
        let shown = library.shown
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                header.padding(.horizontal, 20).padding(.top, 14).padding(.bottom, 12)
                let kinds = library.kinds
                if kinds.count > 2 {
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(spacing: 8) {
                            ForEach(kinds, id: \.self) { k in
                                Pill(label: k, selected: library.filter == k) { library.filter = k }
                            }
                        }
                        .padding(.horizontal, 20)
                    }
                    .padding(.bottom, 14)
                }
                if library.access == .denied || library.access == .restricted {
                    note("Chưa được xem thư viện ảnh",
                         "Hãy cho phép Fuji Transfer xem ảnh trong Cài đặt › Fuji Transfer › Ảnh.",
                         action: "Mở Cài đặt", openSettings)
                } else if !library.loading && shown.isEmpty {
                    note("Chưa có ảnh nào", "Ảnh tải từ máy ảnh sẽ hiện ở đây.")
                } else {
                    if library.access == .limited { limitedBanner }
                    let latest = latestImport(shown)
                    if !latest.isEmpty { LatestCard(latest: latest) { viewing = $0 } }
                    LazyVGrid(columns: columns, spacing: 2) {
                        ForEach(months(shown)) { m in
                            Section {
                                ForEach(m.photos, id: \.photo.id) { item in
                                    SavedTile(photo: item.photo)
                                        .aspectRatio(1, contentMode: .fit)
                                        .onTapGesture { viewing = item.index }
                                }
                            } header: {
                                HStack(alignment: .lastTextBaseline) {
                                    Text(monthTitle(m.photos[0].photo.taken))
                                        .font(Film.sans(17, .semibold)).foregroundStyle(Film.ink)
                                    Spacer()
                                    Text("\(m.photos.count) ẢNH")
                                        .font(Film.mono(11)).tracking(0.6).foregroundStyle(Film.inkSoft)
                                }
                                .padding(.horizontal, 20)
                                .padding(.top, 22)
                                .padding(.bottom, 10)
                            }
                        }
                    }
                }
            }
            .padding(.bottom, 16)
        }
        .refreshable { await library.reload() }
        .background(Film.paper.ignoresSafeArea())
        .task { await library.open() }
        .fullScreenCover(isPresented: Binding(get: { viewing != nil }, set: { if !$0 { viewing = nil } })) {
            if let viewing {
                PhotoViewer(photos: shown, start: viewing).environmentObject(library)
            }
        }
    }

    private var header: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text("Đã lưu")
                .font(Film.sans(30, .bold))
                .tracking(-0.6)
                .foregroundStyle(Film.ink)
            let bytes = Double(library.photos.reduce(0) { $0 + $1.bytes })
            let size = bytes >= 1_073_741_824 ? String(format: "%.1f GB", bytes / 1_073_741_824) : String(format: "%.0f MB", bytes / 1_048_576)
            Text("\(library.photos.count) ẢNH · " + size.replacingOccurrences(of: ".", with: ",") + " TRÊN ĐIỆN THOẠI")
                .font(Film.mono(11))
                .tracking(0.6)
                .foregroundStyle(Film.inkSoft)
        }
    }

    private var limitedBanner: some View {
        HStack {
            Text("Đang hiện ảnh app đã lưu. Cho phép xem toàn bộ thư viện để thấy cả ảnh tải trước đây.")
                .font(Film.sans(13))
                .foregroundStyle(Film.inkSoft)
            Spacer(minLength: 8)
            Button("Cài đặt", action: openSettings)
                .font(Film.sans(14, .semibold))
                .foregroundStyle(Film.accentText)
        }
        .padding(14)
        .background(RoundedRectangle(cornerRadius: 16).fill(Film.card))
        .padding(.horizontal, 20)
        .padding(.bottom, 12)
    }

    private func note(_ title: String, _ text: String, action: String? = nil, _ onAction: @escaping () -> Void = {}) -> some View {
        VStack(spacing: 8) {
            Text(title).font(Film.sans(22, .bold)).foregroundStyle(Film.ink)
            Text(text).font(Film.sans(15)).foregroundStyle(Film.inkSoft).multilineTextAlignment(.center)
            if let action {
                Button(action, action: onAction)
                    .font(Film.sans(15, .semibold))
                    .foregroundStyle(Film.accentText)
                    .padding(.top, 4)
            }
        }
        .frame(maxWidth: .infinity)
        .padding(.horizontal, 32)
        .padding(.top, 100)
    }

    private func openSettings() {
        if let url = URL(string: UIApplication.openSettingsURLString) { UIApplication.shared.open(url) }
    }

    private func months(_ shown: [SavedPhoto]) -> [Month] {
        var out: [Month] = []
        for (i, p) in shown.enumerated() {
            let key = monthKey.string(from: p.taken)
            if out.last?.id == key {
                out[out.count - 1] = Month(id: key, photos: out[out.count - 1].photos + [(i, p)])
            } else {
                out.append(Month(id: key, photos: [(i, p)]))
            }
        }
        return out
    }

    /// Every photo that arrived on the newest import day, whenever it was shot: a card copied
    /// today with last month's photos still counts as today's import.
    private func latestImport(_ shown: [SavedPhoto]) -> [(index: Int, photo: SavedPhoto)] {
        guard let newest = shown.map(\.added).max(), newest.timeIntervalSince1970 > 0 else { return [] }
        let key = dayKey.string(from: newest)
        return shown.enumerated().filter { dayKey.string(from: $0.element.added) == key }.map { ($0.offset, $0.element) }
    }
}

/// "Lần nhập gần nhất": the latest import scrolling sideways, the first photo large.
private struct LatestCard: View {
    let latest: [(index: Int, photo: SavedPhoto)]
    let open: (Int) -> Void

    var body: some View {
        VStack(spacing: 0) {
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 2) {
                    SavedTile(photo: latest[0].photo)
                        .frame(width: 194, height: 194)
                        .onTapGesture { open(latest[0].index) }
                    let rest = Array(latest.dropFirst())
                    LazyHGrid(rows: [GridItem(.fixed(96), spacing: 2), GridItem(.fixed(96), spacing: 2)], spacing: 2) {
                        ForEach(rest, id: \.photo.id) { item in
                            SavedTile(photo: item.photo)
                                .frame(width: 96, height: 96)
                                .onTapGesture { open(item.index) }
                        }
                    }
                }
            }
            .frame(height: 194)
            Button { open(latest[0].index) } label: {
                HStack {
                    VStack(alignment: .leading, spacing: 4) {
                        Text("LẦN NHẬP GẦN NHẤT").font(Film.mono(11)).tracking(0.6).foregroundStyle(Film.accentText)
                        Text("\(dayName(latest[0].photo.added)) · \(latest.count) ảnh")
                            .font(Film.sans(16, .semibold)).foregroundStyle(Film.ink)
                    }
                    Spacer()
                    if latest.count > 3 {
                        Text("VUỐT ĐỂ XEM HẾT").font(Film.mono(11)).tracking(0.6).foregroundStyle(Film.inkSoft)
                    }
                    Image(systemName: "chevron.right")
                        .font(.system(size: 14, weight: .semibold))
                        .foregroundStyle(Film.ink)
                        .frame(width: 36, height: 36)
                        .background(Circle().fill(Film.raised))
                }
                .padding(.horizontal, 16)
                .padding(.vertical, 14)
            }
            .buttonStyle(.plain)
        }
        .background(Film.card)
        .clipShape(RoundedRectangle(cornerRadius: 20))
        .overlay(RoundedRectangle(cornerRadius: 20).stroke(Film.raised, lineWidth: 1))
        .padding(.horizontal, 20)
    }
}

/// One square photo from the library, with its kind when it isn't a JPEG.
struct SavedTile: View {
    @EnvironmentObject var library: SavedLibrary
    let photo: SavedPhoto
    var showKind = true
    @State private var image: UIImage?

    var body: some View {
        Film.frameEmpty
            .overlay {
                if let image { Image(uiImage: image).resizable().scaledToFill() }
            }
            .clipped()
            .overlay(alignment: .bottomTrailing) {
                if showKind && photo.kind != "JPEG" {
                    Text(photo.kind)
                        .font(Film.mono(9))
                        .foregroundStyle(Color(hex: 0xF2EFEA))
                        .padding(.horizontal, 5)
                        .padding(.vertical, 1)
                        .background(RoundedRectangle(cornerRadius: 5).fill(Color.black.opacity(0.72)))
                        .padding(4)
                }
            }
            .contentShape(Rectangle())
            .task(id: photo.id) { image = await library.thumbnail(photo, side: 300) }
    }
}
