package com.socialgrab.downloader

import kotlinx.coroutines.flow.MutableStateFlow

/** Puente simple en memoria entre DownloadService y la UI (mismo proceso, sin IPC). */
object DownloadStatus {
    val stage = MutableStateFlow<DownloadStage>(DownloadStage.Idle)
}
