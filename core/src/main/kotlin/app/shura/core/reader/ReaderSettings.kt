package app.shura.core.reader

class ReaderSettings(
    val autoFlip: AutoFlipSettings = AutoFlipSettings(),
)

class AutoFlipSettings {
    var enabled: Boolean = false
    var durationSeconds: Int = DEFAULT_DURATION_SECONDS
        set(value) {
            require(value in MIN_DURATION_SECONDS..MAX_DURATION_SECONDS) {
                "مدة التقليب التلقائي يجب أن تكون بين $MIN_DURATION_SECONDS و$MAX_DURATION_SECONDS ثانية"
            }
            field = value
        }
    var pauseOnTouch: Boolean = false

    fun setDuration(seconds: Int): Boolean {
        if (seconds !in MIN_DURATION_SECONDS..MAX_DURATION_SECONDS) return false
        durationSeconds = seconds
        return true
    }

    companion object {
        val PRESET_DURATIONS_SECONDS = listOf(3, 5, 10, 15, 30)

        private const val DEFAULT_DURATION_SECONDS = 5
        private const val MIN_DURATION_SECONDS = 1
        private const val MAX_DURATION_SECONDS = 30
    }

}

data class PageTranslation(
    val originalText: String,
    val translatedText: String,
)

data class TranslationOverlay(
    val originalText: String,
    val translatedText: String,
    val mutatesOriginalImage: Boolean,
)

class TranslationOverlayEngine {
    fun renderOverlay(page: PageTranslation): TranslationOverlay = TranslationOverlay(
        originalText = page.originalText,
        translatedText = page.translatedText,
        mutatesOriginalImage = false,
    )
}
