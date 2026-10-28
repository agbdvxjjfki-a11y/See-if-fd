package app.shura.core.repository

import java.time.Instant
import java.util.LinkedHashMap

data class RepositoryCatalogSnapshot(
    val repository: RepositoryAddress,
    val extensions: List<ExtensionDescriptor> = emptyList(),
    val lastUpdated: Instant? = null,
    val error: String? = null,
)

class RepositoryManager(
    private val store: RepositoryConfigurationStore,
    private val indexSource: RepositoryIndexSource,
    private val parser: RepositoryCatalogParser = RepositoryCatalogParser(),
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
        store.save(catalogs.keys.toList() + repository.normalized)
        return RepositoryCatalogSnapshot(repository).also {
            catalogs[repository.normalized] = it
        }
    }

    @Synchronized
    fun removeRepository(url: String): Boolean {
        val normalized = RepositoryAddress.parse(url).normalized
        if (normalized !in catalogs) return false
        store.save(catalogs.keys.filterNot { it == normalized })
        catalogs.remove(normalized)
        return true
    }

    @Synchronized
    fun refreshRepository(url: String): RepositoryCatalogSnapshot {
        val repository = RepositoryAddress.parse(url)
        val previous = catalogs[repository.normalized]
            ?: throw IllegalArgumentException("المستودع غير مضاف")
        val refreshed = try {
            val index = indexSource.fetch(repository)
            previous.copy(
                extensions = parser.parse(index, repository),
                lastUpdated = Instant.now(),
                error = null,
            )
        } catch (error: Exception) {
            previous.copy(error = error.message?.takeIf(String::isNotBlank) ?: error.javaClass.simpleName)
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
}
