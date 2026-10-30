package dev.fujitransfer

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/**
 * Remembers each photo's name, capture date, orientation and thumbnail between connections,
 * so a card that was read once shows up at once next time (on an X-S20 every photo costs a
 * slow GetObjectInfo plus a GetThumb). Entries are only trusted after the camera confirms a
 * few handles still have the same file names, which catches swapped cards.
 *
 * Built to be cheap to read:
 *  - `meta-<camera>.tsv`: one line per photo (handle, name, date, orientation, thumbnail
 *    offset, thumbnail length). ~60 bytes a line, read entirely into memory once per
 *    connection (1,822 photos ≈ 110 KB, a few ms).
 *  - `meta-<camera>.pack`: every thumbnail back to back in one file, kept open and read with
 *    a single positional read per thumbnail. No per-photo file to open, no directory listing.
 *    Superseded thumbnails (new card) are dropped when the pack is compacted on save.
 */
class MetadataCache(
    private val file: File,
    private val packFile: File = File(file.parentFile, file.nameWithoutExtension + ".pack"),
    /** Folder of one-JPEG-per-photo thumbnails written by v0.10.x, imported once. */
    private val legacyThumbDir: File = File(file.parentFile, file.nameWithoutExtension + "-thumbs"),
) {
    data class Entry(val name: String, val date: String, val orientation: Int?)

    /** Where a thumbnail sits in the pack. */
    private data class Slot(val offset: Long, val length: Int)

    private val entries = HashMap<Int, Entry>()
    private val slots = HashMap<Int, Slot>()
    private var dirty = 0
    private var pack: FileChannel? = null

    val size get() = entries.size

    init {
        try {
            if (file.exists()) file.forEachLine { line ->
                val p = line.split('\t')
                if (p.size >= 3) {
                    val handle = p[0].toIntOrNull() ?: return@forEachLine
                    entries[handle] = Entry(p[1], p[2], p.getOrNull(3)?.toIntOrNull())
                    val off = p.getOrNull(4)?.toLongOrNull()
                    val len = p.getOrNull(5)?.toIntOrNull()
                    if (off != null && len != null && len > 0) slots[handle] = Slot(off, len)
                }
            }
            // A pack shorter than the index says (crash mid-write): drop slots past its end.
            val packLen = packFile.length()
            slots.entries.removeAll { it.value.offset + it.value.length > packLen }
        } catch (_: Exception) {
            entries.clear() // A broken cache only costs a re-read.
            slots.clear()
        }
        importLegacyThumbs()
    }

    @Synchronized fun get(handle: Int): Entry? = entries[handle]

    /** Records what the camera said; returns true when enough changed to be worth saving. */
    @Synchronized fun put(handle: Int, name: String, date: String, orientation: Int? = entries[handle]?.takeIf { it.name == name }?.orientation): Boolean {
        val e = Entry(name, date, orientation)
        // The handle now holds another photo (new card): its old thumbnail is useless.
        if (entries[handle]?.name != null && entries[handle]?.name != name) slots.remove(handle)
        if (entries[handle] != e) {
            entries[handle] = e
            dirty++
        }
        return dirty >= 100
    }

    /** Stores [orientation]; creates the entry if the date isn't known yet ([put] keeps it). */
    @Synchronized fun setOrientation(handle: Int, name: String, orientation: Int?) {
        val old = entries[handle]
        if (old != null && old.name != name) slots.remove(handle)
        val next = if (old != null && old.name == name) old.copy(orientation = orientation) else Entry(name, "", orientation)
        if (old != next) {
            entries[handle] = next
            dirty++
        }
    }

    /** Entries for these handles, for laying out the grid straight away. */
    @Synchronized fun known(handles: Collection<Int>): Map<Int, Entry> =
        handles.mapNotNull { h -> entries[h]?.takeIf { it.date.isNotEmpty() }?.let { h to it } }.toMap()

    /** Name, date, thumbnail and (if [needOrientation]) orientation are all known for [handle]. */
    @Synchronized fun isComplete(handle: Int, needOrientation: Boolean): Boolean {
        val e = entries[handle] ?: return false
        return e.date.isNotEmpty() && slots.containsKey(handle) && (!needOrientation || e.orientation != null)
    }

    @Synchronized fun hasThumb(handle: Int, name: String) = entries[handle]?.name == name && slots.containsKey(handle)

    private fun channel(): FileChannel {
        pack?.let { if (it.isOpen) return it }
        packFile.parentFile?.mkdirs()
        return RandomAccessFile(packFile, "rw").channel.also { pack = it }
    }

    /** The camera's thumbnail for [handle] as it was when that handle was [name], or null. */
    @Synchronized fun thumb(handle: Int, name: String): ByteArray? {
        if (entries[handle]?.name != name) return null
        val s = slots[handle] ?: return null
        return try {
            val buf = ByteBuffer.allocate(s.length)
            val ch = channel()
            var pos = s.offset
            while (buf.hasRemaining()) {
                val n = ch.read(buf, pos)
                if (n <= 0) return null
                pos += n
            }
            buf.array()
        } catch (_: Exception) {
            null
        }
    }

    @Synchronized fun saveThumb(handle: Int, name: String, bytes: ByteArray) {
        if (bytes.isEmpty()) return
        try {
            val e = entries[handle]
            if (e == null || e.name != name) {
                if (e != null) slots.remove(handle)
                entries[handle] = Entry(name, "", null)
            }
            val ch = channel()
            val offset = ch.size()
            val buf = ByteBuffer.wrap(bytes)
            var pos = offset
            while (buf.hasRemaining()) pos += ch.write(buf, pos)
            slots[handle] = Slot(offset, bytes.size)
            dirty++
        } catch (_: Exception) {
        }
    }

    /** Everything this cache knows about the camera: names, dates, thumbnails. */
    @Synchronized fun clear() {
        entries.clear()
        slots.clear()
        dirty = 0
        try { pack?.close() } catch (_: Exception) {}
        pack = null
        file.delete()
        packFile.delete()
        legacyThumbDir.deleteRecursively()
    }

    /** Bytes used on the phone (index + thumbnail pack). */
    fun diskBytes(): Long = file.length() + packFile.length()

    @Synchronized fun save() {
        if (dirty == 0) return
        try {
            compactIfWasteful()
            pack?.force(false)
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.bufferedWriter().use { w ->
                val sb = StringBuilder(64)
                for ((h, e) in entries) {
                    sb.setLength(0)
                    // Tabs/newlines never appear in Fuji names or dates; strip them anyway.
                    sb.append(h).append('\t').append(e.name.replace('\t', ' ').replace('\n', ' '))
                        .append('\t').append(e.date).append('\t').append(e.orientation ?: "")
                    slots[h]?.let { sb.append('\t').append(it.offset).append('\t').append(it.length) }
                    sb.append('\n')
                    w.write(sb.toString())
                }
            }
            replace(tmp, file)
            dirty = 0
        } catch (_: Exception) {
        }
    }

    /** Rewrites the pack without superseded thumbnails once they outweigh the live ones. */
    private fun compactIfWasteful() {
        val total = packFile.length()
        val live = slots.values.sumOf { it.length.toLong() }
        if (total < 8L * 1024 * 1024 || total - live < live) return
        val tmp = File(packFile.parentFile, packFile.name + ".tmp")
        val moved = HashMap<Int, Slot>()
        RandomAccessFile(tmp, "rw").channel.use { out ->
            val src = channel()
            for ((h, s) in slots) {
                val buf = ByteBuffer.allocate(s.length)
                src.read(buf, s.offset)
                buf.flip()
                val at = out.size()
                while (buf.hasRemaining()) out.write(buf, out.size())
                moved[h] = Slot(at, s.length)
            }
        }
        pack?.close()
        pack = null
        if (replace(tmp, packFile)) {
            slots.clear()
            slots.putAll(moved)
        }
    }

    /** Moves [from] over [to], replacing it (File.renameTo can't replace on every platform). */
    private fun replace(from: File, to: File): Boolean = try {
        java.nio.file.Files.move(from.toPath(), to.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        true
    } catch (_: Exception) {
        false
    }

    /** v0.10.x kept one JPEG per photo in a folder; move them into the pack once. */
    private fun importLegacyThumbs() {
        val files = legacyThumbDir.listFiles() ?: return
        for (f in files) {
            val handle = f.name.substringBefore('_').toIntOrNull() ?: continue
            val e = entries[handle] ?: continue
            val expected = "${handle}_" + e.name.replace(Regex("[^A-Za-z0-9._-]"), "_") + ".jpg"
            if (f.name == expected && !slots.containsKey(handle)) {
                try { saveThumb(handle, e.name, f.readBytes()) } catch (_: Exception) {}
            }
        }
        legacyThumbDir.deleteRecursively()
        if (dirty > 0) save()
    }

    /** Releases the open pack file (end of a session). */
    @Synchronized fun close() {
        save()
        try { pack?.close() } catch (_: Exception) {}
        pack = null
    }

    companion object {
        fun forCamera(dir: File, cameraName: String): MetadataCache {
            val safe = cameraName.ifBlank { "camera" }.replace(Regex("[^A-Za-z0-9_-]"), "_")
            return MetadataCache(File(dir.apply { mkdirs() }, "meta-$safe.tsv"))
        }
    }
}
