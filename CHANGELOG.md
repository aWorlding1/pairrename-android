# Changelog

This changelog highlights user-facing changes. Release-specific compatibility and validation details are recorded in the versioned release notes; older versions remain available in their original notes.

## 6.2.4 — 2026-10-04

- Enabled R8 code minification and resource shrinking for Release builds, with an additional keep rule for persisted enum members. The supplied v6.2.4 APK measures 2.03 MiB versus 11.91 MiB for v6.2.3 (about 83% smaller). Its package/version metadata and APK Signature Scheme v2 signature were verified; its signer certificate matches the publicly released v6.2.3 APK. This is not a reproducible source-to-binary build.
- CI passed source regression checks, the Android Debug build, and the Release resource-shrinking task without a signing key; see [the verified run](https://github.com/aWorlding1/pairrename-android/actions/runs/37195008426).
- Added the bilingual versioned [v6.2.4 release notes](RELEASE_NOTES-v6.2.4.md) and linked documentation for the current build and verification limits.

## Documentation updates — 2026-10-04

- Added a documentation index, a non-binding roadmap, a build/verification guide, a clearly labelled workflow illustration, and a user-provided capture of the app's Chinese empty state. These documentation updates do not change the v6.2.3 APK or its release assets; a populated pairing-session capture is still pending.

## 6.2.3 — 2026-10-04

- Fixed a duplicate lazy-list key crash that could occur after rename when both panes resolve to the same document URI. If an incremental update would create duplicate keys, PairRename now abandons that patch and falls back to a full refresh.
- Project-reported verification: the affected drag/drop flow was exercised repeatedly on a MuMu Android 15 emulator setup.
- Kept drag initiation on a dedicated grip in drag mode so the image grid can scroll independently.

## 6.2.2

- Added a dedicated drag grip in drag mode, separating the gesture start area from normal card scrolling.

## 6.2.1

- Refined drag gesture direction handling and mode state so vertical scrolling remains available and the toggle can be turned off reliably.

## 6.2.0

- Hardened rename, undo/redo, batch operations, trash/recovery, and concurrent file operations; avoid guessing file identity after a provider error.
- Improved conflict preflight and recovery behavior for storage-provider operations.

## 中文摘要

- **6.2.4（2026-10-04）**：Release 构建开启 R8 代码压缩与资源收缩，并增加持久化枚举成员的保留规则。随包 v6.2.4 APK 为 2.03 MiB，v6.2.3 为 11.91 MiB（约缩小 83%）；已核对包名/版本元数据、APK Signature Scheme v2 签名有效性，且证书与公开发布的 v6.2.3 APK 一致。这不是源码到二进制的可复现构建。CI 的源码回归、Android Debug 构建和无需签名密钥的 Release 资源收缩任务均已通过；详见[运行记录](https://github.com/aWorlding1/pairrename-android/actions/runs/37195008426)。另新增双语[v6.2.4 发行说明](RELEASE_NOTES-v6.2.4.md)。
- **文档更新**：新增文档索引、非承诺式路线图、构建/核验指南、明确标注的流程示意图，以及用户提供的中文空状态应用截图；展示已选图片并完成配对的截图仍待补充。
- **6.2.3**：修复改名后可能出现的 Lazy 列表重复 key 崩溃；检测到增量更新会产生重复 key 时，放弃局部回填并改为完整刷新。项目提供的记录称已在 MuMu Android 15 模拟器反复验证拖放链路。
- **6.2.2**：拖拽模式使用独立把手，降低拖拽与列表滚动的手势冲突。
- **6.2.1**：改进手势方向处理与模式开关状态。
- **6.2.0**：加强改名、撤销/重做、批量操作、回收恢复和并发文件操作的安全处理。
