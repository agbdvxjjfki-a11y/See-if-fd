package app.shura.core.repository

import app.shura.core.content.ContentPolicyEngine
import java.time.Instant
import java.util.LinkedHashMap
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.Executor
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
    private val parsingExecutor: Executor = DIRECT_EXECUTOR,
    private val externalFetchExecutor: Executor = DIRECT_EXECUTOR,
    private val catalogCacheStore: RepositoryCatalogCacheStore = NoRepositoryCatalogCache,
) {
    private val catalogs = LinkedHashMap<String, RepositoryCatalogSnapshot>()

    init {
        store.load().forEach { url ->
            val repository = RepositoryAddress.parse(url)
            val cached = try {
                catalogCacheStore.load(repository.normalized)
                    ?.let { snapshot ->
                        snapshot.copy(extensions = snapshot.extensions.filterNot(contentPolicy::isBlocked))
                    }
            } catch (error: Exception) {
                LOGGER.log(Level.WARNING, "Unable to restore cached repository catalog: ${repository.normalized}", error)
                RepositoryCatalogSnapshot(
                    repository = repository,
                    error = "تعذرت استعادة نسخة الفهرس المحلية: ${error.message ?: error.javaClass.simpleName}",
                )
            }
            require(catalogs.putIfAbsent(
                repository.normalized,
                cached ?: RepositoryCatalogSnapshot(repository),
            ) == null) {
                "إعدادات المستودعات تحتوي عنوانًا مكررًا: ${repository.normalized}"
            }
        }
    }

    @Synchronized
    fun repositories(): List<RepositoryCatalogSnapshot> = catalogs.values.toList()

    fun addRepository(url: String): RepositoryCatalogSnapshot {
        val repository = RepositoryAddress.parse(url)
        synchronized(this) {
            require(repository.normalized !in catalogs) { "المستودع مضاف بالفعل" }
        }
        val validated = fetchAndParse(repository)
        val persisted = validated.copy(lastUpdated = Instant.now())
        return synchronized(this) {
            require(repository.normalized !in catalogs) { "المستودع مضاف بالفعل" }
            try {
                catalogCacheStore.save(persisted)
            } catch (error: Exception) {
                runCatching { catalogCacheStore.remove(repository.normalized) }
                    .onFailure { cleanupError ->
                        LOGGER.log(
                            Level.WARNING,
                            "Unable to clean failed repository catalog cache: ${repository.normalized}",
                            cleanupError,
                        )
                    }
                throw error
            }
            try {
                store.save(catalogs.values.map { it.repository.inputUrl } + repository.inputUrl)
            } catch (error: Exception) {
                runCatching { catalogCacheStore.remove(repository.normalized) }
                    .onFailure { cleanupError ->
                        LOGGER.log(
                            Level.WARNING,
                            "Unable to remove uncommitted repository catalog cache: ${repository.normalized}",
                            cleanupError,
                        )
                    }
                throw error
            }
            persisted.also { catalogs[repository.normalized] = it }
        }
    }

    fun replaceRepository(existingUrl: String, replacementUrl: String): RepositoryCatalogSnapshot {
        val existing = RepositoryAddress.parse(existingUrl)
        val replacement = RepositoryAddress.parse(replacementUrl)
        require(existing.normalized != replacement.normalized) { "رابط المستودع لم يتغير" }
        synchronized(this) {
            require(existing.normalized in catalogs) { "المستودع المراد تعديله غير موجود" }
            require(replacement.normalized !in catalogs) { "رابط المستودع الجديد مضاف بالفعل" }
        }
        val validated = fetchAndParse(replacement).copy(lastUpdated = Instant.now())
        return synchronized(this) {
            require(existing.normalized in catalogs) { "تم حذف المستودع أثناء التعديل" }
            require(replacement.normalized !in catalogs) { "رابط المستودع الجديد أضيف أثناء التعديل" }
            try {
                catalogCacheStore.save(validated)
            } catch (error: Exception) {
                runCatching { catalogCacheStore.remove(replacement.normalized) }
                    .onFailure { cleanupError ->
                        LOGGER.log(
                            Level.WARNING,
                            "Unable to clean failed replacement catalog cache: ${replacement.normalized}",
                            cleanupError,
                        )
                    }
                throw error
            }
            val savedUrls = catalogs.values.map { snapshot ->
                if (snapshot.repository.normalized == existing.normalized) {
                    replacement.inputUrl
                } else {
                    snapshot.repository.inputUrl
                }
            }
            try {
                store.save(savedUrls)
            } catch (error: Exception) {
                runCatching { catalogCacheStore.remove(replacement.normalized) }
                    .onFailure { cleanupError ->
                        LOGGER.log(
                            Level.WARNING,
                            "Unable to remove uncommitted replacement catalog cache: ${replacement.normalized}",
                            cleanupError,
                        )
                    }
                throw error
            }
            catalogs.remove(existing.normalized)
            catalogs[replacement.normalized] = validated
            try {
                catalogCacheStore.remove(existing.normalized)
            } catch (error: Exception) {
                LOGGER.log(Level.WARNING, "Unable to remove replaced repository catalog cache: ${existing.normalized}", error)
            }
            validated
        }
    }

    fun testRepository(url: String): RepositoryCatalogSnapshot =
        fetchAndParse(RepositoryAddress.parse(url))

    private fun fetchAndParse(repository: RepositoryAddress): RepositoryCatalogSnapshot {
        val fetched = indexSource.fetch(repository)
        val parsed = try {
            CompletableFuture.supplyAsync(
                {
                    parser.parse(fetched, repository) { url ->
                        CompletableFuture.supplyAsync(
                            { indexSource.fetchExternalList(url, repository.inputUrl) },
                            externalFetchExecutor,
                        ).join()
                    }.let { parsed ->
                        parsed.copy(extensions = parsed.extensions.filterNot(contentPolicy::isBlocked))
                    }
                },
                parsingExecutor,
            ).join()
        } catch (error: Exception) {
            val cause = if (error is CompletionException) error.cause ?: error else error
            val diagnostic = fetched.diagnostic
            LOGGER.log(
                Level.WARNING,
                "Repository index downloaded but parsing failed: input=${diagnostic.inputUrl}, " +
                    "final=${diagnostic.finalUrl}, status=${diagnostic.statusCode}, " +
                    "contentType=${diagnostic.contentType}, bodyDownloaded=${diagnostic.bodyDownloaded}",
                cause,
            )
            throw RepositoryIndexParseException(
                diagnostic = diagnostic,
                message =
                    "تم تنزيل الفهرس من ${diagnostic.finalUrl} بنجاح (HTTP ${diagnostic.statusCode}, " +
                        "${diagnostic.contentType ?: "نوع محتوى غير معروف"}) لكن تعذر تحليله: " +
                    (cause.message ?: cause.javaClass.simpleName),
                cause = cause,
            )
        }
        return RepositoryCatalogSnapshot(
            repository = repository,
            extensions = parsed.extensions,
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

    fun removeRepository(url: String): Boolean {
        val normalized = RepositoryAddress.parse(url).normalized
        val removed = synchronized(this) {
            if (normalized !in catalogs) return@synchronized false
            store.save(catalogs.filterKeys { it != normalized }.values.map { it.repository.inputUrl })
            catalogs.remove(normalized)
            try {
                catalogCacheStore.remove(normalized)
            } catch (error: Exception) {
                LOGGER.log(Level.WARNING, "Unable to remove repository catalog cache: $normalized", error)
            }
            true
        }
        return removed
    }

    fun refreshRepository(url: String): RepositoryCatalogSnapshot {
        val requestedRepository = RepositoryAddress.parse(url)
        val previous = synchronized(this) {
            catalogs[requestedRepository.normalized]
                ?: throw IllegalArgumentException("المستودع غير مضاف")
        }
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
        return synchronized(this) {
            val current = catalogs[repository.normalized]
                ?: throw IllegalStateException("تم حذف المستودع أثناء التحديث: ${repository.normalized}")
            if (current !== previous) return@synchronized current
            if (refreshed.error == null) {
                try {
                    catalogCacheStore.save(refreshed)
                } catch (error: Exception) {
                    LOGGER.log(
                        Level.WARNING,
                        "Unable to persist refreshed repository catalog cache: ${repository.normalized}",
                        error,
                    )
                }
            }
            catalogs[repository.normalized] = refreshed
            refreshed
        }
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
            .filter { extension ->
                installedVersionCodes[extension.packageName]?.let { extension.versionCode > it } == true
            }
            .toList()

    private companion object {
        val DIRECT_EXECUTOR = Executor { command -> command.run() }
        val LOGGER: Logger = Logger.getLogger(RepositoryManager::class.java.name)
    }
}
