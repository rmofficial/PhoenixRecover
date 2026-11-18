package com.phoenix.recover.engine

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile

interface SeekableSource : Closeable {
    val length: Long
    fun readAt(offset: Long, buffer: ByteArray, bufferOffset: Int, count: Int): Int
}

class FileSource(file: File) : SeekableSource {
    private val raf = RandomAccessFile(file, "r")
    override val length: Long = raf.length()

    override fun readAt(offset: Long, buffer: ByteArray, bufferOffset: Int, count: Int): Int {
        if (offset >= length || count <= 0) return -1
        raf.seek(offset)
        return raf.read(buffer, bufferOffset, count)
    }

    override fun close() {
        runCatching { raf.close() }
    }
}

class ByteArraySource(private val data: ByteArray) : SeekableSource {
    override val length: Long = data.size.toLong()

    override fun readAt(offset: Long, buffer: ByteArray, bufferOffset: Int, count: Int): Int {
        if (offset < 0 || offset >= data.size || count <= 0) return -1
        val n = minOf(count.toLong(), data.size - offset).toInt()
        System.arraycopy(data, offset.toInt(), buffer, bufferOffset, n)
        return n
    }

    override fun close() {}
}

internal object Bytes {
    fun u8(b: ByteArray, i: Int): Int = b[i].toInt() and 0xFF

    fun u16be(b: ByteArray, i: Int): Int = (u8(b, i) shl 8) or u8(b, i + 1)

    fun u32be(b: ByteArray, i: Int): Long =
        (u8(b, i).toLong() shl 24) or (u8(b, i + 1).toLong() shl 16) or
            (u8(b, i + 2).toLong() shl 8) or u8(b, i + 3).toLong()

    fun u32le(b: ByteArray, i: Int): Long =
        u8(b, i).toLong() or (u8(b, i + 1).toLong() shl 8) or
            (u8(b, i + 2).toLong() shl 16) or (u8(b, i + 3).toLong() shl 24)

    fun ascii(b: ByteArray, i: Int, len: Int): String {
        if (i < 0 || i + len > b.size) return ""
        return buildString(len) {
            for (k in 0 until len) append((b[i + k].toInt() and 0xFF).toChar())
        }
    }

    fun find(haystack: ByteArray, hayLen: Int, needle: ByteArray, from: Int = 0): Int {
        if (needle.isEmpty()) return -1
        val last = hayLen - needle.size
        if (last < from) return -1
        val first = needle[0]
        var i = from
        while (i <= last) {
            if (haystack[i] == first) {
                var j = 1
                while (j < needle.size && haystack[i + j] == needle[j]) j++
                if (j == needle.size) return i
            }
            i++
        }
        return -1
    }
}
