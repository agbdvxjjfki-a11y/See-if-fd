package app.shura.android

import android.app.Application
import android.content.Context
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.shura.core.content.ContentPolicyEngine
import app.shura.core.manga.MangaDexClient
import app.shura.core.manga.OnlineChapter
import app.shura.core.manga.OnlineManga
import app.shura.core.reader.AutoFlipSettings
import app.shura.core.repository.ExtensionDescriptor
import app.shura.core.repository.FileRepositoryConfigurationStore
import app.shura.core.repository.FileRepositoryCatalogCacheStore
import app.shura.core.repository.RepositoryCatalogSnapshot
import app.shura.core.repository.RepositoryIndexClient
import app.shura.core.repository.RepositoryManager
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.asExecutor
import org.json.JSONArray

data class ShuraUiState(
    val repositories: List<RepositoryCatalogSnapshot> = emptyList(),
    val preparedExtensionCatalog: List<ExtensionDescriptor>? = null,
    val extensionByPackage: Map<String, ExtensionDescriptor> = emptyMap(),
    val extensionByRepositoryAndPackage: Map<Pair<String, String>, ExtensionDescriptor> = emptyMap(),
    val repositoryOperation: Boolean = false,
    val repositoryError: String? = null,
    val repositoryMessage: String? = null,
    val repositoryValidation: RepositoryCatalogSnapshot? = null,
    val repositoryValidatedInput: String? = null,
    val repositorySuccessName: String? = null,
    val repositorySuccessCount: Int? = null,
    val libraryTitles: List<String> = emptyList(),
    val favoriteTitles: Set<String> = emptySet(),
    val onlineLibrary: List<SavedOnlineManga> = emptyList(),
    val onlineReadingProgress: Map<String, OnlineReadingProgress> = emptyMap(),
    val mangaSearchResults: List<OnlineManga> = emptyList(),
    val selectedOnlineManga: OnlineManga? = null,
    val onlineMangaChapters: List<OnlineChapter> = emptyList(),
    val activeOnlineChapter: OnlineChapter? = null,
    val onlineChapterPages: List<String> = emptyList(),
    val mangaSourceLoading: Boolean = false,
    val mangaChapterLoading: Boolean = false,
    val mangaSourceError: String? = null,
    val autoFlipEnabled: Boolean = false,
    val autoFlipDurationSeconds: Int = 5,
    val pauseAutoFlipOnTouch: Boolean = false,
    val readerOrientation: ReaderOrientation = ReaderOrientation.HORIZONTAL,
    val imageFitMode: ImageFitMode = ImageFitMode.FIT,
    val showReaderControls: Boolean = true,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dataSaver: Boolean = false,
    val reduceImageMemory: Boolean = false,
    val maintenanceMessage: String? = null,
    val language: String = "ar",
    val initialized: Boolean = false,
    val installedPackages: Map<String, InstalledExtensionPackage> = emptyMap(),
    val extensionOperations: Map<String, ExtensionOperationState> = emptyMap(),
) {
    fun extensionCatalog(): List<ExtensionDescriptor> =
        preparedExtensionCatalog ?: repositories.asSequence()
            .flatMap { it.extensions.asSequence() }
            .filterNot(CONTENT_POLICY::isBlocked)
            .toList()

    fun searchExtensions(query: String = "", language: String? = null): List<ExtensionDescriptor> {
        val normalizedQuery = query.trim()
        return extensionCatalog()
            .filter { language == null || it.language.equals(language, ignoreCase = true) }
            .filter { extension ->
                normalizedQuery.isEmpty() ||
                    extension.name.contains(normalizedQuery, ignoreCase = true) ||
                    extension.packageName.contains(normalizedQuery, ignoreCase = true) ||
                    extension.language.contains(normalizedQuery, ignoreCase = true)
            }
    }

    fun findExtension(packageName: String, repositoryUrl: String? = null): ExtensionDescriptor? {
        if (preparedExtensionCatalog != null) {
            return if (repositoryUrl == null) {
                extensionByPackage[packageName]
            } else {
                extensionByRepositoryAndPackage[repositoryUrl to packageName]
            }
        }
        return repositories.asSequence()
            .filter { repositoryUrl == null || it.repository.normalized == repositoryUrl }
            .flatMap { it.extensions.asSequence() }
            .firstOrNull { extension ->
                extension.packageName == packageName && !CONTENT_POLICY.isBlocked(extension)
            }
    }

    private companion object {
        val CONTENT_POLICY = ContentPolicyEngine()
    }
}

enum class ThemeMode(val preferenceValue: String) {
    SYSTEM("system"),
    LIGHT("light"),
    DARK("dark"),
}

enum class ReaderOrientation(val preferenceValue: String) {
    HORIZONTAL("horizontal"),
    VERTICAL("vertical"),
}

enum class ImageFitMode(val preferenceValue: String) {
    FIT("fit"),
    CROP("crop"),
}

enum class ExtensionOperationPhase {
    DOWNLOADING,
    VERIFYING,
    NEED_INSTALL_PERMISSION,
    AWAITING_ANDROID,
    REMOVAL_PENDING,
    INSTALLED,
    UPDATE_AVAILABLE,
    NOT_INSTALLED,
    INCOMPATIBLE,
    FAILED,
}

data class ExtensionOperationState(
    val phase: ExtensionOperationPhase,
    val downloadedBytes: Long = 0,
    val totalBytes: Long? = null,
    val message: String? = null,
)

class ShuraViewModel(application: Application) : AndroidViewModel(application) {
    private val preferences = application.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val mutableState = MutableStateFlow(initialState())
    private val packageAccess = AndroidExtensionPackages(application)
    private val installCoordinator = ExtensionInstallCoordinator(
        cacheDirectory = application.cacheDir,
        downloader = HttpsApkDownloader(),
        archiveInspector = packageAccess::inspectArchive,
    )
    private val contentPolicy = ContentPolicyEngine()
    private val mangaDexClient = MangaDexClient()
    private var mangaSourceJob: Job? = null
    private val installPreferences = application.getSharedPreferences(
        AndroidExtensionPackages.INSTALL_RESULTS_PREFERENCES,
        Context.MODE_PRIVATE,
    )
    private val preparedApks = mutableMapOf<String, VerifiedExtensionApk>()
    private val installJobs = mutableMapOf<String, Job>()
    val state: StateFlow<ShuraUiState> = mutableState.asStateFlow()
    private var manager: RepositoryManager? = null

    fun searchOnlineManga(query: String, language: String) {
        mangaSourceJob?.cancel()
        val normalizedQuery = query.trim()
        if (normalizedQuery.length < MIN_MANGA_QUERY_LENGTH) {
            mutableState.value = mutableState.value.copy(
                mangaSearchResults = emptyList(),
                mangaSourceLoading = false,
                mangaSourceError = null,
            )
            return
        }
        mangaSourceJob = viewModelScope.launch {
            mutableState.value = mutableState.value.copy(
                mangaSourceLoading = true,
                mangaSourceError = null,
            )
            try {
                val results = withContext(Dispatchers.IO) {
                    mangaDexClient.search(normalizedQuery, language)
                }
                mutableState.value = mutableState.value.copy(
                    mangaSearchResults = results,
                    mangaSourceLoading = false,
                )
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                mutableState.value = mutableState.value.copy(
                    mangaSourceLoading = false,
                    mangaSourceError = error.message ?: error.javaClass.simpleName,
                )
            }
        }
    }

    fun openOnlineManga(manga: OnlineManga, language: String) {
        mangaSourceJob?.cancel()
        mangaSourceJob = viewModelScope.launch {
            mutableState.value = mutableState.value.copy(
                selectedOnlineManga = manga,
                onlineMangaChapters = emptyList(),
                mangaSourceLoading = true,
                mangaSourceError = null,
            )
            try {
                val (details, chapters) = withContext(Dispatchers.IO) {
                    mangaDexClient.getManga(manga.id, language) to
                        mangaDexClient.getChapters(manga.id, language)
                }
                mutableState.value = mutableState.value.copy(
                    selectedOnlineManga = details,
                    onlineMangaChapters = chapters,
                    mangaSourceLoading = false,
                )
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                mutableState.value = mutableState.value.copy(
                    mangaSourceLoading = false,
                    mangaSourceError = error.message ?: error.javaClass.simpleName,
                )
            }
        }
    }

    fun closeOnlineMangaDetails() {
        mangaSourceJob?.cancel()
        mutableState.value = mutableState.value.copy(
            selectedOnlineManga = null,
            onlineMangaChapters = emptyList(),
            mangaSourceLoading = false,
            mangaSourceError = null,
        )
    }

    fun openOnlineChapter(chapter: OnlineChapter, dataSaver: Boolean) {
        mangaSourceJob?.cancel()
        mangaSourceJob = viewModelScope.launch {
            mutableState.value = mutableState.value.copy(
                activeOnlineChapter = chapter,
                onlineChapterPages = emptyList(),
                mangaChapterLoading = true,
                mangaSourceError = null,
            )
            try {
                val pages = withContext(Dispatchers.IO) {
                    mangaDexClient.getChapterPageUrls(chapter.id, dataSaver)
                }
                mutableState.value = mutableState.value.copy(
                    onlineChapterPages = pages,
                    mangaChapterLoading = false,
                )
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                mutableState.value = mutableState.value.copy(
                    mangaChapterLoading = false,
                    mangaSourceError = error.message ?: error.javaClass.simpleName,
                )
            }
        }
    }

    fun closeOnlineChapter() {
        mangaSourceJob?.cancel()
        mutableState.value = mutableState.value.copy(
            activeOnlineChapter = null,
            onlineChapterPages = emptyList(),
            mangaChapterLoading = false,
            mangaSourceError = null,
        )
    }

    init {
        viewModelScope.launch {
            PackageInstallEvents.events.collect { refreshInstalledExtensions() }
        }
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val initializedManager = RepositoryManager(
                        store = FileRepositoryConfigurationStore(
                            getApplication<Application>().filesDir.toPath().resolve(REPOSITORIES_FILE),
                        ),
                        indexSource = RepositoryIndexClient(),
                        parsingExecutor = Dispatchers.Default.asExecutor(),
                        externalFetchExecutor = Dispatchers.IO.asExecutor(),
                        catalogCacheStore = FileRepositoryCatalogCacheStore(
                            getApplication<Application>().filesDir.toPath().resolve("repository-catalog-cache"),
                        ),
                    )
                    manager = initializedManager
                    initializedManager.refreshAll()
                }
            }.onSuccess { repositories ->
                publishRepositories(repositories)
                mutableState.value = mutableState.value.copy(initialized = true)
                refreshInstalledExtensions()
            }.onFailure { error ->
                mutableState.value = mutableState.value.copy(
                    initialized = true,
                    repositoryError = error.message ?: error.javaClass.simpleName,
                )
            }
        }
    }

    fun clearRepositoryValidation() {
        mutableState.value = mutableState.value.copy(
            repositoryValidation = null,
            repositoryValidatedInput = null,
            repositoryError = null,
        )
    }

    fun testRepository(url: String) {
        if (mutableState.value.repositoryOperation) return
        viewModelScope.launch {
            beginRepositoryOperation()
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    (manager ?: error("Repository manager is unavailable")).testRepository(url)
                }
            }
            result.onSuccess { snapshot ->
                mutableState.value = mutableState.value.copy(
                    repositoryValidation = snapshot,
                    repositoryValidatedInput = url.trim(),
                    repositoryError = null,
                )
            }.onFailure { error ->
                mutableState.value = mutableState.value.copy(
                    repositoryValidation = null,
                    repositoryValidatedInput = null,
                    repositoryError = error.message ?: error.javaClass.simpleName,
                )
            }
            endRepositoryOperation()
        }
    }

    fun addRepository(url: String) {
        if (mutableState.value.repositoryOperation) return
        if (mutableState.value.repositoryValidatedInput != url.trim()) {
            mutableState.value = mutableState.value.copy(repositoryError = "اختبر رابط الفهرس قبل إضافته")
            return
        }
        viewModelScope.launch {
            beginRepositoryOperation()
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val repositoryManager = manager ?: error("Repository manager is unavailable")
                    repositoryManager.addRepository(url)
                }
            }
            result.onSuccess { snapshot ->
                updateRepository(snapshot)
                mutableState.value = mutableState.value.copy(
                    repositoryMessage = "repository_added",
                    repositorySuccessName = snapshot.storeName,
                    repositorySuccessCount = snapshot.extensions.size,
                    repositoryValidation = null,
                    repositoryValidatedInput = null,
                )
            }.onFailure { error ->
                mutableState.value = mutableState.value.copy(
                    repositoryError = error.message ?: error.javaClass.simpleName,
                )
            }
            endRepositoryOperation()
        }
    }

    fun replaceRepository(existingUrl: String, replacementUrl: String) {
        if (mutableState.value.repositoryOperation) return
        if (mutableState.value.repositoryValidatedInput != replacementUrl.trim()) {
            mutableState.value = mutableState.value.copy(repositoryError = "اختبر رابط الفهرس قبل حفظ التعديل")
            return
        }
        viewModelScope.launch {
            beginRepositoryOperation()
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    (manager ?: error("Repository manager is unavailable"))
                        .replaceRepository(existingUrl, replacementUrl)
                }
            }
            result.onSuccess { snapshot ->
                val repositories = mutableState.value.repositories
                    .filterNot { it.repository.normalized == existingUrl }
                    .plus(snapshot)
                publishRepositories(repositories)
                mutableState.value = mutableState.value.copy(
                    repositoryMessage = "repository_updated",
                    repositorySuccessName = snapshot.storeName,
                    repositorySuccessCount = snapshot.extensions.size,
                    repositoryValidation = null,
                    repositoryValidatedInput = null,
                )
            }.onFailure { error ->
                mutableState.value = mutableState.value.copy(
                    repositoryError = error.message ?: error.javaClass.simpleName,
                )
            }
            endRepositoryOperation()
        }
    }

    fun refreshRepository(url: String) {
        if (mutableState.value.repositoryOperation) return
        viewModelScope.launch {
            beginRepositoryOperation()
            runCatching {
                withContext(Dispatchers.IO) {
                    (manager ?: error("Repository manager is unavailable")).refreshRepository(url)
                }
            }.onSuccess { updateRepository(it) }
                .onFailure { error ->
                    mutableState.value = mutableState.value.copy(
                        repositoryError = error.message ?: error.javaClass.simpleName,
                    )
                }
                refreshInstalledExtensions()
                endRepositoryOperation()
        }
    }

    fun refreshAllRepositories() {
        if (mutableState.value.repositoryOperation) return
        viewModelScope.launch {
            beginRepositoryOperation()
            runCatching {
                withContext(Dispatchers.IO) {
                    (manager ?: error("Repository manager is unavailable")).refreshAll()
                }
            }.onSuccess { repositories ->
                publishRepositories(repositories)
            }.onFailure { error ->
                mutableState.value = mutableState.value.copy(
                    repositoryError = error.message ?: error.javaClass.simpleName,
                )
            }
            refreshInstalledExtensions()
            endRepositoryOperation()
        }
    }

    fun removeRepository(url: String) {
        if (mutableState.value.repositoryOperation) return
        viewModelScope.launch {
            beginRepositoryOperation()
            runCatching {
                withContext(Dispatchers.IO) {
                    val repositoryManager = manager ?: error("Repository manager is unavailable")
                    repositoryManager.removeRepository(url)
                    repositoryManager.repositories()
                }
            }.onSuccess { repositories ->
                publishRepositories(repositories)
            }.onFailure { error ->
                mutableState.value = mutableState.value.copy(
                    repositoryError = error.message ?: error.javaClass.simpleName,
                )
            }
            refreshInstalledExtensions()
            endRepositoryOperation()
        }
    }

    fun addLibraryTitle(title: String) {
        val normalized = title.trim()
        if (normalized.isEmpty()) return
        viewModelScope.launch {
            val updated = (mutableState.value.libraryTitles + normalized).distinct()
            withContext(Dispatchers.IO) {
                preferences.edit().putString(LIBRARY_KEY, JSONArray(updated).toString()).apply()
            }
            mutableState.value = mutableState.value.copy(libraryTitles = updated)
        }
    }

    fun addOnlineMangaToLibrary(manga: OnlineManga) {
        val entries = mutableState.value.onlineLibrary
        if (entries.any { it.id == manga.id }) return
        val updated = entries + SavedOnlineManga(manga.id, manga.title)
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                preferences.edit().putString(ONLINE_LIBRARY_KEY, encodeOnlineLibrary(updated)).apply()
            }
            mutableState.value = mutableState.value.copy(onlineLibrary = updated)
        }
    }

    fun removeOnlineMangaFromLibrary(mangaId: String) {
        val updated = mutableState.value.onlineLibrary.filterNot { it.id == mangaId }
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                preferences.edit().putString(ONLINE_LIBRARY_KEY, encodeOnlineLibrary(updated)).apply()
            }
            mutableState.value = mutableState.value.copy(onlineLibrary = updated)
        }
    }

    fun saveOnlineReadingProgress(chapter: OnlineChapter, pageIndex: Int) {
        if (pageIndex < 0) return
        val progress = OnlineReadingProgress(chapter.mangaId, chapter.id, pageIndex)
        if (mutableState.value.onlineReadingProgress[chapter.mangaId] == progress) return
        val updated = mutableState.value.onlineReadingProgress + (chapter.mangaId to progress)
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                preferences.edit()
                    .putString(ONLINE_READING_PROGRESS_KEY, encodeOnlineReadingProgress(updated))
                    .apply()
            }
            mutableState.value = mutableState.value.copy(onlineReadingProgress = updated)
        }
    }

    fun removeLibraryTitle(title: String) {
        viewModelScope.launch {
            val updated = mutableState.value.libraryTitles - title
            val favorites = mutableState.value.favoriteTitles - title
            withContext(Dispatchers.IO) {
                preferences.edit().putString(LIBRARY_KEY, JSONArray(updated).toString()).apply()
                preferences.edit().putString(FAVORITES_KEY, JSONArray(favorites.toList()).toString()).apply()
            }
            mutableState.value = mutableState.value.copy(
                libraryTitles = updated,
                favoriteTitles = favorites,
            )
        }
    }

    fun toggleFavorite(title: String) {
        if (title !in mutableState.value.libraryTitles) return
        val favorites = mutableState.value.favoriteTitles
        val updated = if (title in favorites) favorites - title else favorites + title
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                preferences.edit().putString(FAVORITES_KEY, JSONArray(updated.toList()).toString()).apply()
            }
            mutableState.value = mutableState.value.copy(favoriteTitles = updated)
        }
    }

    fun installExtension(packageName: String, repositoryUrl: String? = null) {
        if (installJobs[packageName]?.isActive == true) return
        val extension = mutableState.value.findExtension(packageName, repositoryUrl)
        if (extension == null || contentPolicy.isBlocked(extension)) {
            updateExtensionOperation(
                packageName,
                ExtensionOperationState(
                    ExtensionOperationPhase.FAILED,
                    message = "الإضافة غير موجودة في الفهرس المسموح أو رُفضت وفق سياسة المحتوى",
                ),
            )
            return
        }
        val installed = mutableState.value.installedPackages[packageName]
        if (installed != null && !installed.isMihonExtension) {
            updateExtensionOperation(
                packageName,
                ExtensionOperationState(ExtensionOperationPhase.INCOMPATIBLE, message = "الحزمة المثبتة ليست إضافة Mihon معروفة"),
            )
            return
        }
        if (installed != null && installed.versionCode >= extension.versionCode) {
            updateExtensionOperation(packageName, ExtensionOperationState(ExtensionOperationPhase.INSTALLED))
            return
        }
        preparedApks.remove(packageName)?.file?.delete()
        updateExtensionOperation(packageName, ExtensionOperationState(ExtensionOperationPhase.DOWNLOADING))
        lateinit var installJob: Job
        installJob = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                val prepared = withContext(Dispatchers.IO) {
                    installCoordinator.prepare(
                        extension = extension,
                        installedSignerSha256 = installed?.signerSha256.orEmpty(),
                        onProgress = { progress ->
                            updateExtensionOperation(
                                packageName,
                                ExtensionOperationState(
                                    ExtensionOperationPhase.DOWNLOADING,
                                    progress.downloadedBytes,
                                    progress.totalBytes,
                                ),
                            )
                        },
                        onVerifying = {
                            updateExtensionOperation(
                                packageName,
                                ExtensionOperationState(ExtensionOperationPhase.VERIFYING),
                            )
                        },
                    )
                }
                preparedApks[packageName]?.file?.delete()
                preparedApks[packageName] = prepared
                val stillAllowed = mutableState.value.findExtension(packageName, extension.repositoryUrl)
                if (stillAllowed == null ||
                    stillAllowed.apkUrl != extension.apkUrl ||
                    stillAllowed.version != extension.version ||
                    stillAllowed.versionCode != extension.versionCode ||
                    stillAllowed.apkSha256 != extension.apkSha256 ||
                    stillAllowed.apkSignerSha256 != extension.apkSignerSha256 ||
                    contentPolicy.isBlocked(stillAllowed)
                ) {
                    prepared.file.delete()
                    preparedApks.remove(packageName)
                    error("تغير فهرس الإضافة أثناء التنزيل؛ حدّث الفهرس وحاول مجددًا")
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                    !getApplication<Application>().packageManager.canRequestPackageInstalls()
                ) {
                    updateExtensionOperation(
                        packageName,
                        ExtensionOperationState(ExtensionOperationPhase.NEED_INSTALL_PERMISSION),
                    )
                } else {
                    submitPreparedInstall(packageName)
                }
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                updateExtensionOperation(
                    packageName,
                    ExtensionOperationState(ExtensionOperationPhase.FAILED, message = error.message ?: "تعذر تجهيز ملف APK للتثبيت"),
                )
            } finally {
                if (installJobs[packageName] === installJob) installJobs.remove(packageName)
            }
        }
        installJobs[packageName] = installJob
        installJob.start()
    }

    fun cancelExtensionInstall(packageName: String) {
        installJobs.remove(packageName)?.cancel()
        preparedApks.remove(packageName)?.file?.delete()
        val extension = mutableState.value.findExtension(packageName)
        val installed = mutableState.value.installedPackages[packageName]
        updateExtensionOperation(
            packageName,
            extension?.let { extensionOperationForInstalledPackage(it, installed) }
                ?: ExtensionOperationState(ExtensionOperationPhase.NOT_INSTALLED),
        )
    }

    fun submitPreparedInstall(packageName: String) {
        val prepared = preparedApks[packageName]
        if (prepared == null) {
            updateExtensionOperation(
                packageName,
                ExtensionOperationState(ExtensionOperationPhase.FAILED, message = "انتهت صلاحية ملف APK؛ أعد التنزيل"),
            )
            return
        }
        val currentExtension = mutableState.value.findExtension(packageName, prepared.descriptor.repositoryUrl)
        if (currentExtension == null ||
            currentExtension.apkUrl != prepared.descriptor.apkUrl ||
            currentExtension.version != prepared.descriptor.version ||
            currentExtension.versionCode != prepared.descriptor.versionCode ||
            currentExtension.apkSha256 != prepared.descriptor.apkSha256 ||
            currentExtension.apkSignerSha256 != prepared.descriptor.apkSignerSha256 ||
            contentPolicy.isBlocked(currentExtension)
        ) {
            prepared.file.delete()
            preparedApks.remove(packageName)
            updateExtensionOperation(
                packageName,
                ExtensionOperationState(ExtensionOperationPhase.FAILED, message = "لم يعد هذا الإصدار مسموحًا أو موجودًا في الفهرس"),
            )
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !getApplication<Application>().packageManager.canRequestPackageInstalls()
        ) {
            updateExtensionOperation(
                packageName,
                ExtensionOperationState(ExtensionOperationPhase.NEED_INSTALL_PERMISSION),
            )
            return
        }
        updateExtensionOperation(packageName, ExtensionOperationState(ExtensionOperationPhase.AWAITING_ANDROID))
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val installed = packageAccess.inspectInstalled(packageName)
                    require(installed == null || installed.isMihonExtension) {
                        "الحزمة المثبتة ليست إضافة Mihon معروفة"
                    }
                    require(installed == null || installed.versionCode < prepared.archive.versionCode) {
                        "يوجد إصدار مثبت أحدث أو مساوٍ؛ لن يخفض Shura الإصدار"
                    }
                    require(
                        installed == null ||
                            prepared.archive.signerSha256.any(installed.signerSha256::contains),
                    ) { "توقيع APK الجديد لا يطابق توقيع الإضافة المثبتة" }
                    packageAccess.install(prepared)
                }
                preparedApks.remove(packageName)
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                prepared.file.delete()
                preparedApks.remove(packageName)
                updateExtensionOperation(
                    packageName,
                    ExtensionOperationState(ExtensionOperationPhase.FAILED, message = error.message ?: "تعذر بدء التثبيت في Android"),
                )
            }
        }
    }

    fun markInstallPermissionDenied(packageName: String) {
        updateExtensionOperation(
            packageName,
            ExtensionOperationState(
                ExtensionOperationPhase.NEED_INSTALL_PERMISSION,
                message = "لم يمنح Android إذن تثبيت التطبيقات من Shura",
            ),
        )
    }

    fun markPackageActionFailed(packageName: String, message: String) {
        updateExtensionOperation(
            packageName,
            ExtensionOperationState(ExtensionOperationPhase.FAILED, message = message),
        )
    }

    fun openUninstallSettings(packageName: String): android.content.Intent? {
        val installed = mutableState.value.installedPackages[packageName] ?: return null
        if (!installed.isMihonExtension) {
            updateExtensionOperation(
                packageName,
                ExtensionOperationState(ExtensionOperationPhase.INCOMPATIBLE, message = "لن يزيل Shura حزمة لا يتعرف عليها كإضافة Mihon"),
            )
            return null
        }
        updateExtensionOperation(packageName, ExtensionOperationState(ExtensionOperationPhase.REMOVAL_PENDING))
        return packageAccess.uninstallSettingsIntent(packageName)
    }

    fun refreshInstalledExtensions() {
        viewModelScope.launch {
            val packageNames = withContext(Dispatchers.Default) {
                mutableState.value.extensionCatalog().asSequence()
                    .map { it.packageName }
                    .distinct()
                    .toList()
            }
            if (packageNames.isEmpty()) return@launch
            val installed = try {
                withContext(Dispatchers.IO) {
                    packageNames.mapNotNull { packageName ->
                        packageAccess.inspectInstalled(packageName)?.let { packageName to it }
                    }.toMap()
                }
            } catch (error: SecurityException) {
                packageNames.forEach { packageName ->
                    updateExtensionOperation(
                        packageName,
                        ExtensionOperationState(
                            ExtensionOperationPhase.FAILED,
                            message = error.message ?: "لا يسمح Android بالتحقق من الحزم المثبتة",
                        ),
                    )
                }
                return@launch
            }
            mutableState.value = mutableState.value.copy(installedPackages = installed)
            reconcileInstallResult(installed)
            packageNames.forEach { packageName ->
                val operation = mutableState.value.extensionOperations[packageName]
                if (operation?.phase !in setOf(
                        ExtensionOperationPhase.DOWNLOADING,
                        ExtensionOperationPhase.VERIFYING,
                        ExtensionOperationPhase.AWAITING_ANDROID,
                        ExtensionOperationPhase.NEED_INSTALL_PERMISSION,
                    )
                ) {
                    val extension = mutableState.value.findExtension(packageName) ?: return@forEach
                    val packageInfo = installed[packageName]
                    updateExtensionOperation(
                        packageName,
                        extensionOperationForInstalledPackage(extension, packageInfo),
                    )
                }
            }
        }
    }

    private fun reconcileInstallResult(installed: Map<String, InstalledExtensionPackage>) {
        val packageName = installPreferences.getString(
            AndroidExtensionPackages.EXTRA_INSTALL_RESULT_PACKAGE,
            null,
        ) ?: return
        val expectedVersion = installPreferences.getLong(
            AndroidExtensionPackages.EXTRA_INSTALL_RESULT_VERSION,
            -1L,
        )
        val result = installPreferences.getInt(
            AndroidExtensionPackages.EXTRA_INSTALL_RESULT_STATUS,
            PackageInstaller.STATUS_FAILURE,
        )
        updateExtensionOperation(
            packageName,
            extensionInstallOutcome(
                status = result,
                expectedPackage = packageName,
                expectedVersionCode = expectedVersion,
                installed = installed[packageName],
                systemMessage = installPreferences.getString(
                    AndroidExtensionPackages.EXTRA_INSTALL_RESULT_MESSAGE,
                    null,
                ),
            ),
        )
        installPreferences.edit()
            .remove(AndroidExtensionPackages.EXTRA_INSTALL_RESULT_PACKAGE)
            .remove(AndroidExtensionPackages.EXTRA_INSTALL_RESULT_VERSION)
            .remove(AndroidExtensionPackages.EXTRA_INSTALL_RESULT_STATUS)
            .remove(AndroidExtensionPackages.EXTRA_INSTALL_RESULT_MESSAGE)
            .apply()
    }

    private fun updateExtensionOperation(packageName: String, operation: ExtensionOperationState) {
        mutableState.value = mutableState.value.copy(
            extensionOperations = mutableState.value.extensionOperations + (packageName to operation),
        )
    }

    fun setAutoFlipEnabled(enabled: Boolean) {
        updatePreference(AUTO_FLIP_ENABLED_KEY, enabled) {
            copy(autoFlipEnabled = enabled)
        }
    }

    fun setAutoFlipDuration(seconds: Int): Boolean {
        val settings = AutoFlipSettings()
        if (!settings.setDuration(seconds)) return false
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                preferences.edit().putInt(AUTO_FLIP_DURATION_KEY, seconds).apply()
            }
            mutableState.value = mutableState.value.copy(autoFlipDurationSeconds = seconds)
        }
        return true
    }

    fun setPauseAutoFlipOnTouch(enabled: Boolean) {
        updatePreference(PAUSE_ON_TOUCH_KEY, enabled) {
            copy(pauseAutoFlipOnTouch = enabled)
        }
    }

    fun setReaderOrientation(orientation: ReaderOrientation) {
        updateStringPreference(ORIENTATION_KEY, orientation.preferenceValue) {
            copy(readerOrientation = orientation)
        }
    }

    fun setImageFitMode(mode: ImageFitMode) {
        updateStringPreference(IMAGE_FIT_KEY, mode.preferenceValue) {
            copy(imageFitMode = mode)
        }
    }

    fun setShowReaderControls(show: Boolean) {
        updatePreference(SHOW_READER_CONTROLS_KEY, show) {
            copy(showReaderControls = show)
        }
    }

    fun setDataSaver(enabled: Boolean) {
        updatePreference(DATA_SAVER_KEY, enabled) {
            copy(dataSaver = enabled)
        }
    }

    fun setReduceImageMemory(enabled: Boolean) {
        updatePreference(REDUCE_IMAGE_MEMORY_KEY, enabled) {
            copy(reduceImageMemory = enabled)
        }
    }

    fun clearTemporaryFiles() {
        installJobs.keys.toList().forEach(::cancelExtensionInstall)
        viewModelScope.launch {
            val result = try {
                Result.success(withContext(Dispatchers.IO) {
                    val files = getApplication<Application>().cacheDir.listFiles()
                        ?: throw IllegalStateException("Unable to list Shura cache files")
                    files.filter {
                        it.isFile &&
                            ((it.name.startsWith("shura-extension-") && it.extension == "apk") ||
                                (it.name.startsWith("shura-page-") && it.extension == "image"))
                    }
                        .onEach { file ->
                            if (!file.delete() && file.exists()) {
                                throw IllegalStateException("Unable to delete Shura temporary file: ${file.name}")
                            }
                        }
                        .size
                })
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                Result.failure(error)
            }
            mutableState.value = mutableState.value.copy(
                maintenanceMessage = result.fold(
                    onSuccess = { "temporary_files_cleared:$it" },
                    onFailure = { error -> error.message ?: error.javaClass.simpleName },
                ),
            )
        }
    }

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                preferences.edit().putString(THEME_MODE_KEY, mode.preferenceValue).apply()
            }
            mutableState.value = mutableState.value.copy(themeMode = mode)
        }
    }

    fun setLanguage(language: String) {
        if (language !in setOf("ar", "en")) return
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                preferences.edit().putString(LANGUAGE_KEY, language).apply()
            }
            mutableState.value = mutableState.value.copy(language = language)
        }
    }

    private fun initialState(): ShuraUiState {
        val library = runCatching {
            val array = JSONArray(preferences.getString(LIBRARY_KEY, "[]"))
            List(array.length()) { index -> array.getString(index) }
        }.getOrElse { emptyList() }
        val favorites = runCatching {
            val array = JSONArray(preferences.getString(FAVORITES_KEY, "[]"))
            List(array.length()) { index -> array.getString(index) }.toSet()
        }.getOrElse { emptySet() }
        val onlineLibrary = decodeOnlineLibrary(preferences.getString(ONLINE_LIBRARY_KEY, "[]"))
        val readingProgress = decodeOnlineReadingProgress(
            preferences.getString(ONLINE_READING_PROGRESS_KEY, "[]"),
        )
        return ShuraUiState(
            libraryTitles = library,
            favoriteTitles = favorites.intersect(library.toSet()),
            onlineLibrary = onlineLibrary,
            onlineReadingProgress = readingProgress,
            autoFlipEnabled = preferences.getBoolean(AUTO_FLIP_ENABLED_KEY, false),
            autoFlipDurationSeconds = preferences.getInt(AUTO_FLIP_DURATION_KEY, 5).coerceIn(1, 30),
            pauseAutoFlipOnTouch = preferences.getBoolean(PAUSE_ON_TOUCH_KEY, false),
            readerOrientation = preferences.getString(ORIENTATION_KEY, null)
                ?.let { stored -> ReaderOrientation.entries.firstOrNull { it.preferenceValue == stored } }
                ?: ReaderOrientation.HORIZONTAL,
            imageFitMode = preferences.getString(IMAGE_FIT_KEY, null)
                ?.let { stored -> ImageFitMode.entries.firstOrNull { it.preferenceValue == stored } }
                ?: ImageFitMode.FIT,
            showReaderControls = preferences.getBoolean(SHOW_READER_CONTROLS_KEY, true),
            themeMode = preferences.getString(THEME_MODE_KEY, null)
                ?.let { stored -> ThemeMode.entries.firstOrNull { it.preferenceValue == stored } }
                ?: if (preferences.contains(DARK_THEME_KEY)) {
                    if (preferences.getBoolean(DARK_THEME_KEY, false)) ThemeMode.DARK else ThemeMode.LIGHT
                } else {
                    ThemeMode.SYSTEM
                },
            dataSaver = preferences.getBoolean(DATA_SAVER_KEY, false),
            reduceImageMemory = preferences.getBoolean(REDUCE_IMAGE_MEMORY_KEY, false),
            language = preferences.getString(LANGUAGE_KEY, "ar")?.takeIf { it in setOf("ar", "en") } ?: "ar",
        )
    }

    private fun updatePreference(
        key: String,
        value: Boolean,
        update: ShuraUiState.() -> ShuraUiState,
    ) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                preferences.edit().putBoolean(key, value).apply()
            }
            mutableState.value = mutableState.value.update()
        }
    }

    private fun updateStringPreference(
        key: String,
        value: String,
        update: ShuraUiState.() -> ShuraUiState,
    ) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                preferences.edit().putString(key, value).apply()
            }
            mutableState.value = mutableState.value.update()
        }
    }

    private fun beginRepositoryOperation() {
        mutableState.value = mutableState.value.copy(
            repositoryOperation = true,
            repositoryError = null,
            repositoryMessage = null,
            repositorySuccessName = null,
            repositorySuccessCount = null,
        )
    }

    private fun endRepositoryOperation() {
        mutableState.value = mutableState.value.copy(repositoryOperation = false)
    }

    private suspend fun updateRepository(snapshot: RepositoryCatalogSnapshot) {
        val current = mutableState.value.repositories
        val updated = current.filterNot {
            it.repository.normalized == snapshot.repository.normalized
        } + snapshot
        publishRepositories(updated)
        if (snapshot.error != null) {
            mutableState.value = mutableState.value.copy(repositoryError = snapshot.error)
        }
    }

    private suspend fun publishRepositories(repositories: List<RepositoryCatalogSnapshot>) {
        val (catalog, byPackage, byRepositoryAndPackage) = withContext(Dispatchers.Default) {
            repositories.asSequence()
                .flatMap { it.extensions.asSequence() }
                .filterNot(contentPolicy::isBlocked)
                .toList()
                .let { catalog ->
                    Triple(
                        catalog,
                        catalog.distinctBy { it.packageName }.associateBy { it.packageName },
                        catalog.associateBy { it.repositoryUrl to it.packageName },
                    )
                }
        }
        mutableState.value = mutableState.value.copy(
            repositories = repositories,
            preparedExtensionCatalog = catalog,
            extensionByPackage = byPackage,
            extensionByRepositoryAndPackage = byRepositoryAndPackage,
        )
    }

    fun formatTimestamp(snapshot: RepositoryCatalogSnapshot): String? =
        snapshot.lastUpdated?.atZone(ZoneId.systemDefault())
            ?.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))

    private companion object {
        const val PREFERENCES_NAME = "shura_user_preferences"
        const val REPOSITORIES_FILE = "repositories.json"
        const val LIBRARY_KEY = "library_titles"
        const val FAVORITES_KEY = "favorite_titles"
        const val ONLINE_LIBRARY_KEY = "online_manga_library"
        const val ONLINE_READING_PROGRESS_KEY = "online_manga_reading_progress"
        const val AUTO_FLIP_ENABLED_KEY = "auto_flip_enabled"
        const val AUTO_FLIP_DURATION_KEY = "auto_flip_duration_seconds"
        const val PAUSE_ON_TOUCH_KEY = "pause_auto_flip_on_touch"
        const val ORIENTATION_KEY = "reader_orientation"
        const val IMAGE_FIT_KEY = "reader_image_fit"
        const val SHOW_READER_CONTROLS_KEY = "reader_show_controls"
        const val DATA_SAVER_KEY = "data_saver"
        const val REDUCE_IMAGE_MEMORY_KEY = "reduce_image_memory"
        const val DARK_THEME_KEY = "dark_theme"
        const val THEME_MODE_KEY = "theme_mode"
        const val LANGUAGE_KEY = "language"
        const val MIN_MANGA_QUERY_LENGTH = 2
    }
}
