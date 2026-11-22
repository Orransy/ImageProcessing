package com.example.imageprocessinglab.core.model

data class ImageConfig(
    val outputWidth: Int,
    val outputHeight: Int,
    val filter: Boolean = true
) {
    init {
        require(outputWidth > 0) { "outputWidth must be greater than 0, got $outputWidth" }
        require(outputHeight > 0) { "outputHeight must be greater than 0, got $outputHeight" }
    }
}
