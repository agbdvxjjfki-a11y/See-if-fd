package app.shura.android

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ReaderPerformanceSettingsTest {
    @Test
    fun memorySaverUsesSmallerPowerOfTwoDecodeForLargeImages() {
        assertEquals(2, readerImageSampleSize(3_000, 2_000, reduceImageMemory = false))
        assertEquals(4, readerImageSampleSize(3_000, 2_000, reduceImageMemory = true))
        assertEquals(1, readerImageSampleSize(1_024, 768, reduceImageMemory = true))
    }

    @Test
    fun imageDecodeRejectsInvalidDimensions() {
        assertFailsWith<IllegalArgumentException> {
            readerImageSampleSize(0, 100, reduceImageMemory = false)
        }
    }
}
