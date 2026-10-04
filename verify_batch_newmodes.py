"""验证新增的四个批量模式：正则 / 截取 / 插入 / 删除区间。

最高优先级的风险：**绝不能产出空文件名**。
文件系统不允许空名，而截取、删除这两种操作天然容易把名字删光 ——
如果返回空串还继续改名，文件就废了。

第二风险：区间非法（start > end，或越界）要有明确行为，
不能返回空串或抛异常。
"""

import re


def _to_python_repl(rep):
    """
    Kotlin 的 Regex.replace 用 $1 引用分组，Python 用 \1。
    这里是**模拟层**的方言转换，不是被测代码的一部分 ——
    真实实现（Kotlin）直接用 $1，无需转换。
    """
    return re.sub(r"\$(\d+)", r"\\\1", rep)


def regex_replace(base, pattern, replacement, ignore_case=False):
    if not pattern.strip():
        return base
    try:
        flags = re.I if ignore_case else 0
        out = re.sub(pattern, _to_python_repl(replacement), base, flags=flags)
        # 替换成空了就保持原名：不产出空文件名
        return out if out else base
    except re.error:
        return None      # 不合法 -> 由调用方保持原名


def substring(base, start, end):
    if not base:
        return None
    n = len(base)
    s0 = max(n + start, 0) if start < 0 else min(start, n)
    e0 = n if end is None else (max(n + end, 0) if end < 0 else min(end, n))
    if s0 >= e0:
        return None
    return base[s0:e0]


def insert_at(base, pos, text):
    if not text:
        return base
    n = len(base)
    p = min(max(n + pos + 1, 0), n) if pos < 0 else min(max(pos, 0), n)
    return base[:p] + text + base[p:]


def delete_range(base, start, end):
    n = len(base)
    if n == 0:
        return base
    s0 = max(n + start, 0) if start < 0 else min(start, n)
    e0 = min(max(n + end + 1, 0), n) if end < 0 else min(end, n)
    if s0 >= e0:
        return base
    # 删空了就保持原名：不产出空文件名
    if s0 == 0 and e0 >= n:
        return base
    return base[:s0] + base[e0:]


print("=== 关键：任何模式都不得产出空文件名")
bases = ["IMG_0042", "照片", "a"]
ops = [
    ("SUBSTR 0..0", lambda b: substring(b, 0, 0)),
    ("SUBSTR 10..20", lambda b: substring(b, 10, 20)),
    ("SUBSTR 3..1", lambda b: substring(b, 3, 1)),
    ("DELETE 0..99", lambda b: delete_range(b, 0, 99)),
    ("DELETE 全删", lambda b: delete_range(b, 0, len(b))),
    ("REGEX 全删", lambda b: regex_replace(b, ".*", "")),
]
for name, fn in ops:
    for b in bases:
        r = fn(b)
        status = "None(保持原名)" if r is None else repr(r)
        # 空字符串是致命的；None 表示"不改动"，是安全的
        assert r != "", f"{name} 对 {b!r} 产出了空文件名！"
        print(f"  OK  {name:<14} {b!r:<12} -> {status}")
print("  OK：没有出现空文件名")

print("\n=== 截取：负数下标")
cases = [
    ("IMG_0042", 4, None, "0042", "从第 4 位到末尾"),
    ("IMG_0042", 0, 4, "IMG_", "前 4 位"),
    ("IMG_0042", -4, None, "0042", "后 4 位"),
    ("IMG_0042", 0, -5, "IMG", "去掉后 4 位"),
]
for base, s, e, want, desc in cases:
    got = substring(base, s, e)
    ok = got == want
    print(f"  {'OK ' if ok else '!! '} {base} [{s}:{e}] -> {got!r:<10} {desc}")
    assert ok, f"期望 {want!r}"

print("\n=== 插入：位置与负数")
cases = [
    ("IMG_0042", 0, "X", "XIMG_0042", "插到最前"),
    ("IMG_0042", 8, "X", "IMG_0042X", "插到最后（下标=长度）"),
    ("IMG_0042", -1, "X", "IMG_0042X", "-1 = 末尾"),
    ("IMG_0042", 4, "-", "IMG_-0042", "中间插入"),
]
for base, pos, text, want, desc in cases:
    got = insert_at(base, pos, text)
    ok = got == want
    print(f"  {'OK ' if ok else '!! '} {base} @{pos} +{text!r} -> {got!r:<12} {desc}")
    assert ok, f"期望 {want!r}"

print("\n=== 删除区间")
cases = [
    ("IMG_0042", 0, 4, "0042", "删掉前 4 位"),
    ("IMG_0042", 4, 8, "IMG_", "删掉后 4 位"),
    ("IMG_0042", 100, 200, "IMG_0042", "越界：不改动"),
    ("IMG_0042", 5, 2, "IMG_0042", "start>end：不改动"),
]
for base, s, e, want, desc in cases:
    got = delete_range(base, s, e)
    ok = got == want
    print(f"  {'OK ' if ok else '!! '} {base} -[{s}:{e}] -> {got!r:<12} {desc}")
    assert ok, f"期望 {want!r}"

print("\n=== 正则")
r = regex_replace("IMG_0042", r"IMG_(\d+)", r"照片_$1")
print(f"  {r}")
assert r == "照片_0042"
r = regex_replace("AbC", "abc", "x", ignore_case=True)
print(f"  忽略大小写: {r}")
assert r == "x"
bad = regex_replace("a", "[", "x")
print(f"  非法正则 -> {bad}")
assert bad is None, "非法正则必须返回 None，不能静默返回原名"
print("  OK：非法正则有明确信号")

print("\n=== 删除区间：删光时保持原名（而不是产出空名）")
for s_, e_ in [(0, 3), (0, 99), (0, 1000)]:
    d = delete_range("abc", s_, e_)
    print(f"  {'OK ' if d == 'abc' else '!! '} abc -[{s_}:{e_}] -> {d!r}")
    assert d == "abc", f"删光时应保持原名，实际得到 {d!r}"
print("  OK：不会产出空文件名")

print("\n结论：四个新模式行为正确，空文件名风险已覆盖")
