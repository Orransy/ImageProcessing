package com.example.imageprocessinglab.processing

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.InputStream
import java.util.Locale
import kotlin.random.Random

/**
 * Module I/O của benchmark:
 *  - ĐỌC: lấy danh sách 100–1000 ảnh từ bộ nhớ máy (MediaStore). Nếu máy có ít ảnh hơn
 *    số lượng yêu cầu thì lặp lại danh sách cho đủ. Nếu không có ảnh / chưa cấp quyền thì
 *    tự tạo một bộ ảnh synthetic (2560x1920 JPEG) trong thư mục riêng của app.
 *  - GHI: ảnh đã xử lý được ghi vào thư mục output của app (bench_out).
 */
class ImageStore(context: Context) {

    private val context: Context = context.applicationContext

    /** Tập nguồn ảnh cho một lần chạy. */
    data class SourceSet(
        val uris: List<Uri>,
        val uniqueCount: Int,
        val synthetic: Boolean
    )

    // ------------------------------------------------------------------ permission

    fun requiredPermission(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, requiredPermission()) ==
                PackageManager.PERMISSION_GRANTED

    // ------------------------------------------------------------------ read

    /** Trả về đúng [count] Uri (lặp lại nếu nguồn ít hơn). Gọi trên luồng nền. */
    fun loadSources(count: Int): SourceSet {
        val real = if (hasPermission()) queryGallery(count) else emptyList()
        if (real.isNotEmpty()) {
            return SourceSet(cycle(real, count), real.size, false)
        }
        val synthetic = ensureSyntheticDataset()
        return SourceSet(cycle(synthetic, count), synthetic.size, true)
    }

    fun open(uri: Uri): InputStream =
        context.contentResolver.openInputStream(uri)
            ?: throw FileNotFoundException(uri.toString())

    private fun cycle(base: List<Uri>, count: Int): List<Uri> =
        List(count) { base[it % base.size] }

    private fun queryGallery(limit: Int): List<Uri> {
        val result = ArrayList<Uri>(limit)
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(MediaStore.Images.Media._ID)
        val selection = "${MediaStore.Images.Media.MIME_TYPE} IN (?,?,?)"
        val args = arrayOf("image/jpeg", "image/png", "image/webp")
        val sort = "${MediaStore.Images.Media.DATE_ADDED} DESC"

        context.contentResolver.query(collection, projection, selection, args, sort)?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            while (result.size < limit && c.moveToNext()) {
                result.add(ContentUris.withAppendedId(collection, c.getLong(idCol)))
            }
        }
        return result
    }

    // ------------------------------------------------------------------ write

    fun outputDir(): File {
        val root = context.getExternalFilesDir(null) ?: context.filesDir
        return File(root, "bench_out").apply { mkdirs() }
    }

    /** Xóa toàn bộ ảnh output của lần chạy trước. Trả về số file đã xóa. */
    fun clearOutput(): Int {
        var deleted = 0
        outputDir().listFiles()?.forEach {
            if (it.delete()) deleted++
        }
        return deleted
    }

    // ------------------------------------------------------------------ synthetic dataset

    private fun ensureSyntheticDataset(): List<Uri> {
        val dir = File(context.filesDir, "dataset").apply { mkdirs() }
        val files = (0 until SYNTHETIC_COUNT).map {
            File(dir, String.format(Locale.US, "synth_%02d.jpg", it))
        }
        files.forEachIndexed { index, file ->
            if (!file.exists() || file.length() == 0L) {
                writeSyntheticImage(file, index)
            }
        }
        return files.map { Uri.fromFile(it) }
    }

    private fun writeSyntheticImage(file: File, seed: Int) {
        val w = SYNTHETIC_WIDTH
        val h = SYNTHETIC_HEIGHT
        val rnd = Random(seed)

        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        paint.shader = LinearGradient(
            0f, 0f, w.toFloat(), h.toFloat(),
            Color.rgb(rnd.nextInt(256), rnd.nextInt(256), rnd.nextInt(256)),
            Color.rgb(rnd.nextInt(256), rnd.nextInt(256), rnd.nextInt(256)),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
        paint.shader = null

        repeat(120) {
            paint.color = Color.argb(
                60 + rnd.nextInt(120),
                rnd.nextInt(256), rnd.nextInt(256), rnd.nextInt(256)
            )
            canvas.drawCircle(
                rnd.nextFloat() * w,
                rnd.nextFloat() * h,
                20f + rnd.nextFloat() * 220f,
                paint
            )
        }

        FileOutputStream(file).use {
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it)
        }
        bitmap.recycle()
    }

    private companion object {
        const val SYNTHETIC_COUNT = 24
        const val SYNTHETIC_WIDTH = 2560
        const val SYNTHETIC_HEIGHT = 1920
    }
}