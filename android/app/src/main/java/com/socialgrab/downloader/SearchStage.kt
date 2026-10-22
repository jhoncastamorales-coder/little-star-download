package com.socialgrab.downloader

sealed class SearchStage {
    object Idle : SearchStage()
    object Resolving : SearchStage()
    data class Choosing(val url: String, val title: String, val qualities: List<QualityOption>) : SearchStage()
    data class Failed(val message: String) : SearchStage()
}
