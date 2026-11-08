package com.jmti.budgetfy.utils

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream

/** Shrinks images to JPEGs small enough to store in one Firestore document (1 MiB limit). */
object ImageCompress {

    /**
     * Returns JPEG bytes no larger than [maxBytes], scaling the longest side
     * down from [maxSide] and lowering quality as needed; null if it can't fit.
     */
    fun toJpeg(src: Bitmap, maxSide: Int, maxBytes: Int): ByteArray? {
        var side = maxSide
        val minSide = maxOf(200, maxSide / 3)
        while (side >= minSide) {
            val scaled = ImageLoader.scaleDown(src, side)
            var quality = 85
            while (quality >= 40) {
                val out = ByteArrayOutputStream()
                scaled.compress(Bitmap.CompressFormat.JPEG, quality, out)
                if (out.size() <= maxBytes) return out.toByteArray()
                quality -= 10
            }
            side = (side * 0.75).toInt()
        }
        return null
    }
}
