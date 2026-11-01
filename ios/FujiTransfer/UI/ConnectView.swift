import SwiftUI

/// "Kết nối máy ảnh": pick how the camera is reached, then connect.
struct ConnectView: View {
    @EnvironmentObject var session: Session
    @Binding var showLog: Bool
    @AppStorage("connectMode") private var modeRaw = ConnectMode.bluetooth.rawValue

    private var mode: ConnectMode { ConnectMode(rawValue: modeRaw) ?? .bluetooth }
    private var busy: Bool { session.phase != .idle }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                Text("FUJI TRANSFER")
                    .font(Film.mono(11))
                    .tracking(1.3)
                    .foregroundStyle(Film.inkSoft)
                Text("Kết nối máy ảnh")
                    .font(Film.sans(36, .bold))
                    .tracking(-1)
                    .lineLimit(1)
                    .minimumScaleFactor(0.6)
                    .foregroundStyle(Film.ink)
                    .padding(.top, 12)

                cameraCard.padding(.top, 24)

                HStack(spacing: 8) {
                    Pill(label: "Máy đời mới", selected: mode == .bluetooth) { modeRaw = ConnectMode.bluetooth.rawValue }
                    Pill(label: "Đã vào Wi-Fi", selected: mode == .wifiCurrent) { modeRaw = ConnectMode.wifiCurrent.rawValue }
                }
                .disabled(busy)
                .padding(.top, 18)

                VStack(alignment: .leading, spacing: 8) {
                    ForEach(Array(steps.enumerated()), id: \.offset) { i, step in
                        HStack(alignment: .top, spacing: 14) {
                            Text("\(i + 1)")
                                .font(Film.mono(13, .medium))
                                .foregroundStyle(Film.inkSoft)
                                .frame(width: 32, height: 32)
                                .overlay(Circle().stroke(Film.hairline, lineWidth: 1))
                            VStack(alignment: .leading, spacing: 3) {
                                Text(step.0).font(Film.sans(16, .semibold)).foregroundStyle(Film.ink)
                                Text(step.1).font(Film.sans(13)).foregroundStyle(Film.inkSoft)
                            }
                        }
                    }
                }
                .padding(.top, 20)

                if busy {
                    HStack(spacing: 10) {
                        ProgressView().tint(Film.accent)
                        Text(session.status).font(Film.sans(14)).foregroundStyle(Film.ink)
                    }
                    .padding(.top, 18)
                }

                if let error = session.error {
                    HStack(alignment: .top, spacing: 10) {
                        Image(systemName: "exclamationmark.circle").foregroundStyle(Film.error)
                        Text(error).font(Film.sans(14)).foregroundStyle(Film.error)
                    }
                    .padding(14)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .background(RoundedRectangle(cornerRadius: 16).fill(Film.errorBg))
                    .padding(.top, 18)
                }

                VStack(spacing: 10) {
                    if busy {
                        PillButton(label: "Huỷ", primary: false) { session.disconnect() }
                    } else {
                        PillButton(label: "Kết nối", primary: true) { session.connect(mode) }
                    }
                    Button("Xem nhật ký kết nối") { showLog = true }
                        .font(Film.sans(15, .medium))
                        .foregroundStyle(Film.inkSoft)
                        .padding(.top, 6)
                }
                .padding(.top, 24)
            }
            .padding(.horizontal, 20)
            .padding(.vertical, 24)
        }
        .background(Film.paper.ignoresSafeArea())
    }

    private var cameraCard: some View {
        ZStack(alignment: .bottomLeading) {
            RoundedRectangle(cornerRadius: 28).fill(Film.card)
                .overlay(RoundedRectangle(cornerRadius: 28).stroke(Film.raised, lineWidth: 1))
            // Product photo: fujifilm-x.com (X-S20 page).
            // Loose PNG in the bundle, not an asset catalog, so load it through UIImage.
            Image(uiImage: UIImage(named: "camera_xs20.png") ?? UIImage())
                .resizable()
                .scaledToFit()
                .frame(height: 124)
                .frame(maxWidth: .infinity)
                .padding(.bottom, 26)
                .frame(maxHeight: .infinity)
            HStack(spacing: 6) {
                Circle().fill(busy ? Film.accent : Film.inkSoft).frame(width: 7, height: 7)
                Text(mode == .bluetooth ? "BLUETOOTH · X-S20, X-T5, X100VI…" : "WI-FI ĐÃ KẾT NỐI")
                    .font(Film.mono(10))
                    .foregroundStyle(Film.ink)
            }
            .padding(.horizontal, 10)
            .padding(.vertical, 6)
            .background(RoundedRectangle(cornerRadius: 12).fill(Film.paper))
            .padding(14)
        }
        .frame(height: 200)
    }

    private var steps: [(String, String)] {
        switch mode {
        case .bluetooth:
            return [
                ("Tìm máy ảnh qua Bluetooth", "Lần đầu: MENU › CÀI ĐẶT MẠNG/USB › CÀI ĐẶT Bluetooth/ĐIỆN THOẠI › ĐĂNG KÝ GHÉP ĐÔI"),
                ("Bật Wi-Fi của máy ảnh", "App tự nhờ máy ảnh bật Wi-Fi rồi xin vào mạng đó"),
                ("Mở thẻ nhớ", "Ảnh mới nhất hiện trước"),
            ]
        case .wifiCurrent:
            return [
                ("Vào Wi-Fi máy ảnh", "Cài đặt › Wi-Fi › chọn mạng FUJIFILM-… của máy ảnh"),
                ("Quay lại app và bấm Kết nối", "Máy đời cũ: bấm OK trên máy ảnh khi máy hỏi"),
            ]
        }
    }
}
