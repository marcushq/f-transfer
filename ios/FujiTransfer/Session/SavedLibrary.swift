import ImageIO
import Photos
import UIKit

/// A photo this app saved to the Photos library.
struct SavedPhoto: Identifiable, Equatable {
    let id: String
    let asset: PHAsset
    let name: String
    /// When it was shot (the library's creation date).
    let taken: Date
    /// When it arrived on the phone.
    let added: Date
    let bytes: Int

    var kind: String {
        switch name.split(separator: ".").last?.uppercased() ?? "" {
        case "JPG", "JPEG": return "JPEG"
        case "HIF", "HEIF", "HEIC": return "HEIF"
        case "RAF": return "RAF"
        case let ext: return ext
        }
    }
}

/// Exposure, camera and lens for the viewer, already formatted.
struct ShotDetails {
    let line: String
    let camera: String
    let lens: String
}

/// The "Đã lưu" tab's photos: the ones this app saved, found again by their library ids. The
/// Android app lists its own folder; iOS has no folders, so every save is remembered instead
/// (and photos saved before that are found once by name and shooting day).
@MainActor
final class SavedLibrary: NSObject, ObservableObject, PHPhotoLibraryChangeObserver {
    static let all = "Tất cả"

    @Published private(set) var photos: [SavedPhoto] = []
    @Published private(set) var loading = true
    @Published private(set) var access = PHPhotoLibrary.authorizationStatus(for: .readWrite)
    /// "Tất cả", "JPEG", "HEIF" or "RAF".
    @Published var filter = SavedLibrary.all

    let images = PHCachingImageManager()
    private var observing = false
    private var reloadTask: Task<Void, Never>?

    private static let key = "savedAssets"

    /// Library id → when it was saved (seconds since 1970).
    private static var recorded: [String: Double] {
        get { UserDefaults.standard.dictionary(forKey: key) as? [String: Double] ?? [:] }
        set { UserDefaults.standard.set(newValue, forKey: key) }
    }

    static func record(_ id: String, at date: Date = Date()) {
        recorded[id] = date.timeIntervalSince1970
    }

    var shown: [SavedPhoto] { filter == Self.all ? photos : photos.filter { $0.kind == filter } }

    /// The filters worth offering: "Tất cả" plus each kind present.
    var kinds: [String] { [Self.all] + ["JPEG", "HEIF", "RAF"].filter { k in photos.contains { $0.kind == k } } }

    /// Asks for library access the first time, then reads the list.
    func open() async {
        if access == .notDetermined {
            access = await PHPhotoLibrary.requestAuthorization(for: .readWrite)
        }
        guard access == .authorized || access == .limited else {
            loading = false
            return
        }
        if !observing {
            PHPhotoLibrary.shared().register(self)
            observing = true
        }
        if access == .authorized && !UserDefaults.standard.bool(forKey: "savedScanDone") {
            await findEarlier()
            UserDefaults.standard.set(true, forKey: "savedScanDone")
        }
        await reload()
    }

    func reload() async {
        let ids = Self.recorded
        let found = await Task.detached(priority: .userInitiated) { Self.query(ids) }.value
        // Ids of photos deleted elsewhere are dropped from the record.
        if found.count < ids.count {
            let alive = Set(found.map(\.id))
            Self.recorded = ids.filter { alive.contains($0.key) }
        }
        photos = found
        loading = false
    }

    private nonisolated static func query(_ ids: [String: Double]) -> [SavedPhoto] {
        guard !ids.isEmpty else { return [] }
        let assets = PHAsset.fetchAssets(withLocalIdentifiers: Array(ids.keys), options: nil)
        var out: [SavedPhoto] = []
        assets.enumerateObjects { a, _, _ in
            let resources = PHAssetResource.assetResources(for: a)
            let main = resources.first { $0.type == .photo } ?? resources.first
            let bytes = resources.reduce(0) { $0 + (($1.value(forKey: "fileSize") as? Int) ?? 0) }
            let added = Date(timeIntervalSince1970: ids[a.localIdentifier] ?? 0)
            out.append(SavedPhoto(id: a.localIdentifier, asset: a, name: main?.originalFilename ?? "",
                                  taken: a.creationDate ?? added, added: added, bytes: bytes))
        }
        return out.sorted { $0.taken > $1.taken }
    }

    /// Photos saved before ids were remembered: matched once by file name and shooting day
    /// against the app's list of downloads. Needs full library access.
    private func findEarlier() async {
        let wanted = Downloaded.entries
        guard !wanted.isEmpty else { return }
        let found = await Task.detached(priority: .userInitiated) { Self.match(wanted) }.value
        var ids = Self.recorded
        for (id, date) in found where ids[id] == nil { ids[id] = date.timeIntervalSince1970 }
        Self.recorded = ids
    }

    private nonisolated static func match(_ wanted: [(name: String, date: String)]) -> [(String, Date)] {
        let f = DateFormatter()
        f.dateFormat = "yyyyMMdd"
        f.timeZone = .current
        var names: [String: [Date]] = [:]
        for w in wanted {
            guard let d = f.date(from: String(w.date.prefix(8))) else { continue }
            names[w.name, default: []].append(d)
        }
        guard let first = names.values.flatMap({ $0 }).min(), let last = names.values.flatMap({ $0 }).max() else { return [] }
        let options = PHFetchOptions()
        options.predicate = NSPredicate(format: "mediaType == %d AND creationDate >= %@ AND creationDate <= %@",
                                        PHAssetMediaType.image.rawValue,
                                        first.addingTimeInterval(-86_400) as NSDate, last.addingTimeInterval(2 * 86_400) as NSDate)
        var out: [(String, Date)] = []
        PHAsset.fetchAssets(with: options).enumerateObjects { a, _, _ in
            guard let created = a.creationDate,
                  let name = PHAssetResource.assetResources(for: a).first(where: { $0.type == .photo })?.originalFilename,
                  let days = names[name],
                  days.contains(where: { abs(created.timeIntervalSince($0)) < 2 * 86_400 }) else { return }
            out.append((a.localIdentifier, created))
        }
        return out
    }

    nonisolated func photoLibraryDidChange(_ changeInstance: PHChange) {
        Task { @MainActor in
            // Saves come in bursts during a transfer: read once things settle.
            reloadTask?.cancel()
            reloadTask = Task {
                try? await Task.sleep(nanoseconds: 600_000_000)
                if !Task.isCancelled { await reload() }
            }
        }
    }

    #if DEBUG
    /// Treats every photo in the library as saved by the app (the Simulator's samples), to look
    /// at the tab without a camera.
    func demoRecordAll() async {
        guard await PHPhotoLibrary.requestAuthorization(for: .readWrite) == .authorized else { return }
        var ids = Self.recorded
        PHAsset.fetchAssets(with: .image, options: nil).enumerateObjects { a, _, _ in
            ids[a.localIdentifier] = Date().timeIntervalSince1970
        }
        Self.recorded = ids
        await reload()
    }
    #endif

    // MARK: - Images

    func thumbnail(_ p: SavedPhoto, side: CGFloat) async -> UIImage? {
        let options = PHImageRequestOptions()
        options.deliveryMode = .highQualityFormat
        options.resizeMode = .fast
        options.isNetworkAccessAllowed = true
        return await withCheckedContinuation { done in
            images.requestImage(for: p.asset, targetSize: CGSize(width: side, height: side),
                                contentMode: .aspectFill, options: options) { image, _ in
                done.resume(returning: image)
            }
        }
    }

    /// The photo about [side] px on its long side, upright. RAF shows as iOS develops it.
    func full(_ p: SavedPhoto, side: CGFloat) async -> UIImage? {
        let options = PHImageRequestOptions()
        options.deliveryMode = .highQualityFormat
        options.isNetworkAccessAllowed = true
        return await withCheckedContinuation { done in
            images.requestImage(for: p.asset, targetSize: CGSize(width: side, height: side),
                                contentMode: .aspectFit, options: options) { image, _ in
                done.resume(returning: image)
            }
        }
    }

    func details(_ p: SavedPhoto) async -> ShotDetails? {
        let options = PHImageRequestOptions()
        options.isNetworkAccessAllowed = true
        let data: Data? = await withCheckedContinuation { done in
            PHImageManager.default().requestImageDataAndOrientation(for: p.asset, options: options) { data, _, _, _ in
                done.resume(returning: data)
            }
        }
        guard let data, let source = CGImageSourceCreateWithData(data as CFData, nil),
              let props = CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [CFString: Any] else { return nil }
        let exif = props[kCGImagePropertyExifDictionary] as? [CFString: Any] ?? [:]
        let tiff = props[kCGImagePropertyTIFFDictionary] as? [CFString: Any] ?? [:]
        func trim(_ d: Double) -> String { d == d.rounded() ? String(Int(d)) : String(format: "%.1f", d) }
        var parts: [String] = []
        if let v = exif[kCGImagePropertyExifFocalLength] as? Double, v > 0 { parts.append("\(trim(v))mm") }
        if let v = exif[kCGImagePropertyExifFNumber] as? Double, v > 0 { parts.append("ƒ/\(trim(v))") }
        if let v = exif[kCGImagePropertyExifExposureTime] as? Double, v > 0 {
            parts.append(v >= 1 ? "\(trim(v))s" : "1/\(Int((1 / v).rounded()))")
        }
        if let v = (exif[kCGImagePropertyExifISOSpeedRatings] as? [Int])?.first, v > 0 { parts.append("ISO \(v)") }
        let camera = [tiff[kCGImagePropertyTIFFMake] as? String, tiff[kCGImagePropertyTIFFModel] as? String]
            .compactMap { $0 }.joined(separator: " ").trimmingCharacters(in: .whitespaces)
        let lens = (exif[kCGImagePropertyExifLensModel] as? String)?.trimmingCharacters(in: .whitespaces) ?? ""
        return ShotDetails(line: parts.joined(separator: " · "), camera: camera, lens: lens)
    }

    /// The original file in a temporary folder, for the share sheet.
    func exportFile(_ p: SavedPhoto) async -> URL? {
        let resources = PHAssetResource.assetResources(for: p.asset)
        guard let resource = resources.first(where: { $0.type == .photo }) ?? resources.first else { return nil }
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("share", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let url = dir.appendingPathComponent(resource.originalFilename)
        try? FileManager.default.removeItem(at: url)
        let options = PHAssetResourceRequestOptions()
        options.isNetworkAccessAllowed = true
        do {
            try await PHAssetResourceManager.default().writeData(for: resource, toFile: url, options: options)
            return url
        } catch {
            return nil
        }
    }

    /// Deletes [p] from the library (iOS asks for confirmation itself). True when it went.
    func delete(_ p: SavedPhoto) async -> Bool {
        do {
            try await PHPhotoLibrary.shared().performChanges {
                PHAssetChangeRequest.deleteAssets([p.asset] as NSArray)
            }
        } catch {
            return false
        }
        Downloaded.forget(name: p.name)
        Self.recorded[p.id] = nil
        photos.removeAll { $0.id == p.id }
        return true
    }
}
