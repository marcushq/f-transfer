package dev.fujitransfer

import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import androidx.exifinterface.media.ExifInterface
import java.io.File

/**
 * Turns a Fujifilm .HIF (10-bit HEIF) into a JPEG on the phone, for phones and apps that
 * cannot show 10-bit HEIF. Uses the phone's own decoder, so it fails on phones that can't
 * decode the file at all; the caller then keeps the original.
 */
object HeifConverter {
    private val EXIF_TAGS = listOf(
        ExifInterface.TAG_DATETIME, ExifInterface.TAG_DATETIME_ORIGINAL, ExifInterface.TAG_DATETIME_DIGITIZED,
        ExifInterface.TAG_OFFSET_TIME, ExifInterface.TAG_OFFSET_TIME_ORIGINAL, ExifInterface.TAG_SUBSEC_TIME_ORIGINAL,
        ExifInterface.TAG_MAKE, ExifInterface.TAG_MODEL, ExifInterface.TAG_SOFTWARE, ExifInterface.TAG_ARTIST,
        ExifInterface.TAG_COPYRIGHT, ExifInterface.TAG_BODY_SERIAL_NUMBER,
        ExifInterface.TAG_LENS_MAKE, ExifInterface.TAG_LENS_MODEL, ExifInterface.TAG_LENS_SPECIFICATION,
        ExifInterface.TAG_EXPOSURE_TIME, ExifInterface.TAG_F_NUMBER, ExifInterface.TAG_APERTURE_VALUE,
        ExifInterface.TAG_SHUTTER_SPEED_VALUE, ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY,
        ExifInterface.TAG_ISO_SPEED_RATINGS, ExifInterface.TAG_EXPOSURE_BIAS_VALUE,
        ExifInterface.TAG_EXPOSURE_PROGRAM, ExifInterface.TAG_EXPOSURE_MODE, ExifInterface.TAG_METERING_MODE,
        ExifInterface.TAG_WHITE_BALANCE, ExifInterface.TAG_FLASH, ExifInterface.TAG_FOCAL_LENGTH,
        ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM, ExifInterface.TAG_SCENE_CAPTURE_TYPE,
        ExifInterface.TAG_GPS_LATITUDE, ExifInterface.TAG_GPS_LATITUDE_REF,
        ExifInterface.TAG_GPS_LONGITUDE, ExifInterface.TAG_GPS_LONGITUDE_REF,
        ExifInterface.TAG_GPS_ALTITUDE, ExifInterface.TAG_GPS_ALTITUDE_REF,
        ExifInterface.TAG_GPS_TIMESTAMP, ExifInterface.TAG_GPS_DATESTAMP,
    )

    class Result(val jpeg: File, val note: String)

    /** Returns null (and logs why) when the phone can't decode the file. */
    fun toJpeg(heif: File, jpeg: File, log: (String) -> Unit): Result? {
        val source = ImageDecoder.createSource(heif)
        val bitmap = try {
            decode(source, sample = 1)
        } catch (e: OutOfMemoryError) {
            log("Không đủ bộ nhớ để giải mã ảnh đầy đủ, giảm còn 1/2 kích thước")
            try { decode(source, sample = 2) } catch (e2: Throwable) { log("Giải mã HEIF thất bại: $e2"); return null }
        } catch (e: Exception) {
            log("Điện thoại không giải mã được HEIF: ${e.javaClass.simpleName}: ${e.message}")
            return null
        }
        val width = bitmap.width
        val height = bitmap.height
        try {
            jpeg.outputStream().buffered().use { out ->
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)) {
                    log("Nén JPEG thất bại")
                    return null
                }
            }
        } finally {
            bitmap.recycle()
        }
        copyExif(heif, jpeg, width, height, log)
        return Result(jpeg, "$width×$height")
    }

    private fun decode(source: ImageDecoder.Source, sample: Int): Bitmap {
        val decoded = ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
            if (sample > 1) decoder.setTargetSampleSize(sample)
        }
        // 10-bit sources may decode to RGBA_F16 / RGBA_1010102; JPEG wants 8-bit.
        if (decoded.config == Bitmap.Config.ARGB_8888) return decoded
        val copy = decoded.copy(Bitmap.Config.ARGB_8888, false)
        decoded.recycle()
        return copy
    }

    private fun copyExif(from: File, to: File, width: Int, height: Int, log: (String) -> Unit) {
        try {
            val src = ExifInterface(from)
            val dst = ExifInterface(to)
            for (tag in EXIF_TAGS) src.getAttribute(tag)?.let { dst.setAttribute(tag, it) }
            val orientation = src.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            val srcW = src.getAttributeInt(ExifInterface.TAG_IMAGE_WIDTH, 0)
            val srcH = src.getAttributeInt(ExifInterface.TAG_IMAGE_LENGTH, 0)
            // If the decoder already rotated the pixels (width/height swapped), don't rotate twice.
            val quarterTurn = orientation == ExifInterface.ORIENTATION_ROTATE_90 ||
                orientation == ExifInterface.ORIENTATION_ROTATE_270
            val alreadyRotated = quarterTurn && srcW > 0 && srcH > 0 && (srcW > srcH) != (width > height)
            dst.setAttribute(
                ExifInterface.TAG_ORIENTATION,
                (if (alreadyRotated) ExifInterface.ORIENTATION_NORMAL else orientation).toString(),
            )
            dst.saveAttributes()
        } catch (e: Exception) {
            log("Không chép được EXIF: ${e.message}")
        }
    }
}
