# PairRename v6.2.4 — R8 体积优化 / Smaller Release

**Date:** 2026-10-04  
**Tag:** `v6.2.4`  
**Package:** `com.yuanbao.pairrename`  
**Android:** 8.0+ (API 26+)

## Highlights

- Enabled R8 code minification and resource shrinking for Release builds; added a defensive keep rule for enum members whose names are persisted.
- The supplied signed APK is **2,125,574 bytes (2.03 MiB)**, compared with **12,491,727 bytes (11.91 MiB)** for v6.2.3—an **82.98% reduction**.
- No Kotlin application source changed in this update; the change focuses on the Release build configuration and shrinker rules.

## Version and verification

- `versionName`: `6.2.4`
- `versionCode`: `62`
- `minSdk`: `26`; `targetSdk` / `compileSdk`: `35`
- APK package/version metadata match the source configuration.
- Android APK Signature Scheme v2 verification passed. The signer certificate SHA-256 fingerprint is `991b124f5b8ced705a552b128fd93b27444de21b7415f0eb472935fbdf61fc35`, matching the previously published v6.2.3 APK.
- GitHub Actions run [37195008426](https://github.com/aWorlding1/pairrename-android/actions/runs/37195008426) passed the source regression checks, Android Debug build, and Release resource-shrinking task. CI does not use the maintainer's signing key and does not produce the attached Release APK.
- Project-supplied handoff notes report an upgrade install over v6.2.3 with user data retained and interaction checks on a MuMu Android 15 emulator. These results are attributed to the project and were not independently reproduced in this release workflow.

> Signature and metadata checks are not a reproducible source-to-binary build. The attached APK was supplied as a project artifact; this workflow did not independently rebuild it from the tagged source.

## Download and safety

Download `PairRename-v6.2.4-Android.apk` and verify it against `PairRename-v6.2.4-SHA256SUMS.txt` before installation. Back up important photos independently and test new workflows on copies first. In-app history and trash are not backups.

## 中文说明

v6.2.4 的重点是让 Release 安装包更轻，而不是增加 Kotlin 功能：Release 构建启用 R8 代码压缩与资源收缩，并为会持久化枚举名称的成员增加保护规则。本次更新没有修改 Kotlin 应用源码。

- 版本：`6.2.4`（`versionCode 62`），包名：`com.yuanbao.pairrename`
- 最低支持 Android 8.0（API 26）；`targetSdk` / `compileSdk` 为 35。
- 随附签名 APK 大小为 **2,125,574 字节（2.03 MiB）**；v6.2.3 为 **12,491,727 字节（11.91 MiB）**，缩小 **82.98%**。
- 已核对 APK 包名/版本元数据；APK Signature Scheme v2 验证通过。签名证书 SHA-256 指纹为 `991b124f5b8ced705a552b128fd93b27444de21b7415f0eb472935fbdf61fc35`，与此前公开发布的 v6.2.3 APK 一致。
- [GitHub Actions 运行 37195008426](https://github.com/aWorlding1/pairrename-android/actions/runs/37195008426) 的源码回归、Android Debug 构建和 Release 资源收缩任务全部通过。CI 不使用维护者的签名私钥，也没有生成此次 Release 附带的 APK。
- 项目随附交接说明称，v6.2.4 曾覆盖安装 v6.2.3 并保留用户数据，且在 MuMu Android 15 模拟器进行了交互检查。这些是项目提供的记录，本次发布流程未独立复现。

> 签名与版本元数据核验不等于「源码到二进制的可复现构建」。本次 APK 是项目提供的发行文件；本工作流没有从 tag 源码独立重建该 APK。

下载 `PairRename-v6.2.4-Android.apk` 后，请先用同一发布页中的 `PairRename-v6.2.4-SHA256SUMS.txt` 校验。重要照片请独立备份；新流程先在副本上测试。应用内历史和回收站不能替代备份。
