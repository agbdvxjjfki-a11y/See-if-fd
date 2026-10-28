package app.shura.core.repository

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import app.shura.core.repository.proto.Extension as ProtoExtension
import app.shura.core.repository.proto.ExtensionList
import app.shura.core.repository.proto.Index
import app.shura.core.repository.proto.Resources
import app.shura.core.repository.proto.Source as ProtoSource

class RepositoryCatalogParserTest {
    private val parser = RepositoryCatalogParser()
    private val repository = RepositoryAddress.parse("https://example.org/extensions/")

    @Test
    fun parsesMihonStyleIndexAndKeepsRepositoryProvenance() {
        val entries = parser.parse(
            """[{"name":"Example","pkg":"org.example.extension.en","apk":"apk/extension.apk","lang":"en","version":"1.2.0","code":12,"nsfw":0,"sources":[{"name":"Example","lang":"en","id":"123","baseUrl":"https://example.org"}]}]""",
            repository,
        )

        assertEquals(1, entries.size)
        assertEquals("org.example.extension.en", entries.single().packageName)
        assertEquals("https://example.org/extensions", entries.single().repositoryUrl)
        assertEquals(12L, entries.single().versionCode)
        assertEquals("https://example.org/extensions/apk/extension.apk", entries.single().apkUrl)
        assertEquals("123", entries.single().sources.single().id)
        assertEquals("https://example.org", entries.single().sources.single().homeUrl)
    }

    @Test
    fun supportsBothLegacyCodeFieldsAndRejectsMalformedIndexes() {
        assertEquals(
            12L,
            parser.parse(
                """[{"name":"Example","pkg":"org.example.ext","apk":"extension.apk","lang":"en","version":"1.2","versionCode":12}]""",
                repository,
            ).single().versionCode,
        )
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
        assertFailsWith<IllegalArgumentException> {
            parser.parse(
                """[{"name":"Missing fields","pkg":"org.example.ext","apk":"a.apk","lang":"en","version":"1"}]""",
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
        assertEquals(
            "https://cdn.example/extension.apk",
            repository.artifactUri("https://cdn.example/extension.apk").toString(),
        )
        assertEquals(
            "https://example.org/apk/extension.apk",
            repository.artifactUri("/apk/extension.apk").toString(),
        )
        assertFailsWith<IllegalArgumentException> { repository.artifactUri("http://cdn.example/extension.apk") }
    }

    @Test
    fun preservesDirectIndexUrlsAndOnlyAppendsForRepositoryDirectories() {
        val json = RepositoryAddress.parse("https://raw.githubusercontent.com/owner/repo/branch/index.min.json")
        val pb = RepositoryAddress.parse("https://github.com/owner/repo/raw/branch/index.pb")
        val directory = RepositoryAddress.parse("https://raw.githubusercontent.com/owner/repo/branch")

        assertEquals(json.normalized, json.indexCandidates().single().toString())
        assertEquals(pb.normalized, pb.indexCandidates().single().toString())
        assertEquals(
            listOf(
                "https://raw.githubusercontent.com/owner/repo/branch/index.pb",
                "https://raw.githubusercontent.com/owner/repo/branch/index.min.json",
                "https://raw.githubusercontent.com/owner/repo/branch/index.json",
            ),
            directory.indexCandidates().map { it.toString() },
        )
        assertFailsWith<IllegalArgumentException> {
            RepositoryAddress.parse("https://example.org/repository.apk")
        }
    }

    @Test
    fun parsesMihonProtobufSchemaAndResolvesApkRelativeToIndex() {
        val repository = RepositoryAddress.parse("https://github.com/keiyoushi/extensions/raw/repo/index.pb")
        val index = Index.newBuilder()
            .setName("Keiyoushi")
            .setBadgeLabel("KEI")
            .setExtensionList(
                ExtensionList.newBuilder().addExtensions(
                    ProtoExtension.newBuilder()
                        .setName("Example")
                        .setPackageName("org.example.extension")
                        .setResources(Resources.newBuilder().setApkUrl("extension.apk"))
                        .setVersionCode(42)
                        .setVersionName("1.2.0")
                        .addSources(
                            ProtoSource.newBuilder()
                                .setId(123)
                                .setName("Example source")
                                .setLanguage("en")
                                .setHomeUrl("https://source.example")
                                .addMirrorUrls("https://mirror.example")
                                .setMessage("Updated"),
                        ),
                ),
            )
            .build()
        val fetched = fetchedIndex(index.toByteArray(), "application/octet-stream", "https://raw.githubusercontent.com/keiyoushi/extensions/repo/index.pb")
        val parsed = parser.parse(fetched, repository)

        assertEquals("Keiyoushi", parsed.name)
        assertEquals(1, parsed.extensions.size)
        assertEquals(42L, parsed.extensions.single().versionCode)
        assertEquals(
            "https://raw.githubusercontent.com/keiyoushi/extensions/repo/extension.apk",
            parsed.extensions.single().apkUrl,
        )
        assertEquals("en", parsed.extensions.single().language)
        assertEquals("https://source.example", parsed.extensions.single().sources.single().homeUrl)
        assertEquals(listOf("https://mirror.example"), parsed.extensions.single().sources.single().mirrorUrls)
        assertEquals("Updated", parsed.extensions.single().sources.single().message)
    }

    @Test
    fun acceptsValidEmptyIndexAndRejectsMalformedProtobufOrExternalLists() {
        assertTrue(parser.parse("[]", repository).isEmpty())
        assertFailsWith<IllegalArgumentException> {
            parser.parse(
                fetchedIndex(byteArrayOf(0x0a, 0x05, 0x41), "application/octet-stream", "https://example.org/index.pb"),
                repository,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            parser.parse(
                fetchedIndex(
                    Index.newBuilder().setName("Store").setExtensionListUrl("https://example.org/list.pb").build().toByteArray(),
                    "application/octet-stream",
                    "https://example.org/index.pb",
                ),
                repository,
            )
        }
    }

    private fun fetchedIndex(body: ByteArray, contentType: String, finalUrl: String) =
        FetchedRepositoryIndex(
            body = body,
            contentType = contentType,
            diagnostic = IndexRequestDiagnostic(
                inputUrl = finalUrl,
                requestedUrl = finalUrl,
                finalUrl = finalUrl,
                statusCode = 200,
                contentType = contentType,
                redirects = emptyList(),
                redirectStatuses = emptyList(),
                bodyDownloaded = true,
            ),
            attempts = emptyList(),
        )
}