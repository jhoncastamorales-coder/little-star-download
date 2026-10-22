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
        val request = Request.Builder().url(url).build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("No se pudo descargar (${response.code})")
            }
            val body = response.body ?: throw IOException("Respuesta vacia")
            val total = body.contentLength()
            var bytesRead = 0L
            var lastReportedPercent = -1

            BufferedOutputStream(FileOutputStream(destination), BUFFER_SIZE).use { out ->
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
    }

    /** Copia un archivo local a Descargas/LittleStar vía MediaStore. Blocking: correr en Dispatchers.IO. */
    fun saveFileToDownloads(source: File, fileName: String, mimeType: String): Uri {
        val uri = createDownloadEntry(fileName, mimeType)
            ?: throw IOException("No se pudo crear el archivo de destino")

        val outputStream = context.contentResolver.openOutputStream(uri)
            ?: throw IOException("No se pudo abrir el archivo de destino")

        outputStream.use { out ->
            source.inputStream().use { input -> input.copyTo(out) }
        }

        return uri
    }

    private fun createDownloadEntry(fileName: String, mimeType: String): Uri? {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/LittleStar")
        }
        return context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
    }
}
