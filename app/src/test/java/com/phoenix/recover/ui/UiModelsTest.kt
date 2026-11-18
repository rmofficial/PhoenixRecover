package com.phoenix.recover.ui

import com.phoenix.recover.engine.MediaKind
import com.phoenix.recover.engine.Origin
import com.phoenix.recover.engine.RecoveredFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UiModelsTest {

    private fun file(
        id: String,
        name: String,
        size: Long,
        format: String,
        confidence: Int,
    ) = RecoveredFile(
        id = id,
        name = name,
        kind = MediaKind.IMAGE,
        size = size,
        origin = Origin.DEEP_SCAN,
        mime = "image/jpeg",
        confidence = confidence,
        format = format,
    )

    @Test
    fun confidenceFilterBuckets() {
        assertTrue(ConfidenceFilter.STRONG.matches(82))
        assertTrue(ConfidenceFilter.STRONG.matches(100))
        assertFalse(ConfidenceFilter.STRONG.matches(81))

        assertTrue(ConfidenceFilter.LIKELY.matches(55))
        assertTrue(ConfidenceFilter.LIKELY.matches(81))
        assertFalse(ConfidenceFilter.LIKELY.matches(82))

        assertTrue(ConfidenceFilter.FRAGMENT.matches(54))
        assertTrue(ConfidenceFilter.FRAGMENT.matches(0))
        assertFalse(ConfidenceFilter.FRAGMENT.matches(55))
        assertTrue(ConfidenceFilter.ALL.matches(0))
    }

    @Test
    fun duplicatesAndStrongCounts() {
        val state = UiState(
            results = listOf(
                file("1", "IMG_001.jpg", 2048, "JPEG", 90),
                file("2", "IMG_001.jpg", 2048, "jpeg", 60),
                file("3", "IMG_002.jpg", 2048, "JPEG", 90),
                file("4", "VID_001.mp4", 4096, "MP4", 40),
            ),
        )

        assertEquals(2, state.duplicateCount)
        assertEquals(2, state.strongCount)
        assertEquals(1, state.duplicateGroups.size)
    }
}
