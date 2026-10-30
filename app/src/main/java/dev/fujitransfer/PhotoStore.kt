package dev.fujitransfer

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import java.io.OutputStream

/** Saves into the shared gallery (Pictures/Fuji Transfer) and remembers what was copied. */
class PhotoStore(private val context: Context) {
    private val prefs = context.getSharedPreferences("downloaded", Context.MODE_PRIVATE)
    /** Saved name → URI of a copy found cut short; replaced when that photo is downloaded again. */
    private val cutShort = context.getSharedPreferences("cut_short", Context.MODE_PRIVATE)

    class Pending(val uri: Uri, val stream: OutputStream, val fileName: String)

    fun key(fileName: String, captureDate: String) = "$captureDate/$fileName"

    fun isDownloaded(key: String) = prefs.getBoolean(key, false)

    /** The camera access point from the last Bluetooth wake, for when Bluetooth can't find it. */
    var lastAp: CameraAp?
        get() {
            val ssid = prefs.getString("ap_ssid", null) ?: return null
            return CameraAp(ssid, prefs.getString("ap_password", "") ?: "")
        }
        set(v) = prefs.edit().putString("ap_ssid", v?.ssid).putString("ap_password", v?.password).apply()

    var transferSize: dev.fujitransfer.protocol.TransferSize
        get() = prefs.getString("transfer_size", null)
            ?.let { runCatching { dev.fujitransfer.protocol.TransferSize.valueOf(it) }.getOrNull() }
            ?: dev.fujitransfer.protocol.TransferSize.ORIGINAL
        set(v) = prefs.edit().putString("transfer_size", v.name).apply()

    /** Rotate grid thumbnails from each file's EXIF (costs one small read per photo). */
    var rotateThumbs: Boolean
        get() = prefs.getBoolean("rotate_thumbs", true)
        set(v) = prefs.edit().putBoolean("rotate_thumbs", v).apply()

    var hideDownloaded: Boolean
        get() = prefs.getBoolean("hide_downloaded", false)
        set(v) = prefs.edit().putBoolean("hide_downloaded", v).apply()

    var convertHeif: Boolean
        get() = prefs.getBoolean("convert_heif", true)
        set(v) = prefs.edit().putBoolean("convert_heif", v).apply()

    /**
     * Fujifilm names HEIF files .HIF, an extension Android and most gallery apps don't know,
     * so the copy on the phone gets the standard .HEIF.
     */
    fun savedName(fileName: String): String {
        val ext = fileName.substringAfterLast('.', "")
        return if (ext.equals("hif", ignoreCase = true)) fileName.dropLast(ext.length) + "HEIF" else fileName
    }

    fun begin(cameraName: String): Pending {
        val fileName = savedName(cameraName)
        val ext = fileName.substringAfterLast('.', "").lowercase()
        val isVideo = ext in setOf("mov", "mp4")
        val mime = when (ext) {
            "jpg", "jpeg" -> "image/jpeg"
            "raf" -> "image/x-fuji-raf"
            "heif", "heic" -> "image/heif"
            "mov" -> "video/quicktime"
            "mp4" -> "video/mp4"
            else -> "application/octet-stream"
        }
        val collection = if (isVideo) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        }
        val dir = if (isVideo) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "$dir/Fuji Transfer")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = context.contentResolver.insert(collection, values)
            ?: throw IllegalStateException("Không tạo được tệp $fileName")
        val stream = context.contentResolver.openOutputStream(uri)
            ?: throw IllegalStateException("Không ghi được tệp $fileName")
        return Pending(uri, stream.buffered(1 shl 20), fileName)
    }

    fun commit(p: Pending, key: String) {
        p.stream.close()
        val values = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
        context.contentResolver.update(p.uri, values, null, null)
        prefs.edit().putBoolean(key, true).apply()
        replaceCutShort(p)
    }

    /**
     * The new copy is whole: delete the cut-short one it replaces, and take its name back (the
     * new file was saved as "DSCF0944 (1).JPG" because the old one was still there).
     */
    private fun replaceCutShort(p: Pending) {
        val old = cutShort.getString(p.fileName, null) ?: return
        try {
            context.contentResolver.delete(Uri.parse(old), null, null)
            val rename = ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME, p.fileName) }
            context.contentResolver.update(p.uri, rename, null, null)
        } catch (_: Exception) {
            // Not ours any more (e.g. after a reinstall) or already gone: keep both copies.
        }
        cutShort.edit().remove(p.fileName).apply()
    }

    /**
     * Versions before 0.8.1 stopped at the length the camera announced, which an X-S20 rounds —
     * sometimes 2.5 MB short of the real file. Finds this app's saved JPEGs that don't end with
     * the FF D9 marker, forgets they were downloaded (so "Chọn ảnh mới" picks them again) and
     * remembers them so the next download replaces them. Returns their names.
     */
    fun findCutShortJpegs(): List<String> {
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val found = mutableListOf<Pair<String, Uri>>()
        context.contentResolver.query(
            collection,
            arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME),
            "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? AND ${MediaStore.MediaColumns.MIME_TYPE} = ?",
            arrayOf("${Environment.DIRECTORY_PICTURES}/Fuji Transfer%", "image/jpeg"),
            null,
        )?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val nameCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            while (c.moveToNext()) {
                val uri = android.content.ContentUris.withAppendedId(collection, c.getLong(idCol))
                val name = c.getString(nameCol) ?: continue
                if (!endsLikeJpeg(uri)) found += name to uri
            }
        }
        if (found.isEmpty()) return emptyList()
        val keys = prefs.all.keys
        val edit = prefs.edit()
        val cut = cutShort.edit()
        for ((name, uri) in found) {
            keys.filter { it.endsWith("/$name") }.forEach { edit.remove(it) }
            cut.putString(name, uri.toString())
        }
        edit.apply()
        cut.apply()
        return found.map { it.first }
    }

    private fun endsLikeJpeg(uri: Uri): Boolean = try {
        context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
            java.io.FileInputStream(pfd.fileDescriptor).channel.use { ch ->
                val size = ch.size()
                if (size < 2) return@use false
                val buf = java.nio.ByteBuffer.allocate(2)
                ch.read(buf, size - 2)
                (buf.get(0).toInt() and 0xFF) == 0xFF && (buf.get(1).toInt() and 0xFF) == 0xD9
            }
        } ?: true
    } catch (_: Exception) {
        true // Can't read it: leave it alone.
    }

    /**
     * A saved copy was deleted from the phone: stop counting its camera photo as downloaded.
     * The saved name may differ from the camera's ("_S" resized copies, " (1)" duplicates,
     * .HIF saved as .HEIF or converted to .JPG), so every camera name it could have come from is forgotten.
     */
    fun forget(savedName: String) {
        val stem = savedName.substringBeforeLast('.')
            .replace(Regex(" \\(\\d+\\)$"), "")
            .replace(Regex("_(S|XS)$"), "")
        val ext = savedName.substringAfterLast('.', "")
        val names = setOf("$stem.$ext", "$stem.HIF", "$stem.hif")
        val edit = prefs.edit()
        prefs.all.keys.filter { k -> names.any { k.endsWith("/$it", ignoreCase = true) } }.forEach { edit.remove(it) }
        edit.apply()
    }

    /** How many photos the grid showed last time for [camera], so the next session starts the same. */
    fun shownCount(camera: String) = prefs.getInt("shown_$camera", 0)
    fun setShownCount(camera: String, count: Int) = prefs.edit().putInt("shown_$camera", count).apply()

    /** True once the one-time check for cut-short JPEGs has run. */
    var cutShortChecked: Boolean
        get() = prefs.getBoolean("cut_short_checked_v1", false)
        set(v) = prefs.edit().putBoolean("cut_short_checked_v1", v).apply()

    /** Copies a finished local file (e.g. a converted JPEG) into the gallery. */
    fun saveFile(fileName: String, file: java.io.File, key: String) {
        val p = begin(fileName)
        try {
            file.inputStream().use { it.copyTo(p.stream, 1 shl 16) }
            commit(p, key)
        } catch (e: Throwable) {
            abort(p)
            throw e
        }
    }

    fun abort(p: Pending) {
        try {
            p.stream.close()
        } catch (_: Exception) {
        }
        context.contentResolver.delete(p.uri, null, null)
    }
}
