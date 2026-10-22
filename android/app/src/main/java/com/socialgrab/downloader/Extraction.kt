package com.socialgrab.downloader

import android.content.Context
import com.chaquo.python.PyObject
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import org.json.JSONObject

data class QualityOption(
    val formatId: String,
    val height: Int,
    val fps: Int?,
    val ext: String,
    val filesizeBytes: Long?,
    val hasAudio: Boolean,
)

data class VideoInfo(val title: String, val qualities: List<QualityOption>)

sealed class ExtractionResult {
    data class Direct(val title: String, val url: String, val ext: String) : ExtractionResult()
    data class NeedsMux(
        val title: String,
        val videoUrl: String,
        val videoExt: String,
        val audioUrl: String,
        val audioExt: String,
    ) : ExtractionResult()
}

// Python arranca la primera vez que se usa, en un hilo de fondo, y no al abrir
// la app: ahi bloqueaba el hilo principal antes de mostrar la primera pantalla.
private object PythonRuntime {
    @Synchronized
    fun ensureStarted(context: Context) {
        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(context.applicationContext))
        }
    }
}

class Extractor(private val context: Context) {

    private fun qjsPath() = "${context.applicationInfo.nativeLibraryDir}/libqjs.so"

    private fun module(): PyObject {
        PythonRuntime.ensureStarted(context)
        return Python.getInstance().getModule("socialgrab_bridge")
    }

    /** Lista las calidades de video disponibles, sin descargar nada. Blocking: correr en Dispatchers.IO. */
    fun listQualities(url: String): VideoInfo {
        val resultJson = module().callAttr("list_qualities", url, qjsPath()).toString()
        val json = JSONObject(resultJson)
        if (json.has("error")) {
            throw IllegalStateException(json.getString("error"))
        }

        val qualitiesJson = json.getJSONArray("qualities")
        val qualities = (0 until qualitiesJson.length()).map { i ->
            val q = qualitiesJson.getJSONObject(i)
            QualityOption(
                formatId = q.getString("format_id"),
                height = q.getInt("height"),
                fps = if (q.isNull("fps")) null else q.getInt("fps"),
                ext = q.optString("ext", "mp4"),
                filesizeBytes = if (q.isNull("filesize")) null else q.getLong("filesize"),
                hasAudio = q.getBoolean("has_audio"),
            )
        }

        if (qualities.isEmpty()) {
            throw IllegalStateException("No se encontraron calidades de video disponibles.")
        }

        return VideoInfo(title = json.getString("title"), qualities = qualities)
    }

    /** Resuelve las URLs directas para la calidad elegida. Blocking: correr en Dispatchers.IO. */
    fun resolveFormat(url: String, formatId: String, hasAudio: Boolean): ExtractionResult {
        val resultJson = module()
            .callAttr("resolve_format", url, formatId, hasAudio, qjsPath())
            .toString()
        val json = JSONObject(resultJson)

        if (json.has("error")) {
            throw IllegalStateException(json.getString("error"))
        }

        val title = json.getString("title")
        return if (json.getBoolean("needs_mux")) {
            ExtractionResult.NeedsMux(
                title = title,
                videoUrl = json.getString("video_url"),
                videoExt = json.optString("video_ext", "mp4"),
                audioUrl = json.getString("audio_url"),
                audioExt = json.optString("audio_ext", "m4a"),
            )
        } else {
            ExtractionResult.Direct(
                title = title,
                url = json.getString("combined_url"),
                ext = json.optString("ext", "mp4"),
            )
        }
    }
}
