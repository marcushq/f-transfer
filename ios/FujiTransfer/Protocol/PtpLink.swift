import Darwin
import Foundation

struct PtpResponse {
    let code: Int
    let params: [UInt32]
    let payload: [UInt8]
    var ok: Bool { code == Fuji.rcOK }
}

/// One TCP socket speaking USB-style PTP containers, with blocking BSD sockets (like the
/// Android app's java.net.Socket). Not thread safe: callers serialize access.
final class PtpLink {
    private var fd: Int32 = -1
    private let log: (String) -> Void

    /// Transaction id of the next command. Fuji starts OpenSession at 1, not 0.
    var tid: UInt32 = 1
    /// Response code of the last transaction.
    private(set) var lastCode = 0
    private var lastOp = 0
    private var lastParam0: UInt32 = 0
    /// The command waiting for an answer, e.g. "0x100A(#6044)". Names the culprit of a stall.
    private(set) var inFlight: String?

    var isOpen: Bool { fd >= 0 }

    init(log: @escaping (String) -> Void) { self.log = log }

    /// True when the previous command was [code] for [handle] and it succeeded.
    func lastWas(_ code: Int, _ handle: UInt32) -> Bool { lastOp == code && lastParam0 == handle && lastCode == Fuji.rcOK }

    func open(host: String, port: UInt16, connectTimeout: Double = 8, readTimeout: Double = 15) throws {
        close()
        let s = socket(AF_INET, SOCK_STREAM, IPPROTO_TCP)
        guard s >= 0 else { throw PtpError.io("Không tạo được kết nối (errno \(errno))") }
        var one: Int32 = 1
        setsockopt(s, IPPROTO_TCP, TCP_NODELAY, &one, socklen_t(MemoryLayout<Int32>.size))
        setsockopt(s, SOL_SOCKET, SO_NOSIGPIPE, &one, socklen_t(MemoryLayout<Int32>.size))
        var buffer: Int32 = 1 << 20
        setsockopt(s, SOL_SOCKET, SO_RCVBUF, &buffer, socklen_t(MemoryLayout<Int32>.size))

        var addr = sockaddr_in()
        addr.sin_len = UInt8(MemoryLayout<sockaddr_in>.size)
        addr.sin_family = sa_family_t(AF_INET)
        addr.sin_port = port.bigEndian
        inet_pton(AF_INET, host, &addr.sin_addr)

        // Non-blocking connect so it can time out, then back to blocking.
        let flags = fcntl(s, F_GETFL, 0)
        _ = fcntl(s, F_SETFL, flags | O_NONBLOCK)
        let r = withUnsafePointer(to: &addr) {
            $0.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                Darwin.connect(s, $0, socklen_t(MemoryLayout<sockaddr_in>.size))
            }
        }
        if r != 0 {
            guard errno == EINPROGRESS else {
                let e = errno
                Darwin.close(s)
                throw PtpError.io("Không kết nối được \(host):\(port) (errno \(e))")
            }
            var pfd = pollfd(fd: s, events: Int16(POLLOUT), revents: 0)
            let ready = poll(&pfd, 1, Int32(connectTimeout * 1000))
            var err: Int32 = 0
            var len = socklen_t(MemoryLayout<Int32>.size)
            getsockopt(s, SOL_SOCKET, SO_ERROR, &err, &len)
            if ready <= 0 || err != 0 {
                Darwin.close(s)
                throw ready <= 0 ? PtpError.timeout : PtpError.io("Không kết nối được \(host):\(port) (errno \(err))")
            }
        }
        _ = fcntl(s, F_SETFL, flags)
        fd = s
        setReadTimeout(readTimeout)
    }

    func setReadTimeout(_ seconds: Double) {
        guard fd >= 0 else { return }
        var tv = timeval(tv_sec: Int(seconds), tv_usec: Int32((seconds - Double(Int(seconds))) * 1_000_000))
        setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &tv, socklen_t(MemoryLayout<timeval>.size))
    }

    func close() {
        if fd >= 0 { Darwin.close(fd) }
        fd = -1
    }

    func write(_ bytes: [UInt8]) throws {
        guard fd >= 0 else { throw PtpError.io("Chưa kết nối") }
        var sent = 0
        while sent < bytes.count {
            let n = bytes.withUnsafeBytes { send(fd, $0.baseAddress! + sent, bytes.count - sent, 0) }
            if n < 0 && errno == EINTR { continue }
            if n <= 0 { throw PtpError.io("Mất kết nối với máy ảnh (gửi, errno \(errno))") }
            sent += n
        }
    }

    private func readFully(_ count: Int) throws -> [UInt8] {
        guard fd >= 0 else { throw PtpError.io("Chưa kết nối") }
        var buf = [UInt8](repeating: 0, count: count)
        var got = 0
        while got < count {
            let n = buf.withUnsafeMutableBytes { recv(fd, $0.baseAddress! + got, count - got, 0) }
            if n == 0 { throw PtpError.io("Máy ảnh đã đóng kết nối") }
            if n < 0 {
                if errno == EINTR { continue }
                if errno == EAGAIN || errno == EWOULDBLOCK { throw PtpError.timeout }
                throw PtpError.io("Mất kết nối với máy ảnh (nhận, errno \(errno))")
            }
            got += n
        }
        return buf
    }

    /// Reads one length-prefixed packet (the length field is included in the result).
    func readPacket(maxLength: Int = Fuji.maxPartial * 8 + 65_536) throws -> [UInt8] {
        let head = try readFully(4)
        let length = Int(head.u32(0))
        guard length >= 4, length <= maxLength else { throw PtpError.io("Độ dài gói không hợp lệ: \(length)") }
        let rest = try readFully(length - 4)
        return head + rest
    }

    private func container(_ type: Int, _ code: Int, _ tid: UInt32, _ body: [UInt8]) -> [UInt8] {
        var w = LEWriter()
        w.u32(UInt32(12 + body.count))
        w.u16(type)
        w.u16(code)
        w.u32(tid)
        w.append(body)
        return w.bytes
    }

    /// Sends a command (plus an optional data phase in the same write) and reads the
    /// optional incoming data phase and the response.
    @discardableResult
    func transaction(_ code: Int, _ params: [UInt32] = [], data: [UInt8]? = nil) throws -> PtpResponse {
        let id = tid
        tid &+= 1
        inFlight = Fuji.hex(code) + "(" + params.map { $0 > 0 && $0 < 0x8000 ? "#\($0)" : Fuji.hex($0) }.joined(separator: ",") + ")"
        lastOp = 0
        var p = LEWriter()
        params.forEach { p.u32($0) }
        var packet = container(Fuji.typeCommand, code, id, p.bytes)
        if let data { packet += container(Fuji.typeData, code, id, data) }
        try write(packet)

        var payload: [UInt8] = []
        var reply = try readPacket()
        if reply.u16(4) == Fuji.typeData {
            payload = Array(reply[12...])
            reply = try readPacket()
        }
        let type = reply.u16(4)
        guard type == Fuji.typeResponse else {
            throw PtpError.io("Mong đợi gói phản hồi cho \(Fuji.hex(code)), nhận loại \(type): \(reply.hexDump(32))")
        }
        let rc = reply.u16(6)
        lastCode = rc
        lastOp = code
        lastParam0 = params.first ?? 0
        inFlight = nil
        let out = (0..<((reply.count - 12) / 4)).map { reply.u32(12 + $0 * 4) }
        if rc != Fuji.rcOK { log("  \(Fuji.hex(code)) → \(Fuji.hex(rc))") }
        return PtpResponse(code: rc, params: out, payload: payload)
    }
}
