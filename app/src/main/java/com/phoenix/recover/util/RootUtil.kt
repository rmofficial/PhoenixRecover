package com.phoenix.recover.util

import android.os.Build
import java.io.File
import java.util.concurrent.TimeUnit

data class RootStatus(val rooted: Boolean, val detail: String)

data class BlockDevice(
    val name: String,
    val target: String,
    val readable: Boolean,
)

object RootUtil {

    private fun su(vararg args: String): Process? {
        val candidates = listOf("su", "/system/bin/su", "/system/xbin/su", "/sbin/su", "/su/bin/su")
        for (cmd in candidates) {
            try {
                val builder = ProcessBuilder(listOf(cmd) + args)
                builder.redirectErrorStream(true)
                return builder.start()
            } catch (_: Exception) {
            }
        }
        return null
    }

    private fun runSu(command: String, timeoutSeconds: Long = 12): String? {
        val process = su("-c", command) ?: return null
        return try {
            val output = StringBuilder()
            val reader = Thread {
                runCatching {
                    process.inputStream.bufferedReader().useLines { lines ->
                        lines.take(4000).forEach { output.appendLine(it) }
                    }
                }
            }
            reader.isDaemon = true
            reader.start()
            val done = process.waitForCompat(timeoutSeconds)
            if (!done) {
                process.destroyCompat()
                reader.join(800)
                null
            } else {
                reader.join(800)
                output.toString()
            }
        } catch (_: Exception) {
            runCatching { process.destroyCompat() }
            null
        }
    }

    private fun Process.waitForCompat(timeoutSeconds: Long): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return waitFor(timeoutSeconds, TimeUnit.SECONDS)
        }
        val deadline = System.currentTimeMillis() + timeoutSeconds * 1000
        while (System.currentTimeMillis() < deadline) {
            if (!isRunningCompat()) return true
            try {
                Thread.sleep(50)
            } catch (_: InterruptedException) {
                return !isRunningCompat()
            }
        }
        return !isRunningCompat()
    }

    private fun Process.isRunningCompat(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) return isAlive
        return try {
            exitValue()
            false
        } catch (_: IllegalThreadStateException) {
            true
        }
    }

    private fun Process.destroyCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) destroyForcibly() else destroy()
    }

    fun probe(): RootStatus {
        val out = runSu("id", 4)
            ?: return RootStatus(false, "Root shell not available on this device.")
        return if (out.contains("uid=0")) {
            RootStatus(true, "Root shell active. Raw partition access is available.")
        } else {
            RootStatus(false, "A root shell exists but did not grant uid=0.")
        }
    }

    fun listBlockDevices(): List<BlockDevice> {
        val out = runSu("ls -l /dev/block/by-name 2>/dev/null || ls -l /dev/block/bootdevice/by-name 2>/dev/null")
            ?: return emptyList()
        val result = mutableListOf<BlockDevice>()
        out.lineSequence().forEach { line ->
            val arrow = line.indexOf(" -> ")
            if (!line.startsWith("l") || arrow < 0) return@forEach
            val left = line.substring(0, arrow).trim().split(Regex("\\s+"))
            val name = left.lastOrNull() ?: return@forEach
            val target = line.substring(arrow + 4).trim()
            if (name.isBlank() || target.isBlank()) return@forEach
            result += BlockDevice(name, target, true)
        }
        return result.sortedBy { it.name }
    }

    fun createImage(
        devicePath: String,
        dest: File,
        maxBytes: Long,
        isCancelled: () -> Boolean = { false },
        onProgress: (Long) -> Unit = {},
    ): Result<File> {
        dest.parentFile?.mkdirs()
        val blocks = (maxBytes / (1024 * 1024)).coerceAtLeast(1)
        val cmd = "dd if='$devicePath' of='${dest.absolutePath}' bs=1M count=$blocks 2>&1"
        val process = su("-c", cmd) ?: return Result.failure(Exception("Root shell unavailable"))
        return try {
            val output = StringBuilder()
            val reader = Thread {
                runCatching {
                    process.inputStream.bufferedReader().useLines { lines ->
                        lines.take(80).forEach { output.appendLine(it) }
                    }
                }
            }
            reader.isDaemon = true
            reader.start()
            while (process.isRunningCompat()) {
                if (isCancelled()) {
                    process.destroyCompat()
                    reader.join(500)
                    return Result.failure(Exception("Cancelled"))
                }
                onProgress(dest.length())
                Thread.sleep(450)
            }
            reader.join(1200)
            val exit = process.exitValue()
            onProgress(dest.length())
            if (exit == 0 && dest.exists() && dest.length() > 0) {
                Result.success(dest)
            } else {
                Result.failure(Exception(output.toString().ifBlank { "dd exited with code $exit" }))
            }
        } catch (e: Exception) {
            runCatching { process.destroyCompat() }
            Result.failure(e)
        }
    }
}
