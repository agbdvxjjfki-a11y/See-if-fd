package app.shura.core.repository

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.util.logging.Level
import java.util.logging.Logger
import java.util.zip.GZIPInputStream

data class IndexRequestDiagnostic(
    val inputUrl: String,
    val requestedUrl: String,
    val finalUrl: String,
    val statusCode: Int?,
    val contentType: String?,
    val redirects: List<String>,
    val redirectStatuses: List<Int>,
    val bodyDownloaded: Boolean,
)

data class FetchedRepositoryIndex(
    val body: ByteArray,
    val contentType: String?,
    val diagnostic: IndexRequestDiagnostic,
    val attempts: List<IndexRequestDiagnostic>,
)

enum class RepositoryFetchFailure {
    NOT_FOUND,
    CONNECTION,
    HTTP,
    INVALID_BODY,
}

class RepositoryIndexFetchException(
    val failure: RepositoryFetchFailure,
    val diagnostics: List<IndexRequestDiagnostic>,
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)

class RepositoryIndexClient(
    private val connectTimeoutMillis: Int = 8_000,
    private val readTimeoutMillis: Int = 8_000,
    private val maxIndexBytes: Int = 16 * 1024 * 1024,
) : RepositoryIndexSource {
    init {
        require(connectTimeoutMillis > 0) { "مهلة الاتصال يجب أن تكون موجبة" }
        require(readTimeoutMillis > 0) { "مهلة القراءة يجب أن تكون موجبة" }
        require(maxIndexBytes in 1..16 * 1024 * 1024) { "حد حجم الفهرس غير صالح" }
    }

    override fun fetch(repository: RepositoryAddress): FetchedRepositoryIndex {
        val diagnostics = mutableListOf<IndexRequestDiagnostic>()
        var notFound: RepositoryIndexFetchException? = null
        for (candidate in repository.indexCandidates()) {
            try {
                val fetched = fetchCandidate(repository.inputUrl, candidate)
                diagnostics += fetched.diagnostic
                logDiagnostics(diagnostics)
                return fetched.copy(attempts = diagnostics.toList())
            } catch (error: RepositoryIndexFetchException) {
                diagnostics += error.diagnostics
                if (error.failure != RepositoryFetchFailure.NOT_FOUND || repository.isDirectIndexUrl) {
                    logDiagnostics(diagnostics)
                    throw error.copyWithDiagnostics(diagnostics)
                }
                notFound = error
            }
        }
        logDiagnostics(diagnostics)
        throw RepositoryIndexFetchException(
            failure = RepositoryFetchFailure.NOT_FOUND,
            diagnostics = diagnostics,
            message = "لم يُعثر على فهرس صالح. آخر رابط جُرّب: ${notFound?.diagnostics?.lastOrNull()?.finalUrl}",
            cause = notFound,
        )
    }

    override fun fetchExternalList(url: URI, originalInputUrl: String): FetchedRepositoryIndex {
        require(url.scheme.equals("https", ignoreCase = true) && !url.host.isNullOrBlank() && url.userInfo == null) {
            "رابط قائمة الإضافات الخارجية غير آمن"
        }
        val fetched = try {
            fetchCandidate(originalInputUrl, url)
        } catch (error: RepositoryIndexFetchException) {
            logDiagnostics(error.diagnostics)
            throw error
        }
        logDiagnostics(listOf(fetched.diagnostic))
        return fetched
    }

    private fun fetchCandidate(
        inputUrl: String,
        candidate: URI,
    ): FetchedRepositoryIndex {
        val redirects = mutableListOf<String>()
        val redirectStatuses = mutableListOf<Int>()
        var current = candidate
        repeat(MAX_REDIRECTS + 1) { redirectIndex ->
            val connection = try {
                current.toURL().openConnection() as HttpURLConnection
            } catch (error: IOException) {
                val diagnostic = diagnostic(inputUrl, candidate, current, null, null, redirects, redirectStatuses, false)
                logDiagnostics(listOf(diagnostic), error)
                throw RepositoryIndexFetchException(
                    RepositoryFetchFailure.CONNECTION,
                    listOf(diagnostic),
                    "تعذر الاتصال بعنوان الفهرس: ${safeUrl(current)}",
                    error,
                )
            }
            try {
                connection.connectTimeout = connectTimeoutMillis
                connection.readTimeout = readTimeoutMillis
                connection.instanceFollowRedirects = false
                connection.setRequestProperty("Accept", "application/json, application/x-protobuf, application/octet-stream")
                connection.setRequestProperty("User-Agent", "Shura/0.1.1 repository-catalog")
                val status = try {
                    connection.responseCode
                } catch (error: IOException) {
                    val diagnostic = diagnostic(inputUrl, candidate, current, null, connection.contentType, redirects, redirectStatuses, false)
                    logDiagnostics(listOf(diagnostic), error)
                    throw RepositoryIndexFetchException(
                        RepositoryFetchFailure.CONNECTION,
                        listOf(diagnostic),
                        "تعذر الاتصال بعنوان الفهرس: ${safeUrl(current)}",
                        error,
                    )
                }
                val contentType = connection.contentType
                if (status in REDIRECT_CODES) {
                    val location = connection.getHeaderField("Location")
                        ?: throw RepositoryIndexFetchException(
                            RepositoryFetchFailure.HTTP,
                            listOf(diagnostic(inputUrl, candidate, current, status, contentType, redirects, redirectStatuses, false)),
                            "استجابة HTTP $status بلا عنوان تحويل من ${safeUrl(current)}",
                        )
                    require(redirectIndex < MAX_REDIRECTS) { "تجاوز الرابط الحد الأقصى للتحويلات" }
                    val redirected = current.resolve(location)
                    require(
                        redirected.scheme.equals("https", ignoreCase = true) &&
                            !redirected.host.isNullOrBlank() &&
                            redirected.userInfo == null,
                    ) { "رفض تحويل غير آمن إلى ${safeUrl(redirected)}" }
                    redirects += redirected.toString()
                    redirectStatuses += status
                    current = redirected
                    return@repeat
                }

                val diagnostic = diagnostic(inputUrl, candidate, current, status, contentType, redirects, redirectStatuses, false)
                if (status == HttpURLConnection.HTTP_NOT_FOUND) {
                    throw RepositoryIndexFetchException(
                        RepositoryFetchFailure.NOT_FOUND,
                        listOf(diagnostic),
                        "الفهرس غير موجود: HTTP 404 عند ${safeUrl(current)} " +
                            "(Content-Type: ${contentType ?: "غير معروف"})",
                    )
                }
                if (status != HttpURLConnection.HTTP_OK) {
                    throw RepositoryIndexFetchException(
                        RepositoryFetchFailure.HTTP,
                        listOf(diagnostic),
                        "فشل طلب الفهرس: HTTP $status عند ${safeUrl(current)}",
                    )
                }
                val contentLength = connection.contentLengthLong
                require(contentLength in -1..maxIndexBytes.toLong()) {
                    "حجم فهرس المستودع يتجاوز الحد المسموح عند ${safeUrl(current)}"
                }
                val rawBody = connection.inputStream.use { it.readNBytes(maxIndexBytes + 1) }
                require(rawBody.size <= maxIndexBytes) {
                    "حجم فهرس المستودع يتجاوز الحد المسموح عند ${safeUrl(current)}"
                }
                val body = try {
                    decompressIfNeeded(rawBody)
                } catch (error: IOException) {
                    val invalidBody = diagnostic.copy(bodyDownloaded = true)
                    throw RepositoryIndexFetchException(
                        RepositoryFetchFailure.INVALID_BODY,
                        listOf(invalidBody),
                        "تم تنزيل جسم الفهرس من ${safeUrl(current)} لكنه gzip غير صالح",
                        error,
                    )
                }
                require(body.size <= maxIndexBytes) {
                    "حجم فهرس المستودع بعد فك الضغط يتجاوز الحد المسموح عند ${safeUrl(current)}"
                }
                val completed = diagnostic.copy(bodyDownloaded = true)
                return FetchedRepositoryIndex(
                    body = body,
                    contentType = contentType,
                    diagnostic = completed,
                    attempts = listOf(completed),
                )
            } catch (error: RepositoryIndexFetchException) {
                throw error
            } catch (error: IOException) {
                val diagnostic = diagnostic(inputUrl, candidate, current, connection.responseCodeOrNull(), connection.contentType, redirects, redirectStatuses, false)
                logDiagnostics(listOf(diagnostic), error)
                throw RepositoryIndexFetchException(
                    RepositoryFetchFailure.CONNECTION,
                    listOf(diagnostic),
                    "تعذر تنزيل جسم الفهرس من ${safeUrl(current)}",
                    error,
                )
            } catch (error: IllegalArgumentException) {
                val diagnostic = diagnostic(inputUrl, candidate, current, connection.responseCodeOrNull(), connection.contentType, redirects, redirectStatuses, false)
                logDiagnostics(listOf(diagnostic), error)
                throw RepositoryIndexFetchException(
                    RepositoryFetchFailure.HTTP,
                    listOf(diagnostic),
                    error.message ?: "استجابة الفهرس غير صالحة عند ${safeUrl(current)}",
                    error,
                )
            } finally {
                connection.disconnect()
            }
        }
        val diagnostic = diagnostic(inputUrl, candidate, current, null, null, redirects, redirectStatuses, false)
        throw RepositoryIndexFetchException(
            RepositoryFetchFailure.HTTP,
            listOf(diagnostic),
            "تجاوز عنوان الفهرس الحد الأقصى للتحويلات",
        )
    }

    private fun diagnostic(
        inputUrl: String,
        candidate: URI,
        finalUrl: URI,
        statusCode: Int?,
        contentType: String?,
        redirects: List<String>,
        redirectStatuses: List<Int>,
        bodyDownloaded: Boolean,
    ) = IndexRequestDiagnostic(
        inputUrl = inputUrl,
        requestedUrl = safeUrl(candidate),
        finalUrl = safeUrl(finalUrl),
        statusCode = statusCode,
        contentType = contentType,
        redirects = redirects.map { safeUrl(URI(it)) },
        redirectStatuses = redirectStatuses.toList(),
        bodyDownloaded = bodyDownloaded,
    )

    private fun decompressIfNeeded(bytes: ByteArray): ByteArray {
        if (bytes.size < 2 || bytes[0] != GZIP_MAGIC_FIRST || bytes[1] != GZIP_MAGIC_SECOND) return bytes
        val output = ByteArrayOutputStream()
        GZIPInputStream(bytes.inputStream()).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(output.size() + count <= maxIndexBytes) {
                    "حجم فهرس المستودع بعد فك الضغط يتجاوز الحد المسموح"
                }
                output.write(buffer, 0, count)
            }
        }
        return output.toByteArray()
    }

    private fun logDiagnostics(diagnostics: List<IndexRequestDiagnostic>, error: Throwable? = null) {
        diagnostics.forEach { diagnostic ->
            val message = "Repository index request: input=${diagnostic.inputUrl}, " +
                "requested=${diagnostic.requestedUrl}, final=${diagnostic.finalUrl}, " +
                "status=${diagnostic.statusCode ?: "connection-failed"}, " +
                "contentType=${diagnostic.contentType ?: "unknown"}, " +
                "redirectStatuses=${diagnostic.redirectStatuses}, redirects=${diagnostic.redirects}, " +
                "bodyDownloaded=${diagnostic.bodyDownloaded}"
            if (error == null) LOGGER.info(message) else {
                LOGGER.log(Level.WARNING, "$message; cause=${error.javaClass.simpleName}")
            }
        }
    }

    private fun HttpURLConnection.responseCodeOrNull(): Int? =
        runCatching { responseCode }.getOrNull()

    private fun safeUrl(uri: URI): String =
        URI(uri.scheme, null, uri.host, uri.port, uri.path, null, null).toString() +
            if (uri.rawQuery != null) "?<redacted>" else ""

    private fun RepositoryIndexFetchException.copyWithDiagnostics(
        allDiagnostics: List<IndexRequestDiagnostic>,
    ) = RepositoryIndexFetchException(failure, allDiagnostics, message.orEmpty(), this)

    private companion object {
        const val MAX_REDIRECTS = 5
        const val GZIP_MAGIC_FIRST = 0x1f.toByte()
        const val GZIP_MAGIC_SECOND = 0x8b.toByte()
        val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
        val LOGGER: Logger = Logger.getLogger(RepositoryIndexClient::class.java.name)
    }
}
