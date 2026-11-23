package com.phoenix.recover.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class DeepScannerTest {

    private fun jpeg(): ByteArray = byteArrayOf(
        0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(),
        0x00, 0x10, 0x4A, 0x46, 0x49, 0x46, 0x00, 0x01,
        0x01, 0x00, 0x00, 0x01, 0x00, 0x01, 0x00, 0x00,
    )

    @Test
    fun findsGalleryTrashFilesAndCleansTheirNames() {
        val root = Files.createTempDirectory("phoenix-scan").toFile()
        try {
            val trash = File(root, "DCIM/.trashed").apply { mkdirs() }
            val trashedPhoto = File(trash, ".trashed-1700000000-IMG_0001.jpg")
            trashedPhoto.writeBytes(jpeg())

            val camera = File(root, "DCIM/Camera").apply { mkdirs() }
            File(camera, "holiday.jpg").writeBytes(jpeg())
            File(camera, "notes.bin").writeBytes(ByteArray(32) { 0x11 })

            val found = mutableListOf<RecoveredFile>()
            val options = DeepScanOptions(minSize = 4, maxDepth = 8).apply {
                roots.add(root.absolutePath)
            }
            val result = DeepScanner(options).scan { event ->
                if (event is ScanEvent.Found) found += event.item
            }

            assertEquals(1, found.size)
            val photo = found.first()
            assertEquals(Origin.TRASH, photo.origin)
            assertEquals("IMG_0001.jpg", photo.name)
            assertTrue(photo.confidence >= 90)
            assertEquals(MediaKind.IMAGE, photo.kind)
            assertTrue(result.filesInspected >= 3)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun ignoresOrdinaryCameraFilesWhenNothingWasDeleted() {
        val root = Files.createTempDirectory("phoenix-scan").toFile()
        try {
            val camera = File(root, "DCIM/Camera").apply { mkdirs() }
            File(camera, "photo.jpg").writeBytes(jpeg())
            File(root, "Download/document.pdf").apply { parentFile?.mkdirs() }
                .writeBytes("%PDF-1.7\n".toByteArray())

            val found = mutableListOf<RecoveredFile>()
            val options = DeepScanOptions(minSize = 4, maxDepth = 8).apply {
                roots.add(root.absolutePath)
            }
            DeepScanner(options).scan { event ->
                if (event is ScanEvent.Found) found += event.item
            }

            assertTrue(found.isEmpty())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun findsWhatsAppDeletedMediaInsideAndroidMedia() {
        val root = Files.createTempDirectory("phoenix-scan").toFile()
        try {
            val trash = File(
                root,
                "Android/media/com.whatsapp/WhatsApp/Media/.Trashed",
            ).apply { mkdirs() }
            File(trash, "IMG-20240101-WA0001.jpg").writeBytes(jpeg())

            // A live WhatsApp image in the same tree must not be reported as deleted.
            val live = File(
                root,
                "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images",
            ).apply { mkdirs() }
            File(live, "IMG-20240102-WA0002.jpg").writeBytes(jpeg())

            val found = mutableListOf<RecoveredFile>()
            val options = DeepScanOptions(minSize = 4, maxDepth = 16).apply {
                roots.add(root.absolutePath)
            }
            DeepScanner(options).scan { event ->
                if (event is ScanEvent.Found) found += event.item
            }

            assertEquals(1, found.size)
            val photo = found.first()
            assertEquals(Origin.TRASH, photo.origin)
            assertEquals("IMG-20240101-WA0001.jpg", photo.name)
            assertEquals(MediaKind.IMAGE, photo.kind)
        } finally {
            root.deleteRecursively()
        }
    }
}
