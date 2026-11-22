package com.example.imageprocessinglab.core.model

import android.graphics.Bitmap

sealed class ProcessingResult {
    data class Success(val bitmap: Bitmap) : ProcessingResult()
    data class Failure(
        val errorMessage: String,
        val throwable: Throwable? = null
    ) : ProcessingResult()
}
