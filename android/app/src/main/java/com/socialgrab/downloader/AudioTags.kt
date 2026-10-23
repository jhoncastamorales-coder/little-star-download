package com.socialgrab.downloader

import android.util.Base64
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Escribe titulo, artista y caratula dentro de la cancion para que el
 * reproductor los muestre. Android no trae como hacerlo, asi que se escriben
 * a mano las etiquetas estandar de cada formato. El audio no se toca.
 */
object AudioTags {

    data class Tags(val title: String, val artist: String?, val coverJpeg: ByteArray?)

    // region M4A: etiquetas estilo iTunes (moov/udta/meta/ilst)

    private class Box(val type: String, val offset: Long, val size: Long)

    /** Blocking: correr en Dispatchers.IO. */
    fun writeM4a(source: File, destination: File, tags: Tags) {
        RandomAccessFile(source, "r").use { input ->
            val boxes = topLevelBoxes(input)
            val moov = boxes.firstOrNull { it.type == "moov" } ?: throw IOException("M4A sin moov")
            val mdat = boxes.firstOrNull { it.type == "mdat" }

            val oldMoov = ByteArray(moov.size.toInt())
            input.seek(moov.offset)
            input.readFully(oldMoov)
            val newMoov = rebuildMoov(oldMoov, tags)

            // Si el indice va antes del audio, agrandarlo corre el audio de lugar
            // y hay que corregir las posiciones de cada bloque (stco/co64).
            if (mdat != null && moov.offset < mdat.offset) {
                shiftChunkOffsets(ByteBuffer.wrap(newMoov), 8, newMoov.size, newMoov.size - oldMoov.size)
            }

            BufferedOutputStream(FileOutputStream(destination)).use { out ->
                for (box in boxes) {
                    if (box === moov) out.write(newMoov) else copyRange(input, box.offset, box.size, out)
                }
            }
        }
    }

    private fun topLevelBoxes(input: RandomAccessFile): List<Box> {
        val boxes = mutableListOf<Box>()
        var pos = 0L
        val length = input.length()
        while (pos + 8 <= length) {
            input.seek(pos)
            var size = input.readInt().toLong() and 0xffffffffL
            val type = ByteArray(4).also { input.readFully(it) }.toString(Charsets.ISO_8859_1)
            if (size == 1L) size = input.readLong() else if (size == 0L) size = length - pos
            if (size < 8) throw IOException("M4A danado")
            boxes += Box(type, pos, size)
            pos += size
        }
        return boxes
    }

    private fun rebuildMoov(moov: ByteArray, tags: Tags): ByteArray {
        val children = ByteArrayOutputStream()
        val buffer = ByteBuffer.wrap(moov)
        var pos = 8
        while (pos + 8 <= moov.size) {
            val size = buffer.getInt(pos)
            val type = String(moov, pos + 4, 4, Charsets.ISO_8859_1)
            if (size < 8) break
            // La udta vieja se reemplaza. La "meta" suelta que deja MediaMuxer en
            // moov (estilo QuickTime, solo datos internos de Android) hace que
            // lectores como mutagen/TagLib dejen de leer el resto: se quita.
            if (type != "udta" && type != "meta") children.write(moov, pos, size)
            pos += size
        }
        children.write(udta(tags))
        return box("moov", children.toByteArray())
    }

    private fun udta(tags: Tags): ByteArray {
        val items = ByteArrayOutputStream()
        items.write(item(byteArrayOf(0xA9.toByte(), 'n'.code.toByte(), 'a'.code.toByte(), 'm'.code.toByte()), 1, tags.title.toByteArray()))
        tags.artist?.let {
            items.write(item(byteArrayOf(0xA9.toByte(), 'A'.code.toByte(), 'R'.code.toByte(), 'T'.code.toByte()), 1, it.toByteArray()))
        }
        tags.coverJpeg?.let { items.write(item("covr".toByteArray(), 13, it)) } // 13 = JPEG

        val hdlr = box("hdlr", int(0) + int(0) + "mdir".toByteArray() + "appl".toByteArray() + int(0) + int(0) + byteArrayOf(0))
        val meta = box("meta", int(0) + hdlr + box("ilst", items.toByteArray()))
        return box("udta", meta)
    }

    private fun item(type: ByteArray, dataType: Int, value: ByteArray): ByteArray =
        box(type, box("data", int(dataType) + int(0) + value))

    private fun shiftChunkOffsets(buffer: ByteBuffer, start: Int, end: Int, delta: Int) {
        var pos = start
        while (pos + 8 <= end) {
            val size = buffer.getInt(pos)
            if (size < 8) return
            when (String(buffer.array(), pos + 4, 4, Charsets.ISO_8859_1)) {
                "trak", "mdia", "minf", "stbl" -> shiftChunkOffsets(buffer, pos + 8, pos + size, delta)
                "stco" -> for (i in 0 until buffer.getInt(pos + 12)) {
                    val at = pos + 16 + i * 4
                    buffer.putInt(at, buffer.getInt(at) + delta)
                }
                "co64" -> for (i in 0 until buffer.getInt(pos + 12)) {
                    val at = pos + 16 + i * 8
                    buffer.putLong(at, buffer.getLong(at) + delta)
                }
            }
            pos += size
        }
    }

    private fun copyRange(input: RandomAccessFile, offset: Long, size: Long, out: OutputStream) {
        input.seek(offset)
        val buffer = ByteArray(64 * 1024)
        var left = size
        while (left > 0) {
            val read = input.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
            if (read < 0) throw EOFException()
            out.write(buffer, 0, read)
            left -= read
        }
    }

    private fun box(type: String, payload: ByteArray) = box(type.toByteArray(Charsets.ISO_8859_1), payload)

    private fun box(type: ByteArray, payload: ByteArray): ByteArray =
        int(8 + payload.size) + type + payload

    private fun int(value: Int): ByteArray = ByteBuffer.allocate(4).putInt(value).array()

    // endregion

    // region Opus: comentarios de Ogg (paquete OpusTags) con METADATA_BLOCK_PICTURE

    private class Page(
        val headerType: Int,
        val granule: Long,
        val serial: Int,
        val sequence: Int,
        val lacing: ByteArray,
        val body: ByteArray,
    )

    /** Blocking: correr en Dispatchers.IO. */
    fun writeOpus(source: File, destination: File, tags: Tags) {
        BufferedInputStream(FileInputStream(source)).use { input ->
            BufferedOutputStream(FileOutputStream(destination)).use { out ->
                val head = readPage(input) ?: throw IOException("Ogg vacio")
                writePage(out, head)

                // El paquete OpusTags siempre termina su pagina y el audio
                // empieza en una nueva, asi que se puede reemplazar entero.
                val oldTags = ByteArrayOutputStream()
                var oldPages = 0
                while (true) {
                    val page = readPage(input) ?: throw IOException("Ogg sin OpusTags")
                    oldPages++
                    oldTags.write(page.body)
                    if ((page.lacing.last().toInt() and 0xff) < 255) break
                }

                val newPages = paginate(opusTags(vendorOf(oldTags.toByteArray()), tags), head.serial)
                newPages.forEach { writePage(out, it) }

                val shift = newPages.size - oldPages
                while (true) {
                    val page = readPage(input) ?: break
                    writePage(out, Page(page.headerType, page.granule, page.serial, page.sequence + shift, page.lacing, page.body))
                }
            }
        }
    }

    private fun vendorOf(packet: ByteArray): String {
        if (packet.size < 12 || String(packet, 0, 8, Charsets.ISO_8859_1) != "OpusTags") return "Little Star"
        val length = ByteBuffer.wrap(packet, 8, 4).order(ByteOrder.LITTLE_ENDIAN).int
        return if (length in 0..(packet.size - 12)) String(packet, 12, length) else "Little Star"
    }

    private fun opusTags(vendor: String, tags: Tags): ByteArray {
        val comments = mutableListOf("TITLE=${tags.title}")
        tags.artist?.let { comments += "ARTIST=$it" }
        tags.coverJpeg?.let { comments += "METADATA_BLOCK_PICTURE=" + Base64.encodeToString(flacPicture(it), Base64.NO_WRAP) }

        val out = ByteArrayOutputStream()
        out.write("OpusTags".toByteArray())
        val vendorBytes = vendor.toByteArray()
        out.write(le32(vendorBytes.size))
        out.write(vendorBytes)
        out.write(le32(comments.size))
        comments.forEach { comment ->
            val bytes = comment.toByteArray()
            out.write(le32(bytes.size))
            out.write(bytes)
        }
        return out.toByteArray()
    }

    // Bloque PICTURE de FLAC, que es lo que esperan los lectores de Ogg/Opus.
    private fun flacPicture(jpeg: ByteArray): ByteArray {
        val mime = "image/jpeg".toByteArray()
        return int(3) + // portada delantera
            int(mime.size) + mime +
            int(0) + // sin descripcion
            int(Thumbnail.COVER_SIZE) + int(Thumbnail.COVER_SIZE) +
            int(24) + int(0) +
            int(jpeg.size) + jpeg
    }

    private fun paginate(packet: ByteArray, serial: Int): List<Page> {
        // Cada segmento es de hasta 255 bytes; uno de menos de 255 cierra el paquete.
        val lacing = ArrayList<Int>()
        repeat(packet.size / 255) { lacing += 255 }
        lacing += packet.size % 255

        val pages = mutableListOf<Page>()
        var offset = 0
        lacing.chunked(255).forEachIndexed { i, chunk ->
            val length = chunk.sum()
            pages += Page(
                headerType = if (i == 0) 0 else 0x01, // 0x01 = continua el paquete anterior
                granule = 0,
                serial = serial,
                sequence = 1 + i,
                lacing = ByteArray(chunk.size) { chunk[it].toByte() },
                body = packet.copyOfRange(offset, offset + length),
            )
            offset += length
        }
        return pages
    }

    private fun readPage(input: InputStream): Page? {
        val header = ByteArray(27)
        var read = 0
        while (read < 27) {
            val n = input.read(header, read, 27 - read)
            if (n < 0) {
                if (read == 0) return null
                throw EOFException()
            }
            read += n
        }
        if (String(header, 0, 4, Charsets.ISO_8859_1) != "OggS") throw IOException("Pagina Ogg invalida")
        val le = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        val lacing = readFully(input, header[26].toInt() and 0xff)
        val body = readFully(input, lacing.sumOf { it.toInt() and 0xff })
        return Page(header[5].toInt(), le.getLong(6), le.getInt(14), le.getInt(18), lacing, body)
    }

    private fun readFully(input: InputStream, size: Int): ByteArray {
        val bytes = ByteArray(size)
        var read = 0
        while (read < size) {
            val n = input.read(bytes, read, size - read)
            if (n < 0) throw EOFException()
            read += n
        }
        return bytes
    }

    private fun writePage(out: OutputStream, page: Page) {
        val header = ByteBuffer.allocate(27 + page.lacing.size).order(ByteOrder.LITTLE_ENDIAN)
        header.put("OggS".toByteArray()).put(0).put(page.headerType.toByte())
            .putLong(page.granule).putInt(page.serial).putInt(page.sequence).putInt(0)
            .put(page.lacing.size.toByte()).put(page.lacing)
        val bytes = header.array()
        var crc = 0
        crc = crc(crc, bytes)
        crc = crc(crc, page.body)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(22, crc)
        out.write(bytes)
        out.write(page.body)
    }

    // CRC-32 de Ogg: polinomio 0x04c11db7, sin reflejar, valor inicial 0.
    private val CRC_TABLE = IntArray(256) { i ->
        var r = i shl 24
        repeat(8) { r = if (r and Int.MIN_VALUE != 0) (r shl 1) xor 0x04c11db7 else r shl 1 }
        r
    }

    private fun crc(start: Int, bytes: ByteArray): Int {
        var crc = start
        for (b in bytes) crc = (crc shl 8) xor CRC_TABLE[((crc ushr 24) xor (b.toInt() and 0xff)) and 0xff]
        return crc
    }

    private fun le32(value: Int): ByteArray = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()

    // endregion
}
