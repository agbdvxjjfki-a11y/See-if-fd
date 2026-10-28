# Shura

Shura is an Android image reader and a Kotlin core library. The Android app is in `:app`; repository parsing and content policy live in `:core`.

## Android app

The Compose app includes:

- Arabic-first navigation with Arabic/English resources, RTL layout, and system/light/dark appearance options.
- A local title list saved in app preferences. These are user-entered titles, not fetched manga or chapter records.
- Repository management backed by `RepositoryManager`, `RepositoryIndexClient`, and app-private `FileRepositoryConfigurationStore` storage. Repository controls are reached from Settings; network/storage use `Dispatchers.IO`, while index parsing and catalog work use `Dispatchers.Default`, off the UI thread.
- Extension catalog browsing with policy-blocked entries removed before repository snapshots are exposed, and re-filtered at presentation/detail boundaries. Catalog preparation and search/sort run on a worker dispatcher; search input is debounced and prior work is cancelled. Allowed entries can be filtered by language and opened for metadata details. Icons load from HTTPS with bounded size/timeouts and fall back safely.
- APK downloads are limited to HTTPS and 128 MiB, checked for package ID, version, Mihon extension feature, APK adult-content metadata, signing certificate, and any trusted SHA-256 references in the index. Installation uses Android `PackageInstaller` and requires Android's user confirmation; Android may also require the user to grant Shura its per-app "Install unknown apps" permission. Shura only reports success after Android confirms and the installed package is re-checked. Package discovery requires `QUERY_ALL_PACKAGES` because catalog package IDs are dynamic; this side-loaded build is not configured for Google Play distribution.
- A downloads destination that shows active extension download/install operations. Chapter downloads are not implemented.
- A reader for multiple image documents selected by the user, with paging, pinch zoom, retry for unreadable images, and auto-flip connected to the core `AutoFlipController`. The original files are not changed; decoding is downsampled transiently for display. The optional memory-saver preference lowers the decode dimension from 1600 to 1024 pixels and auto-flip can resume after a configured touch pause.
- Persisted reader settings, image-memory preference, data-saver preference, and language/theme preferences.

The Compose surfaces reviewed for the Shura visual system are Home, Library, Explore, Settings, Repositories, Extensions, Downloads, and Reader. Repository management is reachable from Settings and is not a primary-navigation destination or library entry. This workspace has no connected Android device or emulator, so these screens were reviewed in source and the app can be built, but no device screenshot or on-device visual validation is claimed.

Repository URLs may point directly to `index.pb`, `index.json`, or `index.min.json`, or to a repository directory. Directory URLs are tried in this order: `index.pb`, `index.min.json`, then `index.json`. GitHub redirect URLs are followed over HTTPS, and diagnostics record the entered URL, requested/final URLs, HTTP status, content type, redirects, and whether the body was downloaded. A directory is saved only after its index has downloaded and parsed successfully. The latest policy-filtered catalog is persisted atomically in app-private storage (at most 16 catalogs and 128 MiB total); failed refreshes retain it across both refreshes and app restarts.

The protobuf reader uses the published Mihon/Tachiyomix `index.proto` schema, including the Keiyoushi `Resources.jarUrl` extension. JSON parsing follows the actual Mihon legacy array fields (`name`, `pkg`, `apk`, `lang`, `version`, `code`, `nsfw`, and `sources`), while accepting the older `versionCode` spelling. Relative APK and icon paths resolve against the final index URL; HTTPS absolute resource URLs are also supported. External protobuf extension-list URLs are fetched and parsed using the same schema.

For example, the community-maintained Keiyoushi repository publishes `https://github.com/keiyoushi/extensions/raw/repo/index.pb` and `https://raw.githubusercontent.com/keiyoushi/extensions/repo/index.min.json`. Keiyoushi is not an official Mihon repository. The Shura source-code GitHub repository does not publish an extension index and is not pre-added as one.

Reading a catalog does not mean that an extension source can run inside Shura. Mihon APK installation and package discovery are supported, but Shura does not yet include Mihon's extension runtime, API libraries, source class loader, or trust/update machinery; installed sources therefore cannot be used to browse manga. Filtered catalog counts include only entries allowed by the metadata policy. That policy evaluates extension names, package names, source names/messages, repository NSFW classification, and any supplied description/tags. Before installation, Shura also checks the signed APK's Mihon NSFW metadata: only an explicit integer value of `0` is accepted, an explicit `1` is rejected, and missing, malformed, or unsupported values are rejected as unknown. This fail-closed compatibility policy may exclude older extensions without a valid APK classification. The policy does not inspect APK code or page images and cannot guarantee detection of all inappropriate content.

## Content and translation limits

Metadata checks block explicit indicators and positive repository NSFW flags, while weaker/missing metadata is surfaced as a warning. The policy does not inspect page images and cannot guarantee detection of all inappropriate content. No ordinary UI setting disables the block policy.

The reader can display the user's local images. OCR and a translation provider are not implemented; the app does not show a fake translation action.

## Build and tests

The project targets Java 17 and uses Gradle 8.13, Android Gradle Plugin 8.13.2, and Kotlin 2.2.20. Install Android SDK Platform 36 and Build Tools 36.0.0, then run:

```shell
./gradlew clean :core:test :app:testDebugUnitTest :app:assembleDebug --no-daemon
```

GitHub Actions installs the Android SDK packages, runs core and app unit tests, and builds the debug APK on pushes and pull requests. Mock HTTPS responses cover redirects, 404 and connection failures, both JSON index names, gzip-compressed protobuf indexes, and external extension lists. Optional live Keiyoushi checks can be run with `SHURA_RUN_INTEGRATION_TESTS=true ./gradlew :core:test --tests app.shura.core.repository.RepositoryIndexClientTest.keiyoushiPublicIndexCanBeCheckedOnDemand --tests app.shura.core.repository.RepositoryIndexClientTest.keiyoushiPublicJsonIndexCanBeCheckedOnDemand`.

The repeatable `RepositoryCatalogPerformanceTest` generates 10,000 varied JSON entries and reports parse, policy filtering, search, language-filter/sort, repository refresh/save, repeated-refresh, and configuration-save timings. It reports per-thread allocated bytes via the JVM `ThreadMXBean` when available; these are JVM allocation measurements, not Android heap or device-profiler measurements. Catalog parsing and filtering run on worker dispatchers, while Compose memoizes the prepared catalog by identity and debounces worker-thread filtering; these behaviors are also covered by the test and app wiring. Run it with:

```shell
./gradlew :core:test --tests app.shura.core.repository.RepositoryCatalogPerformanceTest --no-daemon
```
