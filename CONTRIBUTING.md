# Contributing to PairRename | 参与 PairRename

Thank you for helping make photo matching clearer and file operations safer. PairRename aims to keep image pairings reviewable and proposed changes understandable. Focused fixes, careful compatibility work, and clear documentation are all valuable.

Thanks for helping the project live up to that aim. See [Project Principles](docs/PROJECT_PRINCIPLES.md) for the design commitments behind the workflow.

## Before opening an issue

- Search existing issues and choose the bug-report or feature-request template.
- For a bug, include the PairRename version, Android version, device/storage provider, exact steps, expected result, actual result, and relevant logs with personal details removed.
- Never attach private photos, full folder listings, access tokens, signing keys, or passwords. Use synthetic files and redacted logs.

## Before sending a pull request

1. Explain the user problem and the intended behavior.
2. Keep the change focused; avoid bundling unrelated refactors.
3. For file operations, preserve safety properties: verify file identity rather than guessing by filename, never overwrite user data silently, report partial failures clearly, and keep undo/history semantics coherent.
4. Add or update a regression check for a bug fix when practical.
5. Run `./verify_all.sh` and include the result. If you have the Android SDK, also build and exercise the affected flow on a device/emulator using test copies.
6. Update user-facing documentation in both languages when behavior or instructions change.

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
- [ ] Documentation and translations are updated when needed.

---

## 中文贡献指南

感谢你帮助 PairRename 让图片配对更清楚、文件操作更安全。项目希望用户能复核图片之间的对应关系，也能在执行前看懂即将发生的改动。聚焦明确的修复、谨慎的兼容性改进和清楚的文档都很有价值。设计约定见[项目原则](docs/PROJECT_PRINCIPLES.md)。

### 提交问题前

- 先搜索现有问题，并选择「问题反馈」或「功能建议」模板。
- Bug 报告请说明 PairRename 版本、Android 版本、设备/存储提供方、准确复现步骤、预期结果、实际结果，以及已脱敏的相关日志。
- 请勿上传私人照片、完整目录清单、访问令牌、签名密钥或密码；请使用合成文件和脱敏日志。

### 提交 Pull Request 前

1. 说明用户遇到的问题和预期行为。
2. 保持改动聚焦，避免顺带进行无关重构。
3. 文件操作必须核实文件身份，不能仅凭文件名猜测；不得静默覆盖用户数据；部分失败时要如实说明，并保持撤销/历史语义一致。
4. 条件允许时，为 Bug 修复添加或更新回归检查。
5. 运行 `./verify_all.sh` 并附上结果。有 Android SDK 时，也请用测试副本构建并在设备或模拟器上验证相关流程。
6. 行为或操作说明有变化时，记得同步更新中英文文档。

### 开发环境

Android SDK 平台/构建工具 API 35、JDK 17、Gradle Wrapper 8.9；最低支持 Android API 26。可用 `./gradlew :app:assembleDebug` 构建本地调试 APK。Release 构建必须使用你自己的签名密钥；请勿提交签名材料或本机配置。
