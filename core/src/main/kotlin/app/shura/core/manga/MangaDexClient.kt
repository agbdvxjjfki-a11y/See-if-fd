package app.shura.core.manga

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

data class OnlineManga(
    val id: String,
    val title: String,
    val description: String,
    val contentRating: String,
)

data class OnlineChapter(
    val id: String,
    val mangaId: String,
    val chapter: String?,
    val volume: String?,
    val title: String,
    val language: String,
    val scanlationGroup: String?,
)

fun interface MangaDexTransport {
    fun get(pathAndQuery: String): String
}

class MangaDexClient(
    private val transport: MangaDexTransport = HttpMangaDexTransport(),
) {
    fun search(query: String, language: String, limit: Int = DEFAULT_PAGE_SIZE): List<OnlineManga> {
        require(query.trim().length in MIN_QUERY_LENGTH..MAX_QUERY_LENGTH) {
            "Search query length is out of range"
        }
        require(language.matches(LANGUAGE_PATTERN)) { "Invalid MangaDex language" }
        require(limit in 1..MAX_PAGE_SIZE) { "MangaDex result limit is out of range" }
        val path = buildString {
            append("/manga?title=").append(encode(query.trim()))
            append("&limit=").append(limit)
            append("&availableTranslatedLanguage%5B%5D=").append(encode(language))
            append("&contentRating%5B%5D=safe")
            append("&order%5Brelevance%5D=desc")
            append("&includes%5B%5D=cover_art")
        }
        return parseMangaList(transport.get(path), language)
    }

    fun getManga(id: String, language: String): OnlineManga {
        require(id.matches(UUID_PATTERN)) { "Invalid MangaDex manga ID" }
        require(language.matches(LANGUAGE_PATTERN)) { "Invalid MangaDex language" }
        val response = parseObject(transport.get("/manga/$id?includes%5B%5D=cover_art"))
        return parseManga(response.getAsJsonObject("data"), language)
            ?: throw IOException("MangaDex manga is unavailable or has an unsupported content rating")
    }

    fun getChapters(
        mangaId: String,
        language: String,
        offset: Int = 0,
        limit: Int = MAX_PAGE_SIZE,
    ): List<OnlineChapter> {
        require(mangaId.matches(UUID_PATTERN)) { "Invalid MangaDex manga ID" }
        require(language.matches(LANGUAGE_PATTERN)) { "Invalid MangaDex language" }
        require(offset >= 0) { "Invalid MangaDex chapter offset" }
        require(limit in 1..MAX_PAGE_SIZE) { "MangaDex chapter limit is out of range" }
        val path = buildString {
            append("/manga/$mangaId/feed?translatedLanguage%5B%5D=").append(encode(language))
            append("&limit=").append(limit)
            append("&offset=").append(offset)
            append("&order%5Bvolume%5D=asc&order%5Bchapter%5D=asc")
            append("&includes%5B%5D=scanlation_group")
        }
        return parseObject(transport.get(path))
            .getAsJsonArray("data")
            ?.mapNotNull { element ->
                val data = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
                val attributes = data.getAsJsonObject("attributes") ?: return@mapNotNull null
                if (attributes.stringOrNull("translatedLanguage") != language) return@mapNotNull null
                val chapterId = data.stringOrNull("id")?.takeIf(UUID_PATTERN::matches)
                    ?: return@mapNotNull null
                val relationship = data.getAsJsonArray("relationships")
                    ?.firstOrNull {
                        it.isJsonObject && it.asJsonObject.stringOrNull("type") == "scanlation_group"
                    }?.asJsonObject
                val group = relationship?.getAsJsonObject("attributes")?.stringOrNull("name")
                OnlineChapter(
                    id = chapterId,
                    mangaId = mangaId,
                    chapter = attributes.stringOrNull("chapter"),
                    volume = attributes.stringOrNull("volume"),
                    title = attributes.stringOrNull("title").orEmpty(),
                    language = language,
                    scanlationGroup = group,
                )
            }
            .orEmpty()
    }

    fun getChapterPageUrls(chapterId: String, dataSaver: Boolean = false): List<String> {
        require(chapterId.matches(UUID_PATTERN)) { "Invalid MangaDex chapter ID" }
        val response = parseObject(transport.get("/at-home/server/$chapterId"))
        val base = response.stringOrNull("baseUrl")
            ?: throw IOException("MangaDex did not provide an image server")
        val baseUri = runCatching { URI(base) }
            .getOrElse { throw IOException("MangaDex returned an invalid image server URL", it) }
        require(
            baseUri.scheme.equals("https", ignoreCase = true) &&
                baseUri.host?.endsWith(".mangadex.network", ignoreCase = true) == true &&
                baseUri.port in setOf(-1, 443) &&
                baseUri.userInfo == null &&
                baseUri.query == null &&
                baseUri.fragment == null,
        ) { "MangaDex returned an untrusted image server" }

        val chapter = response.getAsJsonObject("chapter")
            ?: throw IOException("MangaDex response omitted chapter image metadata")
        val hash = chapter.stringOrNull("hash")
            ?.takeIf(IMAGE_HASH_PATTERN::matches)
            ?: throw IOException("MangaDex returned an invalid chapter image hash")
        val fileList = chapter.getAsJsonArray(if (dataSaver) "dataSaver" else "data")
            ?: throw IOException("MangaDex returned no chapter pages")
        return fileList.map { item ->
            val fileName = item.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
                ?.asString
                ?.takeIf { IMAGE_SEGMENT_PATTERN.matches(it) && it != "." && it != ".." }
                ?: throw IOException("MangaDex returned an invalid page name")
            "${base.trimEnd('/')}/${if (dataSaver) "data-saver" else "data"}/$hash/$fileName"
        }
    }

    private fun parseMangaList(json: String, language: String): List<OnlineManga> =
        parseObject(json).getAsJsonArray("data")
            ?.mapNotNull { element ->
                element.takeIf { it.isJsonObject }?.asJsonObject?.let { parseManga(it, language) }
            }
            .orEmpty()

    private fun parseManga(data: JsonObject, language: String): OnlineManga? {
        val attributes = data.getAsJsonObject("attributes") ?: return null
        val rating = attributes.stringOrNull("contentRating") ?: return null
        if (rating != "safe") return null
        val titles = attributes.getAsJsonObject("title") ?: return null
        val title = titles.stringOrNull(language)
            ?: titles.stringOrNull("en")
            ?: titles.entrySet().firstOrNull()?.value?.takeIf { it.isJsonPrimitive }?.asString
            ?: return null
        val description = attributes.getAsJsonObject("description")
            ?.let { it.stringOrNull(language) ?: it.stringOrNull("en") }
            .orEmpty()
        val id = data.stringOrNull("id")?.takeIf(UUID_PATTERN::matches) ?: return null
        return OnlineManga(id, title, description, rating)
    }

    private fun parseObject(json: String): JsonObject = try {
        JsonParser.parseString(json).takeIf { it.isJsonObject }?.asJsonObject
            ?: throw IOException("MangaDex returned an invalid response")
    } catch (error: Exception) {
        if (error is IOException) throw error
        throw IOException("Unable to parse MangaDex response", error)
    }

    private fun JsonObject.stringOrNull(name: String): String? =
        get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString

    private fun encode(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8)

    private companion object {
        const val DEFAULT_PAGE_SIZE = 20
        const val MAX_PAGE_SIZE = 100
        const val MIN_QUERY_LENGTH = 2
        const val MAX_QUERY_LENGTH = 100
        const val MAX_RESPONSE_BYTES = 4 * 1024 * 1024
        const val CONNECT_TIMEOUT_MILLIS = 10_000
        const val READ_TIMEOUT_MILLIS = 20_000
        const val MAX_REDIRECTS = 3
        val UUID_PATTERN = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
        val LANGUAGE_PATTERN = Regex("[a-zA-Z-]{2,5}")
        val IMAGE_SEGMENT_PATTERN = Regex("[A-Za-z0-9._-]{1,200}")
        val IMAGE_HASH_PATTERN = Regex("[A-Fa-f0-9]{32}")
    }
}

private class HttpMangaDexTransport(
    private val openConnection: (URI) -> HttpURLConnection = {
        it.toURL().openConnection() as HttpURLConnection
    },
) : MangaDexTransport {
    override fun get(pathAndQuery: String): String {
        require(pathAndQuery.startsWith("/") && !pathAndQuery.startsWith("//")) {
            "Invalid MangaDex API path"
        }
        var uri = URI("https://api.mangadex.org$pathAndQuery")
        var redirects = 0
        while (true) {
            val connection = openConnection(uri)
            try {
                connection.connectTimeout = CONNECT_TIMEOUT_MILLIS
                connection.readTimeout = READ_TIMEOUT_MILLIS
                connection.instanceFollowRedirects = false
                connection.setRequestProperty("Accept", "application/json")
                connection.setRequestProperty("User-Agent", "Shura/0.1.2 (Android manga reader)")
                val status = connection.responseCode
                if (status in REDIRECT_CODES) {
                    check(redirects < MAX_REDIRECTS) { "Too many MangaDex API redirects" }
                    val location = connection.getHeaderField("Location")
                        ?: throw IOException("MangaDex returned a redirect without a location")
                    val target = uri.resolve(location)
                    require(
                        target.scheme.equals("https", ignoreCase = true) &&
                            target.host.equals("api.mangadex.org", ignoreCase = true) &&
                        target.port in setOf(-1, 443) &&
                        target.userInfo == null &&
                            target.fragment == null,
                    ) { "MangaDex API redirected to an untrusted URL" }
                    uri = target
                    redirects++
                    continue
                }
                if (status !in 200..299) throw IOException("MangaDex request failed: HTTP $status")
                require(connection.contentLengthLong <= MAX_RESPONSE_BYTES) {
                    "MangaDex response exceeds the size limit"
                }
                val output = ByteArrayOutputStream()
                connection.inputStream.use { input ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        require(output.size() + read <= MAX_RESPONSE_BYTES) {
                            "MangaDex response exceeds the size limit"
                        }
                        output.write(buffer, 0, read)
                    }
                }
                return output.toString(StandardCharsets.UTF_8)
            } finally {
                connection.disconnect()
            }
        }
    }

    private companion object {
        const val MAX_RESPONSE_BYTES = 4 * 1024 * 1024
        const val CONNECT_TIMEOUT_MILLIS = 10_000
        const val READ_TIMEOUT_MILLIS = 20_000
        const val MAX_REDIRECTS = 3
        val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
    }
}
