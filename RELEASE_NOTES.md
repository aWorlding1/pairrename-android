# PairRename v6.2.3 — 2026-10-04

## Highlights

- Fixed a duplicate Compose lazy-list key crash that could occur after a rename when document URIs converge. If an incremental pane update would introduce duplicate keys, it is discarded and the existing full-refresh path is used instead.
- Drag mode uses a dedicated grip so card movement and grid scrolling have separate gesture start areas.
- Preserves the existing file-safety, undo/redo, conflict-review, and recovery flows from earlier releases.

## Version

- `versionName`: 6.2.3
- `versionCode`: 61
- Package: `com.yuanbao.pairrename`
- `minSdk`: 26 (Android 8.0)
- `targetSdk` / `compileSdk`: 35

## APK and validation

- The signed v6.2.3 APK's package/version metadata was checked against this source snapshot; Android APK Signature Scheme v2 verification passed.
- SHA-256 values for the APK and source archive are in `PairRename-v6.2.3-SHA256SUMS.txt`.
- [GitHub Actions run 37170552174](https://github.com/aWorlding1/pairrename-android/actions/runs/37170552174) passed the source regression suite and Android Debug build for application source commit `46e2b65`. The attached release APK is a separate signed release artifact, not the CI-generated Debug APK; this does not claim a reproducible source-to-binary build.
- Supplied project notes report install-and-interaction checks on a MuMu Android 15 emulator, including repeated drag/drop after the duplicate-key fix. Those are project-reported checks, not exhaustive device or storage-provider coverage.

## Safety

Back up important files independently and test on copies before using new workflows. The in-app history and trash are not backups. Do not share release signing keys or passwords.

## 中文摘要

6.2.3 修复了改名后 Compose 懒加载列表可能遇到重复 key 而崩溃的问题；若增量更新会产生重复 key，则放弃局部回填并执行完整刷新。拖拽模式通过独立把手分离拖放和滚动手势。版本号为 **6.2.3 / versionCode 61**，包名为 `com.yuanbao.pairrename`，最低支持 Android 8.0（API 26）。

随附签名 APK 的包名、版本信息与源码一致，APK Signature Scheme v2 签名验证通过。GitHub Actions 的源码回归与 Android Debug 构建通过，但 Debug 构建不是随附的 Release APK，也不代表完成可复现构建验证。项目提供的记录称已在 MuMu Android 15 模拟器完成交互验证；这不代表覆盖所有设备和存储提供方。
