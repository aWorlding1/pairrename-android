"""每句话只留一份：用户可见文案的「单一事实源」门禁。

## 为什么需要这道门禁

v5.16.0 把「撤销列表那行字」钉住之后，顺手扫了一遍全仓，发现一件更基础的事：
**同一句用户可见的话，仓库里存了两份** —— 一份在 `strings.xml`，
一份硬编码在 Kotlin 里。当时统计到 21 处完全同文，另有 lint 报出的 9 条
「定义了但没人引用」的孤儿资源。

**这不是风格问题，是会出错的。** 证据：`exif_hint` 的两份已经漂了 ——
资源写「相册导出、**微信**传输后……」，界面上写的却是「相册导出、**聊天工具**传输后……」。
没有人知道哪份是想要的，因为**两份都不权威**。

## 两条不变量

    A. 同文两份 = 0 —— Kotlin 里任何一个中文字符串字面量，
       都不等于 strings.xml 里某个条目的值。
       （lint 的 UnusedResources 只能看见"引用数为 0"的那部分；
        本脚本看不见 lint 看不见的那部分：引用了 A，同时又在 B 处复制了一份同文。）
    B. 孤儿资源 = 0 —— strings.xml 里每个条目都至少被引用一次
       （源码里的 R.string.X，或资源 XML 里的 @string/x）。

两条合起来 = 每句话在仓库里恰好存在一份。

## 检查器自己也要被验证

第一版扫描把 KDoc 里引用的词（`找出"没配对的""名字有问题的"`）当成了用户可见文案，
多报 4 条假阳性。原因是只按 `//` 切行，没剥块注释。
**一个会误报的检查器结局只有一个 —— 被关掉。**
所以这里用小状态机逐字符剥离 `//` 与 `/* */`（含字符串字面量内的引号），
并留了一组反例把两种误判都钉在测试里：注释里的引号必须**不算**，
真字面量必须**算**。
"""
import pathlib
import re

BASE = pathlib.Path(__file__).parent
SRC = BASE / "app/src/main/java/com/yuanbao/pairrename"
RES_DIR = BASE / "app/src/main/res"
STRINGS = (RES_DIR / "values/strings.xml").read_text(encoding="utf-8")

CJK = re.compile("[\u4e00-\u9fff]")
STR_LIT = re.compile(r'"((?:[^"\\]|\\.)*)"')

RES = dict(re.findall(r'<string name="([^"]+)">(.*?)</string>', STRINGS, re.S))

n_fail = 0


def check(label, cond):
    global n_fail
    print(f"  [{'OK' if cond else 'FAIL'}]   {label}")
    if not cond:
        n_fail += 1


# ---------------------------------------------------------------- 注释剥离状态机

def code_only(text):
    """逐行返回「去掉注释后的代码」。块注释跨行，状态带出循环。

    字符串字面量内的 `//` / `/*` 是内容，不是注释 —— 必须整体保留。
    """
    out, in_block = [], False
    for line in text.split("\n"):
        j, keep = 0, ""
        while j < len(line):
            if in_block:
                k = line.find("*/", j)
                if k < 0:
                    j = len(line)
                else:
                    in_block, j = False, k + 2
                continue
            if line.startswith("//", j):
                break
            if line.startswith("/*", j):
                in_block, j = True, j + 2
                continue
            ch = line[j]
            if ch == '"':
                k, esc = j + 1, False
                while k < len(line):
                    if esc:
                        esc = False
                    elif line[k] == "\\":
                        esc = True
                    elif line[k] == '"':
                        break
                    k += 1
                keep += line[j:k + 1]
                j = k + 1
                continue
            if ch == "'":
                k = line.find("'", j + 1)
                j = (k + 1) if k >= 0 else j + 1
                continue
            keep += ch
            j += 1
        out.append(keep)
    return out


def chinese_literals(path):
    """文件里真正的中文字面量（行号, 值），注释里的引号不算。"""
    out = []
    for i, line in enumerate(code_only(path.read_text(encoding="utf-8")), 1):
        for m in STR_LIT.finditer(line):
            v = m.group(1)
            if CJK.search(v) and len(v) >= 4:
                out.append((i, v))
    return out


# ---------------------------------------------------------------- 不变量 A

print("=" * 64)
print("A. 同文两份 = 0（同一句话不在 resources 里存第二份）")
print("=" * 64)

dups = []
n_lit = 0
for p in sorted(SRC.rglob("*.kt")):
    for i, v in chinese_literals(p):
        n_lit += 1
        if v in RES:
            dups.append((str(p.relative_to(BASE)), i, v))

print(f"  扫过 {n_lit} 条中文字面量（≥4 字），strings.xml {len(RES)} 条")
check("没有任何字面量与 strings.xml 条目同文", not dups)
for f, i, v in dups:
    owner = [k for k, val in RES.items() if val == v]
    print(f"      ✗ {f}:{i}  「{v}」 与 R.string.{owner[0]} 重复")

# ---------------------------------------------------------------- 不变量 B

print()
print("=" * 64)
print("B. 孤儿资源 = 0（每个条目都真的被用到）")
print("=" * 64)

referenced = set()
for p in (BASE / "app/src/main").rglob("*.kt"):
    referenced |= set(re.findall(r"R\.string\.(\w+)", p.read_text(encoding="utf-8")))
for p in RES_DIR.rglob("*.xml"):
    referenced |= set(re.findall(r"@string/(\w+)", p.read_text(encoding="utf-8")))

orphans = sorted(set(RES) - referenced)
check(f"strings.xml 的 {len(RES)} 条全部被引用", not orphans)
for o in orphans:
    print(f"      ✗ {o} =「{RES[o]}」")

# ---------------------------------------------------------------- 反向断言

print()
print("=" * 64)
print("反向断言：不能靠「把资源删空」让上面两条同时变绿")
print("=" * 64)

# 这些是本轮从字面量改成资源的动作文案。逐个要求「源码里确实引用了」——
# 谁把 UI 又改回硬编码字面量，这里立刻红（上面的 A 也会红，是双保险）。
MUST_REFERENCE = {
    "action_pipeline": "一键流水线",
    "action_archive": "按日期归档",
    "action_bigfiles": "大文件排行",
    "action_sync_unique": "补齐独有文件",
    "action_reset_pair": "恢复自动配对",
    "action_diagnostics": "诊断",
    "action_read_exif": "读取拍摄时间",
    "action_verify_content": "按内容校验配对",
    "history_title": "改名历史",
    "exif_hint": "EXIF 提示语",
    "sync_title": "按配对统一",
    "empty_folder": "未选择文件夹",
    "batch_start": "起始序号",
    "batch_digits": "编号位数",
}
for name, why in sorted(MUST_REFERENCE.items()):
    check(f"R.string.{name}（{why}）被源码引用", name in referenced)

n_call = sum(
    len(re.findall(r"stringResource\(|getString\(", p.read_text(encoding="utf-8")))
    for p in (BASE / "app/src/main").rglob("*.kt")
)
check(f"文案渲染确实走资源（stringResource/getString 共 {n_call} 处）", n_call > 100)

# ---------------------------------------------------------------- 反例（检查器自检）

print()
print("=" * 64)
print("反例：检查器必须能分清「注释里的引号」与「真字面量」")
print("=" * 64)

sample = '''/**
 * 找出"没配对的""名字有问题的" —— 注释里的引号不是文案
 */
val a = "真正的文案"
val b = "带//斜杠和/*块注释*/的文案"
// val c = "被注释掉的文案"
'''
def literals_of(text):
    """与 chinese_literals 同一份逻辑，但直接吃字符串（供反例复用）。"""
    out = []
    for i, line in enumerate(code_only(text), 1):
        for m in STR_LIT.finditer(line):
            v = m.group(1)
            if CJK.search(v) and len(v) >= 4:
                out.append((i, v))
    return out


vals = [v for _, v in literals_of(sample)]
check("注释里引用的词**不算**文案（首版在此误报 4 条）",
      "没配对的" not in vals and "名字有问题的" not in vals)
check("真字面量**算**文案", "真正的文案" in vals)
check("字符串里的 // 不被当成注释（否则后半句会被截掉）",
      "带//斜杠和/*块注释*/的文案" in vals)
check("整行注释掉的字面量不算文案", "被注释掉的文案" not in vals)

print()
print("=" * 64)
if n_fail:
    print(f"结论：{n_fail} 条不达标")
    raise SystemExit(1)
print("结论：每句话在仓库里恰好有一份（同文两份 0、孤儿资源 0、渲染走资源）")
