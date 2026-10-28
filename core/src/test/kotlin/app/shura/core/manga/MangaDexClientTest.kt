package app.shura.core.manga

import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue

class MangaDexClientTest {
    @Test
    fun searchRequestsOnlySafeTitlesAndParsesRealResponseFields() {
        var requestedPath = ""
        val client = MangaDexClient { path ->
            requestedPath = path
            """{"data":[
                {"id":"11111111-1111-1111-1111-111111111111","attributes":{"title":{"en":"Safe title"},"description":{"en":"A real description"},"contentRating":"safe"}},
                {"id":"22222222-2222-2222-2222-222222222222","attributes":{"title":{"en":"Unsafe title"},"contentRating":"pornographic"}},
                {"id":"33333333-3333-3333-3333-333333333333","attributes":{"title":{"en":"Suggestive title"},"contentRating":"suggestive"}},
                {"id":"44444444-4444-4444-4444-444444444444","attributes":{"title":{"en":"Unknown rating"}}}
            ]}"""
        }

        val results = client.search("one piece", language = "en")

        assertTrue(requestedPath.startsWith("/manga?title=one+piece&limit=20"))
        assertTrue(requestedPath.contains("availableTranslatedLanguage%5B%5D=en"))
        assertTrue(requestedPath.contains("contentRating%5B%5D=safe"))
        assertEquals(1, results.size)
        assertEquals("Safe title", results.single().title)
        assertEquals("A real description", results.single().description)
    }

    @Test
    fun loadsMangaDetailsAndTranslatedChaptersWithScanlationAttribution() {
        val mangaId = "11111111-1111-1111-1111-111111111111"
        val chapterId = "22222222-2222-2222-2222-222222222222"
        val requestedPaths = mutableListOf<String>()
        val client = MangaDexClient { path ->
            requestedPaths += path
            when {
                path.startsWith("/manga/$mangaId?") ->
                    """{"data":{"id":"$mangaId","attributes":{"title":{"en":"Example"},"description":{"en":"Details"},"contentRating":"safe"}}}"""
                else ->
                    """{"data":[{"id":"$chapterId","attributes":{"chapter":"3","volume":"1","title":"A chapter","translatedLanguage":"en"},"relationships":[{"id":"33333333-3333-3333-3333-333333333333","type":"scanlation_group","attributes":{"name":"Scanlation Team"}}]}]}"""
            }
        }

        assertEquals("Details", client.getManga(mangaId, "en").description)
        val chapters = client.getChapters(mangaId, "en", offset = 100)

        assertEquals(
            "/manga/$mangaId/feed?translatedLanguage%5B%5D=en&limit=100&offset=100&order%5Bvolume%5D=asc&order%5Bchapter%5D=asc&includes%5B%5D=scanlation_group",
            requestedPaths.last(),
        )
        assertEquals(chapterId, chapters.single().id)
        assertEquals("Scanlation Team", chapters.single().scanlationGroup)
    }

    @Test
    fun retrievesStandardAndDataSaverPagesFromTrustedHttpsAtHomeServer() {
        val chapterId = "11111111-1111-1111-1111-111111111111"
        val client = MangaDexClient {
            """{"baseUrl":"https://server.mangadex.network/token","chapter":{"hash":"0123456789abcdef0123456789abcdef","data":["page-1.webp"],"dataSaver":["page-1.jpg"]}}"""
        }

        assertEquals(
            listOf("https://server.mangadex.network/token/data/0123456789abcdef0123456789abcdef/page-1.webp"),
            client.getChapterPageUrls(chapterId),
        )
        assertEquals(
            listOf("https://server.mangadex.network/token/data-saver/0123456789abcdef0123456789abcdef/page-1.jpg"),
            client.getChapterPageUrls(chapterId, dataSaver = true),
        )
    }

    @Test
    fun rejectsInvalidIdsInputAndUntrustedPageServers() {
        val client = MangaDexClient {
            """{"baseUrl":"http://untrusted.example","chapter":{"hash":"0123456789abcdef0123456789abcdef","data":["page.webp"]}}"""
        }
        val unexpectedPortClient = MangaDexClient {
            """{"baseUrl":"https://server.mangadex.network:444","chapter":{"hash":"0123456789abcdef0123456789abcdef","data":["page.webp"]}}"""
        }

        assertFailsWith<IllegalArgumentException> { client.getManga("not-a-uuid", "en") }
        assertFailsWith<IllegalArgumentException> { client.search("title", "en", limit = 101) }
        assertFailsWith<IllegalArgumentException> {
            client.getChapterPageUrls("11111111-1111-1111-1111-111111111111")
        }
        assertFailsWith<IllegalArgumentException> {
            unexpectedPortClient.getChapterPageUrls("11111111-1111-1111-1111-111111111111")
        }
    }

    @Test
    fun malformedApiResponseProducesAnExplicitError() {
        val client = MangaDexClient { "not json" }

        assertFailsWith<IOException> {
            client.search("title", "en")
        }
    }

    @Test
    fun rejectsUnsafePagePathSegments() {
        val client = MangaDexClient {
            """{"baseUrl":"https://server.mangadex.network","chapter":{"hash":"0123456789abcdef0123456789abcdef","data":[".."]}}"""
        }

        assertFailsWith<IOException> {
            client.getChapterPageUrls("11111111-1111-1111-1111-111111111111")
        }
    }

    @Test
    fun liveMangaDexSearchDetailsAndChapterFeedCanBeCheckedOnDemand() {
        assumeTrue(System.getenv("SHURA_RUN_INTEGRATION_TESTS") == "true")
        val client = MangaDexClient()
        val results = client.search("One Piece", language = "en", limit = 5)
        assertTrue(results.isNotEmpty(), "MangaDex live search returned no safe matches")
        val match = results.firstNotNullOfOrNull { candidate ->
            val chapters = client.getChapters(candidate.id, language = "en", limit = 5)
            chapters.firstOrNull()?.let { candidate to it }
        } ?: error("MangaDex live search returned no safe match with an English chapter")
        val (manga, chapter) = match
        val details = client.getManga(manga.id, language = "en")
        val pages = client.getChapterPageUrls(chapter.id)

        assertEquals(manga.id, details.id)
        assertTrue(details.title.isNotBlank())
        assertTrue(pages.isNotEmpty(), "MangaDex live chapter returned no page URLs")
        println(
            "MangaDex live API: title=${details.title}, id=${details.id}, " +
                "safeRating=${details.contentRating}, chapter=${chapter.id}, pages=${pages.size}",
        )
    }
}
