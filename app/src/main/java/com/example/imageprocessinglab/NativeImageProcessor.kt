package com.example.imageprocessinglab
import android.graphics.Bitmap

object NativeImageProcessor {
    const val ENGINE_SKIA = 0
    const val ENGINE_CPP_PLAIN = 1
    const val ENGINE_NEON = 2

    init {
        System.loadLibrary("image_optimizer")
    }

    external fun nativeResize(
        srcBitmap: Bitmap,
        dstBitmap: Bitmap,
        engineType: Int
    ): Int
}