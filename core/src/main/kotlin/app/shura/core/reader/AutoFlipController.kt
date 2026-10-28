package app.shura.core.reader

import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class AutoFlipController(
    private val settings: AutoFlipSettings,
    private val isPageReady: () -> Boolean,
    private val advancePage: () -> Boolean,
    private val onError: (Exception) -> Unit = {},
) : AutoCloseable {
    private val scheduler: ScheduledExecutorService = newScheduler()
    private var running = false
    private var closed = false
    private var pendingFlip: ScheduledFuture<*>? = null

    @Synchronized
    fun start() {
        check(!closed) { "التقليب التلقائي مغلق" }
        settings.enabled = true
        if (running) return
        running = true
        scheduleNext()
    }

    @Synchronized
    fun pause() {
        settings.enabled = false
        running = false
        cancelPending()
    }

    fun onTouch() {
        if (settings.pauseOnTouch) pause()
    }

    @Synchronized
    fun setDuration(seconds: Int): Boolean {
        if (!settings.setDuration(seconds)) return false
        if (running) {
            cancelPending()
            scheduleNext()
        }
        return true
    }

    @Synchronized
    override fun close() {
        if (closed) return
        pause()
        closed = true
        scheduler.shutdownNow()
    }

    private fun scheduleNext() {
        if (!running || closed) return
        pendingFlip = scheduler.schedule(
            ::flipIfReady,
            settings.durationSeconds.toLong(),
            TimeUnit.SECONDS,
        )
    }

    @Synchronized
    private fun flipIfReady() {
        pendingFlip = null
        if (!running || closed) return
        try {
            if (isPageReady() && !advancePage()) {
                pause()
                return
            }
        } catch (error: Exception) {
            pause()
            onError(error)
            return
        }
        scheduleNext()
    }

    private fun cancelPending() {
        pendingFlip?.cancel(false)
        pendingFlip = null
    }

    companion object {
        private val schedulerIds = AtomicInteger()

        private fun newScheduler(): ScheduledExecutorService =
            Executors.newSingleThreadScheduledExecutor { runnable ->
                Thread(runnable, "shura-auto-flip-${schedulerIds.incrementAndGet()}").apply {
                    isDaemon = true
                }
            }
    }
}
