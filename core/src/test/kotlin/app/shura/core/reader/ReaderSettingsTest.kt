package app.shura.core.reader

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReaderSettingsTest {
    @Test
    fun autoFlipSettingsRespectAllowedDurationsAndPersistChoice() {
        val settings = ReaderSettings()

        settings.autoFlip.enabled = true
        settings.autoFlip.durationSeconds = 10
        settings.autoFlip.pauseOnTouch = true

        assertTrue(settings.autoFlip.enabled)
        assertEquals(10, settings.autoFlip.durationSeconds)
        assertTrue(settings.autoFlip.pauseOnTouch)
    }

    @Test
    fun rejectsUnsafeCustomDurations() {
        val settings = ReaderSettings()

        assertFalse(settings.autoFlip.setDuration(0))
        assertFalse(settings.autoFlip.setDuration(45))
        assertTrue(settings.autoFlip.setDuration(12))
        assertEquals(12, settings.autoFlip.durationSeconds)
        assertEquals(listOf(3, 5, 10, 15, 30), AutoFlipSettings.PRESET_DURATIONS_SECONDS)
    }

    @Test
    fun keepsOriginalImageAndOnlyAppliesTranslationAsOverlay() {
        val translator = TranslationOverlayEngine()
        val page = PageTranslation(originalText = "敵が来る", translatedText = "العدو قادم")

        val result = translator.renderOverlay(page)

        assertEquals("敵が来る", result.originalText)
        assertEquals("العدو قادم", result.translatedText)
        assertFalse(result.mutatesOriginalImage)
    }
}
