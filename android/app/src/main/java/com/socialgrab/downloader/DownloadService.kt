package com.socialgrab.downloader

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.io.File

private fun sanitizeFileName(name: String): String {
    val cleaned = name.replace(Regex("[\\\\/*?:\"<>|]"), "_").trim()
    return cleaned.take(150).ifBlank { "video" }
}

/**
 * Corre la descarga como foreground service para que Android no la mate si el
 * usuario sale de la app (comun en skins agresivos como el de TECNO/Transsion).
 */
class DownloadService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())
    private lateinit var notificationManager: NotificationManager

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(NotificationManager::class.java)
        ensureChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val url = intent?.getStringExtra(EXTRA_URL)
        val formatId = intent?.getStringExtra(EXTRA_FORMAT_ID)
        val hasAudio = intent?.getBooleanExtra(EXTRA_HAS_AUDIO, false) ?: false

        if (url == null || formatId == null) {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        startForeground(NOTIFICATION_ID, buildNotification("Preparando descarga...", null))

        serviceScope.launch {
            runDownload(url, formatId, hasAudio)
            stopForeground(STOP_FOREGROUND_DETACH)
            stopSelf(startId)
        }

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }

    private suspend fun runDownload(url: String, formatId: String, hasAudio: Boolean) {
        val extractor = Extractor(applicationContext)
        val downloader = Downloader(applicationContext)

        fun setStage(s: DownloadStage) {
            DownloadStatus.stage.value = s
            updateNotification(s)
        }

        try {
            setStage(DownloadStage.PreparingDownload)
            val result = extractor.resolveFormat(url, formatId, hasAudio)

            when (result) {
                is ExtractionResult.Direct -> {
                    val fileName = "${sanitizeFileName(result.title)}.${result.ext}"
                    downloader.downloadToDownloads(result.url, fileName, "video/${result.ext}") { p ->
                        setStage(DownloadStage.DownloadingVideo(p))
                    }
                    setStage(DownloadStage.Done(fileName))
                }

                is ExtractionResult.NeedsMux -> {
                    val videoTmp = File(cacheDir, "video_${System.currentTimeMillis()}.${result.videoExt}")
                    val audioTmp = File(cacheDir, "audio_${System.currentTimeMillis()}.${result.audioExt}")
                    val outTmp = File(cacheDir, "out_${System.currentTimeMillis()}.mp4")

                    coroutineScope {
                        awaitAll(
                            async { downloader.downloadToFile(result.videoUrl, videoTmp) { p -> setStage(DownloadStage.DownloadingVideo(p)) } },
                            async { downloader.downloadToFile(result.audioUrl, audioTmp) { p -> setStage(DownloadStage.DownloadingAudio(p)) } },
                        )
                    }

                    setStage(DownloadStage.Combining(0))
                    Muxer.mux(applicationContext, videoTmp, audioTmp, outTmp) { p ->
                        setStage(DownloadStage.Combining(p))
                    }

                    val fileName = "${sanitizeFileName(result.title)}.mp4"
                    setStage(DownloadStage.Saving)
                    downloader.saveFileToDownloads(outTmp, fileName, "video/mp4")
                    videoTmp.delete()
                    audioTmp.delete()
                    outTmp.delete()
                    setStage(DownloadStage.Done(fileName))
                }
            }
        } catch (e: Exception) {
            setStage(DownloadStage.Failed(e.message ?: "Error desconocido"))
        }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Descargas", NotificationManager.IMPORTANCE_LOW)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String, progress: Int?): Notification {
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Little Star")
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setOngoing(true)

        if (progress != null) {
            builder.setProgress(100, progress, false)
        } else {
            builder.setProgress(0, 0, true)
        }
        return builder.build()
    }

    private fun updateNotification(stage: DownloadStage) {
        val (text, progress, ongoing) = when (stage) {
            DownloadStage.Idle -> return
            DownloadStage.PreparingDownload -> Triple("Preparando descarga...", null, true)
            is DownloadStage.DownloadingVideo -> Triple("Descargando video... ${stage.progress}%", stage.progress, true)
            is DownloadStage.DownloadingAudio -> Triple("Descargando audio... ${stage.progress}%", stage.progress, true)
            is DownloadStage.Combining -> Triple("Combinando video y audio... ${stage.progress}%", stage.progress, true)
            DownloadStage.Saving -> Triple("Guardando...", null, true)
            is DownloadStage.Done -> Triple("Guardado: ${stage.fileName}", null, false)
            is DownloadStage.Failed -> Triple("Error: ${stage.message}", null, false)
        }

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Little Star")
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setOngoing(ongoing)
            .apply {
                if (progress != null) setProgress(100, progress, false)
            }
            .build()

        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    companion object {
        private const val CHANNEL_ID = "downloads"
        private const val NOTIFICATION_ID = 1
        private const val EXTRA_URL = "url"
        private const val EXTRA_FORMAT_ID = "format_id"
        private const val EXTRA_HAS_AUDIO = "has_audio"

        fun start(context: Context, url: String, formatId: String, hasAudio: Boolean) {
            val intent = Intent(context, DownloadService::class.java).apply {
                putExtra(EXTRA_URL, url)
                putExtra(EXTRA_FORMAT_ID, formatId)
                putExtra(EXTRA_HAS_AUDIO, hasAudio)
            }
            ContextCompat.startForegroundService(context, intent)
        }
    }
}
