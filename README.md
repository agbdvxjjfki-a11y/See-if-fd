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

The app currently understands the Mihon-style `index.min.json` array parsed by `:core`. Other index formats have not been verified. Reading a catalog does not mean that APK installation or Mihon source execution is supported.

## Content and translation limits

Metadata checks block explicit indicators and positive repository NSFW flags, while weaker/missing metadata is surfaced as a warning. The policy does not inspect page images and cannot guarantee detection of all inappropriate content. No ordinary UI setting disables the block policy.

The reader can display the user's local images. OCR and a translation provider are not implemented; the app does not show a fake translation action.

## Build and tests

The project targets Java 17 and uses Gradle 8.13, Android Gradle Plugin 8.13.2, and Kotlin 2.2.20. Install Android SDK Platform 36 and Build Tools 36.0.0, then run:

```shell
./gradlew clean :core:test :app:testDebugUnitTest :app:assembleDebug --no-daemon
```

GitHub Actions installs the Android SDK packages, runs core and app unit tests, and builds the debug APK on pushes and pull requests.
