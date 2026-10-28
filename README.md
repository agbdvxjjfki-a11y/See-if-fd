# Shura

Shura is an Android image reader and a Kotlin core library. The Android app is in `:app`; repository parsing and content policy live in `:core`.

## Android app

The Compose app includes:

- Arabic-first navigation with Arabic/English resources, RTL layout, and a dark theme option.
- A local title list saved in app preferences. These are user-entered titles, not fetched manga or chapter records.
- Repository management backed by `RepositoryManager`, `RepositoryIndexClient`, and app-private `FileRepositoryConfigurationStore` storage. Network/index work runs off the UI thread.
- Extension catalog browsing with the core content policy applied when rendering each catalog entry. It does not expose an install or run action.
- A reader for multiple image documents selected by the user, with paging, pinch zoom, retry for unreadable images, and auto-flip connected to the core `AutoFlipController`. The original files are not changed; decoding is downsampled transiently for display.
- Persisted reader settings and language/theme preferences.

Repository URLs may point directly to `index.pb`, `index.json`, or `index.min.json`, or to a repository directory. Directory URLs are tried in this order: `index.pb`, `index.min.json`, then `index.json`. GitHub redirect URLs are followed over HTTPS, and diagnostics record the entered URL, requested/final URLs, HTTP status, content type, redirects, and whether the body was downloaded. A directory is saved only after its index has downloaded and parsed successfully; failed refreshes keep the last successful catalog.

The protobuf reader uses the published Mihon/Tachiyomix `index.proto` schema, including the Keiyoushi `Resources.jarUrl` extension. JSON parsing follows the actual Mihon legacy array fields (`name`, `pkg`, `apk`, `lang`, `version`, `code`, `nsfw`, and `sources`), while accepting the older `versionCode` spelling. Relative APK and icon paths resolve against the final index URL; HTTPS absolute resource URLs are also supported. External protobuf extension-list URLs are fetched and parsed using the same schema.

For example, the community-maintained Keiyoushi repository publishes `https://github.com/keiyoushi/extensions/raw/repo/index.pb` and `https://raw.githubusercontent.com/keiyoushi/extensions/repo/index.min.json`. Keiyoushi is not an official Mihon repository. The Shura source-code GitHub repository does not publish an extension index and is not pre-added as one.

Reading a catalog does not mean that APK installation or Mihon source execution is supported.

## Content and translation limits

Metadata checks block explicit indicators and positive repository NSFW flags, while weaker/missing metadata is surfaced as a warning. The policy does not inspect page images and cannot guarantee detection of all inappropriate content. No ordinary UI setting disables the block policy.

The reader can display the user's local images. OCR and a translation provider are not implemented; the app does not show a fake translation action.

## Build and tests

The project targets Java 17 and uses Gradle 8.13, Android Gradle Plugin 8.13.2, and Kotlin 2.2.20. Install Android SDK Platform 36 and Build Tools 36.0.0, then run:

```shell
./gradlew clean :core:test :app:testDebugUnitTest :app:assembleDebug --no-daemon
```

GitHub Actions installs the Android SDK packages, runs core and app unit tests, and builds the debug APK on pushes and pull requests. Mock HTTPS responses cover redirects, 404 and connection failures, both JSON index names, gzip-compressed protobuf indexes, and external extension lists. Optional live Keiyoushi checks can be run with `SHURA_RUN_INTEGRATION_TESTS=true ./gradlew :core:test --tests app.shura.core.repository.RepositoryIndexClientTest.keiyoushiPublicIndexCanBeCheckedOnDemand --tests app.shura.core.repository.RepositoryIndexClientTest.keiyoushiPublicJsonIndexCanBeCheckedOnDemand`.
