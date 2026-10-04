#!/usr/bin/env python3
"""Source-level guards for irreversible file-operation hazards.

These assertions are intentionally conservative. They do not replace Android SAF/device tests,
but prevent the known unsafe fallbacks and fail-open paths from silently returning.
"""
from pathlib import Path
import re

ROOT = Path(__file__).parent
SRC = ROOT / "app/src/main/java/com/yuanbao/pairrename"
VM = (SRC / "vm/MainViewModel.kt").read_text(encoding="utf-8")
DOCS = (SRC / "data/DocsRepository.kt").read_text(encoding="utf-8")
TRASH = (SRC / "data/TrashRepository.kt").read_text(encoding="utf-8")
ORG = (SRC / "data/Organizer.kt").read_text(encoding="utf-8")
MODEL = (SRC / "model/ImageItem.kt").read_text(encoding="utf-8")
GRADLE = (ROOT / "app/build.gradle.kts").read_text(encoding="utf-8")
DIALOGS = (SRC / "ui/dialogs/Dialogs.kt").read_text(encoding="utf-8")
SETTINGS = (SRC / "ui/dialogs/SettingsDialog.kt").read_text(encoding="utf-8")
LINT = (ROOT / "lint.sh").read_text(encoding="utf-8")

checks = []


def check(name, condition):
    checks.append((name, bool(condition)))


def body(text, signature, next_marker=None):
    start = text.index(signature)
    if next_marker:
        return text[start:text.index(next_marker, start)]
    return text[start:]


# Exact document identity: a null rename result must never turn into a name-only lookup.
check("renameOrFind is URI-only", re.search(
    r"private fun renameOrFind\([^\n]+\): Uri\?\s*=\s*\n\s*docs\.rename\(item\.docUri, newName, item\.treeUri\)", VM) is not None)
check("rename provider results are rebound to tree permission", "treeUri?.let { documentUriInTree(it, renamed) }" in DOCS)
manual = body(VM, "fun commitManualRename(", "    /** 估算拖放结果名")
check("manual rename returns before Undo on null provider URI", "if (newUri == null)" in manual and manual.index("if (newUri == null)") < manual.index("pushUndo("))
restore_csv = body(VM, "fun restoreFromCsv(", "    /** 生成「按拍摄日期归档」计划")
check("CSV restore simulates mapping in reverse order", "mapping.asReversed()" in restore_csv)
check("CSV restore rejects ambiguity before writes", "candidates.size != 1" in restore_csv and "msg_csv_ambiguous" in restore_csv)
check("CSV restore never falls back to findByName", "findByName" not in restore_csv)

# Delete is recoverable by default. Failure means keep the source, not hard-delete it.
delete = body(VM, "fun deleteChecked(", "    // ---------------- 目录体检")
check("deleteChecked has no hard-delete fallback", "docs.delete(" not in delete and "moveToTrash" in delete)
check("failed delete is reported as retained", "msg_delete_partial" in delete and "failed++" in delete)

# Trash: unique directory, committed recovery metadata first, verified copy, no overwrite.
check("trash IDs are unique and directory reservation is atomic", "UUID.randomUUID()" in TRASH and "dir.mkdir()" in TRASH)
check("trash recovery metadata is fsynced before movement", TRASH.index("stream.fd.sync()") < TRASH.index("Files.move(src.toPath(), dst.toPath())"))
check("trash cross-volume copy is exclusive and size-verified", "dst.createNewFile()" in TRASH and "dst.length() != expectedSize" in TRASH)
check("trash restore refuses existing target", "if (dst.exists()) return RestoreResult.OCCUPIED" in TRASH)

# SAF copy/move reports success only when I/O and source deletion succeeded.
check("stream copy requires both streams and checks known byte count", "Provider returned no input stream" in DOCS and "Provider returned no output stream" in DOCS and "byteCount != expectedSize" in DOCS)
move = body(DOCS, "fun moveTo(", "    /** 把 provider 返回的裸 document URI")
check("SAF move preflights target names", "knownTargetNames" in move and "ignoreCase = true" in move)
check("SAF move only reports copy fallback after successful source delete", "if (delete(sourceUri)) return" in move and "delete(copied)" in move)
check("SAF returned URI is scoped to destination tree", "documentUriInTree(targetTree, it)" in move)

# SAF document moves are represented as reversible operations, not CreatedFile deletions.
check("undo model counts document moves", "documentMoves" in MODEL and "documentMoves.size" in MODEL)
check("cross-pane move records DocumentMoveStep", "DocumentMoveStep(" in body(VM, "fun moveCheckedTo(", "    /** 删除勾选的文件"))
check("undo and redo move using current URI and the opposite tree", "docs.moveTo(step.currentUri, step.fromTree, step.toTree)" in VM and "docs.moveTo(step.currentUri, step.toTree, step.fromTree)" in VM)

# Protect against app-internal interleaving and host-side target replacement.
check("file mutation entry points share Mutex", "fileMutationMutex.withLock" in VM and "private fun mutationIo" in VM)
check("stale refresh requests cannot switch a pane back", "if (!allowFolderChange && previous != uri) return" in VM)
check("folder scan commits require matching generation and URI", "updatePaneForLoad(side, uri, generation)" in VM and "isCurrentFolderLoad(side, uri, generation)" in VM)
check("clear/swap invalidate pending folder scans", "invalidateFolderLoad(side)" in VM and "invalidateFolderLoad(Side.LEFT)" in VM and "invalidateFolderLoad(Side.RIGHT)" in VM)
check("startup restore and user folder selection use mutation queue", "mutationIo { restoreFolders() }" in VM and "load(side, uri, allowFolderChange = true)" in VM)
check("path moves use no-replace Files.move", "Files.move(src.toPath(), dst.toPath())" in ORG)
check("path copy verifies bytes and checks source deletion", "dst.length() != expectedSize" in ORG and "src.delete()" in ORG)

# Destructive overwrite and unsigned releases fail closed.
check("overwrite is absent from conflict/settings UI", "onChoose(ConflictPolicy.OVERWRITE)" not in DIALOGS and "settings.copy(conflictPolicy = ConflictPolicy.OVERWRITE)" not in SETTINGS)
check("legacy overwrite policy is rejected", "msg_overwrite_unsafe" in VM)
check("release APK/AAB packaging requires signed keystore", "name == \"packageRelease\" || name == \"bundleRelease\"" in GRADLE and "releaseSigning.storeFile?.isFile != true" in GRADLE)
check("lint propagates source-verification failure", 'if "$ROOT/verify_all.sh"; then' in LINT and 'VERIFY_EXIT=$status' in LINT and 'exit "$status"' in LINT)

# v6.2.3 真机回归：同目录改名会让两个条目映射到同一 URI，
# 而 LazyVerticalGrid 以 docUri 为 key，重复 key 直接抛 IllegalArgumentException 崩溃。
# 必须在写回列表前检出重复 key 并退给 refreshBoth 兜底。
def _fn(src, start, end):
    a = src.index(start)
    b = src.index(end, a)
    return src[a:b]


_patch = _fn(VM, "private fun patchAfterRename", "private fun refreshBoth()")
check("patchAfterRename refuses to write duplicate item keys",
      "seenKeys" in _patch
      and "return@updatePane pane" in _patch
      and _patch.index("seenKeys") < _patch.index("pane.copy(items = next)"))

failed = [name for name, ok in checks if not ok]
for name, ok in checks:
    print(f"[{'PASS' if ok else 'FAIL'}] {name}")
print(f"\n{len(checks) - len(failed)}/{len(checks)} critical safety guards passed")
if failed:
    raise SystemExit(1)
