package app.shura.core.repository

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RepositoryCatalogParserTest {
    private val parser = RepositoryCatalogParser()
    private val repository = RepositoryAddress.parse("https://example.org/extensions/")

    @Test
    fun parsesMihonStyleIndexAndKeepsRepositoryProvenance() {
        val entries = parser.parse(
            """[{"name":"Example","pkg":"org.example.extension.en","apk":"apk/extension.apk","lang":"en","version":"1.2.0","versionCode":12,"nsfw":0}]""",
            repository,
        )

        assertEquals(1, entries.size)
        assertEquals("org.example.extension.en", entries.single().packageName)
        assertEquals("https://example.org/extensions", entries.single().repositoryUrl)
        assertEquals(12L, entries.single().versionCode)
    }

    @Test
    fun rejectsMalformedIndexAndDuplicatePackages() {
        assertFailsWith<IllegalArgumentException> { parser.parse("{}", repository) }
        assertFailsWith<IllegalArgumentException> {
            parser.parse(
                """[{"name":"Example","pkg":"org.example.ext","apk":"../extension.apk","lang":"en","version":"1","versionCode":1}]""",
                repository,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            parser.parse(
                """[{"name":"A","pkg":"org.example.ext","apk":"a.apk","lang":"en","version":"1","versionCode":1},{"name":"B","pkg":"org.example.ext","apk":"b.apk","lang":"en","version":"2","versionCode":2}]""",
                repository,
            )
        }
    }

    @Test
    fun repositoryAddressRequiresHttpsAndNormalizesDuplicates() {
        assertFailsWith<IllegalArgumentException> { RepositoryAddress.parse("http://example.org/repo") }
        assertEquals(
            RepositoryAddress.parse("https://example.org/repo/"),
            RepositoryAddress.parse("https://EXAMPLE.org/repo"),
        )
    }

    @Test
    fun resolvesArtifactsInsideRepositoryAndRejectsTraversal() {
        assertEquals(
            "https://example.org/extensions/apk/extension.apk",
            repository.artifactUri("apk/extension.apk").toString(),
        )
        assertFailsWith<IllegalArgumentException> { repository.artifactUri("../private.apk") }
        assertFailsWith<IllegalArgumentException> { repository.artifactUri("/outside.apk") }
        assertFailsWith<IllegalArgumentException> {
            repository.artifactUri("https://other.example/extension.apk")
        }
    }
}