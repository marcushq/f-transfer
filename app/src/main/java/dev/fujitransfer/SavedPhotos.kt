package dev.fujitransfer

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.Size
import androidx.exifinterface.media.ExifInterface

/** A photo this app saved to the phone's gallery. */
data class SavedPhoto(
    val id: Long,
    val uri: Uri,
    val name: String,
    val mime: String,
    /** When it was shot (or saved, if the date isn't known), ms since epoch. */
    val takenMs: Long,
    val bytes: Long,
    val width: Int,
    val height: Int,
    /** When it arrived on the phone, ms since epoch. */
    val addedMs: Long = takenMs,
) {
    val kind: String get() = when {
        mime == "image/jpeg" -> "JPEG"
        mime.contains("heif") || mime.contains("heic") -> "HEIF"
        mime.contains("raf") || name.endsWith(".RAF", ignoreCase = true) -> "RAF"
        else -> name.substringAfterLast('.', "").uppercase()
    }
}

/** Shooting details for the viewer, already formatted. */
data class ShotDetails(val line: String, val camera: String, val lens: String)

/**
 * Reads what this app saved under Pictures/Fuji Transfer through MediaStore. Without the
 * photo permission Android only shows the files this install created; with it, also those
 * saved before the app was reinstalled.
 */
class SavedPhotos(private val context: Context) {
    private val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

    fun query(): List<SavedPhoto> {
        val out = ArrayList<SavedPhoto>()
        val cols = arrayOf(
            MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.Images.ImageColumns.DATE_TAKEN, MediaStore.MediaColumns.DATE_ADDED,
            MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.WIDTH, MediaStore.MediaColumns.HEIGHT,
        )
        context.contentResolver.query(
            collection, cols,
            "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? AND ${MediaStore.MediaColumns.IS_PENDING} = 0",
            arrayOf("${Environment.DIRECTORY_PICTURES}/Fuji Transfer%"),
            "${MediaStore.Images.ImageColumns.DATE_TAKEN} DESC, ${MediaStore.MediaColumns.DATE_ADDED} DESC",
        )?.use { c ->
            val id = c.getColumnIndexOrThrow(cols[0]); val name = c.getColumnIndexOrThrow(cols[1])
            val mime = c.getColumnIndexOrThrow(cols[2]); val taken = c.getColumnIndexOrThrow(cols[3])
            val added = c.getColumnIndexOrThrow(cols[4]); val size = c.getColumnIndexOrThrow(cols[5])
            val w = c.getColumnIndexOrThrow(cols[6]); val h = c.getColumnIndexOrThrow(cols[7])
            while (c.moveToNext()) {
                val i = c.getLong(id)
                val t = c.getLong(taken).takeIf { it > 0 } ?: (c.getLong(added) * 1000)
                out += SavedPhoto(
                    i, ContentUris.withAppendedId(collection, i), c.getString(name) ?: "",
                    c.getString(mime) ?: "", t, c.getLong(size), c.getInt(w), c.getInt(h), c.getLong(added) * 1000,
                )
            }
        }
        // DATE_TAKEN can be missing on some files; keep newest first by the date we settled on.
        return out.sortedByDescending { it.takenMs }
    }

    /**
     * The grid thumbnail: the system's cached one (fast), else a RAF's own preview. Android
     * often builds it from the file's 4:3 EXIF thumbnail, so letterbox bands are cut off.
     */
    fun thumbnail(p: SavedPhoto, px: Int = 320): Bitmap? {
        val sys = try {
            context.contentResolver.loadThumbnail(p.uri, Size(px, px), null)
        } catch (_: Exception) {
            null
        }
        return (sys ?: if (p.kind == "RAF") rafPreview(p, px) else null)?.let(::trimBlackBars)
    }

    private fun rafPreview(p: SavedPhoto, maxSide: Int): Bitmap? = try {
        context.contentResolver.openInputStream(p.uri)?.use { RafPreview.decode(it, maxSide) }
    } catch (_: Exception) {
        null
    }

    /**
     * The photo scaled so its long side is at most [maxSide] px, turned upright.
     * RAW files show the camera's own JPEG preview (Android can't develop a RAF). A JPEG cut
     * short in transfer still shows what arrived instead of falling back to a blurry
     * thumbnail. Only when neither works does the small system thumbnail stand in.
     */
    fun full(p: SavedPhoto, maxSide: Int): Bitmap? {
        if (p.kind == "RAF") rafPreview(p, maxSide)?.let { return it }
        try {
            return ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, p.uri)) { d, info, _ ->
                val w = info.size.width; val h = info.size.height
                val scale = minOf(1f, maxSide.toFloat() / maxOf(w, h))
                if (scale < 1f) d.setTargetSize((w * scale).toInt().coerceAtLeast(1), (h * scale).toInt().coerceAtLeast(1))
                // Keep what decoded of a truncated file; the missing part shows grey.
                d.setOnPartialImageListener { true }
            }
        } catch (_: Exception) {
        }
        if (p.kind != "RAF") rafPreview(p, maxSide)?.let { return it }
        return thumbnail(p, maxSide)
    }

    fun details(p: SavedPhoto): ShotDetails? = try {
        context.contentResolver.openInputStream(p.uri)?.use { s ->
            val e = ExifInterface(s)
            val parts = listOfNotNull(
                e.getAttributeDouble(ExifInterface.TAG_FOCAL_LENGTH, 0.0).takeIf { it > 0 }?.let { "${trim(it)}mm" },
                e.getAttributeDouble(ExifInterface.TAG_F_NUMBER, 0.0).takeIf { it > 0 }?.let { "ƒ/${trim(it)}" },
                e.getAttributeDouble(ExifInterface.TAG_EXPOSURE_TIME, 0.0).takeIf { it > 0 }?.let(::shutter),
                e.getAttributeInt(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, 0).takeIf { it > 0 }?.let { "ISO $it" },
            )
            ShotDetails(
                line = parts.joinToString(" · "),
                camera = listOfNotNull(e.getAttribute(ExifInterface.TAG_MAKE), e.getAttribute(ExifInterface.TAG_MODEL)).joinToString(" ").trim(),
                lens = e.getAttribute(ExifInterface.TAG_LENS_MODEL)?.trim() ?: "",
            )
        }
    } catch (_: Exception) {
        null
    }

    private fun trim(d: Double) = if (d == Math.floor(d)) d.toInt().toString() else "%.1f".format(d)
    private fun shutter(s: Double) = if (s >= 1) "${trim(s)}s" else "1/${Math.round(1 / s)}"

    /** Asks the system to delete [photos] (it shows its own confirmation). */
    fun deleteRequest(photos: List<SavedPhoto>) =
        MediaStore.createDeleteRequest(context.contentResolver, photos.map { it.uri }).intentSender
}
