import Foundation

/// Little-endian helpers; everything on the wire is LE.
extension Array where Element == UInt8 {
    func u16(_ o: Int) -> Int { Int(self[o]) | Int(self[o + 1]) << 8 }

    func u32(_ o: Int) -> UInt32 {
        UInt32(self[o]) | UInt32(self[o + 1]) << 8 | UInt32(self[o + 2]) << 16 | UInt32(self[o + 3]) << 24
    }

    /// Big-endian u32 (RAF headers).
    func be32(_ o: Int) -> UInt32 {
        UInt32(self[o]) << 24 | UInt32(self[o + 1]) << 16 | UInt32(self[o + 2]) << 8 | UInt32(self[o + 3])
    }

    func hexDump(_ max: Int = 64) -> String {
        prefix(max).map { String(format: "%02x", $0) }.joined(separator: " ") + (count > max ? " … (\(count) B)" : "")
    }
}

struct LEWriter {
    private(set) var bytes: [UInt8] = []

    mutating func u16(_ v: Int) {
        bytes += [UInt8(v & 0xFF), UInt8((v >> 8) & 0xFF)]
    }

    mutating func u32(_ v: UInt32) {
        bytes += [UInt8(v & 0xFF), UInt8((v >> 8) & 0xFF), UInt8((v >> 16) & 0xFF), UInt8((v >> 24) & 0xFF)]
    }

    mutating func append(_ b: [UInt8]) { bytes += b }

    static func u16(_ v: Int) -> [UInt8] { var w = LEWriter(); w.u16(v); return w.bytes }
    static func u32(_ v: UInt32) -> [UInt8] { var w = LEWriter(); w.u32(v); return w.bytes }
}

/// PTP string: u8 char count (including the terminating NUL), then UTF-16LE.
enum PtpString {
    /// The decoded string and the number of bytes it occupied.
    static func decode(_ b: [UInt8], _ off: Int) -> (String, Int) {
        guard off < b.count else { return ("", 0) }
        let count = Int(b[off])
        if count == 0 { return ("", 1) }
        var units: [UInt16] = []
        for i in 0..<count {
            let p = off + 1 + i * 2
            if p + 1 >= b.count { break }
            let c = UInt16(b.u16(p))
            if c == 0 { break }
            units.append(c)
        }
        return (String(decoding: units, as: UTF16.self), 1 + count * 2)
    }
}
