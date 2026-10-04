# Contributing to PairRename

Thank you for helping make PairRename safer and more useful. Small, focused changes and clear bug reports are welcome.

## Before opening an issue

- Search existing issues for the same problem.
- Use the bug or feature-request template.
- For a bug, include Android version, device/storage provider, PairRename version, exact steps, expected result, actual result, and relevant logs with personal details removed.
- Never attach private photos, full folder listings, access tokens, signing keys, or passwords. Use synthetic files and redacted logs.

## Before sending a pull request

1. Explain the user problem and the intended behavior.
2. Keep changes focused; avoid bundling unrelated refactors.
3. For file operations, preserve the safety properties: verify identity rather than guessing by filename, avoid overwriting user data silently, report partial failures clearly, and keep undo/history semantics coherent.
4. Add or update a regression check for a bug fix when practical.
5. Run `bash verify_all.sh` and include the result in the PR. If you have the Android SDK, also build and exercise the affected flow on a device/emulator using test copies.
6. Update user-facing text and documentation when behavior changes.

## Development environment

- Android SDK platform/build tools: API 35
- JDK: 17
- Gradle: wrapper 8.9
- Minimum Android version: API 26

Build a local debug APK with `./gradlew :app:assembleDebug`. A release build requires your own signing key. Never commit signing material or locally generated configuration.

## Pull request checklist

- [ ] The change solves a specific, described problem.
- [ ] File mutations are explicit and failure paths are safe.
- [ ] Relevant checks pass, or limitations are clearly stated.
- [ ] No private user data, local-machine config, generated build output, or signing material is included.
- [ ] Documentation and translations are updated if needed.
