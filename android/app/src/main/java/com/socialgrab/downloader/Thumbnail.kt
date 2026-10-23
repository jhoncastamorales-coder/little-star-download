package com.socialgrab.downloader

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/**
 * Baja la miniatura del video con OkHttp (que la app ya trae) en vez de sumar
 * una libreria de imagenes solo para esto. Blocking: correr en Dispatchers.IO.
 */
object Thumbnail {

    // La miniatura se muestra chica: con ~480 px de ancho sobra y se gasta
    // mucha menos memoria que decodificando la imagen original (hasta 1280 px).
    private const val TARGET_WIDTH = 480

    /** Lado de la caratula que se incrusta en las canciones. */
    const val COVER_SIZE = 600

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private fun download(url: String): ByteArray? =
        client.newCall(Request.Builder().url(url).build()).execute().use { response ->
            response.takeIf { it.isSuccessful }?.body?.bytes()
        }

    fun load(url: String): ImageBitmap? = try {
        download(url)?.let { bytes ->
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

    /**
     * La miniatura recortada al centro en cuadrado (como se ven las caratulas
     * en los reproductores), en JPEG de COVER_SIZE x COVER_SIZE.
     */
    fun coverJpeg(url: String): ByteArray? = try {
        download(url)?.let { bytes ->
            val image = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
            val side = minOf(image.width, image.height)
            val square = Bitmap.createBitmap(image, (image.width - side) / 2, (image.height - side) / 2, side, side)
            val cover = Bitmap.createScaledBitmap(square, COVER_SIZE, COVER_SIZE, true)
            ByteArrayOutputStream().also { cover.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
        }
    } catch (e: Exception) {
        null // sin caratula la cancion se guarda igual
    }
}
