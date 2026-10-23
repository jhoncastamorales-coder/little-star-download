package com.socialgrab.downloader

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.InAppMuxer
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File

object Muxer {

    // Codecs que el muxer MP4 de Media3 (InAppMuxer) acepta tal cual: con estos
    // las pistas se copian sin re-codificar, que toma segundos. VP9 (Instagram)
    // no entra en MP4 y se re-codifica.
    private val COPYABLE_VIDEO = setOf(MimeTypes.VIDEO_H264, MimeTypes.VIDEO_H265, MimeTypes.VIDEO_AV1)
    private val COPYABLE_AUDIO = setOf(MimeTypes.AUDIO_AAC)

    /**
     * Combina un archivo de solo-video y uno de solo-audio en un unico .mp4.
     * Transformer exige correr en un thread con Looper (por eso Dispatchers.Main)
     * y notifica el resultado por listener, no por valor de retorno.
     */
    suspend fun mux(
        context: Context,
        videoFile: File,
        audioFile: File,
        outputFile: File,
        onProgress: (Int) -> Unit,
    ) {
        val copyVideo = trackMimeType(videoFile, "video/") in COPYABLE_VIDEO
        val copyAudio = trackMimeType(audioFile, "audio/") in COPYABLE_AUDIO
        try {
            export(context, videoFile, audioFile, outputFile, copyVideo, copyAudio, onProgress)
        } catch (e: ExportException) {
            if (!copyVideo && !copyAudio) throw e
            // Si copiar las pistas falla con algun archivo raro, se re-codifica
            // todo: mas lento, pero termina.
            outputFile.delete()
            export(context, videoFile, audioFile, outputFile, copyVideo = false, copyAudio = false, onProgress)
        }
    }

    private fun trackMimeType(file: File, prefix: String): String? {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(file.absolutePath)
            (0 until extractor.trackCount)
                .mapNotNull { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME) }
                .firstOrNull { it.startsWith(prefix) }
        } catch (e: Exception) {
            null
        } finally {
            extractor.release()
        }
    }

    private suspend fun export(
        context: Context,
        videoFile: File,
        audioFile: File,
        outputFile: File,
        copyVideo: Boolean,
        copyAudio: Boolean,
        onProgress: (Int) -> Unit,
    ) {
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine<Unit> { cont ->
                val videoItem = EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(videoFile)))
                    .setRemoveAudio(true)
                    .build()
                val audioItem = EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(audioFile)))
                    .setRemoveVideo(true)
                    .build()

                // Con dos secuencias Media3 re-codifica todas las pistas, salvo
                // que se le pida copiarlas (transmux).
                val videoSequence = EditedMediaItemSequence(listOf(videoItem))
                val audioSequence = EditedMediaItemSequence(listOf(audioItem))
                val composition = Composition.Builder(videoSequence, audioSequence)
                    .setTransmuxVideo(copyVideo)
                    .setTransmuxAudio(copyAudio)
                    .build()

                val transformer = Transformer.Builder(context)
                    // Lo que haya que re-codificar va a H.264/AAC, que usan el
                    // codificador por hardware: si no, en Android 14 un video AV1
                    // se re-codificaba a AV1 por software (decenas de minutos).
                    .setVideoMimeType(MimeTypes.VIDEO_H264)
                    .setAudioMimeType(MimeTypes.AUDIO_AAC)
                    // El muxer de Android solo acepta AV1 desde Android 14.
                    .setMuxerFactory(InAppMuxer.Factory.Builder().build())
                    .addListener(object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                            if (cont.isActive) cont.resumeWith(Result.success(Unit))
                        }

                        override fun onError(
                            composition: Composition,
                            exportResult: ExportResult,
                            exportException: ExportException,
                        ) {
                            if (cont.isActive) cont.resumeWith(Result.failure(exportException))
                        }
                    })
                    .build()

                cont.invokeOnCancellation { transformer.cancel() }
                transformer.start(composition, outputFile.absolutePath)

                val progressHolder = ProgressHolder()
                val handler = Handler(Looper.getMainLooper())
                val pollProgress = object : Runnable {
                    override fun run() {
                        if (!cont.isActive) return
                        val state = transformer.getProgress(progressHolder)
                        if (state == Transformer.PROGRESS_STATE_AVAILABLE) {
                            onProgress(progressHolder.progress)
                        }
                        handler.postDelayed(this, 300)
                    }
                }
                handler.postDelayed(pollProgress, 300)
            }
        }
    }
}
