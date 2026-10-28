# Shura implementation status

Checkpoint: 2026-10-28

## Repository baseline

- Working branch: `work/shura-development`.
- Application ID: `app.shura.android`; version name `0.1.2`; version code `3`.
- Android project remains Kotlin/Compose/Material 3 with Java 17 and the checked-in Gradle wrapper.
- The worktree was clean before this checkpoint. The local branch already contained four commits not present on `origin/work/shura-development`; they were preserved.
- The shell initially selected Java 25 and Gradle failed before tests ran. Selecting `/usr/lib/jvm/java-17-openjdk-amd64` resolved that environment issue.
- Verified APK-classification milestone: `035d77d` (`fix(security): fail closed on unknown APK content metadata`). Repository-local `commit.gpgsign=true` was honored by using an unsigned one-command override after explicit user authorization; the repository signing configuration was not changed.

## Completed capabilities retained

- Real repository management, JSON/Protobuf parsing, app-private persistent catalogs, content filtering, diagnostics, and HTTPS index fetching.
- Extension APK download, verification, Android `PackageInstaller` handoff, install-result verification, persisted app preferences, local-image reading, and Arabic/English UI.
- The app explicitly does not claim that installed Mihon extensions can execute as Shura manga sources. Chapter downloads and OCR/translation are also not implemented.

## Completed checkpoint changes

- APK NSFW metadata is now represented as `SAFE`, `NSFW`, `MISSING`, or `INVALID` instead of a default-false Boolean.
- Only an explicit integer `0` in the APK manifest is accepted. Integer `1` is rejected; absent, null, malformed, and unsupported values fail closed. This may exclude older extensions without valid APK metadata.
- The APK check runs before a `VerifiedExtensionApk` can be created, so rejected downloads are deleted and cannot reach `PackageInstaller`.
- Installed-package/update verification also requires a known-safe APK classification.
- Tests cover classification values, safe catalog metadata paired with an adult APK classification, fail-closed missing/invalid metadata, temporary-file cleanup, and post-install verification.

## Validation

- `./gradlew :app:testDebugUnitTest --tests app.shura.android.ApkNsfwClassificationTest --tests app.shura.android.ExtensionInstallCoordinatorTest --no-daemon` — passed.
- `SHURA_RUN_INTEGRATION_TESTS=true ./gradlew :core:test --rerun-tasks --no-daemon` — passed, including live Keiyoushi checks and the generated 10,000-entry performance test.
- `./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug --no-daemon` with Java 17 — passed; the app unit tests ran and the debug APK assembled.
- Live index results: `index.pb` redirected over HTTPS and parsed 1,043 entries; `index.min.json` returned HTTP 200 and parsed two current entries. These are point-in-time results, not fixed catalog-size assumptions.
- 10,000-entry performance test: parse 562 ms, policy filtering 239 ms, search 34 ms, language filtering/sort 13 ms; measured on the current JVM and not an Android-device benchmark.
- `git diff --check` — passed.
- No emulator or physical Android device is available in this environment; on-device and screenshot validation has not been performed.

## Remaining work, ordered by dependency

- [ ] Integrate and validate a compatible, legally reviewed Mihon source runtime before claiming online manga browsing. This repository currently has no Mihon source API dependency, source loader, runtime lifecycle, or invocation adapter. Merely loading arbitrary extension APK classes is not a safe or compatible execution strategy. The upstream Mihon project is Apache-2.0 and has a separate `source-api` module; any integration must preserve applicable notices/licenses and independently review extension/source terms. References: <https://github.com/mihonapp/mihon/tree/main/source-api> and <https://github.com/mihonapp/mihon/blob/main/LICENSE>.
- [ ] After a real source runtime exists, implement source search/details/chapters/pages, stable source-backed library entries and progress, and chapter downloads. Current manually saved library titles are not fetched manga records; chapter downloads are not supported.
- [ ] Implement OCR/translation only with a real OCR engine and a user-configured translation provider; no translation action or fabricated output is exposed today.
- [ ] Perform visual, RTL/LTR, accessibility, and Android installation testing on an emulator or physical device.

## Checkpoint persistence

The tested implementation is committed as `035d77d`. This status update is staged for its own checkpoint commit; branch push is pending. The development branch must remain `work/shura-development`; do not commit to `main`, merge PR #1, enable auto-merge, or publish a release without explicit authorization.
