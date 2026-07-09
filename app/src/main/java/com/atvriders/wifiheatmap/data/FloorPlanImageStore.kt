package com.atvriders.wifiheatmap.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import kotlin.math.max

/**
 * Imports floor-plan images into app-private storage. SAF URI grants can be revoked
 * or the source document deleted, so the image is copied (downscaled to
 * [MAX_DIMENSION_PX], EXIF-rotated, re-encoded) at import time; every later load is
 * a plain file read.
 */
class FloorPlanImageStore(private val context: Context) {

    data class ImportedPlan(val path: String, val widthPx: Int, val heightPx: Int)

    private val dir: File get() = File(context.filesDir, "floorplans").apply { mkdirs() }

    suspend fun importImage(uri: Uri): Result<ImportedPlan> = withContext(Dispatchers.IO) {
        runCatching {
            val bitmap = decodeDownsampled(uri)
                ?: throw IllegalArgumentException("Could not decode image")
            val file = File(dir, "${UUID.randomUUID()}.jpg")
            file.outputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
            }
            ImportedPlan(file.absolutePath, bitmap.width, bitmap.height).also {
                bitmap.recycle()
            }
        }
    }

    fun loadBitmap(path: String): Bitmap? = BitmapFactory.decodeFile(path)

    fun delete(path: String) {
        runCatching { File(path).takeIf { it.parentFile == dir || it.startsWith(dir) }?.delete() }
    }

    private fun decodeDownsampled(uri: Uri): Bitmap? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            // ImageDecoder applies EXIF rotation itself.
            val source = ImageDecoder.createSource(context.contentResolver, uri)
            ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.isMutableRequired = false
                val longest = max(info.size.width, info.size.height)
                if (longest > MAX_DIMENSION_PX) {
                    decoder.setTargetSampleSize(sampleSizeFor(longest, MAX_DIMENSION_PX))
                }
            }
        } else {
            decodeLegacy(uri)
        }
    }

    private fun decodeLegacy(uri: Uri): Bitmap? {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(max(bounds.outWidth, bounds.outHeight), MAX_DIMENSION_PX)
        }
        val raw = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            ?: return null
        val rotationDegrees = resolver.openInputStream(uri)?.use {
            when (ExifInterface(it).getAttributeInt(
                ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL
            )) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        } ?: 0f
        if (rotationDegrees == 0f) return raw
        val matrix = Matrix().apply { postRotate(rotationDegrees) }
        return Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, matrix, true).also {
            if (it != raw) raw.recycle()
        }
    }

    companion object {
        const val MAX_DIMENSION_PX = 4096

        /** Power-of-two inSampleSize / ImageDecoder sample size. Pure and testable. */
        fun sampleSizeFor(longestPx: Int, maxPx: Int): Int {
            var sample = 1
            var size = longestPx
            while (size > maxPx) {
                sample *= 2
                size /= 2
            }
            return sample
        }
    }
}

private fun File.startsWith(dir: File): Boolean =
    absolutePath.startsWith(dir.absolutePath + File.separator)
