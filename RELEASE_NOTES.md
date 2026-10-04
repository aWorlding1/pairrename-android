# PairRename v6.2.3 — 2026-10-04

## Highlights

- Fixed a duplicate Compose lazy-list key crash that could occur after a rename when document URIs converge. If an incremental pane update would introduce duplicate keys, it is discarded and the existing full-refresh path is used instead.
- Drag mode uses a dedicated grip so card movement and grid scrolling have separate gesture start areas.
- Preserves the existing file-safety, undo/redo, conflict-review, and recovery flows from earlier releases.

## Version

- `versionName`: 6.2.3
- `versionCode`: 61
- `minSdk`: 26 (Android 8.0)
- `targetSdk` / `compileSdk`: 35

## Validation status

The supplied project notes report an install-and-interaction pass on a MuMu Android 15 emulator, including repeated drag/drop after the duplicate-key fix. The repository includes source-level regression scripts (`bash verify_all.sh`). This release note does not claim a fresh Android SDK build or exhaustive testing across devices and storage providers in this environment.

## Safety

Back up important files independently and test on copies before using new workflows. The in-app history and trash are not backups. Release builds require the user's own signing key; never distribute it with the source.

## 中文摘要

6.2.3 修复了改名后 Lazy 列表可能遇到重复 key 而崩溃的问题；若增量更新会生成重复 key，则放弃局部回填并执行完整刷新。拖拽模式通过独立把手分离拖放和滚动手势。版本号为 **6.2.3 / versionCode 61**，最低支持 Android 8.0（API 26）。

项目提供的记录称已在 MuMu Android 15 模拟器完成安装与交互验证；源码回归脚本可通过 `bash verify_all.sh` 运行。本说明不代表当前环境重新完成 Android SDK 构建，也不代表已覆盖所有设备和存储提供方。
