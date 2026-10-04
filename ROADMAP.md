# Roadmap

PairRename's direction is to make photo relationships visible, file changes reviewable, and uncertainty safe to stop on. This page describes priorities—not delivery promises. It has no promised dates; the changelog and release notes remain the record of shipped work.

## Current focus

- Preserve the safe review path around matching, rename plans, conflicts, partial failures, and recovery.
- Improve compatibility from reproducible reports involving Android versions and document/storage providers; avoid claiming coverage that has not been tested.
- Keep onboarding and release evidence understandable, especially the distinction between source checks, Debug builds, signed Release APKs, and device testing.

## Exploring—not committed

- Capture genuine app screenshots and a short walkthrough from a running app using synthetic demo photos. Do not present a mockup or workflow diagram as an app screenshot.
- Improve source-to-binary verification, including dependency verification and repeat-build comparisons, before making any reproducibility claim.
- Add deeper task-oriented or versioned web documentation if the documentation volume and release matrix make a separate site worth maintaining. For now, the README and versioned repository files are the canonical docs.

## How to influence the direction

Use the [feature-request form](https://github.com/aWorlding1/pairrename-android/issues/new?template=feature_request.yml) to describe the problem and workflow. Reproducible bug reports are especially useful. Priorities may change as evidence and community feedback come in; an idea listed here is not a commitment or a guarantee that it will be implemented.

---

## 中文

PairRename 希望让图片之间的对应关系看得见、文件改动能复核、不确定时可以安全停下。本页记录的是维护方向，不是交付承诺；目前不承诺具体日期。已发布的改动以[变更记录](CHANGELOG.md)和各版本的发行说明为准。

### 当前重点

- 持续维护配对、改名方案、冲突、部分失败与恢复流程中的安全边界。
- 根据可复现的问题改进 Android 版本与文档/存储提供方兼容性；不宣称未实际测试过的覆盖范围。
- 让上手说明和发行证据更容易理解，尤其要区分源码检查、Debug 构建、签名 Release APK 与设备测试。

### 正在探索，尚未承诺

- 在真实运行的应用中，用合成演示照片拍摄界面截图和短演示；概念稿或流程图不能冒充应用截图。
- 进一步核验源码与发布二进制之间的关系，例如依赖校验与干净环境重复构建；完成验证前不宣称「可复现构建」。
- 当文档数量和版本矩阵足以支撑维护时，再考虑独立的任务型或版本化文档站。目前以 README 和随版本管理的仓库文档为准。

### 如何影响项目方向

请使用[功能建议表单](https://github.com/aWorlding1/pairrename-android/issues/new?template=feature_request.yml)，说明遇到的问题和实际流程；可复现的问题尤其有帮助。方向会根据证据和社区反馈调整。列出的设想不代表承诺实现，也不保证一定会纳入项目。
