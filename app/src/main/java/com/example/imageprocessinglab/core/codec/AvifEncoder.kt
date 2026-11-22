package com.example.imageprocessinglab.core.codec

import android.graphics.Bitmap

class AvifEncoder : ImageEncoder {

    override fun encode(bitmap: Bitmap, quality: Int): Result<ByteArray> {
        return try {
            // Validate quality
            require(quality in 0..100) {
                "AVIF quality must be between 0 and 100, got $quality"
            }

            // Check if bitmap is recycled
            if (bitmap.isRecycled) {
                return Result.failure(
                    IllegalStateException("Bitmap is recycled and cannot be encoded")
                )
            }

            // AVIF is not available in the current Android SDK configuration
            Result.failure(
                UnsupportedOperationException(
                    "AVIF encoding is not available in the current Android SDK configuration. " +
                        "Bitmap.CompressFormat.AVIF is not defined. " +
                        "Consider using WebP as an alternative format."
                )
            )
        } catch (e: IllegalArgumentException) {
            Result.failure(e)
        } catch (e: IllegalStateException) {
            Result.failure(e)
        } catch (e: Exception) {
            Result.failure(
                RuntimeException(
                    "Failed to validate AVIF encoding: ${e.message}",
                    e
                )
            )
        }
    }
}
