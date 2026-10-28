package app.shura.core.repository

import app.shura.core.content.ContentPolicyEngine
import java.time.Instant
import java.util.LinkedHashMap
import java.util.logging.Level
import java.util.logging.Logger

data class RepositoryCatalogSnapshot(
    val repository: RepositoryAddress,
    val extensions: List<ExtensionDescriptor> = emptyList(),
    val lastUpdated: Instant? = null,
    val error: String? = null,
    val storeName: String? = null,
    val indexUrl: String? = null,
    val contentType: String? = null,
    val httpStatus: Int? = null,
    val redirects: List<String> = emptyList(),
    val redirectStatuses: List<Int> = emptyList(),
    val bodyDownloaded: Boolean = false,
    val fetchDiagnostics: List<IndexRequestDiagnostic> = emptyList(),
)

class RepositoryManager(
    private val store: RepositoryConfigurationStore,
    private val indexSource: RepositoryIndexSource,
    private val parser: RepositoryCatalogParser = RepositoryCatalogParser(),
    private val contentPolicy: ContentPolicyEngine = ContentPolicyEngine(),
) {
    private val catalogs = LinkedHashMap<String, RepositoryCatalogSnapshot>()

    init {
        store.load().forEach { url ->
            val repository = RepositoryAddress.parse(url)
            require(catalogs.putIfAbsent(repository.normalized, RepositoryCatalogSnapshot(repository)) == null) {
                "إعدادات المستودعات تحتوي عنوانًا مكررًا: ${repository.normalized}"
            }
        }
    }

    @Synchronized
    fun repositories(): List<RepositoryCatalogSnapshot> = catalogs.values.toList()

    @Synchronized
    fun addRepository(url: String): RepositoryCatalogSnapshot {
        val repository = RepositoryAddress.parse(url)
        require(repository.normalized !in catalogs) { "المستودع مضاف بالفعل" }
        val validated = fetchAndParse(repository)
        store.save(catalogs.values.map { it.repository.inputUrl } + repository.inputUrl)
        return validated.copy(lastUpdated = Instant.now()).also { catalogs[repository.normalized] = it }
    }

    fun testRepository(url: String): RepositoryCatalogSnapshot =
        fetchAndParse(RepositoryAddress.parse(url))

    private fun fetchAndParse(repository: RepositoryAddress): RepositoryCatalogSnapshot {
        val fetched = indexSource.fetch(repository)
        val parsed = try {
            parser.parse(fetched, repository) { url ->
                indexSource.fetchExternalList(url, repository.inputUrl)
            }
        } catch (error: Exception) {
            val diagnostic = fetched.diagnostic
            LOGGER.log(
                Level.WARNING,
                "Repository index downloaded but parsing failed: input=${diagnostic.inputUrl}, " +
                    "final=${diagnostic.finalUrl}, status=${diagnostic.statusCode}, " +
                    "contentType=${diagnostic.contentType}, bodyDownloaded=${diagnostic.bodyDownloaded}",
                error,
            )
            throw RepositoryIndexParseException(
                diagnostic = diagnostic,
                message =
                    "تم تنزيل الفهرس من ${diagnostic.finalUrl} بنجاح (HTTP ${diagnostic.statusCode}, " +
                        "${diagnostic.contentType ?: "نوع محتوى غير معروف"}) لكن تعذر تحليله: " +
                        (error.message ?: error.javaClass.simpleName),
                cause = error,
            )
        }
        return RepositoryCatalogSnapshot(
            repository = repository,
            extensions = parsed.extensions.filterNot { contentPolicy.evaluate(it).isBlocked },
            storeName = parsed.name,
            indexUrl = fetched.diagnostic.finalUrl,
            contentType = fetched.contentType,
            httpStatus = fetched.diagnostic.statusCode,
            redirects = fetched.diagnostic.redirects,
            redirectStatuses = fetched.diagnostic.redirectStatuses,
            bodyDownloaded = fetched.diagnostic.bodyDownloaded,
            fetchDiagnostics = fetched.attempts,
        )
    }

    @Synchronized
    fun removeRepository(url: String): Boolean {
        val normalized = RepositoryAddress.parse(url).normalized
        if (normalized !in catalogs) return false
        store.save(catalogs.filterKeys { it != normalized }.values.map { it.repository.inputUrl })
        catalogs.remove(normalized)
        return true
    }

    @Synchronized
    fun refreshRepository(url: String): RepositoryCatalogSnapshot {
        val requestedRepository = RepositoryAddress.parse(url)
        val previous = catalogs[requestedRepository.normalized]
            ?: throw IllegalArgumentException("المستودع غير مضاف")
        val repository = previous.repository
        val refreshed = try {
            fetchAndParse(repository).copy(lastUpdated = Instant.now())
        } catch (error: Exception) {
            val diagnostic = when (error) {
                is RepositoryIndexFetchException -> error.diagnostics.lastOrNull()
                is RepositoryIndexParseException -> error.diagnostic
                else -> null
            }
            previous.copy(
                error = error.message?.takeIf(String::isNotBlank) ?: error.javaClass.simpleName,
                indexUrl = diagnostic?.finalUrl ?: previous.indexUrl,
                contentType = diagnostic?.contentType ?: previous.contentType,
                httpStatus = diagnostic?.statusCode ?: previous.httpStatus,
                redirects = diagnostic?.redirects ?: previous.redirects,
                redirectStatuses = diagnostic?.redirectStatuses ?: previous.redirectStatuses,
                bodyDownloaded = diagnostic?.bodyDownloaded ?: previous.bodyDownloaded,
                fetchDiagnostics = when (error) {
                    is RepositoryIndexFetchException -> error.diagnostics
                    is RepositoryIndexParseException -> listOf(error.diagnostic)
                    else -> previous.fetchDiagnostics
                },
            )
        }
        catalogs[repository.normalized] = refreshed
        return refreshed
    }

    fun refreshAll(): List<RepositoryCatalogSnapshot> =
        repositories().map { refreshRepository(it.repository.normalized) }

    @Synchronized
    fun search(
        query: String = "",
        language: String? = null,
        repositoryUrl: String? = null,
    ): List<ExtensionDescriptor> {
        val normalizedQuery = query.trim().lowercase()
        val normalizedRepository = repositoryUrl?.let { RepositoryAddress.parse(it).normalized }
        return catalogs.values.asSequence()
            .filter { normalizedRepository == null || it.repository.normalized == normalizedRepository }
            .flatMap { it.extensions.asSequence() }
            .filterNot { contentPolicy.evaluate(it).isBlocked }
            .filter { language == null || it.language.equals(language, ignoreCase = true) }
            .filter { extension ->
                normalizedQuery.isEmpty() ||
                    extension.name.contains(normalizedQuery, ignoreCase = true) ||
                    extension.packageName.contains(normalizedQuery, ignoreCase = true) ||
                    extension.language.contains(normalizedQuery, ignoreCase = true)
            }
            .toList()
    }

    @Synchronized
    fun availableUpdates(installedVersionCodes: Map<String, Long>): List<ExtensionDescriptor> =
        catalogs.values.asSequence()
            .flatMap { it.extensions.asSequence() }
            .filterNot { contentPolicy.evaluate(it).isBlocked }
            .filter { extension ->
                installedVersionCodes[extension.packageName]?.let { extension.versionCode > it } == true
            }
            .toList()

    private companion object {
        val LOGGER: Logger = Logger.getLogger(RepositoryManager::class.java.name)
    }
}
