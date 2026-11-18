package com.phoenix.recover.engine

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.FileInputStream
import java.nio.ByteBuffer

/**
 * Seekable source over a Storage Access Framework document, so multi-gigabyte
 * disk images can be carved without copying them first.
 */
class ContentUriSource(context: Context, uri: Uri) : SeekableSource {

    private val pfd = context.contentResolver.openFileDescriptor(uri, "r")
        ?: throw IllegalArgumentException("Cannot open the selected document")
    private val stream = FileInputStream(pfd.fileDescriptor)
    private val channel = stream.channel

    override val length: Long = resolveLength(context, uri, pfd.statSize)

    override fun readAt(offset: Long, buffer: ByteArray, bufferOffset: Int, count: Int): Int {
        if (offset < 0 || count <= 0) return -1
        synchronized(channel) {
            channel.position(offset)
            val bb = ByteBuffer.wrap(buffer, bufferOffset, count)
            var total = 0
            while (bb.hasRemaining()) {
                val read = channel.read(bb)
                if (read <= 0) break
                total += read
            }
            return if (total == 0) -1 else total
        }
    }

    override fun nativeFd(): Int {
        return try {
            val duplicate = ParcelFileDescriptor.dup(pfd.fileDescriptor)
            duplicate.detachFd()
        } catch (_: Throwable) {
            -1
        }
    }

    override fun close() {
        runCatching { stream.close() }
        runCatching { pfd.close() }
    }

    private fun resolveLength(context: Context, uri: Uri, statSize: Long): Long {
        if (statSize > 0) return statSize
        val fromChannel = runCatching { channel.size() }.getOrDefault(0L)
        if (fromChannel > 0) return fromChannel
        return runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else 0L
            } ?: 0L
        }.getOrDefault(0L)
    }
}
