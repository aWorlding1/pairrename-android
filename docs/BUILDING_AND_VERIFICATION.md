# Building and verifying PairRename

This guide explains what can be checked from source and what the current signed Release APK does—and does not—prove.

## Build the source

The repository's CI currently uses JDK 17, Android SDK platform 35, Build Tools 35.0.0, and the Gradle 8.9 wrapper. Use the checked-in wrapper so the Gradle version is selected by the repository:

```bash
git clone https://github.com/aWorlding1/pairrename-android.git
cd pairrename-android
git rev-parse HEAD
java -version
./gradlew --version
./gradlew :app:assembleDebug --no-daemon
./verify_all.sh
```

The Debug APK is written to `app/build/outputs/apk/debug/`. The source-level checks do not require an Android SDK, but they are not a substitute for the Android build or device/storage-provider testing. See the [CI workflow](../.github/workflows/ci.yml) for the currently exercised toolchain and tasks.

To build a Release variant locally, supply your own signing key through the documented ignored properties file or environment variables. Do not request, publish, or copy the maintainer's private signing key. A build signed with your own key is not update-compatible with an APK signed by a different key.

## Verify the published v6.2.3 APK

Download the APK, source ZIP, and checksum manifest from the [v6.2.3 GitHub Release](https://github.com/aWorlding1/pairrename-android/releases/tag/v6.2.3) into one directory. Then run:

```bash
sha256sum -c PairRename-v6.2.3-SHA256SUMS.txt
apksigner verify --verbose --print-certs PairRename-v6.2.3-Android.apk
aapt dump badging PairRename-v6.2.3-Android.apk | grep -E '^(package|sdkVersion|targetSdkVersion):'
```

The package should be `com.yuanbao.pairrename`, with `versionName 6.2.3` and `versionCode 61`. A successful checksum confirms downloaded bytes match the published manifest. `apksigner` checks cryptographic signature validity; to establish that a certificate belongs to the expected publisher, compare its fingerprint with a separately trusted, previously verified fingerprint. A filename alone proves neither version nor signer.

## What is not yet claimed

The published APK's signature and package/version metadata were checked, and the repository's CI source-regression and Android Debug-build jobs passed. The CI Debug APK is not the signed Release APK attached to the Release. These checks do **not** establish that an independent build from source produces byte-for-byte identical Release APK contents.

PairRename does not currently claim a reproducible source-to-binary build. The private Release signing key is intentionally not included, and publishing it would be unsafe. Reproducibility would require a documented, pinned dependency/toolchain environment and repeated independent builds whose outputs are compared under a clearly defined signing model. Until that evidence exists, use the wording above rather than calling the Release APK reproducible.

## 中文

本指南说明如何从源码构建 PairRename，以及当前签名 Release APK 的核验结果能证明什么、不能证明什么。

### 从源码构建

仓库 CI 当前使用 JDK 17、Android SDK platform 35、Build Tools 35.0.0，以及项目内的 Gradle 8.9 Wrapper。请使用仓库自带的 Wrapper：

```bash
git clone https://github.com/aWorlding1/pairrename-android.git
cd pairrename-android
git rev-parse HEAD
java -version
./gradlew --version
./gradlew :app:assembleDebug --no-daemon
./verify_all.sh
```

Debug APK 输出到 `app/build/outputs/apk/debug/`。源码级检查不需要 Android SDK，但不能代替 Android 编译、真机测试或存储提供方测试。当前实际验证的工具链与任务见 [CI 工作流](../.github/workflows/ci.yml)。

如需本地构建 Release 变体，请使用你自己的签名密钥，并按项目示例配置被 Git 忽略的属性文件或环境变量。不要索要、复制或公开维护者的私钥。用自己的密钥签出的 APK，不能覆盖安装由其他密钥签名的版本。

### 核验已发布的 v6.2.3 APK

从 [v6.2.3 GitHub Release](https://github.com/aWorlding1/pairrename-android/releases/tag/v6.2.3) 把 APK、源码 ZIP 和校验清单下载到同一目录，然后运行：

```bash
sha256sum -c PairRename-v6.2.3-SHA256SUMS.txt
apksigner verify --verbose --print-certs PairRename-v6.2.3-Android.apk
aapt dump badging PairRename-v6.2.3-Android.apk | grep -E '^(package|sdkVersion|targetSdkVersion):'
```

包名应为 `com.yuanbao.pairrename`，`versionName` 为 `6.2.3`，`versionCode` 为 `61`。校验和成功表示下载字节与公开清单吻合；`apksigner` 检查签名在密码学上是否有效。若要确认签名证书属于预期发布者，还需要将指纹与独立可信、此前已核验的指纹比较。文件名本身不能证明版本或发布者。

### 当前尚未声称的事项

已检查发布 APK 的签名有效性和包名/版本元数据；仓库 CI 的源码回归与 Android Debug 构建任务已通过。CI 生成的 Debug APK 并不是 Release 页面附带的签名 APK。这些检查**不能证明**他人从源码独立构建出的 Release APK 与发布文件逐字节一致。

PairRename 目前不声称具备「源码到二进制的可复现构建」。Release 私钥有意不包含在仓库中，公开它是不安全的。要证明可复现，还需要文档化且固定的依赖/工具链环境，以及在明确签名模型下进行多次独立构建并比较产物。在证据充分前，请使用上述准确表述，不要把 Release APK 称为可复现构建。
