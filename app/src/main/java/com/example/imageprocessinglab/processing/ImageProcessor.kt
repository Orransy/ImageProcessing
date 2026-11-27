package com.example.imageprocessinglab.processing

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import androidx.annotation.RequiresApi
import com.example.imageprocessinglab.benchmark.BenchmarkConfig
import com.example.imageprocessinglab.benchmark.DecodeStrategy
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Xử lý MỘT ảnh, chia thành 3 tầng để Pipeline có thể tách ra chạy ở các luồng khác nhau:
 *   1) readSource       : đọc byte ảnh nguồn (I/O)
 *   2) decodeAndResize  : giải mã + resize về cạnh dài tối đa targetMaxSide (CPU)
 *   3) encodeAndWrite   : nén JPEG/WebP + ghi file output (CPU + I/O)
 * Các mô hình Sequential / Thread pool / Coroutines gọi processOne() = chạy đủ 3 tầng.
 *
 * Engine hiện chỉ có Skia (BitmapFactory/ImageDecoder/Canvas của Android).
 * Lưu ý: BitmapFactory không xoay theo EXIF còn ImageDecoder thì có; PSNR/SSIM tự bỏ qua
 * ảnh mẫu có kích thước lệch nhau (ảnh chụp xoay).
 */
class ImageProcessor(
    private val store: ImageStore,
    private val config: BenchmarkConfig,
    private val bitmapPool: BitmapPool,
    private val bufferPool: BufferPool
) {

    private val compressFormat: Bitmap.CompressFormat =
        if (config.format == "JPEG") Bitmap.CompressFormat.JPEG else webpFormat()

    private val scratch: ThreadLocal<ByteArray> =
        ThreadLocal.withInitial { ByteArray(64 * 1024) }

    val extension: String = if (config.format == "JPEG") "jpg" else "webp"

    /** Ghi chú khi định dạng yêu cầu phải thay thế (AVIF -> WebP). */
    val formatNote: String? =
        if (config.format == "AVIF") {
            "AVIF: Bitmap.compress của Android chưa hỗ trợ -> dùng WebP thay thế"
        } else {
            null
        }

    @Suppress("DEPRECATION")
    private fun webpFormat(): Bitmap.CompressFormat =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Bitmap.CompressFormat.WEBP_LOSSY
        } else {
            Bitmap.CompressFormat.WEBP
        }

    // ------------------------------------------------------------------ tầng 1

    fun readSource(uri: Uri): ReusableBuffer {
        val buffer = bufferPool.acquire()
        try {
            store.open(uri).use { input ->
                if (bufferPool.enabled) {
                    // Pool bật: dùng mảng tạm theo luồng, không cấp phát thêm mỗi ảnh
                    val chunk = scratch.get()
                    while (true) {
                        val read = input.read(chunk)
                        if (read < 0) break
                        buffer.write(chunk, 0, read)
                    }
                } else {
                    input.copyTo(buffer, 64 * 1024)
                }
            }
        } catch (t: Throwable) {
            bufferPool.release(buffer)
            throw t
        }
        return buffer
    }

    // ------------------------------------------------------------------ tầng 2

    fun decodeAndResize(raw: ReusableBuffer): Bitmap {
        val data = raw.array
        val length = raw.length

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(data, 0, length, bounds)
        val srcW = bounds.outWidth
        val srcH = bounds.outHeight
        if (srcW <= 0 || srcH <= 0) {
            throw IOException("Không đọc được kích thước ảnh")
        }

        val longest = max(srcW, srcH)
        val scale = if (longest > config.targetMaxSide) {
            config.targetMaxSide.toDouble() / longest
        } else {
            1.0
        }
        val targetW = max(1, (srcW * scale).roundToInt())
        val targetH = max(1, (srcH * scale).roundToInt())

        return when (config.decode) {
            DecodeStrategy.BASELINE -> {
                val full = BitmapFactory.decodeByteArray(data, 0, length)
                    ?: throw IOException("Giải mã thất bại")
                scaleInto(full, targetW, targetH)
            }

            DecodeStrategy.SAMPLE_SIZE -> decodeSampled(data, length, srcW, srcH, targetW, targetH)

            DecodeStrategy.TARGET_SIZE ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    decodeTargetSize(data, length, targetW, targetH)
                } else {
                    // API < 28 không có ImageDecoder -> dùng inSampleSize
                    decodeSampled(data, length, srcW, srcH, targetW, targetH)
                }
        }
    }

    private fun decodeSampled(
        data: ByteArray,
        length: Int,
        srcW: Int,
        srcH: Int,
        targetW: Int,
        targetH: Int
    ): Bitmap {
        var sample = 1
        while (srcW / (sample * 2) >= targetW && srcH / (sample * 2) >= targetH) {
            sample *= 2
        }
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val decoded = BitmapFactory.decodeByteArray(data, 0, length, options)
            ?: throw IOException("Giải mã thất bại")
        return scaleInto(decoded, targetW, targetH)
    }

    @RequiresApi(Build.VERSION_CODES.P)
    private fun decodeTargetSize(
        data: ByteArray,
        length: Int,
        targetW: Int,
        targetH: Int
    ): Bitmap {
        val source = ImageDecoder.createSource(ByteBuffer.wrap(data, 0, length))
        return ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.setTargetSize(targetW, targetH)
        }
    }

    /** Vẽ [src] vào Bitmap đích (lấy từ BitmapPool) kích thước tw x th, rồi giải phóng [src]. */
    private fun scaleInto(src: Bitmap, targetW: Int, targetH: Int): Bitmap {
        if (src.width == targetW && src.height == targetH) return src

        val dst = bitmapPool.obtain(targetW, targetH)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC)
        }
        Canvas(dst).drawBitmap(
            src,
            Rect(0, 0, src.width, src.height),
            Rect(0, 0, targetW, targetH),
            paint
        )
        src.recycle()
        return dst
    }

    // ------------------------------------------------------------------ tầng 3

    fun outputFile(dir: File, index: Int): File =
        File(dir, String.format(Locale.US, "img_%05d.%s", index, extension))

    /** Nén + ghi file. Trả về số byte đã ghi. */
    fun encodeAndWrite(index: Int, bitmap: Bitmap, dir: File): Long {
        val out = bufferPool.acquire()
        try {
            if (!bitmap.compress(compressFormat, config.quality, out)) {
                throw IOException("Nén ảnh thất bại")
            }
            FileOutputStream(outputFile(dir, index)).use {
                it.write(out.array, 0, out.length)
            }
            return out.length.toLong()
        } finally {
            bufferPool.release(out)
        }
    }

    // ------------------------------------------------------------------ giải phóng

    fun releaseBuffer(buffer: ReusableBuffer) = bufferPool.release(buffer)

    fun releaseBitmap(bitmap: Bitmap) = bitmapPool.release(bitmap)

    // ------------------------------------------------------------------ tổng hợp

    /** Chạy đủ 3 tầng cho một ảnh. */
    fun processOne(index: Int, uri: Uri, dir: File): Long {
        val raw = readSource(uri)
        val bitmap = try {
            decodeAndResize(raw)
        } finally {
            releaseBuffer(raw)
        }
        try {
            return encodeAndWrite(index, bitmap, dir)
        } finally {
            releaseBitmap(bitmap)
        }
    }

    /** Ảnh tham chiếu (Baseline, không pool) để tính PSNR/SSIM. */
    fun referenceBitmap(uri: Uri): Bitmap {
        val ref = ImageProcessor(
            store,
            config.copy(
                decode = DecodeStrategy.BASELINE,
                bitmapPool = false,
                bufferPool = false
            ),
            BitmapPool(false),
            BufferPool(false)
        )
        val raw = ref.readSource(uri)
        try {
            return ref.decodeAndResize(raw)
        } finally {
            ref.releaseBuffer(raw)
        }
    }
}