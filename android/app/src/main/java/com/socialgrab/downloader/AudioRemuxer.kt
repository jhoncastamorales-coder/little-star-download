package com.socialgrab.downloader

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer

/**
 * Pasa la pista de audio de YouTube (Opus dentro de webm, o AAC dentro de MP4
 * fragmentado) a un archivo de musica normal que cualquier reproductor lee:
 * .opus (Ogg) o .m4a. Copia los paquetes tal cual, sin re-codificar, asi que
 * la calidad es exactamente la original y tarda menos de un segundo.
 */
object AudioRemuxer {

    /** Extension, MIME y formato de salida segun el codec de la pista. */
    data class Output(val extension: String, val mimeType: String, val muxerFormat: Int)

    val OPUS = Output("opus", "audio/ogg", MediaMuxer.OutputFormat.MUXER_OUTPUT_OGG)
    val AAC = Output("m4a", "audio/mp4", MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

    /** Blocking: correr en Dispatchers.IO. */
    fun remux(source: File, destination: File, output: Output) {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(source.absolutePath)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw IOException("El archivo no trae audio")
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)

            val muxer = MediaMuxer(destination.absolutePath, output.muxerFormat)
            try {
                val outTrack = muxer.addTrack(format)
                muxer.start()
                val maxSize = if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                    format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)
                } else {
                    256 * 1024
                }
                val buffer = ByteBuffer.allocate(maxSize)
                val info = MediaCodec.BufferInfo()
                while (true) {
                    val size = extractor.readSampleData(buffer, 0)
                    if (size < 0) break
                    info.set(0, size, extractor.sampleTime, MediaCodec.BUFFER_FLAG_KEY_FRAME)
                    muxer.writeSampleData(outTrack, buffer, info)
                    extractor.advance()
                }
                muxer.stop()
            } finally {
                muxer.release()
            }
        } finally {
            extractor.release()
        }
    }
}
