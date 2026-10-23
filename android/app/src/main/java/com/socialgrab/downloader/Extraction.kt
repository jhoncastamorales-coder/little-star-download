package com.socialgrab.downloader

import android.content.Context
import com.chaquo.python.PyObject
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import org.json.JSONObject

data class QualityOption(
    val formatId: String,
    val height: Int,
    val width: Int?,
    val fps: Int?,
    val ext: String,
    val filesizeBytes: Long?,
    val hasAudio: Boolean,
)

/** Pista de solo-audio: "opus" (maxima calidad de YouTube) o "aac" (compatible). */
data class AudioOption(
    val formatId: String,
    val codec: String,
    val bitrateKbps: Int?,
    val filesizeBytes: Long?,
)

data class VideoInfo(
    val title: String,
    val thumbnailUrl: String?,
    val durationSeconds: Int?,
    val qualities: List<QualityOption>,
    val audioOptions: List<AudioOption>,
)

data class AudioSource(val title: String, val artist: String?, val url: String)

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

    // Dentro de la cache de la app, para que Android pueda liberarla si le falta espacio.
    private fun cacheDir() = "${context.cacheDir.absolutePath}/yt-dlp"

    private fun module(): PyObject {
        PythonRuntime.ensureStarted(context)
        return Python.getInstance().getModule("socialgrab_bridge")
    }

    /** Lista las calidades de video disponibles, sin descargar nada. Blocking: correr en Dispatchers.IO. */
    fun listQualities(url: String): VideoInfo {
        val resultJson = module().callAttr("list_qualities", url, qjsPath(), cacheDir()).toString()
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
                width = if (q.isNull("width")) null else q.getInt("width"),
                fps = if (q.isNull("fps")) null else q.getInt("fps"),
                ext = q.optString("ext", "mp4"),
                filesizeBytes = if (q.isNull("filesize")) null else q.getLong("filesize"),
                hasAudio = q.getBoolean("has_audio"),
            )
        }

        if (qualities.isEmpty()) {
            throw IllegalStateException("No se encontraron calidades de video disponibles.")
        }

        return VideoInfo(
            title = json.getString("title"),
            thumbnailUrl = if (json.isNull("thumbnail")) null else json.getString("thumbnail"),
            durationSeconds = if (json.isNull("duration")) null else json.getInt("duration"),
            qualities = qualities,
            audioOptions = parseAudioOptions(json),
        )
    }

    private fun parseAudioOptions(json: JSONObject): List<AudioOption> {
        val array = json.optJSONArray("audio") ?: return emptyList()
        return (0 until array.length()).map { i ->
            val a = array.getJSONObject(i)
            AudioOption(
                formatId = a.getString("format_id"),
                codec = a.getString("codec"),
                bitrateKbps = if (a.isNull("bitrate")) null else a.getInt("bitrate"),
                filesizeBytes = if (a.isNull("filesize")) null else a.getLong("filesize"),
            )
        }
    }

    /** URL directa de la pista de audio elegida. Blocking: correr en Dispatchers.IO. */
    fun resolveAudio(url: String, formatId: String): AudioSource {
        val json = JSONObject(
            module().callAttr("resolve_audio", url, formatId, qjsPath(), cacheDir()).toString()
        )
        if (json.has("error")) {
            throw IllegalStateException(json.getString("error"))
        }
        return AudioSource(
            title = json.getString("title"),
            artist = if (json.isNull("artist")) null else json.getString("artist"),
            url = json.getString("url"),
        )
    }

    /** Resuelve las URLs directas para la calidad elegida. Blocking: correr en Dispatchers.IO. */
    fun resolveFormat(url: String, formatId: String, hasAudio: Boolean): ExtractionResult {
        val resultJson = module()
            .callAttr("resolve_format", url, formatId, hasAudio, qjsPath(), cacheDir())
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
