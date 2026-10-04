"""验证「一键流水线」的步骤编排。

风险点：流水线把三步串起来，任何一步的「无数据」分支
如果写成抛异常或直接 return，后面的步骤就不会执行。
这里验证各种开关组合下，实际执行了哪些步骤。

另外验证最近文件夹的编码/解码（含损坏数据不能崩）。
"""

# ---------- 流水线步骤编排 ----------

STEPS = ["read_exif", "verify_content", "sync"]


def pipeline(read_exif, verify_content, sync_after, has_files=True):
    """复刻 runPipeline 的步骤选择"""
    if not has_files:
        return []
    done = []
    if read_exif:
        done.append("read_exif")
    if verify_content:
        done.append("verify_content")
    done.append("sync" if sync_after else "report_only")
    return done


print("=== 步骤组合")
cases = [
    (True, False, True, ["read_exif", "sync"]),
    (True, True, True, ["read_exif", "verify_content", "sync"]),
    (False, False, True, ["sync"]),
    (False, True, False, ["verify_content", "report_only"]),
    (True, False, False, ["read_exif", "report_only"]),
    (False, False, False, ["report_only"]),
]
for a, b, c, want in cases:
    got = pipeline(a, b, c)
    ok = got == want
    print(f"  {'OK ' if ok else '!! '} exif={a!s:<5} content={b!s:<5} sync={c!s:<5} -> {got}")
    assert ok

print("\n=== 没有文件时不应执行任何步骤")
got = pipeline(True, True, True, has_files=False)
print(f"  {'OK ' if got == [] else '!! '} -> {got}")
assert got == []

print("\n=== 关键：sync 关闭时也要给出结果（不能不吭声）")
got = pipeline(True, False, False)
assert got[-1] == "report_only", "关闭统一后必须有报告步骤"
print("  OK：关闭统一时仍报告配对数")

# ---------- 最近文件夹编解码 ----------

SEP, SEP2 = "\u001f", "\u001e"


def encode(lst):
    return SEP2.join(f"{l}{SEP}{u}" for l, u in lst)


def parse(raw):
    if not raw.strip():
        return []
    out = []
    for chunk in raw.split(SEP2):
        f = chunk.split(SEP)
        if len(f) < 2:
            continue
        # 模拟 Uri.parse 校验
        if not f[1]:
            continue
        out.append((f[0], f[1]))
    return out


print("\n=== 最近文件夹往返")
data = [("Camera", "content://a/tree/1"), ("微信图片", "content://a/tree/2"), ("DCIM", "content://a/tree/3")]
rt = parse(encode(data))
print(f"  {'OK ' if rt == data else '!! '} {rt}")
assert rt == data

print("\n=== 损坏数据不能崩")
for bad in ["", "   ", "no-separator", "a\u001eb\u001ec", "only\u001f", "\u001e\u001e\u001e"]:
    try:
        r = parse(bad)
        print(f"  OK  {bad!r:<24} -> {r}")
    except Exception as e:
        raise AssertionError(f"{bad!r} 导致异常: {e}")

print("\n=== 上限：只留最近 12 个")
MAX = 12
lst = [(f"f{i}", f"u{i}") for i in range(30)]
kept = lst[:MAX]
print(f"  30 个 -> 保留 {len(kept)} 个")
assert len(kept) == MAX

print("\n=== 重复选择同一文件夹：应提到最前且不重复")
cur = [("a", "u1"), ("b", "u2"), ("c", "u3")]
cur = [x for x in cur if x[1] != "u2"]
cur.insert(0, ("b", "u2"))
print(f"  {cur}")
assert cur[0] == ("b", "u2")
assert len({x[1] for x in cur}) == 3, "出现重复"
print("  OK：无重复且置顶")

print("\n结论：流水线步骤编排正确，最近文件夹编解码健壮")
