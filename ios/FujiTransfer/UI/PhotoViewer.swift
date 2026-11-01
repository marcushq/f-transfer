import SwiftUI
import UIKit

private let shotTime: DateFormatter = {
    let f = DateFormatter()
    f.dateFormat = "dd.MM.yyyy · HH:mm"
    return f
}()

/// Full-screen photos from "Đã lưu": swipe between them, pinch or double-tap to zoom, the
/// shooting details, share and delete, and a strip of the neighbours.
struct PhotoViewer: View {
    @EnvironmentObject var library: SavedLibrary
    @Environment(\.dismiss) private var dismiss
    @State private var index: Int
    @State private var details: ShotDetails?
    @State private var shareURL: URL?
    @State private var preparingShare = false

    private static let ink = Color(hex: 0xF2EFEA)
    private static let inkSoft = Color(hex: 0xA8A49C)

    init(photos: [SavedPhoto], start: Int) {
        _index = State(initialValue: start)
    }

    var body: some View {
        let photos = library.shown
        let i = min(index, max(photos.count - 1, 0))
        VStack(spacing: 0) {
            if photos.isEmpty {
                Spacer()
            } else {
                let current = photos[i]
                topBar(current, i, photos.count)
                TabView(selection: $index) {
                    ForEach(Array(photos.enumerated()), id: \.element.id) { n, p in
                        ViewerPage(photo: p).tag(n)
                    }
                }
                .tabViewStyle(.page(indexDisplayMode: .never))
                info(current)
                actions(current)
                Strip(photos: photos, current: i) { index = $0 }
            }
        }
        .background(Color.black.ignoresSafeArea())
        .preferredColorScheme(.dark)
        .task(id: photos.isEmpty ? nil : photos[i].id) {
            details = nil
            if !photos.isEmpty { details = await library.details(photos[i]) }
        }
        .onChange(of: photos.count) { _, n in
            if n == 0 { dismiss() } else if index >= n { index = n - 1 }
        }
        .sheet(item: Binding(get: { shareURL.map(ShareItem.init) }, set: { shareURL = $0?.url })) { item in
            ShareSheet(items: [item.url])
        }
    }

    private func topBar(_ p: SavedPhoto, _ i: Int, _ count: Int) -> some View {
        HStack {
            Button { dismiss() } label: {
                Image(systemName: "chevron.left")
                    .font(.system(size: 20, weight: .semibold))
                    .foregroundStyle(Self.ink)
                    .frame(width: 48, height: 48)
            }
            Spacer()
            VStack(spacing: 2) {
                Text(p.name).font(Film.mono(13, .medium)).foregroundStyle(Self.ink).lineLimit(1)
                Text("\(i + 1) / \(count)").font(Film.mono(11)).foregroundStyle(Self.inkSoft)
            }
            Spacer()
            Color.clear.frame(width: 48, height: 48)
        }
        .padding(.horizontal, 8)
        .frame(height: 60)
    }

    private func info(_ p: SavedPhoto) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            // Exposure and lens only: film simulation isn't readable from the file.
            Text(details.map { d in [d.line, d.lens].filter { !$0.isEmpty }.joined(separator: " · ") } ?? " ")
                .font(Film.mono(12))
                .foregroundStyle(Color(hex: 0xD9D5CD))
                .lineLimit(1)
            HStack {
                Text(shotTime.string(from: p.taken))
                Spacer()
                let size = String(format: "%.1f MB", Double(p.bytes) / 1_048_576).replacingOccurrences(of: ".", with: ",")
                Text(size + " · \(p.asset.pixelWidth)×\(p.asset.pixelHeight)")
            }
            .font(Film.mono(12))
            .foregroundStyle(Self.inkSoft)
        }
        .padding(.horizontal, 20)
        .padding(.top, 16)
    }

    private func actions(_ p: SavedPhoto) -> some View {
        HStack(spacing: 4) {
            action(preparingShare ? "hourglass" : "square.and.arrow.up", "Chia sẻ", Self.ink) {
                guard !preparingShare else { return }
                preparingShare = true
                Task {
                    shareURL = await library.exportFile(p)
                    preparingShare = false
                }
            }
            action("trash", "Xoá", Color(hex: 0xF09595)) {
                // iOS asks for confirmation itself.
                Task { _ = await library.delete(p) }
            }
        }
        .padding(.horizontal, 12)
        .padding(.top, 18)
    }

    private func action(_ icon: String, _ label: String, _ tint: Color, _ run: @escaping () -> Void) -> some View {
        Button(action: run) {
            VStack(spacing: 4) {
                Image(systemName: icon).font(.system(size: 18))
                Text(label).font(Film.sans(11, .medium))
            }
            .foregroundStyle(tint)
            .frame(maxWidth: .infinity)
            .frame(height: 60)
            .background(RoundedRectangle(cornerRadius: 14).fill(Color(hex: 0x1E1E1E)))
        }
        .buttonStyle(.plain)
    }
}

/// One page: the thumbnail at once, then the photo at about two screens wide.
private struct ViewerPage: View {
    @EnvironmentObject var library: SavedLibrary
    let photo: SavedPhoto
    @State private var image: UIImage?

    var body: some View {
        ZoomableImage(image: image)
            .task(id: photo.id) {
                image = await library.thumbnail(photo, side: 300)
                if let full = await library.full(photo, side: 2400) { image = full }
            }
    }
}

/// Thumbnails of the neighbours, the current one ringed, kept centred.
private struct Strip: View {
    let photos: [SavedPhoto]
    let current: Int
    let pick: (Int) -> Void

    var body: some View {
        ScrollViewReader { proxy in
            ScrollView(.horizontal, showsIndicators: false) {
                LazyHStack(spacing: 4) {
                    ForEach(Array(photos.enumerated()), id: \.element.id) { n, p in
                        SavedTile(photo: p, showKind: false)
                            .frame(width: n == current ? 44 : 34, height: 44)
                            .clipShape(RoundedRectangle(cornerRadius: 6))
                            .overlay(RoundedRectangle(cornerRadius: 6).stroke(n == current ? Film.accent : .clear, lineWidth: 2))
                            .id(n)
                            .onTapGesture { pick(n) }
                    }
                }
                .padding(.horizontal, 20)
            }
            .frame(height: 44)
            .padding(.vertical, 14)
            .onAppear { proxy.scrollTo(current, anchor: .center) }
            .onChange(of: current) { _, c in withAnimation { proxy.scrollTo(c, anchor: .center) } }
        }
    }
}

/// Pinch and double-tap zoom with UIKit's scroll view, which also hands a pan at the edge on
/// to the pager, like the Photos app.
private struct ZoomableImage: UIViewRepresentable {
    let image: UIImage?

    func makeUIView(context: Context) -> ZoomView { ZoomView() }

    func updateUIView(_ view: ZoomView, context: Context) {
        if view.imageView.image !== image { view.imageView.image = image }
    }
}

private final class ZoomView: UIScrollView, UIScrollViewDelegate {
    let imageView = UIImageView()

    init() {
        super.init(frame: .zero)
        delegate = self
        minimumZoomScale = 1
        maximumZoomScale = 5
        showsHorizontalScrollIndicator = false
        showsVerticalScrollIndicator = false
        contentInsetAdjustmentBehavior = .never
        imageView.contentMode = .scaleAspectFit
        addSubview(imageView)
        let tap = UITapGestureRecognizer(target: self, action: #selector(doubleTap(_:)))
        tap.numberOfTapsRequired = 2
        addGestureRecognizer(tap)
    }

    required init?(coder: NSCoder) { fatalError() }

    override func layoutSubviews() {
        super.layoutSubviews()
        if zoomScale == 1 {
            imageView.frame = bounds
            contentSize = bounds.size
        }
    }

    func viewForZooming(in scrollView: UIScrollView) -> UIView? { imageView }

    @objc private func doubleTap(_ g: UITapGestureRecognizer) {
        if zoomScale > 1 {
            setZoomScale(1, animated: true)
        } else {
            let p = g.location(in: imageView)
            let size = CGSize(width: bounds.width / 2.5, height: bounds.height / 2.5)
            zoom(to: CGRect(x: p.x - size.width / 2, y: p.y - size.height / 2, width: size.width, height: size.height), animated: true)
        }
    }
}

private struct ShareItem: Identifiable {
    let url: URL
    var id: URL { url }
}

private struct ShareSheet: UIViewControllerRepresentable {
    let items: [Any]

    func makeUIViewController(context: Context) -> UIActivityViewController {
        UIActivityViewController(activityItems: items, applicationActivities: nil)
    }

    func updateUIViewController(_ vc: UIActivityViewController, context: Context) {}
}
