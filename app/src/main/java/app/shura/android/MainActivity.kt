package app.shura.android

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.shura.core.content.ContentRisk
import app.shura.core.reader.AutoFlipController
import app.shura.core.reader.AutoFlipSettings
import app.shura.core.repository.ExtensionDescriptor
import app.shura.core.repository.RepositoryCatalogSnapshot
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class Destination {
    HOME,
    LIBRARY,
    REPOSITORIES,
    EXTENSIONS,
    READER,
    SETTINGS,
}

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val viewModel: ShuraViewModel = viewModel()
            val state by viewModel.state.collectAsStateWithLifecycle()
            LaunchedEffect(state.language) {
                AppCompatDelegate.setApplicationLocales(
                    androidx.core.os.LocaleListCompat.forLanguageTags(state.language),
                )
            }
            ShuraTheme(darkTheme = state.darkTheme) {
                CompositionLocalProvider(
                    LocalLayoutDirection provides if (state.language == "ar") {
                        LayoutDirection.Rtl
                    } else {
                        LayoutDirection.Ltr
                    },
                ) {
                    ShuraApp(viewModel, state)
                }
            }
        }
    }
}

@Composable
private fun ShuraTheme(darkTheme: Boolean, content: @Composable () -> Unit) {
    val colors = if (darkTheme) {
        darkColorScheme(
            primary = Color(0xFFD0BCFF),
            secondary = Color(0xFFCCC2DC),
            tertiary = Color(0xFFEFB8C8),
        )
    } else {
        lightColorScheme(
            primary = Color(0xFF5B4191),
            secondary = Color(0xFF625B71),
            tertiary = Color(0xFF7D5260),
        )
    }
    MaterialTheme(colorScheme = colors, content = content)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ShuraApp(viewModel: ShuraViewModel, state: ShuraUiState) {
    var destination by rememberSaveable { mutableStateOf(Destination.HOME) }
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    LaunchedEffect(state.repositoryMessage) {
        if (state.repositoryMessage == "repository_added") {
            snackbarHostState.showSnackbar(
                context.getString(
                    R.string.repository_added,
                    state.repositorySuccessName ?: context.getString(R.string.repositories_title),
                    state.repositorySuccessCount ?: 0,
                ),
            )
        }
    }
    LaunchedEffect(state.repositoryError) {
        state.repositoryError?.let {
            snackbarHostState.showSnackbar(context.getString(R.string.status_error, it))
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets.navigationBars,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(destination.titleResource()),
                        fontWeight = FontWeight.SemiBold,
                    )
                },
                navigationIcon = {
                    if (destination == Destination.READER) {
                        TextButton(onClick = { destination = Destination.HOME }) {
                            Text("‹")
                        }
                    }
                },
            )
        },
        bottomBar = {
            if (destination != Destination.READER) {
                NavigationBar {
                    listOf(
                        Destination.HOME,
                        Destination.LIBRARY,
                        Destination.REPOSITORIES,
                        Destination.EXTENSIONS,
                        Destination.SETTINGS,
                    ).forEach { item ->
                        NavigationBarItem(
                            selected = destination == item,
                            onClick = { destination = item },
                            icon = {
                                Text(
                                    text = when (item) {
                                        Destination.HOME -> "⌂"
                                        Destination.LIBRARY -> "▤"
                                        Destination.REPOSITORIES -> "↻"
                                        Destination.EXTENSIONS -> "◇"
                                        Destination.SETTINGS -> "⚙"
                                        Destination.READER -> "▣"
                                    },
                                )
                            },
                            label = { Text(stringResource(item.titleResource())) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        when (destination) {
            Destination.HOME -> HomeScreen(
                state = state,
                padding = padding,
                onNavigate = { destination = it },
            )
            Destination.LIBRARY -> LibraryScreen(viewModel, state, padding)
            Destination.REPOSITORIES -> RepositoriesScreen(viewModel, state, padding)
            Destination.EXTENSIONS -> ExtensionsScreen(state, padding)
            Destination.READER -> ReaderScreen(viewModel, state, padding)
            Destination.SETTINGS -> SettingsScreen(viewModel, state, padding)
        }
    }
}

private fun Destination.titleResource(): Int = when (this) {
    Destination.HOME -> R.string.nav_home
    Destination.LIBRARY -> R.string.nav_library
    Destination.REPOSITORIES -> R.string.nav_repositories
    Destination.EXTENSIONS -> R.string.nav_extensions
    Destination.READER -> R.string.reader_title
    Destination.SETTINGS -> R.string.nav_settings
}

@Composable
private fun HomeScreen(
    state: ShuraUiState,
    padding: PaddingValues,
    onNavigate: (Destination) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            stringResource(R.string.home_greeting),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
        )
        Text(
            stringResource(R.string.home_subtitle),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        HomeCard(
            title = stringResource(R.string.home_library_title),
            body = stringResource(R.string.home_library_body),
            count = state.libraryTitles.size.toString(),
            onClick = { onNavigate(Destination.LIBRARY) },
        )
        HomeCard(
            title = stringResource(R.string.home_repositories_title),
            body = stringResource(R.string.home_repositories_body),
            count = state.repositories.size.toString(),
            onClick = { onNavigate(Destination.REPOSITORIES) },
        )
        HomeCard(
            title = stringResource(R.string.home_reader_title),
            body = stringResource(R.string.home_reader_body),
            count = null,
            onClick = { onNavigate(Destination.READER) },
        )
    }
}

@Composable
private fun HomeCard(title: String, body: String, count: String?, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text(body, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (count != null) {
                Spacer(Modifier.width(12.dp))
                Text(count, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
private fun LibraryScreen(viewModel: ShuraViewModel, state: ShuraUiState, padding: PaddingValues) {
    var showAddDialog by rememberSaveable { mutableStateOf(false) }
    Column(
        modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
    ) {
        Button(
            onClick = { showAddDialog = true },
            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
        ) {
            Text(stringResource(R.string.library_add))
        }
        if (state.libraryTitles.isEmpty()) {
            EmptyState(
                title = stringResource(R.string.library_empty_title),
                body = stringResource(R.string.library_empty_body),
                modifier = Modifier.weight(1f),
            )
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 16.dp),
            ) {
                items(state.libraryTitles, key = { it }) { title ->
                    Card(Modifier.fillMaxWidth()) {
                        Row(
                            Modifier.fillMaxWidth().padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                            TextButton(onClick = { viewModel.removeLibraryTitle(title) }) {
                                Text(stringResource(R.string.library_remove))
                            }
                        }
                    }
                }
            }
        }
    }
    if (showAddDialog) {
        AddLibraryTitleDialog(
            onDismiss = { showAddDialog = false },
            onSave = {
                viewModel.addLibraryTitle(it)
                showAddDialog = false
            },
        )
    }
}

@Composable
private fun AddLibraryTitleDialog(onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var title by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.library_add)) },
        text = {
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text(stringResource(R.string.library_title_hint)) },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(onClick = { onSave(title) }, enabled = title.isNotBlank()) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

@Composable
private fun RepositoriesScreen(viewModel: ShuraViewModel, state: ShuraUiState, padding: PaddingValues) {
    var repositoryUrl by rememberSaveable { mutableStateOf("") }
    val validation = state.repositoryValidation?.takeIf {
        state.repositoryValidatedInput == repositoryUrl.trim()
    }
    val alreadyAdded = validation?.let { candidate ->
        state.repositories.any { it.repository.normalized == candidate.repository.normalized }
    } == true
    Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
        OutlinedTextField(
            value = repositoryUrl,
            onValueChange = {
                repositoryUrl = it
                viewModel.clearRepositoryValidation()
            },
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            label = { Text(stringResource(R.string.repository_url_hint)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        )
        Text(
            stringResource(R.string.repository_url_help),
            modifier = Modifier.padding(vertical = 6.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(
                onClick = { viewModel.testRepository(repositoryUrl) },
                enabled = repositoryUrl.isNotBlank() && state.initialized && !state.repositoryOperation,
                modifier = Modifier.weight(1f),
            ) {
                Text(stringResource(R.string.repository_test))
            }
            Button(
                onClick = { viewModel.addRepository(repositoryUrl) },
                enabled = validation != null && !alreadyAdded && !state.repositoryOperation,
                modifier = Modifier.weight(1f),
            ) {
                Text(stringResource(if (alreadyAdded) R.string.repository_added_already else R.string.repository_add))
            }
        }
        Text(
            stringResource(R.string.repository_format_note),
            modifier = Modifier.padding(vertical = 8.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        validation?.let { snapshot ->
            Card(
                Modifier.fillMaxWidth().padding(bottom = 8.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        stringResource(R.string.repository_test_success, snapshot.storeName.orEmpty()),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                    Text(
                        if (snapshot.extensions.isEmpty()) {
                            stringResource(R.string.repository_no_extensions)
                        } else {
                            stringResource(R.string.repository_extension_count, snapshot.extensions.size)
                        },
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                    snapshot.indexUrl?.let {
                        Text(stringResource(R.string.repository_final_url, it), style = MaterialTheme.typography.bodySmall)
                    }
                    snapshot.httpStatus?.let {
                        Text(stringResource(R.string.repository_http_status, it), style = MaterialTheme.typography.bodySmall)
                    }
                    snapshot.contentType?.let {
                        Text(stringResource(R.string.repository_content_type, it), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        state.repositoryError?.let {
            Text(
                stringResource(R.string.repository_error, it),
                modifier = Modifier.padding(vertical = 6.dp),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (state.repositoryOperation) {
            Row(
                Modifier.fillMaxWidth().padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Text(stringResource(R.string.repository_loading))
            }
        }
        if (!state.initialized && !state.repositoryOperation) {
            LinearLoading()
        }
        if (state.repositories.isEmpty() && state.initialized) {
            EmptyState(
                title = stringResource(R.string.repository_empty),
                body = "",
                modifier = Modifier.weight(1f),
            )
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(vertical = 8.dp),
            ) {
                items(state.repositories, key = { it.repository.normalized }) { repository ->
                    RepositoryCard(
                        snapshot = repository,
                        state = state,
                        viewModel = viewModel,
                    )
                }
            }
        }
    }
}

@Composable
private fun LinearLoading() {
    CircularProgressIndicator(
        modifier = Modifier.padding(12.dp).size(24.dp),
        strokeWidth = 2.dp,
    )
}

@Composable
private fun RepositoryCard(
    snapshot: RepositoryCatalogSnapshot,
    state: ShuraUiState,
    viewModel: ShuraViewModel,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(snapshot.storeName ?: stringResource(R.string.repository_unknown_name), style = MaterialTheme.typography.titleMedium)
            Text(snapshot.repository.normalized, style = MaterialTheme.typography.titleSmall)
            Text(
                snapshot.lastUpdated?.let {
                    stringResource(R.string.repository_last_updated, viewModel.formatTimestamp(snapshot).orEmpty())
                } ?: stringResource(R.string.repository_not_loaded),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            snapshot.error?.let {
                Text(
                    stringResource(R.string.repository_error, it),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            snapshot.indexUrl?.let {
                Text(stringResource(R.string.repository_final_url, it), style = MaterialTheme.typography.bodySmall)
            }
            snapshot.httpStatus?.let {
                Text(stringResource(R.string.repository_http_status, it), style = MaterialTheme.typography.bodySmall)
            }
            snapshot.contentType?.let {
                Text(stringResource(R.string.repository_content_type, it), style = MaterialTheme.typography.bodySmall)
            }
            Text(
                if (snapshot.extensions.isEmpty() && snapshot.lastUpdated != null && snapshot.error == null) {
                    stringResource(R.string.repository_no_extensions)
                } else {
                    stringResource(R.string.repository_extension_count, snapshot.extensions.size)
                },
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { viewModel.refreshRepository(snapshot.repository.normalized) },
                    enabled = !state.repositoryOperation,
                ) {
                    Text(stringResource(if (snapshot.error == null) R.string.repository_refresh else R.string.repository_retry))
                }
                TextButton(
                    onClick = { viewModel.removeRepository(snapshot.repository.normalized) },
                    enabled = !state.repositoryOperation,
                ) {
                    Text(stringResource(R.string.repository_remove))
                }
            }
        }
    }
}

@Composable
private fun ExtensionsScreen(state: ShuraUiState, padding: PaddingValues) {
    var query by rememberSaveable { mutableStateOf("") }
    val gate = remember { ExtensionSafetyGate() }
    val allExtensions = state.extensionCatalog()
    val extensions = allExtensions.filter { extension ->
        query.isBlank() ||
            extension.name.contains(query, ignoreCase = true) ||
            extension.packageName.contains(query, ignoreCase = true) ||
            extension.language.contains(query, ignoreCase = true)
    }
    Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            label = { Text(stringResource(R.string.extensions_search_hint)) },
            singleLine = true,
        )
        Text(
            stringResource(R.string.extension_install_unavailable),
            modifier = Modifier.padding(vertical = 8.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (extensions.isEmpty()) {
            EmptyState(
                title = stringResource(R.string.extensions_empty),
                body = "",
                modifier = Modifier.weight(1f),
            )
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 16.dp),
            ) {
                items(extensions, key = { "${it.repositoryUrl}:${it.packageName}" }) { extension ->
                    ExtensionCard(extension, gate)
                }
            }
        }
    }
}

@Composable
private fun ExtensionCard(extension: ExtensionDescriptor, gate: ExtensionSafetyGate) {
    val result = remember(extension) { gate.evaluate(extension) }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (result.isBlocked) {
                MaterialTheme.colorScheme.errorContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainer
            },
        ),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(extension.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(extension.packageName, style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.extension_source, extension.repositoryUrl), style = MaterialTheme.typography.bodySmall)
            Text(
                stringResource(R.string.extension_version, extension.version, extension.versionCode),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(stringResource(R.string.extension_language, extension.language), style = MaterialTheme.typography.bodySmall)
            extension.sources.forEach { source ->
                if (source.name.isNotBlank() || source.language.isNotBlank()) {
                    Text(
                        stringResource(R.string.extension_source_details, source.name, source.language),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    source.homeUrl?.let {
                        Text(stringResource(R.string.extension_source_url, it), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            when {
                result.isBlocked -> Text(
                    stringResource(R.string.extension_blocked) + " · " +
                        stringResource(R.string.extension_policy_reason, result.reasons.joinToString()),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
                result.risk == ContentRisk.UNKNOWN || result.risk == ContentRisk.MATURE -> Text(
                    stringResource(R.string.extension_warning) + " · " +
                        stringResource(R.string.extension_policy_reason, result.warnings.joinToString()),
                    color = MaterialTheme.colorScheme.tertiary,
                    style = MaterialTheme.typography.bodySmall,
                )
                else -> Text(
                    stringResource(R.string.extension_allowed),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun SettingsScreen(viewModel: ShuraViewModel, state: ShuraUiState, padding: PaddingValues) {
    var customDuration by rememberSaveable { mutableStateOf(state.autoFlipDurationSeconds.toString()) }
    Column(
        Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        SettingSwitch(
            title = stringResource(R.string.settings_dark_theme),
            checked = state.darkTheme,
            onCheckedChange = viewModel::setDarkTheme,
        )
        Text(stringResource(R.string.settings_language), style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = state.language == "ar",
                onClick = { viewModel.setLanguage("ar") },
                label = { Text(stringResource(R.string.settings_arabic)) },
            )
            FilterChip(
                selected = state.language == "en",
                onClick = { viewModel.setLanguage("en") },
                label = { Text(stringResource(R.string.settings_english)) },
            )
        }
        HorizontalDivider()
        Text(stringResource(R.string.settings_reader_title), style = MaterialTheme.typography.titleLarge)
        SettingSwitch(
            title = stringResource(R.string.settings_autoflip),
            checked = state.autoFlipEnabled,
            onCheckedChange = viewModel::setAutoFlipEnabled,
        )
        Text(stringResource(R.string.settings_autoflip_duration), style = MaterialTheme.typography.titleMedium)
        PresetDurations(state.autoFlipDurationSeconds, viewModel::setAutoFlipDuration)
        OutlinedTextField(
            value = customDuration,
            onValueChange = { customDuration = it.filter(Char::isDigit).take(2) },
            label = { Text(stringResource(R.string.settings_custom_duration)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedButton(
            onClick = { customDuration.toIntOrNull()?.let(viewModel::setAutoFlipDuration) },
            enabled = customDuration.toIntOrNull() in 1..30,
        ) {
            Text(stringResource(R.string.settings_custom_apply))
        }
        SettingSwitch(
            title = stringResource(R.string.settings_pause_touch),
            checked = state.pauseAutoFlipOnTouch,
            onCheckedChange = viewModel::setPauseAutoFlipOnTouch,
        )
        HorizontalDivider()
        Text(stringResource(R.string.settings_policy_title), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.settings_policy_body), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(stringResource(R.string.settings_compatibility_title), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.settings_compatibility_body), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SettingSwitch(title: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onCheckedChange(!checked) }.padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PresetDurations(selected: Int, onSelect: (Int) -> Boolean) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        AutoFlipSettings.PRESET_DURATIONS_SECONDS.forEach { seconds ->
            FilterChip(
                selected = selected == seconds,
                onClick = { onSelect(seconds) },
                label = { Text(stringResource(R.string.duration_seconds, seconds)) },
            )
        }
    }
}

@Composable
private fun ReaderScreen(viewModel: ShuraViewModel, state: ShuraUiState, padding: PaddingValues) {
    val context = LocalContext.current
    val imageUris = rememberSaveable { mutableStateOf<List<Uri>>(emptyList()) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        imageUris.value = uris
        uris.forEach { uri ->
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
        }
    }

    Column(
        Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Button(
            onClick = { launcher.launch(arrayOf("image/*")) },
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        ) {
            Text(stringResource(R.string.reader_open_images))
        }
        if (imageUris.value.isEmpty()) {
            EmptyState(
                title = stringResource(R.string.reader_empty_title),
                body = stringResource(R.string.reader_empty_body),
                modifier = Modifier.weight(1f),
            )
        } else {
            ImagePager(
                uris = imageUris.value,
                autoFlipEnabled = state.autoFlipEnabled,
                autoFlipDurationSeconds = state.autoFlipDurationSeconds,
                pauseOnTouch = state.pauseAutoFlipOnTouch,
                onAutoFlipToggle = { viewModel.setAutoFlipEnabled(!state.autoFlipEnabled) },
                onAutoFlipFinished = { viewModel.setAutoFlipEnabled(false) },
                onPageTouch = {
                    if (state.pauseAutoFlipOnTouch && state.autoFlipEnabled) {
                        viewModel.setAutoFlipEnabled(false)
                    }
                },
                modifier = Modifier.weight(1f),
            )
        }
        Text(
            stringResource(R.string.reader_translation_note),
            modifier = Modifier.padding(vertical = 8.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ImagePager(
    uris: List<Uri>,
    autoFlipEnabled: Boolean,
    autoFlipDurationSeconds: Int,
    pauseOnTouch: Boolean,
    onAutoFlipToggle: () -> Unit,
    onAutoFlipFinished: () -> Unit,
    onPageTouch: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val pagerState = rememberPagerState(pageCount = { uris.size })
    val currentPage = remember { AtomicInteger(0) }
    val readyPages = remember { ConcurrentHashMap.newKeySet<Int>() }
    val controllerReference = remember { java.util.concurrent.atomic.AtomicReference<AutoFlipController?>(null) }
    val scope = rememberCoroutineScope()
    var controllerError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(uris) {
        readyPages.clear()
        currentPage.set(0)
        pagerState.scrollToPage(0)
    }
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.currentPage }.collect(currentPage::set)
    }
    DisposableEffect(
        uris,
        autoFlipEnabled,
        autoFlipDurationSeconds,
        pauseOnTouch,
        pagerState,
    ) {
        val settings = AutoFlipSettings().apply {
            enabled = autoFlipEnabled
            durationSeconds = autoFlipDurationSeconds
            this.pauseOnTouch = pauseOnTouch
        }
        val controller = AutoFlipController(
            settings = settings,
            isPageReady = { currentPage.get() in readyPages },
            advancePage = {
                val nextPage = currentPage.get() + 1
                if (nextPage >= uris.size) {
                    scope.launch { onAutoFlipFinished() }
                    false
                } else {
                    scope.launch { pagerState.animateScrollToPage(nextPage) }
                    true
                }
            },
            onError = { error ->
                scope.launch { controllerError = error.message ?: error.javaClass.simpleName }
            },
        )
        controllerReference.set(controller)
        if (autoFlipEnabled && uris.isNotEmpty()) controller.start()
        onDispose {
            controllerReference.compareAndSet(controller, null)
            controller.close()
        }
    }

    Column(modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.reader_page, pagerState.currentPage + 1, uris.size),
                style = MaterialTheme.typography.labelLarge,
            )
        }
        controllerError?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            beyondViewportPageCount = 1,
        ) { index ->
            ImagePage(
                uri = uris[index],
                onTouch = {
                    if (pauseOnTouch) {
                        controllerReference.get()?.onTouch()
                        onPageTouch()
                    }
                },
                onReady = { ready ->
                    if (ready) readyPages.add(index) else readyPages.remove(index)
                },
            )
        }
        OutlinedButton(onClick = onAutoFlipToggle) {
            Text(
                if (autoFlipEnabled) stringResource(R.string.reader_auto_flip_stop)
                else stringResource(R.string.reader_auto_flip_start),
            )
        }
    }
}

private sealed interface ImageLoadState {
    data object Loading : ImageLoadState
    data class Ready(val bitmap: Bitmap) : ImageLoadState
    data class Failed(val message: String?) : ImageLoadState
}

@Composable
private fun ImagePage(uri: Uri, onTouch: () -> Unit, onReady: (Boolean) -> Unit) {
    val context = LocalContext.current
    var retryKey by remember(uri) { mutableIntStateOf(0) }
    val imageState by androidx.compose.runtime.produceState<ImageLoadState>(
        initialValue = ImageLoadState.Loading,
        key1 = uri,
        key2 = retryKey,
    ) {
        value = ImageLoadState.Loading
        value = withContext(Dispatchers.IO) {
            runCatching { decodeForDisplay(context, uri) }
                .fold(
                    onSuccess = { ImageLoadState.Ready(it) },
                    onFailure = { ImageLoadState.Failed(it.message) },
                )
        }
    }
    LaunchedEffect(imageState) {
        onReady(imageState is ImageLoadState.Ready)
    }
    DisposableEffect(uri) {
        onDispose { onReady(false) }
    }
    var scale by remember(uri) { mutableFloatStateOf(1f) }
    var offsetX by remember(uri) { mutableFloatStateOf(0f) }
    var offsetY by remember(uri) { mutableFloatStateOf(0f) }

    Box(
        Modifier.fillMaxSize()
            .pointerInput(uri) {
                detectTapGestures(onTap = { onTouch() })
            }
            .pointerInput(uri) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(1f, 4f)
                    offsetX += pan.x
                    offsetY += pan.y
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        when (val image = imageState) {
            ImageLoadState.Loading -> CircularProgressIndicator()
            is ImageLoadState.Failed -> Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(stringResource(R.string.reader_image_error), color = MaterialTheme.colorScheme.error)
                image.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                OutlinedButton(onClick = { retryKey++ }) {
                    Text(stringResource(R.string.reader_retry))
                }
            }
            is ImageLoadState.Ready -> Image(
                bitmap = image.bitmap.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize().graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offsetX
                    translationY = offsetY
                },
            )
        }
    }
}

private fun decodeForDisplay(context: android.content.Context, uri: Uri): Bitmap {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        ?: error("Unable to read image")
    require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Invalid image" }
    val maxDimension = 1600
    var sampleSize = 1
    while (bounds.outWidth / sampleSize > maxDimension || bounds.outHeight / sampleSize > maxDimension) {
        sampleSize *= 2
    }
    val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
    return context.contentResolver.openInputStream(uri)?.use {
        BitmapFactory.decodeStream(it, null, options)
    } ?: error("Unable to decode image")
}

@Composable
private fun EmptyState(title: String, body: String, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            Modifier.size(56.dp).clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Text("·", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
        }
        Spacer(Modifier.height(12.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        if (body.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(
                body,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}
