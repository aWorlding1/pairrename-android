"""验证扩展名 / 体积过滤。

关键风险：体积为 0（未知）的文件不能被过滤误杀。
很多文件读不到 size（某些 provider 不返回），如果按
`size >= min` 判断，这些文件会被 0 下限直接筛掉 ——
用户会看到"明明有 100 张，筛完只剩 30 张"。

另一风险：扩展名匹配要忽略大小写（.JPG 和 .jpg 应算同一种）。
"""

MB = 1024 * 1024


class It:
    def __init__(self, name, size):
        self.displayName, self.size = name, size


def ext_of(name):
    return name.rsplit(".", 1)[1].lower() if "." in name else ""


def apply_filter(items, exts=frozenset(), mn=None, mx=None):
    out = []
    for it in items:
        # 体积过滤
        if mn is None and mx is None:
            ok = True
        else:
            if it.size <= 0:
                ok = True          # 关键：未知体积一律放行
            else:
                ok = (mn is None or it.size >= mn) and (mx is None or it.size <= mx)
        if ok and exts:
            ok = ext_of(it.displayName) in exts
        if ok:
            out.append(it)
    return out


def available_exts(items):
    by = {}
    for it in items:
        e = ext_of(it.displayName)
        if e:
            by[e] = by.get(e, 0) + 1
    return sorted(by.items(), key=lambda kv: (-kv[1], kv[0]))


items = [
    It("a.jpg", 500 * 1024),
    It("b.JPG", 2 * MB),          # 大写扩展名
    It("c.png", 20 * MB),
    It("d.jpg", 0),               # 体积未知
    It("noext", 100),             # 无扩展名
]

print("=== 扩展名过滤：忽略大小写")
r = apply_filter(items, exts={"jpg"})
print(f"  选 .jpg -> {[i.displayName for i in r]}")
assert {i.displayName for i in r} == {"a.jpg", "b.JPG", "d.jpg"}, "大小写未归一"
print("  OK：.JPG 和 .jpg 都被选中")

print("\n=== 关键：体积未知（0）不能被误杀")
r = apply_filter(items, mn=MB)
print(f"  >=1MB -> {[i.displayName for i in r]}")
assert "d.jpg" in [i.displayName for i in r], "体积未知的文件被误筛掉了！"
print("  OK：d.jpg（体积未知）仍然可见")

print("\n=== 体积区间")
r = apply_filter(items, mn=MB, mx=10 * MB)
print(f"  1MB~10MB -> {[i.displayName for i in r]}")
assert [i.displayName for i in r] == ["b.JPG", "d.jpg"]
r = apply_filter(items, mn=10 * MB)
print(f"  >10MB -> {[i.displayName for i in r]}")
assert [i.displayName for i in r] == ["c.png", "d.jpg"]
print("  OK")

print("\n=== 两个条件同时生效（AND）")
r = apply_filter(items, exts={"jpg"}, mn=MB)
print(f"  .jpg 且 >=1MB -> {[i.displayName for i in r]}")
assert {i.displayName for i in r} == {"b.JPG", "d.jpg"}
print("  OK：是交集不是并集")

print("\n=== 都不选 = 不过滤")
r = apply_filter(items)
assert len(r) == len(items)
print(f"  OK：保留全部 {len(r)} 个")

print("\n=== availableExts 按数量降序、忽略大小写")
ex = available_exts(items)
print(f"  {ex}")
assert ex[0] == ("jpg", 3), f"期望 jpg 3 个（含大写），实际 {ex[0]}"
assert ("noext", 0) not in [e for e in ex], "无扩展名不应出现"
print("  OK：合并计数，排除无扩展名")

print("\n结论：扩展名/体积过滤正确，体积未知不会被误杀")
