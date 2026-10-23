package com.socialgrab.downloader

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit

private const val BUFFER_SIZE = 64 * 1024
private const val CHUNK_SIZE = 10L * 1024 * 1024

class Downloader(private val context: Context) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.MINUTES)
        .writeTimeout(10, TimeUnit.MINUTES)
        .build()

    /** Descarga una URL directa a un archivo local. Blocking: correr en Dispatchers.IO. */
    fun downloadToFile(url: String, destination: File, onProgress: (Int) -> Unit) {
        BufferedOutputStream(FileOutputStream(destination), BUFFER_SIZE).use { out ->
            streamUrl(url, out, onProgress)
        }
    }

    /**
     * Descarga una URL directo a Descargas/LittleStar, sin pasar por la cache,
     * para no escribir el video dos veces. Blocking: correr en Dispatchers.IO.
     */
    fun downloadToDownloads(url: String, fileName: String, mimeType: String, onProgress: (Int) -> Unit): Uri =
        writeToDownloads(fileName, mimeType) { out -> streamUrl(url, out, onProgress) }

    /** Copia un archivo local a Descargas/LittleStar vía MediaStore. Blocking: correr en Dispatchers.IO. */
    fun saveFileToDownloads(source: File, fileName: String, mimeType: String): Uri =
        writeToDownloads(fileName, mimeType) { out ->
            source.inputStream().use { input -> input.copyTo(out, BUFFER_SIZE) }
        }

    private fun streamUrl(url: String, out: OutputStream, onProgress: (Int) -> Unit) {
        val progress = Progress(onProgress)
        if (url.toHttpUrlOrNull()?.host?.endsWith("googlevideo.com") == true) {
            streamInChunks(url, out, progress)
            return
        }

        client.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("No se pudo descargar (${response.code})")
            }
            val body = response.body ?: throw IOException("Respuesta vacia")
            progress.total = body.contentLength()
            copy(body.byteStream(), out, progress)
        }
    }

    // YouTube frena a ~60 KB/s las descargas que piden el archivo entero; por
    // partes de 10 MB (como hace yt-dlp) las sirve a la velocidad de la red.
    private fun streamInChunks(url: String, out: OutputStream, progress: Progress) {
        while (progress.total < 0 || progress.bytes < progress.total) {
            val start = progress.bytes
            val request = Request.Builder()
                .url(url)
                .header("Range", "bytes=$start-${start + CHUNK_SIZE - 1}")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("No se pudo descargar (${response.code})")
                }
                val body = response.body ?: throw IOException("Respuesta vacia")
                if (response.code != 206) {
                    // El servidor ignoro el Range y manda el archivo entero
                    progress.total = body.contentLength()
                    copy(body.byteStream(), out, progress)
                    return
                }
                // Content-Range: bytes 0-10485759/36254812
                progress.total = response.header("Content-Range")
                    ?.substringAfterLast('/')?.toLongOrNull()
                    ?: throw IOException("Respuesta sin tamano total")
                copy(body.byteStream(), out, progress)
            }
            if (progress.bytes == start) throw IOException("La descarga no avanza")
        }
    }

    private class Progress(private val onProgress: (Int) -> Unit) {
        var total = -1L
        var bytes = 0L
        private var lastPercent = -1

        fun add(read: Int) {
            bytes += read
            if (total > 0) {
                val percent = ((bytes * 100) / total).toInt()
                if (percent != lastPercent) {
                    lastPercent = percent
                    onProgress(percent)
                }
            }
        }
    }

    private fun copy(input: InputStream, out: OutputStream, progress: Progress) {
        input.use {
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val read = it.read(buffer)
                if (read == -1) break
                out.write(buffer, 0, read)
                progress.add(read)
            }
        }
    }

    // La entrada queda "pendiente" mientras se escribe (la galeria no muestra un
    // video a medias) y se borra si algo falla, para no dejar archivos rotos.
    private fun writeToDownloads(fileName: String, mimeType: String, write: (OutputStream) -> Unit): Uri {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/LittleStar")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("No se pudo crear el archivo de destino")

        try {
            val stream = resolver.openOutputStream(uri)
                ?: throw IOException("No se pudo abrir el archivo de destino")
            BufferedOutputStream(stream, BUFFER_SIZE).use { write(it) }
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            return uri
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
    }
}
