package com.locket.app.ui

import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Build
import java.io.ByteArrayOutputStream

/**
 * Match the ordinary Locket thumbnail defaults recorded in the API spec.
 * Crop to the displayed square and never upscale a smaller camera frame.
 * Call on a worker dispatcher; the source remains owned by the review UI.
 */
@Suppress("DEPRECATION")
internal fun encodeMomentThumbnail(source: Bitmap): ByteArray {
    val side = minOf(source.width, source.height)
    val target = minOf(side, 1020)
    val scale = target.toFloat() / side
    val thumbnail = Bitmap.createBitmap(
        source, (source.width - side) / 2, (source.height - side) / 2,
        side, side, Matrix().apply { setScale(scale, scale) }, true
    )
    try {
        val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
            Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP
        return ByteArrayOutputStream().use { stream ->
            check(thumbnail.compress(format, 83, stream)) { "Không thể mã hóa WebP" }
            stream.toByteArray()
        }
    } finally {
        if (thumbnail !== source) thumbnail.recycle()
    }
}
