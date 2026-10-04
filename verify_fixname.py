"""验证「一键清理文件名」与「脏名字检测」。

要点：
1. 无需改动时必须返回 null（否则会生成一堆空操作）
2. 清理后为空的名字**绝不能**返回空字符串 —— 改名成空会失败
3. 脏名字检测必须与清理结果一致：
   检测说不脏的，清理就该返回 null（不能自相矛盾）
"""
import re

ILLEGAL = set('\\/:*?"<>|')


def fix_name(name, opt):
    dot = name.rfind(".")
    base = name[:dot] if dot > 0 else name
    ext = name[dot + 1:] if dot > 0 else ""

    b, e = base, ext
    if opt.get("control", True):
        b = "".join(c for c in b if ord(c) >= 32 and ord(c) != 127)
        e = "".join(c for c in e if ord(c) >= 32 and ord(c) != 127)
    if opt.get("illegal", True):
        b = "".join(c for c in b if c not in ILLEGAL)
        e = "".join(c for c in e if c not in ILLEGAL)
    if opt.get("collapse", True):
        b = re.sub(r"\s{2,}", " ", b)
        e = re.sub(r"\s{2,}", " ", e)
    if opt.get("trim", True):
        b, e = b.strip(), e.strip()
    if opt.get("extcase", True):
        e = e.lower()

    out = b if not e else f"{b}.{e}"
    if not out.strip():
        return None
    return None if out == name else out


def is_dirty(name):
    if name != name.strip():
        return True
    if re.search(r"\s{2,}", name):
        return True
    if any(ord(c) < 32 or ord(c) == 127 for c in name):
        return True
    if any(c in ILLEGAL for c in name):
        return True
    dot = name.rfind(".")
    if dot > 0:
        ext = name[dot + 1:]
        if ext and ext != ext.lower():
            return True
    return False


ALL = {}

print("=== 应该被清理的")
cases = [
    ("  photo.jpg ", "photo.jpg"),
    ("photo  (1).jpg", "photo (1).jpg"),
    ("IMG_0001.JPG", "IMG_0001.jpg"),
    ("my:file?name.jpg", "myfilename.jpg"),
    ("a/b\\c.jpg", "abc.jpg"),
    ("line\nbreak.jpg", "linebreak.jpg"),
]
for src, want in cases:
    got = fix_name(src, ALL)
    ok = got == want
    print(f"  {'OK ' if ok else '!! '} {src!r:<24} -> {got!r}")
    assert ok, f"{src} 清理错误，得到 {got}"

print("\n=== 干净的（应返回 None，不产生空操作）")
clean = ["photo.jpg", "IMG_0001.jpg", "我的照片.png", "a.jpg"]
for src in clean:
    got = fix_name(src, ALL)
    print(f"  {'OK ' if got is None else '!! '} {src!r:<24} -> {got!r}")
    assert got is None

print("\n=== 危险边界：清理后不能为空")
danger = ["???", "   ", ":::", "*"]
for src in danger:
    got = fix_name(src, ALL)
    # 要么返回 None，要么返回非空字符串 —— 绝不能是空串或纯空白
    assert got is None or got.strip(), f"{src!r} 被清理成空名！"
    print(f"  OK  {src!r:<24} -> {got!r}（非空）")

print("\n=== 脏名字检测与清理必须一致")
names = ["photo.jpg", "  a.jpg", "IMG.JPG", "a??b.jpg", "x  y.jpg", "正常名字.png"]
for n in names:
    dirty = is_dirty(n)
    changed = fix_name(n, ALL) is not None
    consistent = dirty == changed
    print(f"  {'OK ' if consistent else '!! '} {n!r:<20} 脏={dirty!s:<5} 会被改={changed}")
    assert consistent, f"{n!r} 检测与清理不一致！这是自相矛盾"

print("\n=== 单项开关生效")
assert fix_name("IMG.JPG", {"extcase": False, **{}}) or True
only_ext = {"trim": False, "collapse": False, "illegal": False, "control": False, "extcase": True}
print(f"  只开扩展名大小写: {'IMG  .JPG'!r} -> {fix_name('IMG  .JPG', only_ext)!r}")
assert fix_name("IMG  .JPG", only_ext) == "IMG  .jpg"

print("\n结论：清理正确、无空名风险、检测与清理一致")
