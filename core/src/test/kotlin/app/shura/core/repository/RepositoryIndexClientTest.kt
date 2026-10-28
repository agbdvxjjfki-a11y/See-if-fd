package app.shura.core.repository

import app.shura.core.repository.proto.ExtensionList
import app.shura.core.repository.proto.Index
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPOutputStream
import javax.net.ssl.HttpsURLConnection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import okio.Buffer
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.jupiter.api.Assumptions.assumeTrue

class RepositoryIndexClientTest {
    @Test
    fun requestsDirectJsonAndMinifiedJsonIndexUrlsWithoutAppendingAnotherSuffix() = withHttpsServer { server ->
        val json = """[{"name":"Reader","pkg":"org.example.reader","apk":"reader.apk","lang":"en","version":"1","code":1}]"""
        server.enqueue(jsonResponse(json))
        server.enqueue(jsonResponse(json))
        val client = RepositoryIndexClient()

        listOf("index.json", "index.min.json").forEach { filename ->
            val repository = RepositoryAddress.parse(serverUrl(server, "/repo/$filename"))
            val fetched = client.fetch(repository)
            assertEquals("/repo/$filename", server.takeRequest(1, TimeUnit.SECONDS)?.path)
            assertEquals(200, fetched.diagnostic.statusCode)
            assertTrue(fetched.diagnostic.bodyDownloaded)
            assertEquals(1, RepositoryCatalogParser().parse(fetched, repository).extensions.size)
        }
    }

    @Test
    fun followsRedirectsAndRecordsFinalUrlAndRedirectChain() = withHttpsServer { server ->
        server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "/storage/index.pb"))
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/octet-stream")
                .setBody(Buffer().write(indexProto().toByteArray())),
        )
        val repository = RepositoryAddress.parse(serverUrl(server, "/repo/index.pb"))
        val fetched = RepositoryIndexClient().fetch(repository)
        val parsed = RepositoryCatalogParser().parse(fetched, repository)

        assertEquals("/repo/index.pb", server.takeRequest(1, TimeUnit.SECONDS)?.path)
        assertEquals("/storage/index.pb", server.takeRequest(1, TimeUnit.SECONDS)?.path)
        assertEquals(200, fetched.diagnostic.statusCode)
        assertEquals("/storage/index.pb", java.net.URI(fetched.diagnostic.finalUrl).path)
        assertEquals(1, fetched.diagnostic.redirects.size)
        assertEquals(listOf(302), fetched.diagnostic.redirectStatuses)
        assertTrue(fetched.diagnostic.bodyDownloaded)
        assertEquals(1, parsed.extensions.size)
    }

    @Test
    fun retriesDirectoryCandidatesAfter404AndUsesTheFirstValidIndex() = withHttpsServer { server ->
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/x-protobuf")
                .setBody(Buffer().write(indexProto().toByteArray())),
        )
        val repository = RepositoryAddress.parse(serverUrl(server, "/repo"))
        val fetched = RepositoryIndexClient().fetch(repository)

        assertEquals("/repo/index.pb", server.takeRequest(1, TimeUnit.SECONDS)?.path)
        assertEquals("/repo/index.min.json", server.takeRequest(1, TimeUnit.SECONDS)?.path)
        assertEquals(2, fetched.attempts.size)
        assertEquals(404, fetched.attempts.first().statusCode)
        assertEquals(200, fetched.diagnostic.statusCode)
        assertEquals("/repo/index.min.json", java.net.URI(fetched.diagnostic.finalUrl).path)
    }

    @Test
    fun reportsReal404AndDoesNotHideTheFailedUrl() = withHttpsServer { server ->
        server.enqueue(MockResponse().setResponseCode(404))
        val repository = RepositoryAddress.parse(serverUrl(server, "/repo/index.json"))
        val error = assertFailsWith<RepositoryIndexFetchException> {
            RepositoryIndexClient().fetch(repository)
        }

        assertEquals(RepositoryFetchFailure.NOT_FOUND, error.failure)
        assertEquals(404, error.diagnostics.single().statusCode)
        assertEquals(serverUrl(server, "/repo/index.json"), error.diagnostics.single().finalUrl)
        assertFalse(error.diagnostics.single().bodyDownloaded)
    }

    @Test
    fun distinguishesConnectionFailureFromHttpFailure() {
        val server = MockWebServer()
        server.start()
        val port = server.port
        server.shutdown()
        val repository = RepositoryAddress.parse("https://localhost:$port/repo/index.pb")

        val error = assertFailsWith<RepositoryIndexFetchException> {
            RepositoryIndexClient(connectTimeoutMillis = 500, readTimeoutMillis = 500).fetch(repository)
        }

        assertEquals(RepositoryFetchFailure.CONNECTION, error.failure)
        assertEquals(null, error.diagnostics.single().statusCode)
        assertFalse(error.diagnostics.single().bodyDownloaded)
    }

    @Test
    fun rejectsMalformedJsonAfterSuccessfulDownloadAndAllowsEmptyValidCatalog() = withHttpsServer { server ->
        server.enqueue(jsonResponse("{broken"))
        server.enqueue(jsonResponse("[]"))
        val repository = RepositoryAddress.parse(serverUrl(server, "/repo/index.json"))
        val client = RepositoryIndexClient()
        val malformed = client.fetch(repository)

        assertEquals(200, malformed.diagnostic.statusCode)
        assertTrue(malformed.diagnostic.bodyDownloaded)
        assertFailsWith<IllegalArgumentException> {
            RepositoryCatalogParser().parse(malformed, repository)
        }
        val emptyRepository = RepositoryAddress.parse(serverUrl(server, "/empty/index.json"))
        val empty = client.fetch(emptyRepository)
        assertTrue(RepositoryCatalogParser().parse(empty, emptyRepository).extensions.isEmpty())
    }

    @Test
    fun decompressesRealSchemaProtobufPayload() = withHttpsServer { server ->
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/octet-stream")
                .setBody(Buffer().write(gzip(indexProto().toByteArray()))),
        )
        val repository = RepositoryAddress.parse(serverUrl(server, "/repo/index.pb"))
        val fetched = RepositoryIndexClient().fetch(repository)

        assertEquals(1, RepositoryCatalogParser().parse(fetched, repository).extensions.size)
        assertEquals(200, fetched.diagnostic.statusCode)
        assertTrue(fetched.diagnostic.bodyDownloaded)
    }

    @Test
    fun fetchesExternalExtensionListUsingTheMihonIndexField() = withHttpsServer { server ->
        val externalList = indexProto().extensionList.toByteArray()
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/x-protobuf")
                .setBody(
                    Buffer().write(
                        Index.newBuilder()
                            .setName("External list store")
                            .setExtensionListUrl(serverUrl(server, "/lists/extensions.pb"))
                            .build()
                            .toByteArray(),
                    ),
                ),
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/x-protobuf")
                .setBody(Buffer().write(externalList)),
        )
        val repository = RepositoryAddress.parse(serverUrl(server, "/repo/index.pb"))
        val client = RepositoryIndexClient()
        val fetched = client.fetch(repository)
        val parsed = RepositoryCatalogParser().parse(fetched, repository) { url ->
            client.fetchExternalList(url, repository.inputUrl)
        }

        assertEquals("/repo/index.pb", server.takeRequest(1, TimeUnit.SECONDS)?.path)
        assertEquals("/lists/extensions.pb", server.takeRequest(1, TimeUnit.SECONDS)?.path)
        assertEquals("External list store", parsed.name)
        assertEquals(1, parsed.extensions.size)
        assertEquals(
            "https://localhost:${server.port}/lists/reader.apk",
            parsed.extensions.single().apkUrl,
        )
    }

    @Test
    fun keiyoushiPublicIndexCanBeCheckedOnDemand() {
        assumeTrue(System.getenv("SHURA_RUN_INTEGRATION_TESTS") == "true")
        val url = "https://github.com/keiyoushi/extensions/raw/repo/index.pb"
        val repository = RepositoryAddress.parse(url)
        val fetched = RepositoryIndexClient().fetch(repository)
        val parsed = RepositoryCatalogParser().parse(fetched, repository)

        println(
            "Keiyoushi index: input=$url, final=${fetched.diagnostic.finalUrl}, " +
                "status=${fetched.diagnostic.statusCode}, contentType=${fetched.contentType}, " +
                "redirects=${fetched.diagnostic.redirects.size}, extensions=${parsed.extensions.size}",
        )
        assertTrue(parsed.extensions.isNotEmpty())
    }

    @Test
    fun keiyoushiPublicJsonIndexCanBeCheckedOnDemand() {
        assumeTrue(System.getenv("SHURA_RUN_INTEGRATION_TESTS") == "true")
        val url = "https://raw.githubusercontent.com/keiyoushi/extensions/repo/index.min.json"
        val repository = RepositoryAddress.parse(url)
        val fetched = RepositoryIndexClient().fetch(repository)
        val parsed = RepositoryCatalogParser().parse(fetched, repository)

        println(
            "Keiyoushi JSON index: input=$url, final=${fetched.diagnostic.finalUrl}, " +
                "status=${fetched.diagnostic.statusCode}, contentType=${fetched.contentType}, " +
                "extensions=${parsed.extensions.size}",
        )
        assertTrue(parsed.extensions.isNotEmpty())
    }

    private fun indexProto(): Index =
        Index.newBuilder()
            .setName("Keiyoushi community")
            .setExtensionList(
                ExtensionList.newBuilder()
                    .addExtensions(
                        app.shura.core.repository.proto.Extension.newBuilder()
                            .setName("Reader")
                            .setPackageName("org.example.reader")
                            .setResources(
                                app.shura.core.repository.proto.Resources.newBuilder()
                                    .setApkUrl("reader.apk"),
                            )
                            .setVersionCode(1)
                            .setVersionName("1.0"),
                    ),
            )
            .build()

    private fun jsonResponse(body: String) =
        MockResponse().setResponseCode(200).addHeader("Content-Type", "application/json").setBody(body)

    private fun serverUrl(server: MockWebServer, path: String): String =
        server.url(path).toString().replaceFirst("http://", "https://")

    private fun gzip(bytes: ByteArray): ByteArray =
        ByteArrayOutputStream().also { output ->
            GZIPOutputStream(output).use { it.write(bytes) }
        }.toByteArray()

    private fun withHttpsServer(block: (MockWebServer) -> Unit) {
        val certificate = HeldCertificate.Builder()
            .addSubjectAlternativeName("localhost")
            .build()
        val serverCertificates = HandshakeCertificates.Builder()
            .heldCertificate(certificate)
            .build()
        val clientCertificates = HandshakeCertificates.Builder()
            .addTrustedCertificate(certificate.certificate)
            .build()
        val originalSocketFactory = HttpsURLConnection.getDefaultSSLSocketFactory()
        val server = MockWebServer()
        server.useHttps(serverCertificates.sslSocketFactory(), false)
        HttpsURLConnection.setDefaultSSLSocketFactory(clientCertificates.sslSocketFactory())
        try {
            server.start()
            block(server)
        } finally {
            server.shutdown()
            HttpsURLConnection.setDefaultSSLSocketFactory(originalSocketFactory)
        }
    }
}
