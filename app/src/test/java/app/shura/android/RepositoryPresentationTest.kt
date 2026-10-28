package app.shura.android

import app.shura.core.repository.ExtensionDescriptor
import app.shura.core.repository.FetchedRepositoryIndex
import app.shura.core.repository.IndexRequestDiagnostic
import app.shura.core.repository.RepositoryAddress
import app.shura.core.repository.RepositoryCatalogSnapshot
import app.shura.core.repository.RepositoryConfigurationStore
import app.shura.core.repository.RepositoryIndexSource
import app.shura.core.repository.RepositoryManager
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RepositoryPresentationTest {
    @Test
    fun extensionCatalogFeedsAllParsedRepositoryEntriesToPresentation() {
        val first = extension("org.example.one", "English reader", "en")
        val second = extension("org.example.two", "Lecteur", "fr")
        val state = ShuraUiState(
            repositories = listOf(
                RepositoryCatalogSnapshot(
                    repository = RepositoryAddress.parse("https://example.org/repo/index.json"),
                    extensions = listOf(first, second),
                ),
            ),
        )

        assertEquals(listOf(first, second), state.extensionCatalog())
    }

    @Test
    fun mihonIndexFlowsThroughParserRepositoryManagerAndUiState() {
        val indexJson =
            """[{"name":"Fixture Reader","pkg":"org.example.fixture.en","apk":"apk/fixture.apk","lang":"en","version":"1.2.3","code":123,"nsfw":0,"sources":[{"name":"Fixture Source","lang":"en","id":"321","baseUrl":"https://source.example"}]}]"""
        val manager = RepositoryManager(
            store = InMemoryRepositoryStore(),
            indexSource = RepositoryIndexSource { repository ->
                val finalUrl = repository.indexCandidates().first().toString()
                FetchedRepositoryIndex(
                    body = indexJson.toByteArray(),
                    contentType = "application/json",
                    diagnostic = IndexRequestDiagnostic(
                        inputUrl = repository.inputUrl,
                        requestedUrl = finalUrl,
                        finalUrl = finalUrl,
                        statusCode = 200,
                        contentType = "application/json",
                        redirects = emptyList(),
                        redirectStatuses = emptyList(),
                        bodyDownloaded = true,
                    ),
                    attempts = emptyList(),
                )
            },
        )

        val added = manager.addRepository("https://example.org/repository/index.min.json")
        val state = ShuraUiState(repositories = manager.repositories())

        assertEquals(1, added.extensions.size)
        assertEquals("Fixture Reader", state.extensionCatalog().single().name)
        assertEquals("org.example.fixture.en", state.extensionCatalog().single().packageName)
        assertEquals(123L, state.extensionCatalog().single().versionCode)
        assertEquals("Fixture Source", state.extensionCatalog().single().sources.single().name)
    }

    @Test
    fun launcherNameMatchesRequestedArabicAndEnglishBranding() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        val arabicStrings = File("src/main/res/values/strings.xml").readText()
        val englishStrings = File("src/main/res/values-en/strings.xml").readText()

        assertTrue(manifest.contains("""android:label="@string/app_name""""))
        assertTrue(arabicStrings.contains("""<string name="app_name">شورا</string>"""))
        assertTrue(englishStrings.contains("""<string name="app_name">Shura</string>"""))
        assertTrue(!arabicStrings.contains("Sura Beta"))
        assertTrue(!englishStrings.contains("Sura Beta"))
    }

    private fun extension(packageName: String, name: String, language: String) =
        ExtensionDescriptor(
            name = name,
            packageName = packageName,
            apkPath = "$packageName.apk",
            language = language,
            version = "1.0",
            versionCode = 1,
            repositoryUrl = "https://example.org/repo/index.json",
            adultFlag = 0,
            apkUrl = "https://example.org/repo/$packageName.apk",
        )

    private class InMemoryRepositoryStore : RepositoryConfigurationStore {
        override fun load(): List<String> = emptyList()

        override fun save(repositoryUrls: List<String>) = Unit
    }
}
