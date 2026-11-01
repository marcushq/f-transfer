import Foundation

/// Constants for Fujifilm's Wi-Fi flavour of PTP — the same values as the Android app's
/// `protocol/Fuji.kt`, which follow the MIT-licensed libfuji (github.com/petabyt/libfuji).
///
/// Fuji uses an ISO PTP/IP style init handshake, then plain USB-style PTP containers
/// (12-byte little-endian header) on the same TCP socket.
enum Fuji {
    static let cameraHost = "192.168.0.1"
    static let cmdPort: UInt16 = 55740
    static let eventPort: UInt16 = 55741
    static let liveviewPort: UInt16 = 55742

    static let protocolVersion: UInt32 = 0x8f53_e4f2
    static let camConnectRemoteVer: UInt32 = 0x2000C

    /// GetPartialObject window. Fuji caps reads at 1 MB.
    static let maxPartial = 0x100000
    /// Ceiling when reading past a too-small announced length; far above any X-series JPEG.
    static let maxJpegBytes = 64 * 1024 * 1024

    // Container types
    static let typeCommand = 1
    static let typeData = 2
    static let typeResponse = 3

    // PTP/IP init packet types
    static let initCommandReq: UInt32 = 1
    static let initFail: UInt32 = 5

    // Operation codes
    static let ocOpenSession = 0x1002
    static let ocCloseSession = 0x1003
    static let ocGetObjectInfo = 0x1008
    static let ocGetThumb = 0x100A
    static let ocGetPropValue = 0x1015
    static let ocSetPropValue = 0x1016
    static let ocTerminateOpenCapture = 0x1018
    static let ocGetPartialObject = 0x101B
    static let ocInitiateOpenCapture = 0x101C
    static let ocGetObjectPropValue = 0x9803

    // XApp-era vendor operations (X-S20, X-T5, X-H2, X100VI…)
    static let ocGetImageImportFolders = 0x9050
    static let ocGetImageImportDates = 0x9053
    static let ocGetExtensionObjectInfo = 0x9054
    static let ocGetExtensionThumb = 0x9055

    // Object properties
    static let opcObjectSize: UInt32 = 0xDC04
    static let opcObjectFileName: UInt32 = 0xDC07
    static let opcDateCreated: UInt32 = 0xDC08
    /// Length of the resized copy the camera would send (XApp asks this before a resized read).
    static let opcCompressedSize: UInt32 = 0xD802

    // Response codes
    static let rcOK = 0x2001
    static let rcSessionAlreadyOpen = 0x201E
    static let rcInvalidObjectHandle = 0x2009
    static let rcStoreNotAvailable = 0x2013
    static let rcDeviceBusy = 0x2019
    static let rcInvalidParameter = 0x201D

    /// Codes that mean "not now" rather than "never": the body is still reading the card.
    static func isTransient(_ rc: Int) -> Bool { rc == rcDeviceBusy || rc == rcStoreNotAvailable }

    // Device properties
    static let dpcEventsList: UInt32 = 0xD212
    static let dpcObjectCount: UInt32 = 0xD222
    /// XApp's ImageForceCompression: 2 = send the original, 1 = resized.
    static let dpcCompressSmall: UInt32 = 0xD226
    static let dpcCorrectFileSize: UInt32 = 0xD227
    /// XApp's Resize: 1 = S, 0 = XS (used while D226 = 1).
    static let dpcResizeRate: UInt32 = 0xD22E
    static let dpcUnknownD22B: UInt32 = 0xD22B
    static let dpcImageImportCount: UInt32 = 0xD620
    static let dpcImageImportHandles: UInt32 = 0xD621
    static let dpcRemotePhotoViewEx: UInt32 = 0xDF28
    static let dpcCameraState: UInt32 = 0xDF00
    static let dpcClientState: UInt32 = 0xDF01
    static let dpcImageGetVersion: UInt32 = 0xDF21
    static let dpcGetObjectVersion: UInt32 = 0xDF22
    static let dpcRemoteVersion: UInt32 = 0xDF24
    static let dpcRemoteGetObjectVersion: UInt32 = 0xDF25

    // Camera states (DF00)
    static let stateWaitForAccess = 0
    static let stateMultipleTransfer = 1
    static let statePcAutoSave = 3
    static let stateRemoteAccess = 6

    // Client states (DF01)
    static let clientViewMultiple = 1
    static let clientViewAllImgs = 2
    static let clientOldRemote = 3
    static let clientRemoteMode = 5
    static let clientRemoteImgView = 11
    static let clientRemoteImgViewXapp = 20

    static func hex(_ v: Int) -> String { "0x" + String(v, radix: 16, uppercase: true) }
    static func hex(_ v: UInt32) -> String { hex(Int(v)) }
}

enum PtpError: LocalizedError {
    /// The socket failed or the camera closed it.
    case io(String)
    /// The camera didn't answer in time.
    case timeout
    /// The camera answered with an error or something unexpected.
    case camera(String, code: Int = 0)
    case cancelled

    var errorDescription: String? {
        switch self {
        case .io(let m): return m
        case .timeout: return "Máy ảnh không phản hồi. Hãy kiểm tra máy ảnh vẫn đang ở màn hình kết nối không dây."
        case .camera(let m, _): return m
        case .cancelled: return "Đã huỷ"
        }
    }
}

/// What the camera sends over Wi-Fi. Resized copies are made by the camera itself, like XApp's
/// "Resize" setting, and only for JPEG; HEIF and RAF always come as shot.
enum TransferSize: String, CaseIterable {
    case original, small, extraSmall

    /// D22E for a resized copy, nil for the original.
    var resizeRate: Int? {
        switch self {
        case .original: return nil
        case .small: return 1
        case .extraSmall: return 0
        }
    }
}
