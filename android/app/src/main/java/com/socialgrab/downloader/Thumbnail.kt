package com.socialgrab.downloader

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Baja la miniatura del video con OkHttp (que la app ya trae) en vez de sumar
 * una libreria de imagenes solo para esto. Blocking: correr en Dispatchers.IO.
 */
object Thumbnail {

    // La miniatura se muestra chica: con ~480 px de ancho sobra y se gasta
    // mucha menos memoria que decodificando la imagen original (hasta 1280 px).
    private const val TARGET_WIDTH = 480

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    fun load(url: String): ImageBitmap? = try {
        client.newCall(Request.Builder().url(url).build()).execute().use { response ->
            val bytes = response.takeIf { it.isSuccessful }?.body?.bytes() ?: return null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= TARGET_WIDTH) sample *= 2
            val options = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.asImageBitmap()
        }
    } catch (e: Exception) {
        null // sin miniatura la pantalla funciona igual
    }
}
