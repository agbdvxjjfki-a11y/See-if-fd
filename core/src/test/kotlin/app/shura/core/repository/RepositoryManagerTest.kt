package app.shura.core.repository

import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class RepositoryManagerTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    @Test
    fun persistsRepositoriesAcrossManagerInstancesAndRejectsDuplicates() {
        val store = FileRepositoryConfigurationStore(temporaryDirectory.resolve("repositories.json"))
        val firstManager = RepositoryManager(store, RepositoryIndexSource { fetched("[]", it) })
        firstManager.addRepository("https://example.org/extensions/index.min.json")
        assertFailsWith<IllegalArgumentException> {
            firstManager.addRepository("https://EXAMPLE.org/extensions/index.min.json")
        }

        val restoredManager = RepositoryManager(store, RepositoryIndexSource { fetched("[]", it) })
        assertEquals(
            listOf("https://example.org/extensions/index.min.json"),
            restoredManager.repositories().map { it.repository.normalized },
        )
        assertTrue(restoredManager.removeRepository("https://example.org/extensions/index.min.json"))
        assertTrue(RepositoryManager(store, RepositoryIndexSource { fetched("[]", it) }).repositories().isEmpty())
    }

    @Test
    fun refreshSearchAndUpdateDiscoveryUseValidatedCatalogData() {
        val store = InMemoryRepositoryStore()
        var index = indexJson(versionCode = 4)
        val manager = RepositoryManager(store, RepositoryIndexSource { fetched(index, it) })
        manager.addRepository("https://example.org/extensions")

        val refreshed = manager.refreshRepository("https://example.org/extensions/")
        assertEquals(1, refreshed.extensions.size)
        assertNotNull(refreshed.lastUpdated)
        assertEquals("Reader", manager.search(query = "reader", language = "en").single().name)
        assertEquals(
            listOf("org.example.reader"),
            manager.availableUpdates(mapOf("org.example.reader" to 3)).map { it.packageName },
        )

        index = indexJson(versionCode = 5)
        assertEquals(5L, manager.refreshRepository("https://example.org/extensions").extensions.single().versionCode)
    }

    @Test
    fun testsBeforeSavingAndOnlyPersistsAParsedDirectIndexUrl() {
        val store = InMemoryRepositoryStore()
        val url = "https://RAW.githubusercontent.com/owner/extensions/repo/index.min.json"
        val manager = RepositoryManager(
            store,
            RepositoryIndexSource { fetched(indexJson(versionCode = 7), it) },
        )

        val tested = manager.testRepository(url)
        assertEquals(emptyList(), store.load())
        assertEquals(1, tested.extensions.size)
        assertEquals("https://raw.githubusercontent.com/owner/extensions/repo/index.min.json", tested.repository.normalized)
        assertEquals("https://raw.githubusercontent.com/owner/extensions/repo/index.min.json", manager.addRepository(url).repository.normalized)
        assertEquals(listOf(url), store.load())
        assertEquals(url, RepositoryManager(store, RepositoryIndexSource { fetched("[]", it) })
            .repositories().single().repository.inputUrl)
    }

    @Test
    fun replacingRepositoryValidatesFirstAndKeepsTheOldIndexUntilCommit() {
        val store = InMemoryRepositoryStore()
        val oldUrl = "https://example.org/old"
        val newUrl = "https://example.org/new"
        var rejectReplacement = true
        val manager = RepositoryManager(store, RepositoryIndexSource { repository ->
            if (repository.normalized == newUrl && rejectReplacement) {
                error("replacement unavailable")
            }
            fetched(indexJson(versionCode = 8), repository)
        })
        manager.addRepository(oldUrl)

        assertFailsWith<IllegalStateException> {
            manager.replaceRepository(oldUrl, newUrl)
        }
        assertEquals(listOf(oldUrl), store.load())
        assertEquals(listOf(oldUrl), manager.repositories().map { it.repository.normalized })

        rejectReplacement = false
        val replaced = manager.replaceRepository(oldUrl, newUrl)
        assertEquals(newUrl, replaced.repository.normalized)
        assertEquals(listOf(newUrl), store.load())
        assertEquals(listOf(newUrl), manager.repositories().map { it.repository.normalized })

        val restored = RepositoryManager(store, RepositoryIndexSource { fetched("[]", it) })
        assertEquals(listOf(newUrl), restored.repositories().map { it.repository.normalized })
    }

    @Test
    fun doesNotPersistAnIndexThatCannotBeParsed() {
        val store = InMemoryRepositoryStore()
        val manager = RepositoryManager(
            store,
            RepositoryIndexSource { fetched("not json", it) },
        )

        assertFailsWith<IllegalArgumentException> {
            manager.addRepository("https://example.org/repo/index.json")
        }
        assertEquals(emptyList(), store.load())
    }

    @Test
    fun failedRefreshKeepsLastGoodCatalogAndExposesRetryableError() {
        val store = InMemoryRepositoryStore()
        var fail = false
        val manager = RepositoryManager(store, RepositoryIndexSource {
            if (fail) error("network unavailable")
            fetched(indexJson(versionCode = 1), it)
        })
        manager.addRepository("https://example.org/extensions")
        manager.refreshRepository("https://example.org/extensions")

        fail = true
        val failed = manager.refreshRepository("https://example.org/extensions")
        assertEquals("network unavailable", failed.error)
        assertEquals(1L, failed.extensions.single().versionCode)

        fail = false
        assertEquals(null, manager.refreshRepository("https://example.org/extensions").error)
    }

    @Test
    fun cachedCatalogSurvivesProcessRestartAndFailedRefresh() {
        val configurationStore = FileRepositoryConfigurationStore(temporaryDirectory.resolve("repositories.json"))
        val cacheStore = FileRepositoryCatalogCacheStore(temporaryDirectory.resolve("catalog-cache"))
        var failNetwork = false
        val source = RepositoryIndexSource { repository ->
            if (failNetwork) error("network unavailable after restart")
            fetched(indexJson(versionCode = 9), repository)
        }
        val firstManager = RepositoryManager(
            store = configurationStore,
            indexSource = source,
            catalogCacheStore = cacheStore,
        )
        val firstSnapshot = firstManager.addRepository("https://example.org/extensions")

        failNetwork = true
        val restartedManager = RepositoryManager(
            store = configurationStore,
            indexSource = source,
            catalogCacheStore = cacheStore,
        )
        val refreshed = restartedManager.refreshAll().single()

        assertEquals(9L, refreshed.extensions.single().versionCode)
        assertEquals(firstSnapshot.lastUpdated, refreshed.lastUpdated)
        assertEquals("network unavailable after restart", refreshed.error)
        assertEquals(9L, cacheStore.load("https://example.org/extensions")?.extensions?.single()?.versionCode)
    }

    @Test
    fun cachedCatalogIsRecheckedByContentPolicyAfterRestart() {
        val configurationStore = FileRepositoryConfigurationStore(temporaryDirectory.resolve("policy-repositories.json"))
        val cacheStore = FileRepositoryCatalogCacheStore(temporaryDirectory.resolve("policy-catalog-cache"))
        val source = RepositoryIndexSource { repository -> fetched(policyIndex(), repository) }
        RepositoryManager(
            store = configurationStore,
            indexSource = source,
            catalogCacheStore = cacheStore,
        ).addRepository("https://example.org/extensions")

        val restarted = RepositoryManager(
            store = configurationStore,
            indexSource = RepositoryIndexSource { error("offline") },
            catalogCacheStore = cacheStore,
        )
        val restored = restarted.refreshAll().single()

        assertEquals(listOf("org.example.safe"), restored.extensions.map { it.packageName })
        assertEquals("offline", restored.error)
    }

    @Test
    fun policyBlockedExtensionsNeverEnterCatalogSearchUpdatesOrRestoredCatalog() {
        val store = InMemoryRepositoryStore()
        val source = RepositoryIndexSource { fetched(policyIndex(), it) }
        val manager = RepositoryManager(store, source)

        val added = manager.addRepository("https://example.org/extensions")

        assertEquals(listOf("org.example.safe"), added.extensions.map { it.packageName })
        assertEquals(listOf("org.example.safe"), manager.search(query = "reader").map { it.packageName })
        assertEquals(listOf("org.example.safe"), manager.availableUpdates(mapOf(
            "org.example.safe" to 1L,
            "org.example.blocked" to 1L,
        )).map { it.packageName })

        val restoredManager = RepositoryManager(store, source)
        val restored = restoredManager.refreshAll().single()
        assertEquals(listOf("org.example.safe"), restored.extensions.map { it.packageName })
        assertTrue(restored.error == null)
    }

    @Test
    fun policyRemovesOnlyBlockedExtensionsAndKeepsAllowedExtensionsFromSameRepository() {
        val manager = RepositoryManager(
            InMemoryRepositoryStore(),
            RepositoryIndexSource { fetched(policyIndex(), it) },
        )

        val result = manager.testRepository("https://example.org/extensions")

        assertEquals(1, result.extensions.size)
        assertEquals("org.example.safe", result.extensions.single().packageName)
    }

    @Test
    fun repositoryReadsDoNotWaitForNetworkFetchInsideManagerLock() {
        val requestStarted = CountDownLatch(1)
        val allowRequestToFinish = CountDownLatch(1)
        val manager = RepositoryManager(
            InMemoryRepositoryStore(),
            RepositoryIndexSource { repository ->
                requestStarted.countDown()
                check(allowRequestToFinish.await(5, TimeUnit.SECONDS))
                fetched("[]", repository)
            },
        )
        val executor = Executors.newFixedThreadPool(2)
        try {
            val addRepository = executor.submit {
                manager.addRepository("https://example.org/extensions")
            }
            assertTrue(requestStarted.await(5, TimeUnit.SECONDS))

            val reads = executor.submit<List<RepositoryCatalogSnapshot>> { manager.repositories() }
            assertEquals(emptyList(), reads.get(500, TimeUnit.MILLISECONDS))

            allowRequestToFinish.countDown()
            addRepository.get(5, TimeUnit.SECONDS)
            assertEquals(1, manager.repositories().size)
        } finally {
            allowRequestToFinish.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun concurrentAddsPersistEveryDistinctRepositoryWithoutLostUpdates() {
        val requestsStarted = CountDownLatch(2)
        val allowRequestsToFinish = CountDownLatch(1)
        val store = InMemoryRepositoryStore()
        val manager = RepositoryManager(store, RepositoryIndexSource { repository ->
            requestsStarted.countDown()
            check(allowRequestsToFinish.await(5, TimeUnit.SECONDS))
            fetched("[]", repository)
        })
        val executor = Executors.newFixedThreadPool(2)
        try {
            val first = executor.submit { manager.addRepository("https://example.org/first") }
            val second = executor.submit { manager.addRepository("https://example.org/second") }
            assertTrue(requestsStarted.await(5, TimeUnit.SECONDS))
            allowRequestsToFinish.countDown()

            first.get(5, TimeUnit.SECONDS)
            second.get(5, TimeUnit.SECONDS)

            assertEquals(
                setOf("https://example.org/first", "https://example.org/second"),
                store.load().toSet(),
            )
            assertEquals(2, manager.repositories().size)
        } finally {
            allowRequestsToFinish.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun concurrentDuplicateAddsCommitOnlyOneRepository() {
        val requestsStarted = CountDownLatch(2)
        val allowRequestsToFinish = CountDownLatch(1)
        val store = InMemoryRepositoryStore()
        val manager = RepositoryManager(store, RepositoryIndexSource { repository ->
            requestsStarted.countDown()
            check(allowRequestsToFinish.await(5, TimeUnit.SECONDS))
            fetched("[]", repository)
        })
        val executor = Executors.newFixedThreadPool(2)
        try {
            val first = executor.submit<Boolean> {
                try {
                    manager.addRepository("https://example.org/extensions")
                    true
                } catch (_: IllegalArgumentException) {
                    false
                }
            }
            val second = executor.submit<Boolean> {
                try {
                    manager.addRepository("https://example.org/extensions")
                    true
                } catch (_: IllegalArgumentException) {
                    false
                }
            }
            assertTrue(requestsStarted.await(5, TimeUnit.SECONDS))
            allowRequestsToFinish.countDown()

            val successes = listOf(first.get(5, TimeUnit.SECONDS), second.get(5, TimeUnit.SECONDS))
            assertEquals(1, successes.count { it })
            assertEquals(1, successes.count { !it })
            assertEquals(listOf("https://example.org/extensions"), store.load())
            assertEquals(1, manager.repositories().size)
        } finally {
            allowRequestsToFinish.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun olderConcurrentRefreshCannotOverwriteNewerCatalog() {
        val fetchCount = AtomicInteger()
        val olderRefreshStarted = CountDownLatch(1)
        val finishOlderRefresh = CountDownLatch(1)
        val manager = RepositoryManager(InMemoryRepositoryStore(), RepositoryIndexSource { repository ->
            when (fetchCount.incrementAndGet()) {
                1 -> fetched(indexJson(versionCode = 1), repository)
                2 -> {
                    olderRefreshStarted.countDown()
                    check(finishOlderRefresh.await(5, TimeUnit.SECONDS))
                    fetched(indexJson(versionCode = 2), repository)
                }
                else -> fetched(indexJson(versionCode = 3), repository)
            }
        })
        manager.addRepository("https://example.org/extensions")
        val executor = Executors.newFixedThreadPool(2)
        try {
            val olderRefresh = executor.submit<RepositoryCatalogSnapshot> {
                manager.refreshRepository("https://example.org/extensions")
            }
            assertTrue(olderRefreshStarted.await(5, TimeUnit.SECONDS))

            val newerRefresh = executor.submit<RepositoryCatalogSnapshot> {
                manager.refreshRepository("https://example.org/extensions")
            }
            assertEquals(3L, newerRefresh.get(5, TimeUnit.SECONDS).extensions.single().versionCode)

            finishOlderRefresh.countDown()
            assertEquals(3L, olderRefresh.get(5, TimeUnit.SECONDS).extensions.single().versionCode)
            assertEquals(3L, manager.repositories().single().extensions.single().versionCode)
        } finally {
            finishOlderRefresh.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun parsesCatalogOnConfiguredBackgroundExecutor() {
        val executor = Executors.newSingleThreadExecutor { command ->
            Thread(command, "repository-parser-test")
        }
        val parsingThreadName = AtomicReference<String?>()
        val parsingExecutor = Executor { command ->
            executor.execute {
                parsingThreadName.set(Thread.currentThread().name)
                command.run()
            }
        }
        try {
            val manager = RepositoryManager(
                InMemoryRepositoryStore(),
                RepositoryIndexSource { fetched(indexJson(versionCode = 4), it) },
                parsingExecutor = parsingExecutor,
            )

            manager.testRepository("https://example.org/extensions")

            assertEquals("repository-parser-test", parsingThreadName.get())
        } finally {
            executor.shutdownNow()
        }
    }

    private fun indexJson(versionCode: Int): String =
        """[{"name":"Reader","pkg":"org.example.reader","apk":"apk/reader.apk","lang":"en","version":"1.$versionCode","versionCode":$versionCode,"nsfw":0}]"""

    private fun policyIndex(): String =
        """[{"name":"Hentai Reader","pkg":"org.example.blocked","apk":"blocked.apk","lang":"en","version":"1.0","code":2,"nsfw":0},{"name":"Romance Reader","pkg":"org.example.safe","apk":"safe.apk","lang":"en","version":"1.0","code":2,"nsfw":0}]"""

    private fun fetched(body: String, repository: RepositoryAddress): FetchedRepositoryIndex {
        val indexUrl = repository.indexCandidates()
            .firstOrNull { it.path.endsWith(".json") }
            ?.toString()
            ?: repository.indexCandidates().first().toString()
        val diagnostic = IndexRequestDiagnostic(
            inputUrl = repository.normalized,
            requestedUrl = indexUrl,
            finalUrl = indexUrl,
            statusCode = 200,
            contentType = "application/json",
            redirects = emptyList(),
            redirectStatuses = emptyList(),
            bodyDownloaded = true,
        )
        return FetchedRepositoryIndex(body.toByteArray(), "application/json", diagnostic, listOf(diagnostic))
    }

    private class InMemoryRepositoryStore : RepositoryConfigurationStore {
        private var urls: List<String> = emptyList()

        override fun load(): List<String> = urls.toList()

        override fun save(repositoryUrls: List<String>) {
            urls = repositoryUrls.toList()
        }
    }
}
