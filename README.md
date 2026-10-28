# See-if-fd

## Current project scope

Shura currently contains a Kotlin/JVM core library (`:core`), not an Android application module. The core provides:

- HTTPS repository URL validation, Mihon-style `index.min.json` parsing, and bounded index downloads.
- Local repository URL persistence, catalog refresh/retry state, extension search, and catalog version comparisons.
- Built-in metadata checks that block explicit-content indicators and positive repository NSFW classifications, while reporting weaker mature-content indicators as warnings.
- Reader settings, a cancellable auto-flip controller, and a non-destructive translation-overlay data model.

Repository fetching and file persistence are synchronous core APIs. An eventual Android UI must call them off the main thread and persist files inside app-private storage.

## Build and tests

The project targets Java 17 and uses Kotlin 2.2.20. With a Java 17 JDK installed, run:

```shell
./gradlew :core:test --no-daemon
```

GitHub Actions runs the core tests on pushes and pull requests.

## Compatibility and safety limits

Only the Mihon-style JSON array format represented by the checked-in parser is currently supported. The code can read catalog metadata but does not download or install APKs, run extension code, or implement the Mihon source runtime. No Android app module, Android SDK integration, or user interface exists yet.

The content policy evaluates supplied metadata and repository classifications. It does not analyze page images and cannot guarantee detection of all inappropriate content. Mature-content keywords produce warnings rather than automatic blocks unless an explicit indicator or positive repository NSFW flag is present.

The translation types preserve original text/image data, but OCR and translation-provider integrations are not implemented. The auto-flip controller is a core component and is not yet connected to an Android reader screen.