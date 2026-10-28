package app.shura.android

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.VerticalPager
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
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.shura.core.reader.AutoFlipController
import app.shura.core.reader.AutoFlipSettings
import app.shura.core.repository.ExtensionDescriptor
import app.shura.core.repository.RepositoryCatalogSnapshot
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URISyntaxException

internal enum class Destination {
    HOME,
    LIBRARY,
    EXPLORE,
    REPOSITORIES,
    EXTENSIONS,
    DOWNLOADS,
    READER,
    SETTINGS,
}

internal val PRIMARY_DESTINATIONS = listOf(
    Destination.HOME,
    Destination.LIBRARY,
    Destination.EXPLORE,
    Destination.DOWNLOADS,
    Destination.SETTINGS,
)

internal fun appLayoutDirection(language: String): LayoutDirection =
    if (language == "ar") LayoutDirection.Rtl else LayoutDirection.Ltr

private data class ExtensionCatalogPresentation(
    val extensions: List<ExtensionDescriptor>,
    val languages: List<String>,
    val byRepositoryAndPackage: Map<Pair<String, String>, ExtensionDescriptor>,
)

internal fun filterLibraryTitles(
    titles: List<String>,
    favoriteTitles: Set<String>,
    query: String,
    favoritesOnly: Boolean,
): List<String> {
    val normalizedQuery = query.trim()
    return titles.asSequence()
        .filter { !favoritesOnly || it in favoriteTitles }
        .filter { normalizedQuery.isEmpty() || it.contains(normalizedQuery, ignoreCase = true) }
        .sortedBy(String::lowercase)
        .toList()
}

class MainActivity : AppCompatActivity() {
    private var shuraViewModel: ShuraViewModel? = null
    private var permissionPackageName: String? = null
    private val installPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        val packageName = permissionPackageName ?: return@registerForActivityResult
        permissionPackageName = null
        val viewModel = shuraViewModel ?: return@registerForActivityResult
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || packageManager.canRequestPackageInstalls()) {
            viewModel.submitPreparedInstall(packageName)
        } else {
            viewModel.markInstallPermissionDenied(packageName)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val viewModel: ShuraViewModel = viewModel()
            shuraViewModel = viewModel
            val state by viewModel.state.collectAsStateWithLifecycle()
            LaunchedEffect(state.language) {
                AppCompatDelegate.setApplicationLocales(
                    androidx.core.os.LocaleListCompat.forLanguageTags(state.language),
                )
            }
            ShuraTheme(themeMode = state.themeMode) {
                CompositionLocalProvider(
                    LocalLayoutDirection provides appLayoutDirection(state.language),
                ) {
                    ShuraApp(
                        viewModel = viewModel,
                        state = state,
                        onRequestInstallPermission = ::requestInstallPermission,
                        onOpenUninstallSettings = ::openUninstallSettings,
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        shuraViewModel?.refreshInstalledExtensions()
    }

    private fun requestInstallPermission(packageName: String, viewModel: ShuraViewModel) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || packageManager.canRequestPackageInstalls()) {
            viewModel.submitPreparedInstall(packageName)
            return
        }
        permissionPackageName = packageName
        val intent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${applicationContext.packageName}"),
        )
        try {
            installPermissionLauncher.launch(intent)
        } catch (_: ActivityNotFoundException) {
            permissionPackageName = null
            viewModel.markInstallPermissionDenied(packageName)
        }
    }

    private fun openUninstallSettings(packageName: String, viewModel: ShuraViewModel) {
        val intent = viewModel.openUninstallSettings(packageName) ?: return
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            viewModel.markPackageActionFailed(packageName, "تعذر فتح إعدادات الحزمة في Android")
        }
    }
}

@Composable
private fun ShuraTheme(themeMode: ThemeMode, content: @Composable () -> Unit) {
    val darkTheme = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val colors = if (darkTheme) {
        darkColorScheme(
            primary = Color(0xFFD8B8F2),
            onPrimary = Color(0xFF32164B),
            primaryContainer = Color(0xFF4A3062),
            onPrimaryContainer = Color(0xFFF0DDFE),
            secondary = Color(0xFF9CD2C5),
            onSecondary = Color(0xFF12372F),
            secondaryContainer = Color(0xFF244B42),
            onSecondaryContainer = Color(0xFFC0EBDD),
            tertiary = Color(0xFFFFB8A7),
            background = Color(0xFF151219),
            surface = Color(0xFF1D1921),
            surfaceVariant = Color(0xFF302A35),
        )
    } else {
        lightColorScheme(
            primary = Color(0xFF593A73),
            onPrimary = Color.White,
            primaryContainer = Color(0xFFEEDCFA),
            onPrimaryContainer = Color(0xFF241333),
            secondary = Color(0xFF356D62),
            onSecondary = Color.White,
            secondaryContainer = Color(0xFFD6EEE7),
            onSecondaryContainer = Color(0xFF102F29),
            tertiary = Color(0xFF9A4F43),
            background = Color(0xFFFAF7FC),
            surface = Color(0xFFFFFBFF),
            surfaceVariant = Color(0xFFF0EAF3),
        )
    }
    val typography = Typography(
        headlineLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, letterSpacing = (-0.4).sp),
        headlineMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, letterSpacing = (-0.2).sp),
        titleLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold),
        titleMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium),
        bodyLarge = TextStyle(fontFamily = FontFamily.SansSerif, lineHeight = 26.sp),
        bodyMedium = TextStyle(fontFamily = FontFamily.SansSerif, lineHeight = 22.sp),
    )
    MaterialTheme(
        colorScheme = colors,
        typography = typography,
        shapes = Shapes(
            small = RoundedCornerShape(12.dp),
            medium = RoundedCornerShape(18.dp),
            large = RoundedCornerShape(26.dp),
        ),
        content = content,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ShuraApp(
    viewModel: ShuraViewModel,
    state: ShuraUiState,
    onRequestInstallPermission: (String, ShuraViewModel) -> Unit,
    onOpenUninstallSettings: (String, ShuraViewModel) -> Unit,
) {
    var destination by rememberSaveable { mutableStateOf(Destination.HOME) }
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    LaunchedEffect(state.repositoryMessage) {
        if (state.repositoryMessage == "repository_added" ||
            state.repositoryMessage == "repository_updated"
        ) {
            val message = if (state.repositoryMessage == "repository_updated") {
                R.string.repository_updated
            } else {
                R.string.repository_added
            }
            snackbarHostState.showSnackbar(
                context.getString(
                    message,
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
                    Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                        Text(
                            text = stringResource(R.string.app_name),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            text = stringResource(destination.titleResource()),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                },
                navigationIcon = {
                    if (destination == Destination.READER) {
                        TextButton(onClick = { destination = Destination.HOME }) {
                            Text(stringResource(R.string.nav_home))
                        }
                    } else {
                        Box(
                            Modifier.padding(start = 12.dp).size(38.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(MaterialTheme.colorScheme.primaryContainer),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                stringResource(R.string.brand_mark),
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                },
            )
        },
        bottomBar = {
            if (destination != Destination.READER) {
                NavigationBar {
                    PRIMARY_DESTINATIONS.forEach { item ->
                        NavigationBarItem(
                            selected = destination == item,
                            onClick = { destination = item },
                            icon = {
                                Icon(
                                    imageVector = when (item) {
                                        Destination.HOME -> Icons.Filled.Home
                                        Destination.LIBRARY -> Icons.AutoMirrored.Filled.List
                                        Destination.EXPLORE -> Icons.Filled.Search
                                        Destination.DOWNLOADS -> Icons.Filled.KeyboardArrowDown
                                        Destination.SETTINGS -> Icons.Filled.Settings
                                        Destination.REPOSITORIES,
                                        Destination.EXTENSIONS,
                                        Destination.READER -> Icons.Filled.Home
                                    },
                                    contentDescription = null,
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
            Destination.LIBRARY -> LibraryScreen(
                viewModel,
                state,
                padding,
                onOpenOnlineManga = { destination = Destination.EXPLORE },
            )
            Destination.EXPLORE -> ExploreScreen(
                viewModel,
                state,
                padding,
                onOpenReader = { destination = Destination.READER },
            )
            Destination.REPOSITORIES -> RepositoriesScreen(viewModel, state, padding)
            Destination.EXTENSIONS -> ExtensionsScreen(
                viewModel,
                state,
                padding,
                onRequestInstallPermission,
                onOpenUninstallSettings,
            )
            Destination.DOWNLOADS -> DownloadsScreen(viewModel, state, padding)
            Destination.READER -> ReaderScreen(viewModel, state, padding)
            Destination.SETTINGS -> SettingsScreen(
                viewModel,
                state,
                padding,
                onNavigate = { destination = it },
            )
        }
    }
}

private fun Destination.titleResource(): Int = when (this) {
    Destination.HOME -> R.string.nav_home
    Destination.LIBRARY -> R.string.nav_library
    Destination.EXPLORE -> R.string.nav_explore
    Destination.REPOSITORIES -> R.string.nav_repositories
    Destination.EXTENSIONS -> R.string.nav_extensions
    Destination.DOWNLOADS -> R.string.nav_downloads
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
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.extraLarge,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(22.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(52.dp).clip(RoundedCornerShape(18.dp))
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.72f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        stringResource(R.string.brand_mark),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Column(
                    Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        stringResource(R.string.home_greeting),
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    Text(
                        stringResource(R.string.home_subtitle),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    Button(onClick = { onNavigate(Destination.READER) }) {
                        Text(stringResource(R.string.home_reader_title))
                    }
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            HomeCard(
                title = stringResource(R.string.home_library_title),
                body = stringResource(R.string.home_library_body),
                count = (state.libraryTitles.size + state.onlineLibrary.size).toString(),
                onClick = { onNavigate(Destination.LIBRARY) },
                modifier = Modifier.weight(1f),
            )
            HomeCard(
                title = stringResource(R.string.nav_explore),
                body = stringResource(R.string.explore_home_body),
                count = null,
                onClick = { onNavigate(Destination.EXPLORE) },
                modifier = Modifier.weight(1f),
            )
        }
        if (state.libraryTitles.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(stringResource(R.string.home_recent_library), style = MaterialTheme.typography.titleLarge)
                TextButton(onClick = { onNavigate(Destination.LIBRARY) }) {
                    Text(stringResource(R.string.home_library_title))
                }
            }
            state.libraryTitles.takeLast(4).asReversed().forEach { title ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                ) {
                    Text(title, Modifier.fillMaxWidth().padding(16.dp), style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }
}

@Composable
private fun ExploreScreen(
    viewModel: ShuraViewModel,
    state: ShuraUiState,
    padding: PaddingValues,
    onOpenReader: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(query, state.language) {
        delay(350)
        viewModel.searchOnlineManga(query, state.language)
    }
    Column(
        Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            label = { Text(stringResource(R.string.explore_search_hint)) },
            singleLine = true,
        )
        Text(
            stringResource(R.string.explore_source_credit),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (state.selectedOnlineManga != null) {
            TextButton(onClick = viewModel::closeOnlineMangaDetails) {
                Text(stringResource(R.string.explore_back_to_results))
            }
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 16.dp),
            ) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(state.selectedOnlineManga.title, style = MaterialTheme.typography.headlineSmall)
                        if (state.selectedOnlineManga.description.isNotBlank()) {
                            Text(
                                state.selectedOnlineManga.description,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(
                            stringResource(R.string.explore_chapters_title),
                            style = MaterialTheme.typography.titleLarge,
                        )
                        val isInLibrary = state.onlineLibrary.any {
                            it.id == state.selectedOnlineManga.id
                        }
                        OutlinedButton(
                            onClick = {
                                if (isInLibrary) {
                                    viewModel.removeOnlineMangaFromLibrary(state.selectedOnlineManga.id)
                                } else {
                                    viewModel.addOnlineMangaToLibrary(state.selectedOnlineManga)
                                }
                            },
                        ) {
                            Text(
                                stringResource(
                                    if (isInLibrary) R.string.library_remove
                                    else R.string.library_save_online,
                                ),
                            )
                        }
                        state.onlineReadingProgress[state.selectedOnlineManga.id]?.let { progress ->
                            val resumeChapter = state.onlineMangaChapters.firstOrNull {
                                it.id == progress.chapterId
                            }
                            if (resumeChapter != null) {
                                Button(
                                    onClick = {
                                        viewModel.openOnlineChapter(resumeChapter, state.dataSaver)
                                        onOpenReader()
                                    },
                                ) {
                                    Text(
                                        stringResource(
                                            R.string.reader_resume_progress,
                                            resumeChapter.chapter ?: stringResource(R.string.value_unknown),
                                            progress.pageIndex + 1,
                                        ),
                                    )
                                }
                            } else {
                                Text(
                                    stringResource(R.string.reader_progress_unavailable),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        if (state.onlineMangaChapters.isEmpty() && !state.mangaSourceLoading) {
                            Text(
                                stringResource(R.string.explore_no_chapters),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                if (state.mangaSourceLoading) {
                    item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                }
                state.mangaSourceError?.let { error ->
                    item { Text(error, color = MaterialTheme.colorScheme.error) }
                }
                items(state.onlineMangaChapters, key = { it.id }) { chapter ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    stringResource(
                                        R.string.explore_chapter_label,
                                        chapter.chapter ?: stringResource(R.string.value_unknown),
                                        chapter.title,
                                    ),
                                    style = MaterialTheme.typography.titleMedium,
                                )
                                Text(
                                    stringResource(
                                        R.string.explore_scanlation_credit,
                                        chapter.scanlationGroup ?: stringResource(R.string.explore_unknown_group),
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            TextButton(
                                onClick = {
                                    viewModel.openOnlineChapter(chapter, state.dataSaver)
                                    onOpenReader()
                                },
                            ) {
                                Text(stringResource(R.string.action_open))
                            }
                        }
                    }
                }
            }
        } else {
            if (state.mangaSourceLoading) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            state.mangaSourceError?.let { error ->
                Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            if (state.mangaSearchResults.isEmpty() && query.length >= 2 && !state.mangaSourceLoading) {
                EmptyState(
                    title = stringResource(
                        if (state.mangaSourceError == null) R.string.explore_no_results
                        else R.string.explore_search_failed,
                    ),
                    body = "",
                    modifier = Modifier.weight(1f),
                )
            } else if (state.mangaSearchResults.isEmpty()) {
                EmptyState(
                    title = stringResource(R.string.explore_search_prompt),
                    body = stringResource(R.string.explore_source_limit),
                    modifier = Modifier.weight(1f),
                )
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(bottom = 16.dp),
                ) {
                    items(state.mangaSearchResults, key = { it.id }) { manga ->
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = { viewModel.openOnlineManga(manga, state.language) },
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                        ) {
                            Text(
                                manga.title,
                                Modifier.fillMaxWidth().padding(16.dp),
                                style = MaterialTheme.typography.titleMedium,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HomeCard(
    title: String,
    body: String,
    count: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier,
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
private fun LibraryScreen(
    viewModel: ShuraViewModel,
    state: ShuraUiState,
    padding: PaddingValues,
    onOpenOnlineManga: () -> Unit,
) {
    var showAddDialog by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var favoritesOnly by rememberSaveable { mutableStateOf(false) }
    val visibleTitles = remember(state.libraryTitles, state.favoriteTitles, query, favoritesOnly) {
        filterLibraryTitles(state.libraryTitles, state.favoriteTitles, query, favoritesOnly)
    }
    val visibleOnlineManga = remember(state.onlineLibrary, query, favoritesOnly) {
        state.onlineLibrary
            .filter { !favoritesOnly && (query.isBlank() || it.title.contains(query.trim(), ignoreCase = true)) }
    }
    Column(
        modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(stringResource(R.string.library_title), style = MaterialTheme.typography.titleLarge)
                Text(
                    stringResource(R.string.library_count, state.libraryTitles.size + state.onlineLibrary.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Button(onClick = { showAddDialog = true }) {
                Text(stringResource(R.string.library_add))
            }
        }
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
            label = { Text(stringResource(R.string.library_search)) },
            singleLine = true,
        )
        FilterChip(
            selected = favoritesOnly,
            onClick = { favoritesOnly = !favoritesOnly },
            label = { Text(stringResource(R.string.library_favorites_filter)) },
        )
        if (state.libraryTitles.isEmpty() && state.onlineLibrary.isEmpty()) {
            EmptyState(
                title = stringResource(R.string.library_empty_title),
                body = stringResource(R.string.library_empty_body),
                modifier = Modifier.weight(1f),
            )
        } else if (visibleTitles.isEmpty() && visibleOnlineManga.isEmpty()) {
            EmptyState(
                title = stringResource(R.string.library_no_matches),
                body = stringResource(R.string.library_search_help),
                modifier = Modifier.weight(1f),
            )
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 16.dp),
            ) {
                items(visibleOnlineManga, key = { "mangadex:${it.id}" }) { manga ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            viewModel.openOnlineManga(
                                app.shura.core.manga.OnlineManga(
                                    id = manga.id,
                                    title = manga.title,
                                    description = "",
                                    contentRating = "safe",
                                ),
                                state.language,
                            )
                            onOpenOnlineManga()
                        },
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(manga.title, style = MaterialTheme.typography.titleMedium)
                                Text(
                                    stringResource(R.string.library_source_mangadex),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            TextButton(onClick = { viewModel.removeOnlineMangaFromLibrary(manga.id) }) {
                                Text(stringResource(R.string.library_remove))
                            }
                        }
                    }
                }
                items(visibleTitles, key = { it }) { title ->
                    Card(
                        Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(title, style = MaterialTheme.typography.titleMedium)
                                Text(
                                    stringResource(R.string.library_user_entry),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            TextButton(onClick = { viewModel.toggleFavorite(title) }) {
                                Text(
                                    stringResource(
                                        if (title in state.favoriteTitles) R.string.library_unfavorite
                                        else R.string.library_favorite
                                    ),
                                )
                            }
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
    var editingRepositoryUrl by rememberSaveable { mutableStateOf<String?>(null) }
    val validation = state.repositoryValidation?.takeIf {
        state.repositoryValidatedInput == repositoryUrl.trim()
    }
    val alreadyAdded = validation?.let { candidate ->
        state.repositories.any {
            it.repository.normalized == candidate.repository.normalized &&
                it.repository.normalized != editingRepositoryUrl
        }
    } == true
    Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
        editingRepositoryUrl?.let {
            Text(
                stringResource(R.string.repository_editing),
                modifier = Modifier.padding(top = 8.dp),
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.titleSmall,
            )
        }
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
                onClick = {
                    val oldUrl = editingRepositoryUrl
                    if (oldUrl == null) {
                        viewModel.addRepository(repositoryUrl)
                    } else if (validation?.repository?.normalized == oldUrl) {
                        viewModel.refreshRepository(oldUrl)
                        editingRepositoryUrl = null
                        repositoryUrl = ""
                    } else {
                        viewModel.replaceRepository(oldUrl, repositoryUrl)
                        editingRepositoryUrl = null
                        repositoryUrl = ""
                    }
                },
                enabled = validation != null && !alreadyAdded && !state.repositoryOperation,
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    stringResource(
                        when {
                            alreadyAdded -> R.string.repository_added_already
                            editingRepositoryUrl != null -> R.string.repository_save_edit
                            else -> R.string.repository_add
                        },
                    ),
                )
            }
        }
        if (editingRepositoryUrl != null) {
            TextButton(
                onClick = {
                    editingRepositoryUrl = null
                    repositoryUrl = ""
                    viewModel.clearRepositoryValidation()
                },
                enabled = !state.repositoryOperation,
            ) {
                Text(stringResource(R.string.action_cancel))
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
                        onEdit = {
                            editingRepositoryUrl = repository.repository.normalized
                            repositoryUrl = repository.repository.inputUrl
                            viewModel.clearRepositoryValidation()
                        },
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
    onEdit: () -> Unit,
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
                    onClick = onEdit,
                    enabled = !state.repositoryOperation,
                ) {
                    Text(stringResource(R.string.repository_edit))
                }
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
private fun ExtensionsScreen(
    viewModel: ShuraViewModel,
    state: ShuraUiState,
    padding: PaddingValues,
    onRequestInstallPermission: (String, ShuraViewModel) -> Unit,
    onOpenUninstallSettings: (String, ShuraViewModel) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var selectedLanguage by rememberSaveable { mutableStateOf<String?>(null) }
    var sortByLatest by rememberSaveable { mutableStateOf(false) }
    var selectedExtension by remember { mutableStateOf<ExtensionDescriptor?>(null) }
    val catalogPresentation by androidx.compose.runtime.produceState(
        initialValue = ExtensionCatalogPresentation(emptyList(), emptyList(), emptyMap()),
        key1 = state.preparedExtensionCatalog,
    ) {
        value = withContext(Dispatchers.Default) {
            val catalog = state.extensionCatalog()
            ExtensionCatalogPresentation(
                extensions = catalog,
                languages = catalog.asSequence()
                    .map { it.language }
                    .filter(String::isNotBlank)
                    .distinct()
                    .sorted()
                    .toList(),
                byRepositoryAndPackage = state.extensionByRepositoryAndPackage
                    .ifEmpty { catalog.associateBy { it.repositoryUrl to it.packageName } },
            )
        }
    }
    val allExtensions = catalogPresentation.extensions
    val languages = catalogPresentation.languages
    val extensions by androidx.compose.runtime.produceState(
        initialValue = emptyList<ExtensionDescriptor>(),
        key1 = allExtensions,
        key2 = query,
        key3 = selectedLanguage to sortByLatest,
    ) {
        delay(180)
        value = withContext(Dispatchers.Default) {
            val normalizedQuery = query.trim()
            allExtensions.asSequence()
                .filter { selectedLanguage == null || it.language.equals(selectedLanguage, ignoreCase = true) }
                .filter { extension ->
                    normalizedQuery.isEmpty() ||
                        extension.name.contains(normalizedQuery, ignoreCase = true) ||
                        extension.packageName.contains(normalizedQuery, ignoreCase = true) ||
                        extension.language.contains(normalizedQuery, ignoreCase = true)
                }
                .let { matches ->
                    if (sortByLatest) matches.sortedByDescending { it.versionCode }
                    else matches.sortedBy { it.name.lowercase() }
                }
                .toList()
        }
    }
    Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            label = { Text(stringResource(R.string.extensions_search_hint)) },
            singleLine = true,
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(
                selected = !sortByLatest,
                onClick = { sortByLatest = false },
                label = { Text(stringResource(R.string.extensions_sort_name)) },
            )
            FilterChip(
                selected = sortByLatest,
                onClick = { sortByLatest = true },
                label = { Text(stringResource(R.string.extensions_sort_version)) },
            )
        }
        if (languages.isNotEmpty()) {
            LanguageFilterRow(
                languages = languages,
                selectedLanguage = selectedLanguage,
                onSelect = { selectedLanguage = it },
            )
        }
        Text(
            stringResource(R.string.extension_install_unavailable),
            modifier = Modifier.padding(vertical = 8.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            stringResource(R.string.repository_extension_count, extensions.size),
            modifier = Modifier.padding(bottom = 6.dp),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
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
                    ExtensionCard(
                        extension = extension,
                        operation = state.extensionOperations[extension.packageName],
                        dataSaver = state.dataSaver,
                    ) {
                        selectedExtension = extension
                    }
                }
            }
        }
    }
    selectedExtension?.let { extension ->
        val stillVisible = catalogPresentation.byRepositoryAndPackage[
            extension.repositoryUrl to extension.packageName
        ]
        if (stillVisible != null) {
            ExtensionDetailsDialog(
                extension = stillVisible,
                state = state,
                onInstall = { viewModel.installExtension(stillVisible.packageName, stillVisible.repositoryUrl) },
                onCancelInstall = { viewModel.cancelExtensionInstall(stillVisible.packageName) },
                onRequestPermission = { onRequestInstallPermission(stillVisible.packageName, viewModel) },
                onUninstall = { onOpenUninstallSettings(stillVisible.packageName, viewModel) },
                onDismiss = { selectedExtension = null },
            )
        } else {
            selectedExtension = null
        }
    }
}

@Composable
private fun LanguageFilterRow(
    languages: List<String>,
    selectedLanguage: String?,
    onSelect: (String?) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterChip(
            selected = selectedLanguage == null,
            onClick = { onSelect(null) },
            label = { Text(stringResource(R.string.extensions_language_all)) },
        )
        languages.forEach { language ->
            FilterChip(
                selected = selectedLanguage == language,
                onClick = { onSelect(if (selectedLanguage == language) null else language) },
                label = { Text(language) },
            )
        }
    }
}

@Composable
private fun ExtensionCard(
    extension: ExtensionDescriptor,
    operation: ExtensionOperationState?,
    dataSaver: Boolean,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ExtensionIcon(extension, enabled = !dataSaver)
            Column(
                Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(extension.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    stringResource(R.string.extension_summary, extension.language, extension.version),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(extension.packageName, style = MaterialTheme.typography.labelSmall)
                Text(
                    stringResource(operation.phaseResource()),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (operation?.phase == ExtensionOperationPhase.FAILED ||
                        operation?.phase == ExtensionOperationPhase.INCOMPATIBLE
                    ) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                )
                if (operation?.phase == ExtensionOperationPhase.DOWNLOADING) {
                    val progress = operation.totalBytes?.takeIf { it > 0 }?.let {
                        (operation.downloadedBytes.toFloat() / it).coerceIn(0f, 1f)
                    }
                    LinearProgressIndicator(
                        progress = { progress ?: 0f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

@Composable
private fun ExtensionIcon(extension: ExtensionDescriptor, enabled: Boolean) {
    val iconUrl = extension.iconUrl
    val bitmap by androidx.compose.runtime.produceState<Bitmap?>(
        initialValue = null,
        key1 = iconUrl,
        key2 = enabled,
    ) {
        value = iconUrl?.takeIf { enabled }?.let { url ->
            withContext(Dispatchers.IO) {
                loadExtensionIcon(url)
            }
        }
    }
    val loadedBitmap = bitmap
    if (loadedBitmap == null) {
        Box(
            Modifier.size(44.dp).clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                extension.name.firstOrNull()?.uppercase() ?: "•",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    } else {
        Image(
            bitmap = loadedBitmap.asImageBitmap(),
            contentDescription = extension.name,
            modifier = Modifier.size(44.dp).clip(CircleShape),
            contentScale = ContentScale.Crop,
        )
    }
}

private fun loadExtensionIcon(url: String): Bitmap? {
    val uri = try {
        URI(url)
    } catch (_: URISyntaxException) {
        return null
    }
    if (!uri.scheme.equals("https", ignoreCase = true) || uri.host.isNullOrBlank() || uri.userInfo != null) {
        return null
    }
    val connection = uri.toURL().openConnection() as? HttpURLConnection ?: return null
    return try {
        connection.connectTimeout = ICON_CONNECT_TIMEOUT_MILLIS
        connection.readTimeout = ICON_READ_TIMEOUT_MILLIS
        connection.instanceFollowRedirects = false
        connection.setRequestProperty("Accept", "image/*")
        if (connection.responseCode != HttpURLConnection.HTTP_OK ||
            connection.contentLengthLong > MAX_ICON_BYTES
        ) {
            return null
        }
        val bytes = connection.inputStream.use { it.readNBytes(MAX_ICON_BYTES + 1) }
        if (bytes.size > MAX_ICON_BYTES) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0 ||
            bounds.outWidth > MAX_ICON_DIMENSION || bounds.outHeight > MAX_ICON_DIMENSION
        ) return null
        var sampleSize = 1
        while (bounds.outWidth / sampleSize > ICON_DECODE_DIMENSION ||
            bounds.outHeight / sampleSize > ICON_DECODE_DIMENSION
        ) {
            sampleSize *= 2
        }
        BitmapFactory.decodeByteArray(
            bytes,
            0,
            bytes.size,
            BitmapFactory.Options().apply { inSampleSize = sampleSize },
        )
    } catch (_: IOException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    } finally {
        connection.disconnect()
    }
}

@Composable
private fun ExtensionDetailsDialog(
    extension: ExtensionDescriptor,
    state: ShuraUiState,
    onInstall: () -> Unit,
    onCancelInstall: () -> Unit,
    onRequestPermission: () -> Unit,
    onUninstall: () -> Unit,
    onDismiss: () -> Unit,
) {
    val operation = state.extensionOperations[extension.packageName]
        ?: ExtensionOperationState(ExtensionOperationPhase.NOT_INSTALLED)
    val installed = state.installedPackages[extension.packageName]
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(extension.name) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(stringResource(R.string.extension_package, extension.packageName))
                Text(stringResource(R.string.extension_version, extension.version, extension.versionCode))
                Text(stringResource(R.string.extension_language, extension.language))
                Text(stringResource(R.string.extension_source, extension.repositoryUrl))
                Text(stringResource(operation.phaseResource()), fontWeight = FontWeight.SemiBold)
                operation.message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (installed?.isMihonExtension == true) {
                    Text(
                        stringResource(
                            R.string.extension_installed_version,
                            installed.versionName.orEmpty(),
                            installed.versionCode,
                        ),
                    )
                }
                if (operation.phase == ExtensionOperationPhase.DOWNLOADING) {
                    val progress = operation.totalBytes?.takeIf { it > 0 }?.let {
                        (operation.downloadedBytes.toFloat() / it).coerceIn(0f, 1f)
                    }
                    LinearProgressIndicator(
                        progress = { progress ?: 0f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        if (operation.totalBytes != null) {
                            stringResource(
                                R.string.extension_download_progress,
                                formatBytes(operation.downloadedBytes),
                                formatBytes(operation.totalBytes),
                            )
                        } else {
                            stringResource(R.string.extension_downloaded_bytes, formatBytes(operation.downloadedBytes))
                        },
                    )
                }
                if (extension.sources.isNotEmpty()) {
                    Text(stringResource(R.string.extension_sources_title), fontWeight = FontWeight.SemiBold)
                    extension.sources.forEach { source ->
                        if (source.name.isNotBlank() || source.language.isNotBlank()) {
                            Text(stringResource(R.string.extension_source_details, source.name, source.language))
                        }
                        source.homeUrl?.let {
                            Text(stringResource(R.string.extension_source_url, it))
                        }
                    }
                }
                Text(stringResource(R.string.extension_runtime_unavailable))
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                when (operation.phase) {
                    ExtensionOperationPhase.NOT_INSTALLED,
                    ExtensionOperationPhase.UPDATE_AVAILABLE,
                    ExtensionOperationPhase.FAILED -> TextButton(onClick = onInstall) {
                        Text(
                            stringResource(
                                if (operation.phase == ExtensionOperationPhase.UPDATE_AVAILABLE) {
                                    R.string.extension_action_update
                                } else {
                                    R.string.extension_action_install
                                },
                            ),
                        )
                    }
                    ExtensionOperationPhase.NEED_INSTALL_PERMISSION -> {
                        TextButton(onClick = onRequestPermission) {
                            Text(stringResource(R.string.extension_action_permission))
                        }
                        TextButton(onClick = onCancelInstall) {
                            Text(stringResource(R.string.action_cancel))
                        }
                    }
                    ExtensionOperationPhase.DOWNLOADING,
                    ExtensionOperationPhase.VERIFYING -> TextButton(onClick = onCancelInstall) {
                        Text(stringResource(R.string.action_cancel))
                    }
                    ExtensionOperationPhase.INSTALLED -> TextButton(onClick = onUninstall) {
                        Text(stringResource(R.string.extension_action_manage))
                    }
                    else -> Unit
                }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) }
            }
        },
    )
}

@Composable
private fun DownloadsScreen(viewModel: ShuraViewModel, state: ShuraUiState, padding: PaddingValues) {
    val allExtensions by androidx.compose.runtime.produceState(
        initialValue = emptyList<ExtensionDescriptor>(),
        key1 = state.preparedExtensionCatalog,
    ) {
        value = withContext(Dispatchers.Default) { state.extensionCatalog() }
    }
    val operations by androidx.compose.runtime.produceState(
        initialValue = emptyList<Pair<ExtensionDescriptor, ExtensionOperationState>>(),
        key1 = allExtensions,
        key2 = state.extensionOperations,
    ) {
        value = withContext(Dispatchers.Default) {
            allExtensions.distinctBy { it.packageName }.mapNotNull { extension ->
                state.extensionOperations[extension.packageName]?.let { operation -> extension to operation }
                    ?.takeIf { (_, operation) ->
                        operation.phase in setOf(
                            ExtensionOperationPhase.DOWNLOADING,
                            ExtensionOperationPhase.VERIFYING,
                            ExtensionOperationPhase.NEED_INSTALL_PERMISSION,
                            ExtensionOperationPhase.AWAITING_ANDROID,
                            ExtensionOperationPhase.REMOVAL_PENDING,
                            ExtensionOperationPhase.FAILED,
                        )
                    }
                }
            }
        }
    Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
        Text(
            stringResource(R.string.downloads_scope_note),
            modifier = Modifier.padding(top = 12.dp, bottom = 8.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (operations.isEmpty()) {
            EmptyState(
                title = stringResource(R.string.downloads_empty_title),
                body = stringResource(R.string.downloads_unavailable_body),
                modifier = Modifier.weight(1f),
            )
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(vertical = 12.dp),
            ) {
                items(operations, key = { it.first.packageName }) { (extension, operation) ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(
                            Modifier.fillMaxWidth().padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(extension.name, style = MaterialTheme.typography.titleMedium)
                            Text(stringResource(operation.phaseResource()))
                            operation.message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                            if (operation.phase == ExtensionOperationPhase.DOWNLOADING) {
                                LinearProgressIndicator(Modifier.fillMaxWidth())
                                Text(
                                    stringResource(
                                        R.string.extension_download_progress,
                                        formatBytes(operation.downloadedBytes),
                                        formatBytes(operation.totalBytes ?: operation.downloadedBytes),
                                    ),
                                )
                            }
                            if (operation.phase == ExtensionOperationPhase.DOWNLOADING ||
                                operation.phase == ExtensionOperationPhase.NEED_INSTALL_PERMISSION
                            ) {
                                TextButton(onClick = { viewModel.cancelExtensionInstall(extension.packageName) }) {
                                    Text(stringResource(R.string.action_cancel))
                                }
                            }
                            if (operation.phase == ExtensionOperationPhase.FAILED) {
                                TextButton(
                                    onClick = {
                                        viewModel.installExtension(extension.packageName, extension.repositoryUrl)
                                    },
                                ) {
                                    Text(stringResource(R.string.extension_action_retry))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun ExtensionOperationState?.phaseResource(): Int = when (this?.phase) {
    ExtensionOperationPhase.DOWNLOADING -> R.string.extension_status_downloading
    ExtensionOperationPhase.VERIFYING -> R.string.extension_status_verifying
    ExtensionOperationPhase.NEED_INSTALL_PERMISSION -> R.string.extension_status_permission
    ExtensionOperationPhase.AWAITING_ANDROID -> R.string.extension_status_awaiting_android
    ExtensionOperationPhase.REMOVAL_PENDING -> R.string.extension_status_removal_pending
    ExtensionOperationPhase.INSTALLED -> R.string.extension_status_installed
    ExtensionOperationPhase.UPDATE_AVAILABLE -> R.string.extension_status_update
    ExtensionOperationPhase.INCOMPATIBLE -> R.string.extension_status_incompatible
    ExtensionOperationPhase.FAILED -> R.string.extension_status_failed
    ExtensionOperationPhase.NOT_INSTALLED, null -> R.string.extension_status_not_installed
}

private fun formatBytes(bytes: Long): String = when {
    bytes < 1024L -> "$bytes B"
    bytes < 1024L * 1024 -> "%.1f KB".format(bytes / 1024f)
    else -> "%.1f MB".format(bytes / (1024f * 1024f))
}

@Composable
private fun SettingsScreen(
    viewModel: ShuraViewModel,
    state: ShuraUiState,
    padding: PaddingValues,
    onNavigate: (Destination) -> Unit,
) {
    var customDuration by rememberSaveable { mutableStateOf(state.autoFlipDurationSeconds.toString()) }
    var showCleanupConfirmation by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(state.autoFlipDurationSeconds) {
        customDuration = state.autoFlipDurationSeconds.toString()
    }
    val context = LocalContext.current
    val cacheBytes by androidx.compose.runtime.produceState<Long?>(
        initialValue = null,
        key1 = context.cacheDir,
        key2 = state.maintenanceMessage,
    ) {
        value = withContext(Dispatchers.IO) {
            val transientCache = context.cacheDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
            val repositoryCache = context.filesDir.resolve("repository-catalog-cache")
                .takeIf { it.exists() }
                ?.walkTopDown()
                ?.filter { it.isFile }
                ?.sumOf { it.length() }
                ?: 0L
            transientCache + repositoryCache
        }
    }
    val packageInfo = remember(context) {
        context.packageManager.getPackageInfo(context.packageName, 0)
    }
    Column(
        Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SettingsGroup(
            title = stringResource(R.string.settings_sources_title),
            description = stringResource(R.string.settings_sources_body),
        ) {
            OutlinedButton(
                onClick = { onNavigate(Destination.REPOSITORIES) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.settings_manage_repositories))
            }
            OutlinedButton(
                onClick = { onNavigate(Destination.EXTENSIONS) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.settings_manage_extensions))
            }
        }
        SettingsGroup(title = stringResource(R.string.settings_theme)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = state.themeMode == ThemeMode.SYSTEM,
                    onClick = { viewModel.setThemeMode(ThemeMode.SYSTEM) },
                    label = { Text(stringResource(R.string.settings_theme_system)) },
                )
                FilterChip(
                    selected = state.themeMode == ThemeMode.LIGHT,
                    onClick = { viewModel.setThemeMode(ThemeMode.LIGHT) },
                    label = { Text(stringResource(R.string.settings_theme_light)) },
                )
                FilterChip(
                    selected = state.themeMode == ThemeMode.DARK,
                    onClick = { viewModel.setThemeMode(ThemeMode.DARK) },
                    label = { Text(stringResource(R.string.settings_theme_dark)) },
                )
            }
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
        }
        SettingsGroup(
            title = stringResource(R.string.settings_performance_title),
            description = stringResource(R.string.settings_performance_body),
        ) {
            SettingSwitch(
                title = stringResource(R.string.settings_data_saver),
                checked = state.dataSaver,
                onCheckedChange = viewModel::setDataSaver,
            )
            SettingSwitch(
                title = stringResource(R.string.settings_reduce_image_memory),
                checked = state.reduceImageMemory,
                onCheckedChange = viewModel::setReduceImageMemory,
            )
        }
        SettingsGroup(title = stringResource(R.string.settings_reader_title)) {
            Text(stringResource(R.string.settings_reader_orientation), style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = state.readerOrientation == ReaderOrientation.HORIZONTAL,
                    onClick = { viewModel.setReaderOrientation(ReaderOrientation.HORIZONTAL) },
                    label = { Text(stringResource(R.string.settings_reader_horizontal)) },
                )
                FilterChip(
                    selected = state.readerOrientation == ReaderOrientation.VERTICAL,
                    onClick = { viewModel.setReaderOrientation(ReaderOrientation.VERTICAL) },
                    label = { Text(stringResource(R.string.settings_reader_vertical)) },
                )
            }
            Text(stringResource(R.string.settings_image_fit), style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = state.imageFitMode == ImageFitMode.FIT,
                    onClick = { viewModel.setImageFitMode(ImageFitMode.FIT) },
                    label = { Text(stringResource(R.string.settings_image_fit_contain)) },
                )
                FilterChip(
                    selected = state.imageFitMode == ImageFitMode.CROP,
                    onClick = { viewModel.setImageFitMode(ImageFitMode.CROP) },
                    label = { Text(stringResource(R.string.settings_image_fit_crop)) },
                )
            }
            SettingSwitch(
                title = stringResource(R.string.settings_reader_controls),
                checked = state.showReaderControls,
                onCheckedChange = viewModel::setShowReaderControls,
            )
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
        }
        SettingsGroup(title = stringResource(R.string.settings_storage_title)) {
            Text(
                stringResource(
                    R.string.settings_storage_usage,
                    cacheBytes?.let(::formatBytes) ?: stringResource(R.string.value_unknown),
                ),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(stringResource(R.string.settings_storage_note), color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(
                onClick = { showCleanupConfirmation = true },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.settings_clear_temporary))
            }
            state.maintenanceMessage?.let { message ->
                Text(
                    if (message.startsWith("temporary_files_cleared:")) {
                        stringResource(
                            R.string.settings_temporary_cleared,
                            message.substringAfter(':').toIntOrNull() ?: 0,
                        )
                    } else {
                        stringResource(R.string.settings_maintenance_error, message)
                    },
                    color = if (message.startsWith("temporary_files_cleared:")) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                )
            }
        }
        SettingsGroup(title = stringResource(R.string.settings_policy_title)) {
            Text(stringResource(R.string.settings_policy_body), color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(stringResource(R.string.settings_compatibility_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.settings_compatibility_body), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        SettingsGroup(title = stringResource(R.string.settings_about_title)) {
            Text(stringResource(R.string.settings_about_name, stringResource(R.string.app_name)))
            Text(stringResource(R.string.settings_about_version, packageInfo.versionName ?: stringResource(R.string.value_unknown)))
            Text(stringResource(R.string.settings_about_build, packageInfo.longVersionCode))
            Text(stringResource(R.string.settings_about_package, context.packageName))
            Text(stringResource(R.string.settings_about_android, Build.VERSION.SDK_INT, Build.VERSION.RELEASE))
        }
    }
    if (showCleanupConfirmation) {
        AlertDialog(
            onDismissRequest = { showCleanupConfirmation = false },
            title = { Text(stringResource(R.string.settings_cleanup_confirm_title)) },
            text = { Text(stringResource(R.string.settings_cleanup_confirm_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showCleanupConfirmation = false
                        viewModel.clearTemporaryFiles()
                    },
                ) {
                    Text(stringResource(R.string.settings_clear_temporary))
                }
            },
            dismissButton = {
                TextButton(onClick = { showCleanupConfirmation = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

@Composable
private fun SettingsGroup(
    title: String,
    description: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            description?.let {
                Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            content()
        }
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
        state.activeOnlineChapter?.let { chapter ->
            Text(
                stringResource(
                    R.string.reader_chapter_source_credit,
                    chapter.scanlationGroup ?: stringResource(R.string.explore_unknown_group),
                ),
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Button(
            onClick = {
                viewModel.closeOnlineChapter()
                launcher.launch(arrayOf("image/*"))
            },
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        ) {
            Text(stringResource(R.string.reader_open_images))
        }
        val pages = if (state.activeOnlineChapter != null) {
            state.onlineChapterPages.map(Uri::parse)
        } else {
            imageUris.value
        }
        val activeChapter = state.activeOnlineChapter
        val initialPage = activeChapter?.let { chapter ->
            state.onlineReadingProgress[chapter.mangaId]
                ?.takeIf { it.chapterId == chapter.id }
                ?.pageIndex
        } ?: 0
        when {
            state.mangaChapterLoading -> {
                Column(
                    Modifier.weight(1f).fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    CircularProgressIndicator()
                    Text(stringResource(R.string.reader_chapter_loading))
                }
            }
            state.mangaSourceError != null && state.activeOnlineChapter != null -> {
                EmptyState(
                    title = stringResource(R.string.explore_search_failed),
                    body = state.mangaSourceError,
                    modifier = Modifier.weight(1f),
                )
            }
            pages.isEmpty() -> {
            EmptyState(
                title = stringResource(R.string.reader_empty_title),
                body = stringResource(R.string.reader_empty_body),
                modifier = Modifier.weight(1f),
            )
            }
            else -> {
            ImagePager(
                uris = pages,
                autoFlipEnabled = state.autoFlipEnabled,
                autoFlipDurationSeconds = state.autoFlipDurationSeconds,
                pauseOnTouch = state.pauseAutoFlipOnTouch,
                orientation = state.readerOrientation,
                fitMode = state.imageFitMode,
                reduceImageMemory = state.reduceImageMemory,
                showControls = state.showReaderControls,
                onAutoFlipToggle = { viewModel.setAutoFlipEnabled(!state.autoFlipEnabled) },
                onAutoFlipFinished = { viewModel.setAutoFlipEnabled(false) },
                initialPage = initialPage,
                onPageChanged = { pageIndex ->
                    activeChapter?.let { viewModel.saveOnlineReadingProgress(it, pageIndex) }
                },
                modifier = Modifier.weight(1f),
            )
            }
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
    orientation: ReaderOrientation,
    fitMode: ImageFitMode,
    reduceImageMemory: Boolean,
    showControls: Boolean,
    onAutoFlipToggle: () -> Unit,
    onAutoFlipFinished: () -> Unit,
    initialPage: Int = 0,
    onPageChanged: (Int) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val pagerState = rememberPagerState(
        initialPage = initialPage.coerceIn(0, uris.lastIndex),
        pageCount = { uris.size },
    )
    val currentPage = remember { AtomicInteger(0) }
    val readyPages = remember { ConcurrentHashMap.newKeySet<Int>() }
    val controllerReference = remember { java.util.concurrent.atomic.AtomicReference<AutoFlipController?>(null) }
    val scope = rememberCoroutineScope()
    val latestOnPageChanged by androidx.compose.runtime.rememberUpdatedState(onPageChanged)
    var controllerError by remember { mutableStateOf<String?>(null) }
    var pausedByTouch by remember(uris) { mutableStateOf(false) }

    LaunchedEffect(autoFlipEnabled) {
        if (!autoFlipEnabled) pausedByTouch = false
    }

    LaunchedEffect(uris) {
        readyPages.clear()
        currentPage.set(0)
        pagerState.scrollToPage(initialPage.coerceIn(0, uris.lastIndex))
    }
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.currentPage }.collect { page ->
            currentPage.set(page)
            latestOnPageChanged(page)
        }
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
        if (showControls) {
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
        }
        controllerError?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        val pageContent: @Composable (Int) -> Unit = { index ->
            ImagePage(
                uri = uris[index],
                fitMode = fitMode,
                reduceImageMemory = reduceImageMemory,
                onTouch = {
                    if (pauseOnTouch) {
                        controllerReference.get()?.onTouch()
                        if (autoFlipEnabled) pausedByTouch = true
                    }
                },
                onReady = { ready ->
                    if (ready) readyPages.add(index) else readyPages.remove(index)
                },
            )
        }
        when (orientation) {
            ReaderOrientation.HORIZONTAL -> HorizontalPager(
                state = pagerState,
                modifier = Modifier.weight(1f).fillMaxWidth()
                    .clip(MaterialTheme.shapes.large)
                    .background(Color(0xFF100D14)),
                beyondViewportPageCount = 1,
            ) { index -> pageContent(index) }
            ReaderOrientation.VERTICAL -> VerticalPager(
                state = pagerState,
                modifier = Modifier.weight(1f).fillMaxWidth()
                    .clip(MaterialTheme.shapes.large)
                    .background(Color(0xFF100D14)),
                beyondViewportPageCount = 1,
            ) { index -> pageContent(index) }
        }
        if (showControls) {
            OutlinedButton(
                onClick = {
                    if (pausedByTouch && autoFlipEnabled) {
                        controllerReference.get()?.start()
                        pausedByTouch = false
                    } else {
                        onAutoFlipToggle()
                    }
                },
            ) {
                Text(
                    when {
                        pausedByTouch && autoFlipEnabled -> stringResource(R.string.reader_auto_flip_resume)
                        autoFlipEnabled -> stringResource(R.string.reader_auto_flip_stop)
                        else -> stringResource(R.string.reader_auto_flip_start)
                    },
                )
            }
        }
    }
}

private sealed interface ImageLoadState {
    data object Loading : ImageLoadState
    data class Ready(val bitmap: Bitmap) : ImageLoadState
    data class Failed(val message: String?) : ImageLoadState
}

@Composable
private fun ImagePage(
    uri: Uri,
    fitMode: ImageFitMode,
    reduceImageMemory: Boolean,
    onTouch: () -> Unit,
    onReady: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    var retryKey by remember(uri) { mutableIntStateOf(0) }
    val imageState by androidx.compose.runtime.produceState<ImageLoadState>(
        initialValue = ImageLoadState.Loading,
        key1 = uri,
        key2 = retryKey,
        key3 = reduceImageMemory,
    ) {
        value = ImageLoadState.Loading
        value = withContext(Dispatchers.IO) {
            runCatching { decodeForDisplay(context, uri, reduceImageMemory) }
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
            .pointerInput(uri, onTouch) {
                while (true) {
                    var touchNotified = false
                    detectTransformGestures { _, pan, zoom, _ ->
                        if (!touchNotified) {
                            onTouch()
                            touchNotified = true
                        }
                        scale = (scale * zoom).coerceIn(1f, 4f)
                        offsetX += pan.x
                        offsetY += pan.y
                    }
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
                contentScale = when (fitMode) {
                    ImageFitMode.FIT -> ContentScale.Fit
                    ImageFitMode.CROP -> ContentScale.Crop
                },
            )
        }
    }
}

internal fun readerImageSampleSize(width: Int, height: Int, reduceImageMemory: Boolean): Int {
    require(width > 0 && height > 0) { "Image dimensions must be positive" }
    val maxDimension = if (reduceImageMemory) 1_024 else 1_600
    var sampleSize = 1
    while (width / sampleSize > maxDimension || height / sampleSize > maxDimension) {
        sampleSize *= 2
    }
    return sampleSize
}

private fun decodeForDisplay(
    context: android.content.Context,
    uri: Uri,
    reduceImageMemory: Boolean,
): Bitmap {
    if (uri.scheme.equals("https", ignoreCase = true)) {
        return decodeRemoteMangaPage(context, uri, reduceImageMemory)
    }
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        ?: error("Unable to read image")
    require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Invalid image" }
    val sampleSize = readerImageSampleSize(bounds.outWidth, bounds.outHeight, reduceImageMemory)
    val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
    return context.contentResolver.openInputStream(uri)?.use {
        BitmapFactory.decodeStream(it, null, options)
    } ?: error("Unable to decode image")
}

private fun decodeRemoteMangaPage(
    context: android.content.Context,
    uri: Uri,
    reduceImageMemory: Boolean,
): Bitmap {
    val temporaryPage = java.io.File.createTempFile("shura-page-", ".image", context.cacheDir)
    try {
        val connection = openMangaPage(uri.toString())
        try {
            require(connection.contentLengthLong <= MAX_MANGA_PAGE_BYTES) {
                "MangaDex page exceeds the download size limit"
            }
            connection.inputStream.use { input ->
                temporaryPage.outputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var totalBytes = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        totalBytes += count
                        require(totalBytes <= MAX_MANGA_PAGE_BYTES) {
                            "MangaDex page exceeds the download size limit"
                        }
                        output.write(buffer, 0, count)
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(temporaryPage.absolutePath, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Invalid MangaDex page image" }
        val sampleSize = readerImageSampleSize(bounds.outWidth, bounds.outHeight, reduceImageMemory)
        return BitmapFactory.decodeFile(
            temporaryPage.absolutePath,
            BitmapFactory.Options().apply { inSampleSize = sampleSize },
        ) ?: error("Unable to decode MangaDex page image")
    } finally {
        if (!temporaryPage.delete() && temporaryPage.exists()) {
            throw IOException("Unable to remove temporary MangaDex page")
        }
    }
}

private fun openMangaPage(url: String): HttpURLConnection {
    var current = java.net.URI(url)
    var redirects = 0
    while (true) {
        require(
            current.scheme.equals("https", ignoreCase = true) &&
                current.host?.endsWith(".mangadex.network", ignoreCase = true) == true &&
                current.port in setOf(-1, 443) &&
                current.userInfo == null &&
                current.fragment == null,
        ) { "MangaDex page URL is not trusted" }
        val connection = current.toURL().openConnection() as HttpURLConnection
        connection.connectTimeout = 10_000
        connection.readTimeout = 20_000
        connection.instanceFollowRedirects = false
        connection.setRequestProperty("Referer", "https://mangadex.org/")
        val status = connection.responseCode
        if (status in setOf(301, 302, 303, 307, 308)) {
            val location = connection.getHeaderField("Location")
            connection.disconnect()
            check(redirects < 5) { "Too many MangaDex page redirects" }
            current = current.resolve(location ?: error("MangaDex page redirect omitted its location"))
            redirects++
            continue
        }
        if (status != HttpURLConnection.HTTP_OK) {
            connection.disconnect()
            throw IOException("MangaDex page request failed: HTTP $status")
        }
        return connection
    }
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
            Text(
                stringResource(R.string.brand_mark),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
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

private const val ICON_CONNECT_TIMEOUT_MILLIS = 4_000
private const val ICON_READ_TIMEOUT_MILLIS = 4_000
private const val MAX_ICON_BYTES = 512 * 1024
private const val MAX_ICON_DIMENSION = 32_768
private const val ICON_DECODE_DIMENSION = 256
private const val MAX_MANGA_PAGE_BYTES = 16L * 1024 * 1024
