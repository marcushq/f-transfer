import Photos
import UIKit

/// Saves downloaded files into the Photos app. "Add only" access is enough: the app never
/// reads the library.
enum PhotoSaver {
    static func authorize() async -> Bool {
        let s = await PHPhotoLibrary.requestAuthorization(for: .addOnly)
        return s == .authorized || s == .limited
    }

    /// Adds [url] as a photo (JPEG, HEIF or RAF), moving the file into the library, and
    /// remembers it for the "Đã lưu" tab.
    static func save(_ url: URL, name: String) async throws {
        var id: String?
        try await PHPhotoLibrary.shared().performChanges {
            let request = PHAssetCreationRequest.forAsset()
            let options = PHAssetResourceCreationOptions()
            options.originalFilename = name
            options.shouldMoveFile = true
            request.addResource(with: .photo, fileURL: url, options: options)
            id = request.placeholderForCreatedAsset?.localIdentifier
        }
        if let id { await SavedLibrary.record(id) }
    }
}

/// Camera thumbnails are always 4:3 (160×120), so a 3:2 or 16:9 photo comes letterboxed with
/// black bands top and bottom, and an upright one with bands left and right. Cuts those bands
/// off so the grid's square crop shows only picture. Bands on opposite edges must match in
/// size — a night shot that is just dark at one edge keeps its pixels.
func trimBlackBars(_ image: UIImage) -> UIImage {
    guard let cg = image.cgImage else { return image }
    let w = cg.width
    let h = cg.height
    guard w >= 8, h >= 8 else { return image }
    var px = [UInt8](repeating: 0, count: w * h * 4)
    let drawn: Bool = px.withUnsafeMutableBytes { buf in
        guard let ctx = CGContext(data: buf.baseAddress, width: w, height: h, bitsPerComponent: 8, bytesPerRow: w * 4,
                                  space: CGColorSpaceCreateDeviceRGB(),
                                  bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue) else { return false }
        ctx.draw(cg, in: CGRect(x: 0, y: 0, width: w, height: h))
        return true
    }
    guard drawn else { return image }

    // JPEG ringing leaves bars a few levels above zero.
    func dark(_ x: Int, _ y: Int) -> Bool {
        let i = (y * w + x) * 4
        return px[i] <= 40 && px[i + 1] <= 40 && px[i + 2] <= 40
    }
    func rowDark(_ y: Int) -> Bool { (0..<w).allSatisfy { dark($0, y) } }
    func colDark(_ x: Int, _ y0: Int, _ y1: Int) -> Bool { (y0..<y1).allSatisfy { dark(x, $0) } }
    func balanced(_ a: Int, _ b: Int, _ size: Int) -> Bool { max(a, b) >= 2 && abs(a - b) <= max(2, size / 30) }

    var top = 0
    while top < h / 3 && rowDark(top) { top += 1 }
    var bottom = 0
    while bottom < h / 3 && rowDark(h - 1 - bottom) { bottom += 1 }
    if !balanced(top, bottom, h) { top = 0; bottom = 0 }

    var left = 0
    while left < w / 3 && colDark(left, top, h - bottom) { left += 1 }
    var right = 0
    while right < w / 3 && colDark(w - 1 - right, top, h - bottom) { right += 1 }
    if !balanced(left, right, w) { left = 0; right = 0 }

    if top + bottom + left + right == 0 { return image }
    // One more pixel in from each trimmed edge: the band's soft JPEG edge.
    let x = left > 0 ? left + 1 : 0
    let y = top > 0 ? top + 1 : 0
    let cw = w - x - (right > 0 ? right + 1 : 0)
    let ch = h - y - (bottom > 0 ? bottom + 1 : 0)
    guard cw >= w / 3, ch >= h / 3, let cropped = cg.cropping(to: CGRect(x: x, y: y, width: cw, height: ch)) else { return image }
    return UIImage(cgImage: cropped, scale: image.scale, orientation: image.imageOrientation)
}
