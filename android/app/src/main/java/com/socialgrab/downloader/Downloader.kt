package com.socialgrab.downloader

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.TimeUnit

private const val BUFFER_SIZE = 64 * 1024

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
        val request = Request.Builder().url(url).build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("No se pudo descargar (${response.code})")
            }
            val body = response.body ?: throw IOException("Respuesta vacia")
            val total = body.contentLength()
            var bytesRead = 0L
            var lastReportedPercent = -1

            body.byteStream().use { input ->
                val buffer = ByteArray(BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read == -1) break
                    out.write(buffer, 0, read)
                    bytesRead += read
                    if (total > 0) {
                        val percent = ((bytesRead * 100) / total).toInt()
                        if (percent != lastReportedPercent) {
                            lastReportedPercent = percent
                            onProgress(percent)
                        }
                    }
                }
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
