package app.shura.core.repository

import com.google.gson.Gson
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Instant

interface RepositoryCatalogCacheStore {
    fun load(repositoryUrl: String): RepositoryCatalogSnapshot?
    fun save(snapshot: RepositoryCatalogSnapshot)
    fun remove(repositoryUrl: String)
}

class FileRepositoryCatalogCacheStore(
    private val directory: Path,
) : RepositoryCatalogCacheStore {
    override fun load(repositoryUrl: String): RepositoryCatalogSnapshot? {
        val repository = RepositoryAddress.parse(repositoryUrl)
        val file = cachePath(repository.normalized)
        if (!Files.exists(file)) return null
        require(Files.size(file) <= MAX_CACHE_BYTES) { "ملف ذاكرة الفهرس أكبر من الحد المسموح" }
        val cached = try {
            GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), CachedCatalog::class.java)
        } catch (error: Exception) {
            throw IllegalStateException("تعذرت قراءة ذاكرة فهرس ${repository.normalized}", error)
        } ?: throw IllegalStateException("ملف ذاكرة الفهرس فارغ")
        require(RepositoryAddress.parse(cached.repositoryUrl).normalized == repository.normalized) {
            "رابط المستودع في ذاكرة الفهرس لا يطابق اسم الملف"
        }
        return cached.toSnapshot(repository)
    }

    override fun save(snapshot: RepositoryCatalogSnapshot) {
        require(snapshot.error == null) { "لا يمكن تخزين فهرس فاشل كنسخة صالحة" }
        require(snapshot.extensions.size <= MAX_CACHED_EXTENSIONS) {
            "عدد الإضافات في الفهرس يتجاوز الحد المسموح للتخزين"
        }
        val file = cachePath(snapshot.repository.normalized)
        Files.createDirectories(directory)
        val temporary = Files.createTempFile(directory, "shura-catalog-", ".tmp")
        try {
            Files.writeString(
                temporary,
                GSON.toJson(CachedCatalog.from(snapshot)),
                StandardCharsets.UTF_8,
            )
            require(Files.size(temporary) <= MAX_CACHE_BYTES) {
                "حجم ذاكرة الفهرس يتجاوز الحد المسموح للتخزين"
            }
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING)
            }
            trimCache(file)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    override fun remove(repositoryUrl: String) {
        Files.deleteIfExists(cachePath(RepositoryAddress.parse(repositoryUrl).normalized))
    }

    private fun cachePath(repositoryUrl: String): Path {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(repositoryUrl.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
        return directory.resolve("$digest.json")
    }

    private fun trimCache(newestFile: Path) {
        val files = Files.newDirectoryStream(directory, "*.json").use { stream ->
            stream.toList().sortedBy { Files.getLastModifiedTime(it) }
        }.toMutableList()
        var totalBytes = files.sumOf(Files::size)
        while (files.size > MAX_CACHE_FILES || totalBytes > MAX_TOTAL_CACHE_BYTES) {
            val oldest = files.firstOrNull { it != newestFile }
                ?: throw IllegalStateException("تعذر ضبط الحد الأقصى لذاكرة فهارس المستودعات")
            totalBytes -= Files.size(oldest)
            Files.delete(oldest)
            files.remove(oldest)
        }
    }

    private data class CachedCatalog(
        val repositoryUrl: String,
        val extensions: List<ExtensionDescriptor>,
        val lastUpdated: String?,
        val storeName: String?,
        val indexUrl: String?,
        val contentType: String?,
        val httpStatus: Int?,
        val redirects: List<String>,
        val redirectStatuses: List<Int>,
        val bodyDownloaded: Boolean,
        val fetchDiagnostics: List<IndexRequestDiagnostic>,
    ) {
        fun toSnapshot(repository: RepositoryAddress) = RepositoryCatalogSnapshot(
            repository = repository,
            extensions = extensions,
            lastUpdated = lastUpdated?.let(Instant::parse),
            storeName = storeName,
            indexUrl = indexUrl,
            contentType = contentType,
            httpStatus = httpStatus,
            redirects = redirects,
            redirectStatuses = redirectStatuses,
            bodyDownloaded = bodyDownloaded,
            fetchDiagnostics = fetchDiagnostics,
        )

        companion object {
            fun from(snapshot: RepositoryCatalogSnapshot) = CachedCatalog(
                repositoryUrl = snapshot.repository.normalized,
                extensions = snapshot.extensions,
                lastUpdated = snapshot.lastUpdated?.toString(),
                storeName = snapshot.storeName,
                indexUrl = snapshot.indexUrl,
                contentType = snapshot.contentType,
                httpStatus = snapshot.httpStatus,
                redirects = snapshot.redirects,
                redirectStatuses = snapshot.redirectStatuses,
                bodyDownloaded = snapshot.bodyDownloaded,
                fetchDiagnostics = snapshot.fetchDiagnostics,
            )
        }
    }

    private companion object {
        const val MAX_CACHE_BYTES = 32 * 1024 * 1024L
        const val MAX_TOTAL_CACHE_BYTES = 128 * 1024 * 1024L
        const val MAX_CACHE_FILES = 16
        const val MAX_CACHED_EXTENSIONS = 50_000
        val GSON = Gson()
    }
}

object NoRepositoryCatalogCache : RepositoryCatalogCacheStore {
    override fun load(repositoryUrl: String): RepositoryCatalogSnapshot? = null
    override fun save(snapshot: RepositoryCatalogSnapshot) = Unit
    override fun remove(repositoryUrl: String) = Unit
}
