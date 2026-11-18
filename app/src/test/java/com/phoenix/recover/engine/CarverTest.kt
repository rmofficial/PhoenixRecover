package com.phoenix.recover.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files

class CarverTest {

    private val found = mutableListOf<RecoveredFile>()

    private fun carve(data: ByteArray, options: CarveOptions = CarveOptions(minSize = 8)): CarveResult {
        val dir = Files.createTempDirectory("phoenix-carve").toFile()
        val result = Carver(ByteArraySource(data), options).carve(dir, { false }) { event ->
            if (event is CarveEvent.Found) found += event.item
        }
        return result
    }

    private fun zip(): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0x14, 0x00, 0x00, 0x00, 0x00, 0x00))
        out.write(ByteArray(8))
        out.write(byteArrayOf(0x05, 0x00))
        out.write(byteArrayOf(0x00, 0x00))
        out.write("readme.txt".toByteArray())
        out.write("hello world".toByteArray())
        out.write(byteArrayOf(0x50, 0x4B, 0x05, 0x06))
        out.write(ByteArray(16))
        out.write(byteArrayOf(0x04, 0x00))
        out.write("note".toByteArray())
        return out.toByteArray()
    }

    private fun jpeg(): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()))
        out.write(byteArrayOf(0x00, 0x10))
        out.write(ByteArray(14) { 'J'.code.toByte() })
        out.write(byteArrayOf(0xFF.toByte(), 0xE1.toByte(), 0x00, 0x22))
        val thumb = ByteArray(32) { 0x11 }
        thumb[4] = 0xFF.toByte(); thumb[5] = 0xD8.toByte()
        thumb[10] = 0xFF.toByte(); thumb[11] = 0xD9.toByte()
        out.write(thumb)
        out.write(byteArrayOf(0xFF.toByte(), 0xDA.toByte(), 0x00, 0x0C))
        out.write(ByteArray(10) { 0x22 })
        out.write(byteArrayOf(0x12, 0x34, 0xFF.toByte(), 0x00, 0x56, 0x78))
        out.write(byteArrayOf(0xFF.toByte(), 0xD9.toByte()))
        return out.toByteArray()
    }

    private fun png(): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
        out.write(byteArrayOf(0, 0, 0, 13))
        out.write("IHDR".toByteArray())
        out.write(ByteArray(13) { it.toByte() })
        out.write(ByteArray(4))
        out.write(byteArrayOf(0, 0, 0, 4))
        out.write("IDAT".toByteArray())
        out.write(byteArrayOf(1, 2, 3, 4))
        out.write(ByteArray(4))
        out.write(byteArrayOf(0, 0, 0, 0))
        out.write("IEND".toByteArray())
        out.write(byteArrayOf(0xAE.toByte(), 0x42, 0x60, 0x82.toByte()))
        return out.toByteArray()
    }

    private fun mp4(): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0, 0, 0, 24))
        out.write("ftyp".toByteArray())
        out.write("isom".toByteArray())
        out.write(byteArrayOf(0, 0, 2, 0))
        out.write("isommp42".toByteArray())
        out.write(byteArrayOf(0, 0, 0, 108))
        out.write("mdat".toByteArray())
        out.write(ByteArray(100) { 0x33 })
        out.write(byteArrayOf(0, 0, 0, 32))
        out.write("moov".toByteArray())
        out.write(ByteArray(24) { 0x44 })
        return out.toByteArray()
    }

    private fun sqlite(): ByteArray {
        val out = ByteArray(8192)
        "SQLite format 3\u0000".toByteArray(Charsets.ISO_8859_1).copyInto(out)
        out[16] = 0x10
        out[17] = 0x00
        out[28] = 0x00
        out[29] = 0x00
        out[30] = 0x00
        out[31] = 0x02
        return out
    }

    private fun mp3(): ByteArray {
        val out = ByteArrayOutputStream()
        out.write("ID3".toByteArray())
        out.write(byteArrayOf(0x03, 0x00, 0x00))
        out.write(ByteArray(4))
        repeat(3) {
            out.write(byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0x90.toByte(), 0x00))
            out.write(ByteArray(413))
        }
        return out.toByteArray()
    }

    private fun wav(): ByteArray {
        val out = ByteArray(4096)
        "RIFF".toByteArray().copyInto(out, 0)
        val size = out.size - 8
        out[4] = (size and 0xFF).toByte()
        out[5] = ((size shr 8) and 0xFF).toByte()
        out[6] = ((size shr 16) and 0xFF).toByte()
        out[7] = ((size shr 24) and 0xFF).toByte()
        "WAVE".toByteArray().copyInto(out, 8)
        return out
    }

    @Test
    fun carvesAllMajorFormats() {
        val out = ByteArrayOutputStream()
        repeat(1234) { out.write(0x5A) }

        val jpegStart = out.size().toLong()
        val jpegBytes = jpeg()
        out.write(jpegBytes)
        val jpegEnd = out.size().toLong()
        repeat(4096) { out.write(0x5A) }

        val pngStart = out.size().toLong()
        out.write(png())
        val pngEnd = out.size().toLong()
        repeat(4096) { out.write(0x5A) }

        val wavStart = out.size().toLong()
        out.write(wav())
        val wavEnd = out.size().toLong()
        repeat(4096) { out.write(0x5A) }

        val mp4Start = out.size().toLong()
        out.write(mp4())
        val mp4End = out.size().toLong()
        repeat(4096) { out.write(0x5A) }

        val mp3Start = out.size().toLong()
        out.write(mp3())
        val mp3End = out.size().toLong()
        repeat(4096) { out.write(0x5A) }

        val sqliteStart = out.size().toLong()
        out.write(sqlite())
        val sqliteEnd = out.size().toLong()
        repeat(4096) { out.write(0x5A) }

        val pdfStart = out.size().toLong()
        out.write("%PDF-1.7\n1 0 obj\n<< /Type /Catalog >>\nendobj\n".toByteArray())
        out.write(ByteArray(3000) { 0x20 })
        out.write("%%EOF\n".toByteArray())
        val pdfEnd = out.size().toLong()
        repeat(4096) { out.write(0x5A) }

        val zipStart = out.size().toLong()
        out.write(zip())
        val zipEnd = out.size().toLong()
        repeat(4096) { out.write(0x5A) }

        val result = carve(out.toByteArray())
        val byFormat = found.groupBy { it.format }

        assertTrue("JPEG missing", byFormat.containsKey("JPEG"))
        assertTrue("PNG missing", byFormat.containsKey("PNG"))
        assertTrue("WAV missing", byFormat.containsKey("WAV"))
        assertTrue("MP4 missing", byFormat.containsKey("MP4"))
        assertTrue("MP3 missing", byFormat.containsKey("MP3"))
        assertTrue("SQLite missing", byFormat.containsKey("SQLite"))
        assertTrue("PDF missing", byFormat.containsKey("PDF"))
        assertTrue("ZIP missing", byFormat.containsKey("ZIP"))

        assertEquals(jpegEnd - jpegStart, byFormat["JPEG"]!!.first().size)
        assertEquals(jpegStart, byFormat["JPEG"]!!.first().offset)
        assertEquals(pngEnd - pngStart, byFormat["PNG"]!!.first().size)
        assertEquals(wavEnd - wavStart, byFormat["WAV"]!!.first().size)
        assertEquals(mp4End - mp4Start, byFormat["MP4"]!!.first().size)
        assertEquals(mp3End - mp3Start, byFormat["MP3"]!!.first().size)
        assertEquals(sqliteEnd - sqliteStart, byFormat["SQLite"]!!.first().size)
        assertEquals(pdfEnd - pdfStart, byFormat["PDF"]!!.first().size)
        assertEquals(zipEnd - zipStart, byFormat["ZIP"]!!.first().size)

        val jpegFile = File(byFormat["JPEG"]!!.first().scratchPath!!)
        val tail = jpegFile.readBytes().takeLast(2)
        assertEquals(listOf(0xFF.toByte(), 0xD9.toByte()), tail)
        assertEquals(8, result.filesFound)
    }

    @Test
    fun stopsAtNextSignatureForFooterlessFormats() {
        val out = ByteArrayOutputStream()
        repeat(512) { out.write(0x5A) }
        val sevenStart = out.size().toLong()
        out.write(byteArrayOf(0x37, 0x7A, 0xBC.toByte(), 0xAF.toByte(), 0x27, 0x1C))
        out.write(ByteArray(2000) { 0x21 })
        val pngStart = out.size().toLong()
        out.write(png())
        val data = out.toByteArray()

        carve(data)

        val seven = found.firstOrNull { it.format == "7-Zip" }
        val png = found.firstOrNull { it.format == "PNG" }
        assertNotNull(seven)
        assertNotNull(png)
        assertEquals(pngStart, seven!!.offset!! + seven.size)
        assertEquals(pngStart, png!!.offset)
    }

    @Test
    fun ignoresRandomNoise() {
        val random = java.util.Random(7)
        val data = ByteArray(512 * 1024)
        random.nextBytes(data)
        val result = carve(data, CarveOptions(minSize = 4096, maxResults = 50))
        assertTrue("false positives: ${found.map { it.format }}", found.size < 5)
        assertTrue(result.filesFound < 5)
    }

    @Test
    fun honoursMinimumSize() {
        val out = ByteArrayOutputStream()
        repeat(1000) { out.write(0x5A) }
        out.write(png())
        repeat(1000) { out.write(0x5A) }
        carve(out.toByteArray(), CarveOptions(minSize = 1024 * 1024))
        assertTrue(found.isEmpty())
    }
}
