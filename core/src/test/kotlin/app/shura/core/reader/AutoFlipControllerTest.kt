package app.shura.core.reader

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AutoFlipControllerTest {
    @Test
    fun waitsForPageReadinessAndStopsAtChapterEnd() {
        val settings = AutoFlipSettings()
        settings.durationSeconds = 1
        val pageReady = AtomicBoolean(false)
        val checkedWhileUnready = CountDownLatch(1)
        val reachedEnd = CountDownLatch(1)
        val controller = AutoFlipController(
            settings = settings,
            isPageReady = {
                if (!pageReady.get()) checkedWhileUnready.countDown()
                pageReady.get()
            },
            advancePage = {
                reachedEnd.countDown()
                false
            },
        )

        controller.use {
            controller.start()
            assertTrue(checkedWhileUnready.await(3, TimeUnit.SECONDS))
            assertTrue(reachedEnd.count == 1L)

            pageReady.set(true)
            assertTrue(reachedEnd.await(3, TimeUnit.SECONDS))
            assertFalse(settings.enabled)
        }
    }

    @Test
    fun touchPauseCancelsPendingFlipWhenConfigured() {
        val settings = AutoFlipSettings().apply {
            durationSeconds = 1
            pauseOnTouch = true
        }
        val flips = CountDownLatch(1)
        val controller = AutoFlipController(
            settings = settings,
            isPageReady = { true },
            advancePage = {
                flips.countDown()
                true
            },
        )

        controller.use {
            controller.start()
            controller.onTouch()
            Thread.sleep(1_100)
            assertTrue(flips.count == 1L)
            assertFalse(settings.enabled)
        }
    }

    @Test
    fun changingDurationResetsThePendingTimerAndRejectsUnsafeValues() {
        val settings = AutoFlipSettings()
        val controller = AutoFlipController(
            settings = settings,
            isPageReady = { true },
            advancePage = { true },
        )

        controller.use {
            controller.start()
            assertTrue(controller.setDuration(10))
            assertFalse(controller.setDuration(45))
            assertTrue(settings.durationSeconds == 10)
            controller.pause()
            assertFalse(settings.enabled)
        }
    }
}
