package com.socialgrab.downloader

sealed class DownloadStage {
    object Idle : DownloadStage()
    object PreparingDownload : DownloadStage()
    data class DownloadingVideo(val progress: Int) : DownloadStage()
    data class DownloadingAudio(val progress: Int) : DownloadStage()
    data class Combining(val progress: Int) : DownloadStage()
    object Saving : DownloadStage()
    data class Done(val fileName: String) : DownloadStage()
    data class Failed(val message: String) : DownloadStage()
}

fun DownloadStage.isBusy(): Boolean = this is DownloadStage.PreparingDownload ||
    this is DownloadStage.DownloadingVideo ||
    this is DownloadStage.DownloadingAudio ||
    this is DownloadStage.Combining ||
    this is DownloadStage.Saving
