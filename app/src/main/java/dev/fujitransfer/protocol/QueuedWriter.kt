package dev.fujitransfer.protocol

import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Writes to [out] on its own thread, so the next read from the camera goes out while the
 * last megabyte is still being saved — the camera link and the phone's storage work at the
 * same time instead of taking turns. At most [depth] chunks wait; past that the reader waits
 * too, so memory stays bounded.
 *
 * Chunks are queued without copying: the caller must not reuse an array after writing it.
 */
class QueuedWriter(private val out: OutputStream, depth: Int = 4) {
    private class Chunk(val bytes: ByteArray, val len: Int)

    private val queue = ArrayBlockingQueue<Chunk>(depth)
    private val end = Chunk(ByteArray(0), -1)
    @Volatile private var error: Throwable? = null
    @Volatile private var stopped = false

    private val thread = Thread({
        try {
            while (true) {
                val c = queue.take()
                if (c === end || stopped) break
                out.write(c.bytes, 0, c.len)
            }
        } catch (_: InterruptedException) {
        } catch (t: Throwable) {
            error = t
        }
    }, "photo-writer").apply { isDaemon = true; start() }

    fun write(bytes: ByteArray, len: Int) {
        rethrow()
        while (!queue.offer(Chunk(bytes, len), 200, TimeUnit.MILLISECONDS)) {
            rethrow()
            if (!thread.isAlive) throw IOException("Ghi ảnh bị dừng")
        }
    }

    /** Waits until everything queued is written; throws what the writer hit, if anything. */
    fun finish() {
        queue.put(end)
        thread.join()
        rethrow()
    }

    /** Stops without writing what is still queued (the file is being thrown away). */
    fun abort() {
        if (!thread.isAlive) return
        stopped = true
        queue.clear()
        queue.offer(end)
        thread.join(2_000)
    }

    private fun rethrow() {
        error?.let { throw if (it is IOException) it else IOException(it) }
    }
}
