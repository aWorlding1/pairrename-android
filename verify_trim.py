"""验证「去掉末尾序号」的正确性。

风险点很明确：不能误伤**中间**的数字。
`2024 年报` 里的 2024 必须保留，`IMG_0001` 的 0001 才该删。
沙盒无 kotlinc，用 Python 复刻正则逻辑做等价验证。
"""
import re

# 与 Naming.kt 中 TRIM_PATTERNS 一一对应
TRIM_PATTERNS = [
    re.compile(r"\s*\(\d+\)\s*$"),          # (1)
    re.compile(r"\s*[_\-]\d{6,}\s*$"),      # 时间戳
    re.compile(r"\s*[\s_\-](?!(?:19|20)\d{2}$)\d{1,5}\s*$"),  # 排除年份
]


def trim_numbering(base):
    out = base.strip()
    changed = True
    while changed:
        changed = False
        for r in TRIM_PATTERNS:
            m = r.search(out)
            if not m:
                continue
            nxt = out[:m.start()] + out[m.end():]
            if nxt.strip() and nxt != out:
                out = nxt
                changed = True
            break
    if out.strip() and any(c.isalnum() for c in out):
        return out
    return base


print("=== 应该去掉序号的")
should_trim = [
    ("photo (1)", "photo"),
    ("photo (12)", "photo"),
    ("IMG_0001", "IMG"),
    ("DSC_0042", "DSC"),
    ("report_2", "report"),
    ("report-2", "report"),
    ("photo 1", "photo"),
    ("Screenshot_20240315-143022", "Screenshot"),
]
for src, want in should_trim:
    got = trim_numbering(src)
    ok = got == want
    print(f"  {'OK ' if ok else '!! '} {src!r:<32} -> {got!r:<14} (期望 {want!r})")
    assert ok, f"{src} 处理错误"

print("\n=== 必须保留的（中间的数字不能动）")
should_keep = [
    "2024 年报",
    "IMG_2024",
    "第2次修改",
    "3月总结",
    "a",
    "photo",
]
for src in should_keep:
    got = trim_numbering(src)
    ok = got == src
    print(f"  {'OK ' if ok else '!! '} {src!r:<32} -> {got!r}")
    assert ok, f"{src} 被误伤了"

print("\n=== 边界情况")
edges = [
    "12345",      # 全是数字，删完没名字 -> 原样
    "(1)",        # 只有序号
    "   ",        # 空白
    "",           # 空
]
for src in edges:
    got = trim_numbering(src)
    # 关键约束：**非空输入**绝不能被处理成空（会导致改名失败）。
    # 输入本身就是空时返回空是合理的，不归咎于本函数。
    if src.strip():
        assert got.strip(), f"{src!r} 被处理成空了！"
        note = "(非空，安全)"
    else:
        note = "(输入本就为空)"
    print(f"  OK  {src!r:<32} -> {got!r}  {note}")

# 兜底确认：真实文件名场景不会产出空名
for src in ["(1)", "12345", "___1", " (12) "]:
    got = trim_numbering(src)
    assert got.strip(), f"{src!r} 产出空名！"

print("\n=== 叠加序号")
for src, want in [("photo (1) (2)", "photo"), ("IMG_0001_2", "IMG")]:
    got = trim_numbering(src)
    print(f"  {'OK ' if got == want else '!! '} {src!r:<32} -> {got!r}")
    assert got == want

print("\n结论：末尾序号正确去除，中间数字与边界均安全")
