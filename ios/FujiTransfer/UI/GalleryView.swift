import SwiftUI

/// "20261027" → "27.10".
private func shortDate(_ day: String) -> String { "\(day.suffix(2)).\(day.dropFirst(4).prefix(2))" }

/// "Hôm nay", "Hôm qua", else the weekday ("Thứ Hai" … "Chủ nhật").
private func dayTitle(_ day: String) -> String {
    let f = DateFormatter()
    f.dateFormat = "yyyyMMdd"
    f.timeZone = .current
    guard let date = f.date(from: day) else { return day }
    let cal = Calendar.current
    let days = cal.dateComponents([.day], from: cal.startOfDay(for: date), to: cal.startOfDay(for: Date())).day ?? 99
    switch days {
    case 0: return "Hôm nay"
    case 1: return "Hôm qua"
    default:
        return ["Chủ nhật", "Thứ Hai", "Thứ Ba", "Thứ Tư", "Thứ Năm", "Thứ Sáu", "Thứ Bảy"][cal.component(.weekday, from: date) - 1]
    }
}

/// The camera's photos, newest first, by shooting day: tap to select, then download.
struct GalleryView: View {
    @EnvironmentObject var session: Session
    @Binding var showLog: Bool

    private let columns = Array(repeating: GridItem(.flexible(), spacing: 2), count: 3)
    /// (already on the phone, selected) while the "download again?" question is up.
    @State private var askRedownload: (already: Int, chosen: Int)?

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
                    if !session.photos.isEmpty { chips }
                    let groups = session.dayGroups
                    if session.photos.isEmpty {
                        emptyNote("Thẻ nhớ trống", "Máy ảnh không trả về ảnh nào. Nếu thẻ nhớ có ảnh, hãy gửi nhật ký kết nối.")
                    } else if groups.isEmpty {
                        let allSaved = session.hideDownloaded && session.dayFilter == nil
                        emptyNote(allSaved ? "Đã tải hết" : "Không có ảnh nào",
                                  allSaved ? "Mọi ảnh trên thẻ đã ở trong điện thoại." : "Không có ảnh nào khớp bộ lọc.")
                    }
                    LazyVGrid(columns: columns, spacing: 2) {
                        ForEach(groups) { group in
                            Section {
                                ForEach(group.photos) { photo in
                                    Tile(photo: photo, selected: session.selected.contains(photo.handle), rotate: session.rotateThumbs)
                                        .onAppear { session.requestThumb(photo.handle) }
                                        .onTapGesture { session.toggle(photo.handle) }
                                }
                            } header: {
                                dayHeader(group)
                            }
                        }
                    }
                    Spacer(minLength: 110)
                }
                .padding(.top, 14)
            }
            bottomBar.padding(.horizontal, 20).padding(.bottom, 12)
        }
        .background(Film.paper.ignoresSafeArea())
        .alert(redownloadTitle, isPresented: Binding(get: { askRedownload != nil }, set: { if !$0 { askRedownload = nil } })) {
            if let a = askRedownload {
                let fresh = a.chosen - a.already
                if fresh > 0 {
                    Button("Chỉ tải \(fresh) ảnh mới") { session.downloadSelected(includeDownloaded: false) }
                    Button("Tải lại cả \(a.chosen)") { session.downloadSelected() }
                } else {
                    Button("Tải lại \(a.chosen) ảnh") { session.downloadSelected() }
                }
                Button("Huỷ", role: .cancel) {}
            }
        } message: {
            if let a = askRedownload {
                Text(a.chosen > a.already
                     ? "\(a.already) trong \(a.chosen) ảnh đã chọn đã có trong điện thoại. Bạn muốn tải lại những ảnh đó không?"
                     : "Cả \(a.chosen) ảnh đã chọn đều đã có trong điện thoại. Tải lại sẽ tạo thêm một bản nữa trong thư viện ảnh.")
            }
        }
    }

    private var redownloadTitle: String {
        guard let a = askRedownload else { return "" }
        return a.chosen > a.already ? "Có ảnh đã tải rồi" : "Đã tải hết ảnh này"
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
                    Text(statusLine)
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
                Button("Chọn tất cả ảnh đang hiện") { session.selectAll() }
                Picker("Kích thước khi tải ảnh JPEG", selection: $session.transferSize) {
                    Text("Ảnh gốc").tag(TransferSize.original)
                    Text("Nhỏ (S) · thử nghiệm").tag(TransferSize.small)
                    Text("Rất nhỏ (XS) · thử nghiệm").tag(TransferSize.extraSmall)
                }
                .pickerStyle(.menu)
                .disabled(session.transfer != nil)
                Toggle("Xoay ảnh thu nhỏ theo hướng chụp", isOn: $session.rotateThumbs)
                Button(String(format: "Xoá bộ nhớ đệm ảnh (%.1f MB)", Double(MetadataCache.diskBytes()) / 1_048_576)) {
                    session.clearCache()
                }
                .disabled(session.transfer != nil)
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

    private var sizeLine: String {
        switch session.transferSize {
        case .original: return "ẢNH GỐC"
        case .small: return "CỠ S · MÁY ẢNH THU NHỎ"
        case .extraSmall: return "CỠ XS · MÁY ẢNH THU NHỎ"
        }
    }

    private var statusLine: String {
        if !session.selected.isEmpty { return "ĐÃ CHỌN \(session.selected.count) ẢNH" }
        let unread = session.unreadCount
        if unread > 0 { return "ĐANG ĐỌC NGÀY CHỤP \(session.photos.count - unread)/\(session.photos.count)" }
        return "ĐÃ KẾT NỐI · \(session.photos.count) ẢNH"
    }

    /// All, not yet downloaded, then one chip per shooting day, newest first.
    private var chips: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                Pill(label: "Tất cả", selected: session.dayFilter == nil && !session.hideDownloaded) {
                    session.dayFilter = nil
                    session.hideDownloaded = false
                }
                Pill(label: "Chưa tải", selected: session.hideDownloaded) { session.hideDownloaded.toggle() }
                ForEach(session.days, id: \.self) { d in
                    Pill(label: "\(d.suffix(2)) Th\(Int(d.dropFirst(4).prefix(2)) ?? 0)", selected: session.dayFilter == d) {
                        session.dayFilter = session.dayFilter == d ? nil : d
                    }
                }
            }
            .padding(.horizontal, 20)
        }
    }

    private func dayHeader(_ g: DayGroup) -> some View {
        let allSelected = g.photos.allSatisfy { session.selected.contains($0.handle) }
        return HStack(alignment: .center) {
            HStack(alignment: .lastTextBaseline, spacing: 8) {
                Text(g.day.map(dayTitle) ?? "Chưa rõ ngày")
                    .font(Film.sans(17, .semibold))
                    .foregroundStyle(Film.ink)
                Text((g.day.map { shortDate($0) + " · " } ?? "") + "\(g.photos.count) ẢNH")
                    .font(Film.mono(11))
                    .tracking(0.6)
                    .foregroundStyle(Film.inkSoft)
            }
            Spacer()
            if session.transfer == nil {
                Button {
                    session.toggleDay(g.day)
                } label: {
                    Text(g.day != nil && session.selectingDay == g.day ? "Đang tìm…"
                         : allSelected ? "Bỏ chọn" : g.day == nil ? "Chọn tất cả" : "Chọn cả ngày")
                        .font(Film.sans(14, .semibold))
                        .foregroundStyle(Film.accentText)
                }
                .disabled(session.selectingDay != nil)
            }
        }
        .padding(.leading, 20)
        .padding(.trailing, 16)
        .padding(.top, 10)
        .padding(.bottom, 4)
    }

    private func emptyNote(_ title: String, _ text: String) -> some View {
        VStack(spacing: 8) {
            Text(title).font(Film.sans(20, .semibold)).foregroundStyle(Film.ink)
            Text(text).font(Film.sans(15)).foregroundStyle(Film.inkSoft).multilineTextAlignment(.center)
        }
        .frame(maxWidth: .infinity)
        .padding(.horizontal, 32)
        .padding(.top, 60)
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
            Button {
                let chosen = session.photos.filter { session.selected.contains($0.handle) }
                let already = chosen.filter(\.downloaded).count
                if already > 0 { askRedownload = (already, chosen.count) } else { session.downloadSelected() }
            } label: {
                HStack {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("Tải \(session.selected.count) ảnh về máy").font(Film.sans(18, .bold))
                        Text(sizeLine).font(Film.mono(11)).tracking(0.6)
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
    let rotate: Bool

    var body: some View {
        Film.frameEmpty
            .aspectRatio(1, contentMode: .fit)
            .overlay {
                if let thumb = photo.shownThumb(rotate: rotate) {
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
