import Foundation

/// Remembers each photo's name, shooting date, orientation and thumbnail between connections,
/// so a card that was read once shows up at once next time (on an X-S20 every photo costs a
/// slow GetObjectInfo plus a GetThumb). A port of the Android app's `MetadataCache.kt`, kept
/// simpler: one JSON index per camera and one small JPEG per thumbnail.
///
/// Entries are keyed by handle and only used while the camera still reports the same file
/// name for that handle, which catches swapped cards.
@MainActor
final class MetadataCache {
    struct Entry: Codable, Equatable {
        var name: String
        /// "YYYYMMDDThhmmss", or empty while only the thumbnail is known.
        var date: String
        var orientation: Int?
        var hasThumb = false
    }

    private let dir: URL
    private var entries: [UInt32: Entry] = [:]
    private var dirty = 0
    /// File reads and writes, in order, off the main thread.
    private let io = DispatchQueue(label: "metadata-cache", qos: .utility)

    var count: Int { entries.count }

    init(camera: String) {
        let safe = (camera.isEmpty ? "camera" : camera)
            .replacingOccurrences(of: "[^A-Za-z0-9_-]", with: "_", options: .regularExpression)
        dir = Self.root.appendingPathComponent(safe, isDirectory: true)
        try? FileManager.default.createDirectory(at: dir.appendingPathComponent("thumbs"), withIntermediateDirectories: true)
        // A broken index only costs a re-read.
        if let data = try? Data(contentsOf: indexURL),
           let saved = try? JSONDecoder().decode([String: Entry].self, from: data) {
            for (k, e) in saved { if let h = UInt32(k) { entries[h] = e } }
        }
    }

    static var root: URL {
        FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("meta", isDirectory: true)
    }

    private var indexURL: URL { dir.appendingPathComponent("index.json") }
    private func thumbURL(_ h: UInt32) -> URL { dir.appendingPathComponent("thumbs/\(h).jpg") }

    func entry(_ h: UInt32) -> Entry? { entries[h] }

    /// Records what the camera said about [h]. A new name or date means another photo now has
    /// that handle (new card, numbering restarted): its old orientation and thumbnail are dropped.
    func put(_ h: UInt32, name: String, date: String) {
        let same = entries[h].flatMap { $0.name == name && ($0.date.isEmpty || $0.date == date) ? $0 : nil }
        var e = same ?? Entry(name: name, date: "")
        e.date = date
        update(h, e)
    }

    func setOrientation(_ h: UInt32, name: String, _ orientation: Int) {
        var e = entries[h].flatMap { $0.name == name ? $0 : nil } ?? Entry(name: name, date: "")
        e.orientation = orientation
        update(h, e)
    }

    /// The camera's thumbnail for [h] as it was when that handle was [name], or nil.
    func thumb(_ h: UInt32, name: String) async -> Data? {
        guard let e = entries[h], e.name == name, e.hasThumb else { return nil }
        let url = thumbURL(h)
        return await withCheckedContinuation { done in
            io.async { done.resume(returning: try? Data(contentsOf: url)) }
        }
    }

    func saveThumb(_ h: UInt32, name: String, _ data: Data) {
        var e = entries[h].flatMap { $0.name == name ? $0 : nil } ?? Entry(name: name, date: "")
        e.hasThumb = true
        let url = thumbURL(h)
        io.async { try? data.write(to: url, options: .atomic) }
        update(h, e)
    }

    private func update(_ h: UInt32, _ e: Entry) {
        guard entries[h] != e else { return }
        entries[h] = e
        dirty += 1
        if dirty >= 100 { save() }
    }

    /// Writes the index if anything changed (thumbnails are written as they come).
    func save() {
        guard dirty > 0 else { return }
        dirty = 0
        let snapshot = Dictionary(uniqueKeysWithValues: entries.map { (String($0.key), $0.value) })
        let url = indexURL
        io.async {
            if let data = try? JSONEncoder().encode(snapshot) { try? data.write(to: url, options: .atomic) }
        }
    }

    /// Forgets every camera's names, dates and thumbnails.
    static func clearAll() {
        try? FileManager.default.removeItem(at: root)
    }

    /// Bytes the cache uses on the phone, for every camera.
    static func diskBytes() -> Int {
        guard let files = FileManager.default.enumerator(at: root, includingPropertiesForKeys: [.fileSizeKey]) else { return 0 }
        var total = 0
        for case let url as URL in files {
            total += (try? url.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0
        }
        return total
    }
}
