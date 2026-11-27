package com.example.imageprocessinglab

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect

object NativeImageProcessor {
    const val ENGINE_SKIA = 0
    const val ENGINE_CPP_PLAIN = 1
    const val ENGINE_NEON = 2

    private val skiaPaint = Paint(Paint.FILTER_BITMAP_FLAG)

    init {
        System.loadLibrary("image_optimizer")
    }

    external fun nativeResize(
        srcBitmap: Bitmap,
        dstBitmap: Bitmap,
        engineType: Int
    ): Int

    /**
     * Hàm resize thống nhất ghi trực tiếp vào dstBitmap tái sử dụng từ BitmapPool.
     * Hỗ trợ đồng thời ENGINE_SKIA (0), ENGINE_CPP_PLAIN (1), ENGINE_NEON (2).
     */
    fun resizeInto(
        srcBitmap: Bitmap,
        dstBitmap: Bitmap,
        engineType: Int
    ): Int {
        val safeSrc = if (srcBitmap.config == Bitmap.Config.ARGB_8888) {
            srcBitmap
        } else {
            srcBitmap.copy(Bitmap.Config.ARGB_8888, false)
        }

        val resultCode = when (engineType) {
            ENGINE_SKIA -> {
                val canvas = Canvas(dstBitmap)
                val srcRect = Rect(0, 0, safeSrc.width, safeSrc.height)
                val dstRect = Rect(0, 0, dstBitmap.width, dstBitmap.height)
                canvas.drawBitmap(safeSrc, srcRect, dstRect, skiaPaint)
                0
            }
            ENGINE_CPP_PLAIN, ENGINE_NEON -> {
                nativeResize(safeSrc, dstBitmap, engineType)
            }
            else -> -5
        }

        if (safeSrc !== srcBitmap) {
            safeSrc.recycle()
        }
        return resultCode
    }
}