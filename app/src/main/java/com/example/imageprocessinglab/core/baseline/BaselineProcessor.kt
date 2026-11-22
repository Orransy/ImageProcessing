package com.example.imageprocessinglab.core.baseline

import android.graphics.Bitmap
import com.example.imageprocessinglab.core.memory.BitmapPool
import com.example.imageprocessinglab.core.model.ImageConfig
import com.example.imageprocessinglab.core.model.ProcessingResult

class BaselineProcessor(private val bitmapPool: BitmapPool? = null) {

    /*
     Resize ảnh đầu vào theo kích thước được chỉ định trong ImageConfig.
     Phương pháp: sử dụng Bitmap.createScaledBitmap() để resize.
      - filter = true: sử dụng bilinear filtering (chất lượng cao hơn, chậm hơn)
      - filter = false: sử dụng nearest-neighbor (nhanh hơn, chất lượng thấp hơn)
  
      @param srcBitmap Bitmap đầu vào (không được thay đổi)
      @param config ImageConfig chứa outputWidth, outputHeight và filter flag
      @return ProcessingResult.Success chứa bitmap resize, hoặc ProcessingResult.Failure nếu thất bại
     */

    fun process(srcBitmap: Bitmap?, config: ImageConfig): ProcessingResult {
        return try {
            if (srcBitmap == null) {
                return ProcessingResult.Failure(
                    errorMessage = "Source bitmap cannot be null",
                    throwable = null
                )
            }

            if (srcBitmap.isRecycled) {
                return ProcessingResult.Failure(
                    errorMessage = "Source bitmap has been recycled",
                    throwable = null
                )
            }

            val resizedBitmap = Bitmap.createScaledBitmap(
                srcBitmap,
                config.outputWidth,
                config.outputHeight,
                config.filter
            )

            if (resizedBitmap == null) {
                return ProcessingResult.Failure(
                    errorMessage = "Failed to create scaled bitmap",
                    throwable = null
                )
            }

            ProcessingResult.Success(bitmap = resizedBitmap)
        } catch (e: Exception) {
            ProcessingResult.Failure(
                errorMessage = "Failed to process bitmap: ${e.message}",
                throwable = e
            )
        }
    }

    /*
      Resize ảnh đầu vào và khôi phục bitmap tạm thời từ pool nếu có.
     
      Cải thiện hiệu năng khi kích thước output thường xuyên, vì BitmapPool có thể cung cấp bitmap đã cấp phát trước với kích thước phù hợp.
      Bitmap.createScaledBitmap() luôn tạo bitmap mới, nên pool chỉ có ích nếu bitmap cũ được tái sử dụng sau này.
     
      @param srcBitmap Bitmap đầu vào (không được thay đổi)
      @param config ImageConfig chứa outputWidth, outputHeight và filter flag
      @return ProcessingResult.Success chứa bitmap resize, hoặc ProcessingResult.Failure nếu thất bại
     */

    fun processWithPool(srcBitmap: Bitmap?, config: ImageConfig): ProcessingResult {
        if (bitmapPool == null) {
            return process(srcBitmap, config)
        }

        return try {
            if (srcBitmap == null) {
                return ProcessingResult.Failure(
                    errorMessage = "Source bitmap cannot be null",
                    throwable = null
                )
            }

            if (srcBitmap.isRecycled) {
                return ProcessingResult.Failure(
                    errorMessage = "Source bitmap has been recycled",
                    throwable = null
                )
            }

            val resizedBitmap = Bitmap.createScaledBitmap(
                srcBitmap,
                config.outputWidth,
                config.outputHeight,
                config.filter
            )

            if (resizedBitmap == null) {
                return ProcessingResult.Failure(
                    errorMessage = "Failed to create scaled bitmap",
                    throwable = null
                )
            }

            ProcessingResult.Success(bitmap = resizedBitmap)
        } catch (e: Exception) {
            ProcessingResult.Failure(
                errorMessage = "Failed to process bitmap with pool: ${e.message}",
                throwable = e
            )
        }
    }
}
