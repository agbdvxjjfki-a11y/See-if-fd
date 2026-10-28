package app.shura.android

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.shura.core.content.ContentPolicyEngine
import app.shura.core.reader.AutoFlipSettings
import app.shura.core.repository.ExtensionDescriptor
import app.shura.core.repository.FileRepositoryConfigurationStore
import app.shura.core.repository.RepositoryCatalogSnapshot
import app.shura.core.repository.RepositoryIndexClient
import app.shura.core.repository.RepositoryManager
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray

data class ShuraUiState(
    val repositories: List<RepositoryCatalogSnapshot> = emptyList(),
    val repositoryOperation: Boolean = false,
    val repositoryError: String? = null,
    val repositoryMessage: String? = null,
    val repositoryValidation: RepositoryCatalogSnapshot? = null,
    val repositoryValidatedInput: String? = null,
    val repositorySuccessName: String? = null,
    val repositorySuccessCount: Int? = null,
    val libraryTitles: List<String> = emptyList(),
    val autoFlipEnabled: Boolean = false,
    val autoFlipDurationSeconds: Int = 5,
    val pauseAutoFlipOnTouch: Boolean = false,
    val darkTheme: Boolean = false,
    val language: String = "ar",
    val initialized: Boolean = false,
) {
    fun extensionCatalog(): List<ExtensionDescriptor> =
        repositories.asSequence()
            .flatMap { it.extensions.asSequence() }
            .filterNot { CONTENT_POLICY.evaluate(it).isBlocked }
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

    fun findExtension(packageName: String, repositoryUrl: String? = null): ExtensionDescriptor? =
        extensionCatalog().firstOrNull {
            it.packageName == packageName &&
                (repositoryUrl == null || it.repositoryUrl == repositoryUrl)
        }

    private companion object {
        val CONTENT_POLICY = ContentPolicyEngine()
    }
}

class ShuraViewModel(application: Application) : AndroidViewModel(application) {
    private val preferences = application.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val mutableState = MutableStateFlow(initialState())
    val state: StateFlow<ShuraUiState> = mutableState.asStateFlow()
    private var manager: RepositoryManager? = null

    init {
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val initializedManager = RepositoryManager(
                        store = FileRepositoryConfigurationStore(
                            getApplication<Application>().filesDir.toPath().resolve(REPOSITORIES_FILE),
                        ),
                        indexSource = RepositoryIndexClient(),
                    )
                    manager = initializedManager
                    initializedManager.refreshAll()
                }
            }.onSuccess { repositories ->
                mutableState.value = mutableState.value.copy(
                    repositories = repositories,
                    initialized = true,
                )
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

    fun refreshRepository(url: String) {
        if (mutableState.value.repositoryOperation) return
        viewModelScope.launch {
            beginRepositoryOperation()
            runCatching {
                withContext(Dispatchers.IO) {
                    (manager ?: error("Repository manager is unavailable")).refreshRepository(url)
                }
            }.onSuccess(::updateRepository)
                .onFailure { error ->
                    mutableState.value = mutableState.value.copy(
                        repositoryError = error.message ?: error.javaClass.simpleName,
                    )
                }
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
                mutableState.value = mutableState.value.copy(repositories = repositories)
            }.onFailure { error ->
                mutableState.value = mutableState.value.copy(
                    repositoryError = error.message ?: error.javaClass.simpleName,
                )
            }
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
                mutableState.value = mutableState.value.copy(repositories = repositories)
            }.onFailure { error ->
                mutableState.value = mutableState.value.copy(
                    repositoryError = error.message ?: error.javaClass.simpleName,
                )
            }
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

    fun removeLibraryTitle(title: String) {
        viewModelScope.launch {
            val updated = mutableState.value.libraryTitles - title
            withContext(Dispatchers.IO) {
                preferences.edit().putString(LIBRARY_KEY, JSONArray(updated).toString()).apply()
            }
            mutableState.value = mutableState.value.copy(libraryTitles = updated)
        }
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

    fun setDarkTheme(enabled: Boolean) {
        updatePreference(DARK_THEME_KEY, enabled) { copy(darkTheme = enabled) }
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
        return ShuraUiState(
            libraryTitles = library,
            autoFlipEnabled = preferences.getBoolean(AUTO_FLIP_ENABLED_KEY, false),
            autoFlipDurationSeconds = preferences.getInt(AUTO_FLIP_DURATION_KEY, 5).coerceIn(1, 30),
            pauseAutoFlipOnTouch = preferences.getBoolean(PAUSE_ON_TOUCH_KEY, false),
            darkTheme = preferences.getBoolean(DARK_THEME_KEY, false),
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

    private fun updateRepository(snapshot: RepositoryCatalogSnapshot) {
        val current = mutableState.value.repositories
        val updated = current.filterNot {
            it.repository.normalized == snapshot.repository.normalized
        } + snapshot
        mutableState.value = mutableState.value.copy(repositories = updated)
        if (snapshot.error != null) {
            mutableState.value = mutableState.value.copy(repositoryError = snapshot.error)
        }
    }

    fun formatTimestamp(snapshot: RepositoryCatalogSnapshot): String? =
        snapshot.lastUpdated?.atZone(ZoneId.systemDefault())
            ?.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))

    private companion object {
        const val PREFERENCES_NAME = "shura_user_preferences"
        const val REPOSITORIES_FILE = "repositories.json"
        const val LIBRARY_KEY = "library_titles"
        const val AUTO_FLIP_ENABLED_KEY = "auto_flip_enabled"
        const val AUTO_FLIP_DURATION_KEY = "auto_flip_duration_seconds"
        const val PAUSE_ON_TOUCH_KEY = "pause_auto_flip_on_touch"
        const val DARK_THEME_KEY = "dark_theme"
        const val LANGUAGE_KEY = "language"
    }
}
