package com.socialgrab.downloader

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File

object Muxer {

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
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine<Unit> { cont ->
                val videoItem = EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(videoFile)))
                    .setRemoveAudio(true)
                    .build()
                val audioItem = EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(audioFile)))
                    .setRemoveVideo(true)
                    .build()

                // VERIFICAR contra el Javadoc de media3-transformer (version 1.11.1)
                // al abrir el proyecto: este constructor y Composition.Builder(seq1, seq2)
                // son la forma mas directa para este caso (video y audio de
                // fuentes YA separadas), pero no se confirmo al 100% contra la
                // documentacion publica -- la guia oficial solo muestra el caso
                // de combinar clips que ya traen ambas pistas.
                val videoSequence = EditedMediaItemSequence(listOf(videoItem))
                val audioSequence = EditedMediaItemSequence(listOf(audioItem))
                val composition = Composition.Builder(videoSequence, audioSequence).build()

                val transformer = Transformer.Builder(context)
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
