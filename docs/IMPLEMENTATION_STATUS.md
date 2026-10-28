# Shura implementation status

Checkpoint: 2026-10-28

## Current repository state

- Development branch: `work/shura-development`.
- Application ID: `app.shura.android`; version name `0.1.2`; version code `3`.
- Kotlin, Jetpack Compose, Material 3, Java 17, and the checked-in Gradle wrapper are retained.
- Latest implementation checkpoint: `9fb65da` (`feat(app): add real MangaDex browsing and library progress`), pushed to `origin/work/shura-development`.
- Repository-race checkpoint: `77da65d` (`fix(core): guard repository mutation races`).
- The development branch was two commits ahead of its remote before the push; `77da65d` and `9fb65da` are now pushed. This status-document update is the only pending local change.

## Implemented and validated

- Repository management supports validated add/edit, refresh, remove, retry, JSON and Protobuf indexes, diagnostics, and persistent catalogs. Network fetching and parsing run outside manager state locks. Concurrent additions and stale refreshes have regression tests.
- APK installation retains HTTPS download limits, archive/package/version checks, APK-level Mihon NSFW metadata validation that fails closed, signer/hash verification, temporary-file cleanup, and Android `PackageInstaller` confirmation.
- A built-in MangaDex provider now searches real safe-rated results, loads details and translated chapters, and obtains real chapter-page URLs. Search, chapter, and page metadata requests are bounded and HTTPS-validated; remote page requests restrict hosts, redirects, ports, and payload size.
- Explore displays real MangaDex results and chapters. It credits MangaDex and the scanlation group when available. It does not execute installed Mihon extension code.
- MangaDex entries can be saved using stable source IDs. Their chapter and page positions persist and can be resumed when the source still offers the chapter.
- The reader displays selected local images and on-demand MangaDex pages. It supports paging, fit/crop, pinch zoom, retry, auto-flip, pause-on-touch, and memory-conscious sampled decoding. Remote page data is bounded and written to Shura-owned temporary cache files, then removed after decoding.
- Settings include persistent system/light/dark appearance, Arabic/English language, reader navigation orientation and fit, controls, auto-flip and interval, pause-on-touch, data saver, image-memory preference, cache usage, confirmed Shura-temporary-file cleanup, repository/extension navigation, compatibility information, and accurate About details.
- Arabic/English resources and the README were updated. The UI makes no claim that chapter downloads or translation exist.

## Latest verification

Executed with Java 17:

```shell
SHURA_RUN_INTEGRATION_TESTS=true ./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug --no-daemon
```

- Build succeeded; the debug APK was produced at `app/build/outputs/apk/debug/app-debug.apk` (about 13 MiB).
- Core tests: 57 tests passed, including repository race tests and the generated catalog performance test.
- App unit tests: 27 tests passed, including APK validation, online-library persistence, and reader-performance tests.
- Live MangaDex integration test ran (not skipped): safe-rated search, manga details, a real English chapter, and three real page URLs were returned. Network availability is independent of the mocked parser tests.
- Live Keiyoushi checks passed: the redirected Protobuf index returned HTTP 200 with 1,043 entries, and the JSON index returned HTTP 200 with two entries. These are point-in-time catalog sizes.
- The 10,000-entry JVM performance test reported: parse 965 ms, content filtering 638 ms, search 74 ms, language filtering/sort 49 ms; manager parse/filter/config-save 4,018 ms; repeated refresh 1,297/894/607 ms. This is a JVM measurement on the current Codespaces environment, not an Android-device benchmark.
- `git diff --check` passed before the implementation checkpoint.
- No emulator or physical device is available; installation, visual, RTL, and accessibility behavior have not been verified on-device.

## Remaining work and limitations

- [ ] Integrate a compatible and legally reviewed Mihon source runtime if a safe supported architecture is available. Shura does not execute installed extension APK source code; arbitrary APK class loading is not an acceptable substitute.
- [ ] Add source pagination beyond the current MangaDex result/chapter limits where needed.
- [ ] Implement a real chapter-download queue only when a compatible source and persistent page-download workflow are available. Current online pages are temporary reader loads, not downloads.
- [ ] Implement OCR and translation only with an actual OCR engine and a genuine translation provider; neither feature is currently exposed as a fake action.
- [ ] Consider explicit left-to-right/right-to-left reader direction and screen-orientation controls; current persisted navigation mode is horizontal or vertical.
- [ ] Validate installation, visual layout, accessibility, and RTL/LTR flows on an Android emulator or device.

## Checkpoint and branch policy

The current implementation checkpoint is `9fb65da`; prior security and repository-concurrency checkpoints are `035d77d` and `77da65d`. These are on `origin/work/shura-development`. Keep development on `work/shura-development`; do not commit to `main`, merge PR #1, enable auto-merge, or publish a release without explicit authorization.
