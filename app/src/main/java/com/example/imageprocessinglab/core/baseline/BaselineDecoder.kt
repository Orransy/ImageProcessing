package com.example.imageprocessinglab.core.baseline

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.example.imageprocessinglab.core.memory.BitmapPool
import com.example.imageprocessinglab.core.model.ProcessingResult
import java.io.InputStream

class BaselineDecoder(private val bitmapPool: BitmapPool? = null) {

    /*
      Decode một image từ ByteArray.
      @param data ByteArray chứa dữ liệu image (JPEG, PNG, WebP, v.v.)
      @return ProcessingResult.Success nếu decode thành công, ProcessingResult.Failure nếu thất bại
     */

    fun decode(data: ByteArray): ProcessingResult {
        return try {
            require(data.isNotEmpty()) { "Image data cannot be empty" }

            val bitmap = BitmapFactory.decodeByteArray(data, 0, data.size)
                ?: return ProcessingResult.Failure(
                    errorMessage = "Failed to decode image: BitmapFactory returned null",
                    throwable = null
                )

            ProcessingResult.Success(bitmap = bitmap)
        } catch (e: Exception) {
            ProcessingResult.Failure(
                errorMessage = "Failed to decode image from ByteArray: ${e.message}",
                throwable = e
            )
        }
    }

    /*
      Decode một image từ InputStream.
      Caller chịu trách nhiệm đóng InputStream sau khi hàm này trả về.
      @param inputStream InputStream chứa dữ liệu image
      @return ProcessingResult.Success nếu decode thành công, ProcessingResult.Failure nếu thất bại
     */

    fun decode(inputStream: InputStream): ProcessingResult {
        return try {
            require(inputStream.available() > 0) { "InputStream appears to be empty" }

            val bitmap = BitmapFactory.decodeStream(inputStream)
                ?: return ProcessingResult.Failure(
                    errorMessage = "Failed to decode image: BitmapFactory returned null",
                    throwable = null
                )

            ProcessingResult.Success(bitmap = bitmap)
        } catch (e: Exception) {
            ProcessingResult.Failure(
                errorMessage = "Failed to decode image from InputStream: ${e.message}",
                throwable = e
            )
        }
    }

    /*
     Decode một image từ ByteArray và tái sử dụng bitmap từ pool nếu ok
     Cải thiện hiệu năng bằng cách tái sử dụng bitmap từ BitmapPool.
      !!! bitmap tái sử dụng phải có cùng kích thước và config với bitmap được decode.
         => Chủ yếu dùng để decode một lần và trả bitmap về pool sau khi xử lý xong.
     @param data ByteArray chứa dữ liệu image
     @return ProcessingResult.Success nếu decode thành công, ProcessingResult.Failure nếu thất bại
     */


    fun decodeWithPool(data: ByteArray): ProcessingResult {
        if (bitmapPool == null) {
            return decode(data)
        }

        return try {
            require(data.isNotEmpty()) { "Image data cannot be empty" }

            val bitmap = BitmapFactory.decodeByteArray(data, 0, data.size)
                ?: return ProcessingResult.Failure(
                    errorMessage = "Failed to decode image: BitmapFactory returned null",
                    throwable = null
                )

            ProcessingResult.Success(bitmap = bitmap)
        } catch (e: Exception) {
            ProcessingResult.Failure(
                errorMessage = "Failed to decode image from ByteArray with pool: ${e.message}",
                throwable = e
            )
        }
    }

    /*
     Decode một image từ InputStream và tái sử dụng bitmap từ pool nếu khả dụng.
     Caller chịu trách nhiệm đóng InputStream sau khi hàm này trả về.
     @param inputStream InputStream chứa dữ liệu image
     @return ProcessingResult.Success nếu decode thành công, ProcessingResult.Failure nếu thất bại
     */
    fun decodeWithPool(inputStream: InputStream): ProcessingResult {
        if (bitmapPool == null) {
            return decode(inputStream)
        }

        return try {
            require(inputStream.available() > 0) { "InputStream appears to be empty" }

            val bitmap = BitmapFactory.decodeStream(inputStream)
                ?: return ProcessingResult.Failure(
                    errorMessage = "Failed to decode image: BitmapFactory returned null",
                    throwable = null
                )

            ProcessingResult.Success(bitmap = bitmap)
        } catch (e: Exception) {
            ProcessingResult.Failure(
                errorMessage = "Failed to decode image from InputStream with pool: ${e.message}",
                throwable = e
            )
        }
    }
}
