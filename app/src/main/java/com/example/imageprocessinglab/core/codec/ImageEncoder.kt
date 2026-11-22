package com.example.imageprocessinglab.core.codec

import android.graphics.Bitmap


interface ImageEncoder {
    fun encode(bitmap: Bitmap, quality: Int): Result<ByteArray>
}
