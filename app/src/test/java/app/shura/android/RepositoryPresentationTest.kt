package app.shura.android

import app.shura.core.repository.ExtensionDescriptor
import app.shura.core.repository.RepositoryAddress
import app.shura.core.repository.RepositoryCatalogSnapshot
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
}
