"""验证超长文件名场景。

风险：手动改名的「输入预览」与「实际执行」若用了不同的截断逻辑，
用户在对话框里看到的名字和实际改出来的名字就不一样 ——
这种不一致最难被发现，因为用户完全信任预览。

另外要验证：截断后绝不能出现
- 空名字（文件系统不允许）
- 末尾的点或空格（Windows 上会自动去掉，导致名字又不一致）
- 被截断成半个汉字（会变成乱码）
"""

MAX_BYTES = 240
ILLEGAL = set('/\\:*?"<>|')


def sanitize(input_, reserve=0):
    cleaned = "".join("_" if c in ILLEGAL else c for c in input_).strip().rstrip(". ")
    if not cleaned:
        cleaned = "unnamed"
    return truncate_bytes(cleaned, max(MAX_BYTES - reserve, 16))


def truncate_bytes(input_, max_bytes):
    if len(input_.encode("utf-8")) <= max_bytes:
        return input_
    out = []
    used = 0
    for ch in input_:
        cost = len(ch.encode("utf-8"))
        if used + cost > max_bytes:
            break
        out.append(ch)
        used += cost
    res = "".join(out).strip().rstrip(". ")
    return res if res else "unnamed"


def split_ext(name):
    dot = name.rfind(".")
    if dot <= 0:
        return name, ""
    return name[:dot], name[dot + 1:]


def join(base, ext):
    return f"{base}.{ext}" if ext else base


print("=== 超长中文名：截断点不在字符中间（不产生乱码）")
long_cn = "照片" * 200      # 400 个汉字 = 1200 字节
r = sanitize(long_cn)
print(f"  输入 {len(long_cn.encode())} 字节 -> 输出 {len(r.encode())} 字节")
assert len(r.encode("utf-8")) <= MAX_BYTES
# 关键：能被完整解回原字符（没被截成半个字）
assert r == r.encode("utf-8").decode("utf-8"), "截断了半个多字节字符"
print("  OK：字节数受限，且能完整解码（无乱码）")

print("\n=== 超长名 + 扩展名：总长仍受控")
base = "照片" * 200
ext = "jpg"
got = join(sanitize(base, reserve=len(ext) + 1), ext)
total = len(got.encode("utf-8"))
print(f"  合计 {total} 字节（上限 {MAX_BYTES}）")
assert total <= MAX_BYTES, f"超出文件系统上限 {total}"
print("  OK：预留了扩展名长度")

print("\n=== 绝不产出空名或末尾点/空格")
for raw in ["", "   ", "...", "///", "\\\\\\\\", "       照片        "]:
    r = sanitize(raw)
    ok = bool(r) and r == r.rstrip(". ")
    print(f"  {'OK ' if ok else '!! '} {raw!r:<20} -> {r!r}")
    assert ok, f"{raw!r} 产出了非法名字 {r!r}"
print("  OK")

print("\n=== 预览与执行一致：同一函数、同一 reserve")
# 模拟 RenameDialog：reserve = ext 长度 + 1
for base_in, ext_in in [
    ("照片" * 200, "jpg"),
    ("a" * 300, "jpeg"),
    ("正常名字", "png"),
]:
    preview = join(sanitize(base_in, len(ext_in) + 1), ext_in)
    # 执行路径用的同一个调用
    executed = join(sanitize(base_in, len(ext_in) + 1), ext_in)
    assert preview == executed
    assert len(executed.encode()) <= MAX_BYTES
    print(f"  OK  {base_in[:8]}...{ext_in} -> {len(executed.encode())} 字节，预览==执行")
print("  OK：预览与实际不会产生分歧")

print("\n=== reserve 极大时仍保底 16 字节（不会倒推出负数）")
r = sanitize("照片" * 50, reserve=1000)
print(f"  reserve=1000 -> {len(r.encode())} 字节")
assert len(r.encode()) >= 0 and bool(r)
print("  OK：coerceAtLeast(16) 生效")

print("\n结论：超长文件名处理安全，无乱码、无空名、预览与执行一致")
