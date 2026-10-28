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
        val firstManager = RepositoryManager(store, RepositoryIndexSource { "[]" })
        firstManager.addRepository("https://example.org/extensions/")
        assertFailsWith<IllegalArgumentException> {
            firstManager.addRepository("https://EXAMPLE.org/extensions")
        }

        val restoredManager = RepositoryManager(store, RepositoryIndexSource { "[]" })
        assertEquals(
            listOf("https://example.org/extensions"),
            restoredManager.repositories().map { it.repository.normalized },
        )
        assertTrue(restoredManager.removeRepository("https://example.org/extensions"))
        assertTrue(RepositoryManager(store, RepositoryIndexSource { "[]" }).repositories().isEmpty())
    }

    @Test
    fun refreshSearchAndUpdateDiscoveryUseValidatedCatalogData() {
        val store = InMemoryRepositoryStore()
        var index = indexJson(versionCode = 4)
        val manager = RepositoryManager(store, RepositoryIndexSource { index })
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
    fun failedRefreshKeepsLastGoodCatalogAndExposesRetryableError() {
        val store = InMemoryRepositoryStore()
        var fail = false
        val manager = RepositoryManager(
            store,
            RepositoryIndexSource {
                if (fail) error("network unavailable")
                indexJson(versionCode = 1)
            },
        )
        manager.addRepository("https://example.org/extensions")
        manager.refreshRepository("https://example.org/extensions")

        fail = true
        val failed = manager.refreshRepository("https://example.org/extensions")
        assertEquals("network unavailable", failed.error)
        assertEquals(1L, failed.extensions.single().versionCode)

        fail = false
        assertEquals(null, manager.refreshRepository("https://example.org/extensions").error)
    }

    private fun indexJson(versionCode: Int): String =
        """[{"name":"Reader","pkg":"org.example.reader","apk":"apk/reader.apk","lang":"en","version":"1.$versionCode","versionCode":$versionCode,"nsfw":0}]"""

    private class InMemoryRepositoryStore : RepositoryConfigurationStore {
        private var urls: List<String> = emptyList()

        override fun load(): List<String> = urls.toList()

        override fun save(repositoryUrls: List<String>) {
            urls = repositoryUrls.toList()
        }
    }
}
