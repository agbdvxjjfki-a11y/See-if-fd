package app.shura.core.repository

import app.shura.core.content.ContentPolicyEngine
import java.lang.management.ManagementFactory
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class RepositoryCatalogPerformanceTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    @Test
    fun parsesFiltersSearchesAndSavesARepeatableTenThousandEntryIndex() {
        val entryCount = 10_000
        val json = syntheticIndex(entryCount)
        val repository = RepositoryAddress.parse("https://performance.example/extensions")
        val parser = RepositoryCatalogParser()
        val policy = ContentPolicyEngine()
        val allocationBean = ManagementFactory.getThreadMXBean() as? com.sun.management.ThreadMXBean
            ?: error("Thread allocation measurements are unavailable on this JVM")
        require(allocationBean.isThreadAllocatedMemorySupported) {
            "Thread allocation measurements are unsupported on this JVM"
        }
        if (!allocationBean.isThreadAllocatedMemoryEnabled) {
            allocationBean.isThreadAllocatedMemoryEnabled = true
        }
        val callerThreadId = Thread.currentThread().id
        val parseAllocatedBefore = allocationBean.getThreadAllocatedBytes(callerThreadId)
        val parseStarted = System.nanoTime()
        val parsed = parser.parse(json, repository)
        val parseMillis = elapsedMillis(parseStarted)

        val filterAllocatedBefore = allocationBean.getThreadAllocatedBytes(callerThreadId)
        val filterStarted = System.nanoTime()
        val allowed = parsed.filterNot(policy::isBlocked)
        val filterMillis = elapsedMillis(filterStarted)
        val filterAllocatedBytes =
            allocationBean.getThreadAllocatedBytes(callerThreadId) - filterAllocatedBefore

        val parseAllocatedBytes = allocationBean.getThreadAllocatedBytes(callerThreadId) - parseAllocatedBefore
        val searchStarted = System.nanoTime()
        val searchResults = allowed.filter {
            it.name.contains("Series 09998", ignoreCase = true) ||
                it.packageName.contains("extension09998", ignoreCase = true)
        }
        val searchMillis = elapsedMillis(searchStarted)

        val sortStarted = System.nanoTime()
        val sortedLanguageResults = allowed.asSequence()
            .filter { it.language == "ja" }
            .sortedByDescending { it.versionCode }
            .toList()
        val sortMillis = elapsedMillis(sortStarted)

        val store = FileRepositoryConfigurationStore(temporaryDirectory.resolve("repositories.json"))
        val callerThreadName = Thread.currentThread().name
        val parsingThreadName = AtomicReference<String?>()
        val backgroundParseAllocatedBytes = AtomicLong(-1L)
        val parserExecutor = Executors.newSingleThreadExecutor { command ->
            Thread(command, "catalog-performance-parser")
        }
        val manager = RepositoryManager(
            store = store,
            indexSource = RepositoryIndexSource { fetched(json, it) },
            parsingExecutor = Executor { command ->
                parserExecutor.execute {
                    parsingThreadName.set(Thread.currentThread().name)
                    val threadId = Thread.currentThread().id
                    val before = allocationBean.getThreadAllocatedBytes(threadId)
                    command.run()
                    backgroundParseAllocatedBytes.set(allocationBean.getThreadAllocatedBytes(threadId) - before)
                }
            },
        )
        val refreshStarted = System.nanoTime()
        val repeatedRefreshMillis = mutableListOf<Long>()
        val snapshot = try {
            manager.addRepository(repository.inputUrl).also {
                repeat(3) {
                    val repeatedStarted = System.nanoTime()
                    val refreshed = manager.refreshRepository(repository.inputUrl)
                    repeatedRefreshMillis += elapsedMillis(repeatedStarted)
                    assertEquals(entryCount - (0 until entryCount).count { index ->
                        index % 197 == 0 || index % 251 == 0
                    }, refreshed.extensions.size)
                }
            }
        } finally {
            parserExecutor.shutdownNow()
        }
        val refreshAndSaveMillis = elapsedMillis(refreshStarted)

        val managerSearchStarted = System.nanoTime()
        val indexedSearchResults = manager.search(query = "extension09998")
        val managerSearchMillis = elapsedMillis(managerSearchStarted)

        val configSaveStarted = System.nanoTime()
        store.save(listOf(repository.inputUrl))
        val configSaveMillis = elapsedMillis(configSaveStarted)

        assertEquals(entryCount, parsed.size)
        assertEquals(entryCount - (0 until entryCount).count { it % 197 == 0 || it % 251 == 0 }, allowed.size)
        assertEquals(1, searchResults.size)
        assertEquals("org.example.extension09998", indexedSearchResults.single().packageName)
        assertEquals(allowed.size, snapshot.extensions.size)
        assertTrue(parsingThreadName.get() != callerThreadName)
        assertTrue(sortedLanguageResults.isNotEmpty())
        assertEquals(listOf(repository.inputUrl), store.load())

        println(
            "SHURA_CATALOG_PERF entries=$entryCount bytes=${json.toByteArray().size} " +
                "parse_ms=$parseMillis parse_thread_allocated_bytes=$parseAllocatedBytes " +
                "filter_ms=$filterMillis filter_thread_allocated_bytes=$filterAllocatedBytes " +
                "search_ms=$searchMillis language_sort_ms=$sortMillis " +
                "manager_parse_filter_config_save_ms=$refreshAndSaveMillis " +
                "repeated_refresh_ms=${repeatedRefreshMillis.joinToString(",")} " +
                "manager_search_ms=$managerSearchMillis config_save_ms=$configSaveMillis " +
                "background_parse_thread=$parsingThreadName background_parse_thread_allocated_bytes=$backgroundParseAllocatedBytes",
        )
    }

    private fun syntheticIndex(entryCount: Int): String = buildString(entryCount * 190) {
        append('[')
        repeat(entryCount) { index ->
            if (index > 0) append(',')
            val language = LANGUAGES[index % LANGUAGES.size]
            val name = when {
                index % 197 == 0 -> "Explicit Series ${index.toString().padStart(5, '0')}"
                else -> "Series ${index.toString().padStart(5, '0')}"
            }
            val adultFlag = if (index % 251 == 0) 1 else 0
            append(
                """{"name":"$name","pkg":"org.example.extension${index.toString().padStart(5, '0')}","apk":"apk/$index.apk","lang":"$language","version":"1.${index % 50}.${index % 10}","code":$index,"nsfw":$adultFlag,"sources":[{"name":"Source ${index % 31}","lang":"$language"}]}""",
            )
        }
        append(']')
    }

    private fun fetched(body: String, repository: RepositoryAddress): FetchedRepositoryIndex {
        val indexUrl = repository.indexCandidates().first { it.path.endsWith(".json") }.toString()
        val diagnostic = IndexRequestDiagnostic(
            inputUrl = repository.inputUrl,
            requestedUrl = indexUrl,
            finalUrl = indexUrl,
            statusCode = 200,
            contentType = "application/json",
            redirects = emptyList(),
            redirectStatuses = emptyList(),
            bodyDownloaded = true,
        )
        return FetchedRepositoryIndex(
            body = body.toByteArray(),
            contentType = "application/json",
            diagnostic = diagnostic,
            attempts = listOf(diagnostic),
        )
    }

    private fun elapsedMillis(startedAt: Long): Long =
        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)

    private companion object {
        val LANGUAGES = listOf("en", "ar", "ja", "fr", "es")
    }
}
