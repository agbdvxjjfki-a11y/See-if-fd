package app.shura.core.repository

import java.nio.file.Path
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
