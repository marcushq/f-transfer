package dev.fujitransfer.protocol

import java.io.DataInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.SocketFactory

class PtpResponse(val code: Int, val params: IntArray, val payload: ByteArray) {
    val ok get() = code == Fuji.RC_OK
}

/**
 * One TCP socket speaking USB-style PTP containers. Not thread safe: callers serialize access.
 */
class PtpLink(private val log: (String) -> Unit) {
    private var socket: Socket? = null
    private var input: DataInputStream? = null
    private var output: OutputStream? = null

    /** Transaction id of the next command. Fuji starts OpenSession at 1, not 0. */
    var tid = 1

    val isOpen get() = socket?.isConnected == true && socket?.isClosed == false

    /** Response code of the last transaction. */
    var lastCode = 0
        private set

    private var lastOp = 0
    private var lastParam0 = 0

    /** The command waiting for an answer, e.g. "0x100A(#6044)", or null. Names the culprit of a stall. */
    @Volatile var inFlight: String? = null
        private set

    /** True when the previous command was [code] for [handle] and it succeeded. */
    fun lastWas(code: Int, handle: Int) = lastOp == code && lastParam0 == handle && lastCode == Fuji.RC_OK

    fun open(factory: SocketFactory, host: String, port: Int, readTimeoutMs: Int = 15_000) {
        close()
        val s = factory.createSocket()
        s.tcpNoDelay = true
        s.receiveBufferSize = 1 shl 20
        s.connect(InetSocketAddress(host, port), 8_000)
        s.soTimeout = readTimeoutMs
        socket = s
        input = DataInputStream(s.getInputStream().buffered(1 shl 16))
        output = s.getOutputStream()
    }

    fun setReadTimeout(ms: Int) {
        socket?.soTimeout = ms
    }

    fun close() {
        try {
            socket?.close()
        } catch (_: IOException) {
        }
        socket = null
        input = null
        output = null
    }

    fun write(bytes: ByteArray) {
        val out = output ?: throw IOException("Chưa kết nối")
        out.write(bytes)
        out.flush()
    }

    /** Reads one length-prefixed packet (the length field is included in the result). */
    fun readPacket(maxLength: Int = Fuji.MAX_PARTIAL * 8 + 65_536): ByteArray {
        val inp = input ?: throw IOException("Chưa kết nối")
        val head = ByteArray(4)
        inp.readFully(head)
        val length = LE.u32(head, 0)
        if (length < 4 || length > maxLength) throw IOException("Độ dài gói không hợp lệ: $length")
        val packet = ByteArray(length)
        System.arraycopy(head, 0, packet, 0, 4)
        inp.readFully(packet, 4, length - 4)
        return packet
    }

    private fun container(type: Int, code: Int, tid: Int, body: ByteArray): ByteArray =
        LEWriter().u32(12 + body.size).u16(type).u16(code).u32(tid).bytes(body).toByteArray()

    /**
     * Sends a command (plus an optional data phase in the same write) and reads the
     * optional incoming data phase and the response.
     */
    fun transaction(code: Int, params: IntArray = IntArray(0), dataOut: ByteArray? = null): PtpResponse {
        val id = tid++
        inFlight = Fuji.hex(code) + params.joinToString(",", "(", ")") { if (it in 1 until 0x8000) "#$it" else Fuji.hex(it) }
        lastOp = 0
        val p = LEWriter().apply { params.forEach { u32(it) } }.toByteArray()
        var packet = container(Fuji.TYPE_COMMAND, code, id, p)
        if (dataOut != null) packet += container(Fuji.TYPE_DATA, code, id, dataOut)
        write(packet)

        var payload = ByteArray(0)
        var reply = readPacket()
        if (LE.u16(reply, 4) == Fuji.TYPE_DATA) {
            payload = reply.copyOfRange(12, reply.size)
            reply = readPacket()
        }
        val type = LE.u16(reply, 4)
        if (type != Fuji.TYPE_RESPONSE) {
            throw IOException("Mong đợi gói phản hồi cho ${Fuji.hex(code)}, nhận loại $type: ${reply.hexDump(32)}")
        }
        val rc = LE.u16(reply, 6)
        lastCode = rc
        lastOp = code
        lastParam0 = params.firstOrNull() ?: 0
        inFlight = null
        val count = (reply.size - 12) / 4
        val outParams = IntArray(count) { LE.u32(reply, 12 + it * 4) }
        if (rc != Fuji.RC_OK) log("  ${Fuji.hex(code)} → ${Fuji.hex(rc)}")
        return PtpResponse(rc, outParams, payload)
    }
}
