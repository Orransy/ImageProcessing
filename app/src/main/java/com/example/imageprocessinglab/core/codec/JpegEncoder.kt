package com.example.imageprocessinglab.core.codec

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream

class JpegEncoder : ImageEncoder {

    override fun encode(bitmap: Bitmap, quality: Int): Result<ByteArray> {
        return try {
            // Validate quality
            require(quality in 0..100) {
                "JPEG quality must be between 0 and 100, got $quality"
            }

            // Check if bitmap is recycled
            if (bitmap.isRecycled) {
                return Result.failure(
                    IllegalStateException("Bitmap is recycled and cannot be encoded")
                )
            }

            // Encode to ByteArray
            val outputStream = ByteArrayOutputStream()
            val compressed = bitmap.compress(
                Bitmap.CompressFormat.JPEG,
                quality,
                outputStream
            )

            if (!compressed) {
                return Result.failure(
                    RuntimeException("Failed to compress bitmap to JPEG format")
                )
            }

            val byteArray = outputStream.toByteArray()

            if (byteArray.isEmpty()) {
                return Result.failure(
                    RuntimeException("JPEG encoding produced empty byte array")
                )
            }

            Result.success(byteArray)
        } catch (e: IllegalArgumentException) {
            Result.failure(e)
        } catch (e: Exception) {
            Result.failure(
                RuntimeException("Failed to encode bitmap to JPEG: ${e.message}", e)
            )
        }
    }
}
