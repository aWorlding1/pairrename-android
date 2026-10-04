# The PairRename Promise | PairRename 的设计约定

## Our purpose

Photographs rarely stay in one folder. They move from a camera to an editor, from a phone to a backup, and from one archive to another. When those copies have different names, the relationship between them matters.

PairRename exists to make that relationship visible before a batch operation changes it. The aim is simple: turn “Which image belongs with which?” from a guess into a decision a person can inspect and own.

## Principles

### 1. See before changing

Keep both sides of a photo set visible. Let people preview the images and inspect the proposed mapping before files are renamed or organized.

### 2. Treat suggestions as evidence, not authority

Names, sequence, timestamps, EXIF, dimensions, and other signals can help find a match. They are reasons to review—not a substitute for the person's judgment.

### 3. Prefer a safe stop over a confident mistake

When identity, permissions, name conflicts, or a storage provider's behavior is uncertain, explain the uncertainty and avoid silently changing the wrong file. Report partial success honestly.

### 4. Make recovery useful—and describe its limits

Undo, history, and in-app trash can help recover from mistakes where supported. They are not backups. Encourage independent copies before unfamiliar or high-impact operations.

### 5. Respect the local library

PairRename is designed for local use, asks people to choose the folders they want to work with, and should request broad access only when needed and understood.

## A maintainer's review questions

Before accepting a change, ask:

- Can a person see which files will be paired and changed?
- If the evidence is ambiguous or the operation partially fails, is the result clear?
- Could this silently overwrite a file or act on a different folder than the user selected?
- Are tests and release notes precise about what was—and was not—validated?

These principles describe design intent, not a guarantee that every device, Android version, or storage provider behaves identically. Please report edge cases with redacted logs and synthetic files; never upload private photo libraries.

---

## 中文

### 项目想守护的事

照片很少只待在一个目录里：它们会从相机进入修图软件，从手机复制到备份，也会在不同归档之间迁移。当副本的名字不再相同时，图片之间的对应关系就格外重要。

PairRename 希望在批量操作发生前，把这层关系重新「摆到眼前」。它的目标并不复杂：让「哪张对应哪张」不再靠猜，而成为用户看得见、核得清、自己能做主的决定。

### 设计原则

1. **先看见，再改动。** 两侧图片保持可见；真正执行前，让用户预览图片并检查方案。
2. **建议是依据，不是命令。** 文件名、序号、时间、EXIF、尺寸等信息可以帮忙发现候选项，但最终判断仍属于用户。
3. **宁可安全停下，不要自信地改错。** 身份、权限、重名冲突或存储提供方行为不明时，应说明不确定性，避免悄悄改错文件，并如实报告部分成功。
4. **提供恢复路径，也讲清边界。** 撤销、历史和应用内回收站在支持时可以帮忙补救，但它们不是备份。第一次尝试高影响操作前，应保留独立副本。
5. **尊重本地照片库。** PairRename 面向本地整理；用户应能明确选择工作目录，广泛权限也应只在需要且理解时授予。

### 维护者自检

合并改动前，先问自己：用户是否看得见哪些文件将被配对和改动？依据含糊或操作部分失败时，结果是否讲清楚？改动会不会悄悄覆盖文件，或作用到用户没选的目录？测试与发行说明是否准确区分「已验证」和「尚未验证」？

这些原则描述的是设计方向，并不保证所有设备、Android 版本或存储提供方的行为都相同。反馈边缘问题时请使用脱敏日志和合成文件，勿上传私人照片库。
