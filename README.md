# PairRename — 双栏图片改名器

**Compare two image folders side by side. Match the right photos, then rename with confidence.**  
**左右并排对照两组图片，快速配对，让批量改名更安心。**

PairRename is an open-source Android image workflow tool for people who need to reconcile two copies of a photo set—for example, camera originals and edited exports. Browse both folders in a two-pane view, inspect likely matches, and bring filenames into sync without losing sight of the images.

PairRename 是一款开源 Android 图片整理工具，适合整理相机原片与编辑导出件等两组照片。左右双栏并排浏览、核对候选配对，再把文件名统一起来；整个过程都能看见图片本身。

> **Current source snapshot: v6.2.3 · versionCode 61 · Android 8.0+ (API 26+)**  
> Source and signed APK downloads: [GitHub Releases](https://github.com/aWorlding1/pairrename-android/releases).

## Why PairRename?

Ordinary bulk renamers make it easy to lose track of *which* image is being renamed. PairRename keeps both sides visible and offers several ways to identify corresponding images:

- **Two-pane visual comparison** — browse two user-selected folders together; preview images and inspect file details.
- **Flexible matching** — pair by tapping, drag-and-drop, or order; review suggestions based on filenames, numbering, capture time/EXIF, image dimensions, and other available evidence.
- **Batch workflows** — rename by a confirmed pairing, use naming templates, and export/import supported plans or reports.
- **A safety-first editing flow** — review conflicts before applying changes; keep an operation history with undo/redo; use the in-app trash and recovery tools where supported by the selected storage provider.
- **Practical controls** — filters, sorting, selection tools, duplicate/mismatch review, and tablet/keyboard-friendly navigation.

Feature availability can depend on Android and the storage provider. Always review the proposed mapping and test unfamiliar workflows on copies first.

## Get started

### Use the app

1. Install a PairRename build on Android 8.0 (API 26) or later.
2. Open the app and choose the left and right folders through Android's folder picker; grant access to the folders you intend to manage.
3. Compare thumbnails and names. Pair images by tapping, dragging, or aligning by order; inspect suggested matches before accepting them.
4. Review the proposed rename/organize operation and any conflicts, then apply it. Try undo/history on a small test folder before using a new workflow on important files.

Download the signed release APK from [GitHub Releases](https://github.com/aWorlding1/pairrename-android/releases) and verify its SHA-256 checksum before installing. You can also build a debug APK using the instructions below. Do not install APKs from sources you do not trust.

### Build from source

Requirements: Android Studio or Android SDK, JDK 17, and the Android SDK platform/build tools for API 35. The Gradle wrapper downloads Gradle 8.9 on first use.

```bash
git clone https://github.com/aWorlding1/pairrename-android.git
cd pairrename-android
./gradlew :app:assembleDebug
```

The debug APK is written to `app/build/outputs/apk/debug/`. On Windows, use `gradlew.bat :app:assembleDebug`.

To create a signed release build, provide **your own** signing key through the ignored `app/keystore.properties` file or the `PAIRRENAME_*` environment variables described in `app/keystore.properties.example`. Never commit or share a private signing key or its passwords. Release packaging intentionally fails if signing details are missing. A build signed with your key is not update-compatible with builds signed by another key.

### Run the source-level regression checks

The checks below do not require an Android SDK or Gradle; they inspect source invariants and regression cases. They are not a substitute for compiling, running on Android, or testing every storage provider.

```bash
bash verify_all.sh
```

## Version and validation

The source snapshot is **v6.2.3 (versionCode 61)**. The project-supplied notes report installation and interaction checks on a MuMu Android 15 emulator, including the dedicated drag grip, scrolling while drag mode is active, and repeated drag/drop without the duplicate-list-key crash fixed in this version. These are project-reported checks, not a claim that every Android device or document provider has been tested.

The source-level regression suite and an Android Debug build both passed in [GitHub Actions run 37170552174](https://github.com/aWorlding1/pairrename-android/actions/runs/37170552174). The separate v6.2.3 release APK was verified with Android `apksigner` (APK Signature Scheme v2); its package and version metadata match this source snapshot. This verifies signature integrity and metadata, not reproducible source-to-binary equivalence or every device/storage-provider workflow.

## Privacy and file safety

- The manifest does not request the Android `INTERNET` permission. The app is designed to work locally and does not require an account or cloud service.
- Folder access is granted by you through Android's system picker. The app also declares optional `MANAGE_EXTERNAL_STORAGE` access; grant broad access only if you understand and need it. Basic folder workflows should use the narrowest access Android allows.
- Renaming and organizing files changes data in the folders you select. Review each operation, keep an independent backup of important files, and test on copies first. In-app history/trash are convenience safeguards, not backups.
- App-private history, settings, and work state may be removed when the app is uninstalled or its data is cleared.
- For a security concern, see [SECURITY.md](SECURITY.md). Do not include private photos or sensitive personal data in a public issue.

## Contributing

Bug reports, thoughtful feature requests, documentation improvements, and pull requests are welcome. Start with [CONTRIBUTING.md](CONTRIBUTING.md), use the issue templates, and include reproducible steps without sharing private images or folder contents.

## License

PairRename is released under the [MIT License](LICENSE). Third-party dependencies remain subject to their own licenses.

---

# PairRename 中文说明

PairRename 面向需要整理**两套对应图片**的人：例如原片与修图导出件、手机照片与备份副本。双栏同时显示缩略图与文件名，配合点选、拖拽、顺序对齐和配对建议，帮助你确认“哪张对应哪张”之后再统一命名。

### 核心能力

- 左右双栏浏览、图片预览与文件信息查看。
- 点选、拖放、按顺序配对；依据文件名、序号、拍摄时间/EXIF、图片尺寸等信息辅助核对。
- 批量改名与命名模板；支持的方案/报告可导入或导出。
- 执行前检查冲突；提供撤销/重做、操作历史，以及受存储提供方能力影响的回收与恢复流程。
- 筛选、排序、多选、重复/差异核查，以及适合平板和键盘的操作方式。

不同 Android 版本和存储提供方的能力可能不同。第一次使用某个流程时，请先在**副本**上验证。

### 编译

环境要求：Android Studio 或 Android SDK、JDK 17、API 35 Android SDK 平台与构建工具。首次运行 Gradle Wrapper 会下载 Gradle 8.9。

```bash
git clone https://github.com/aWorlding1/pairrename-android.git
cd pairrename-android
./gradlew :app:assembleDebug
```

调试 APK 输出在 `app/build/outputs/apk/debug/`。Windows 可运行 `gradlew.bat :app:assembleDebug`。

**Release APK 可从 [GitHub Releases](https://github.com/aWorlding1/pairrename-android/releases) 下载。** 自行编译 Release 包时必须使用你自己的签名密钥；请参照 `app/keystore.properties.example`，勿将签名密钥或密码提交到 Git。

### 隐私与文件安全

- Manifest 未申请 Android `INTERNET` 权限；应用以本地使用为目标，不依赖账号或云服务。
- 通过 Android 系统文件夹选择器授权；应用还声明了可选的“管理所有文件”权限。只有确实需要且理解其影响时才授予广泛权限。
- 改名和整理会修改你选择的目录。请先核对方案、备份重要资料，并先用副本测试。应用内历史和回收站不能替代独立备份。
- 卸载应用或清除数据可能移除应用私有的历史、设置和工作状态。
- 贡献和问题反馈方式见 [CONTRIBUTING.md](CONTRIBUTING.md)；安全问题请见 [SECURITY.md](SECURITY.md)。公开反馈中请勿上传私人照片。

版本：**v6.2.3 · versionCode 61 · Android 8.0+（API 26+）**。  
许可：**MIT**；第三方依赖仍受各自许可约束。
