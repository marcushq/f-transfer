import Foundation

/// Finds the EXIF Orientation tag (0x0112) in the first few KB of a file. Fujifilm's own
/// thumbnails are never rotated, so the grid needs this to show portrait shots upright.
enum ExifOrientation {
    static let normal = 1

    /// 1–8 as in EXIF, or nil when no orientation was found in [b].
    static func parse(_ b: [UInt8]) -> Int? {
        guard let tiff = findTiff(b), tiff + 8 <= b.count else { return nil }
        let little = b[tiff] == 0x49
        func u16(_ o: Int) -> Int { little ? b.u16(o) : Int(b[o]) << 8 | Int(b[o + 1]) }
        func u32(_ o: Int) -> Int { little ? Int(b.u32(o)) : u16(o) << 16 | u16(o + 2) }
        let ifd0 = tiff + u32(tiff + 4)
        guard ifd0 >= tiff, ifd0 + 2 <= b.count else { return nil }
        for i in 0..<u16(ifd0) {
            let e = ifd0 + 2 + i * 12
            guard e + 12 <= b.count else { return nil }
            if u16(e) == 0x0112 {
                let v = u16(e + 8)
                return (1...8).contains(v) ? v : nil
            }
        }
        return nil
    }

    /// Degrees to rotate clockwise so the image shows upright. Cameras never write the
    /// mirrored variants.
    static func degrees(_ orientation: Int?) -> Int {
        switch orientation {
        case 3, 4: return 180
        case 5, 6: return 90
        case 7, 8: return 270
        default: return 0
        }
    }

    /// Offset of the TIFF header ("II*\0" / "MM\0*") that follows an "Exif\0\0" marker.
    private static func findTiff(_ b: [UInt8]) -> Int? {
        let marker: [UInt8] = [0x45, 0x78, 0x69, 0x66, 0, 0]
        var i = 0
        while i + marker.count + 4 <= b.count {
            if Array(b[i..<(i + marker.count)]) == marker {
                let t = i + marker.count
                if (b[t] == 0x49 && b[t + 1] == 0x49 && b[t + 2] == 0x2A && b[t + 3] == 0) ||
                    (b[t] == 0x4D && b[t + 1] == 0x4D && b[t + 2] == 0 && b[t + 3] == 0x2A) {
                    return t
                }
            }
            i += 1
        }
        return nil
    }
}
