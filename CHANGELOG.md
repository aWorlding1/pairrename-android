# Changelog

Notable changes are recorded here. Version details below describe the supplied v6.2.3 source snapshot; earlier history is summarized rather than reproduced from internal handoff notes.

## 6.2.3 — 2026-10-04

- Fixed a duplicate lazy-list key crash that could occur after rename when both panes resolve to the same document URI. If an incremental update would create duplicate keys, PairRename now abandons that patch and falls back to a full refresh.
- Verified the affected drag/drop flow repeatedly on the project's reported MuMu Android 15 emulator setup.
- Kept drag initiation on a dedicated grip in drag mode so the image grid can scroll independently.

## 6.2.2

- Added a dedicated drag grip in drag mode, separating the gesture start area from normal card scrolling.

## 6.2.1

- Refined drag gesture direction handling and mode state so vertical scrolling remains available and the toggle can be turned off reliably.

## 6.2.0

- Hardened rename, undo/redo, batch operations, trash/recovery, and concurrent file operations; avoid guessing file identity after a provider error.
- Improved conflict preflight and recovery behavior for storage-provider operations.

## 中文摘要

- **6.2.3**：修复改名后可能出现的 Lazy 列表重复 key 崩溃；检测到增量更新会产生重复 key 时，放弃局部回填并改为完整刷新。项目记录称已在 MuMu Android 15 模拟器反复验证拖放链路。
- **6.2.2**：拖拽模式使用独立把手，降低拖拽与列表滚动的手势冲突。
- **6.2.1**：改进手势方向处理与模式开关状态。
- **6.2.0**：加强改名、撤销/重做、批量操作、回收恢复和并发文件操作的安全处理。
