package app.shura.core.repository

import java.net.URI

class RepositoryIndexParseException(
    val diagnostic: IndexRequestDiagnostic,
    message: String,
    cause: Throwable,
) : IllegalArgumentException(message, cause)

fun interface RepositoryIndexSource {
    fun fetch(repository: RepositoryAddress): FetchedRepositoryIndex

    fun fetchExternalList(url: URI, originalInputUrl: String): FetchedRepositoryIndex =
        throw UnsupportedOperationException("مصدر الفهرس لا يدعم القوائم الخارجية")
}
