"""验证目录体检的统计与问题识别。

风险点：
1. 0 字节和极小文件的判定边界 —— 把正常的图误判成坏文件会造成恐慌
2. 格式分布的数量/体积要能对上总数（不能漏也不能重）
3. 「不支持改名」和「文件名脏」是不同维度，不能混
"""

BROKEN = 1024          # 小于 1KB 视为坏
HUGE = 10 * 1024 * 1024


class It:
    def __init__(self, name, size, can_rename=True, dirty=False):
        self.displayName, self.size = name, size
        self.canRename, self.dirty = can_rename, dirty


def ext_of(name):
    return name.rsplit(".", 1)[1].lower() if "." in name else ""


def analyze(items):
    by = {}
    broken = huge = nren = dirty = None
    broken, huge, nren, dirty = [], [], [], []
    total_bytes = 0
    for it in items:
        s = max(it.size, 0)
        total_bytes += s
        e = ext_of(it.displayName) or "(无)"
        c, b = by.get(e, (0, 0))
        by[e] = (c + 1, b + s)
        if 1 <= s <= BROKEN:
            broken.append(it)
        if s > HUGE:
            huge.append(it)
        if not it.canRename:
            nren.append(it)
        if it.dirty:
            dirty.append(it)
    formats = sorted(
        [{"ext": k, "count": v[0], "bytes": v[1]} for k, v in by.items()],
        key=lambda f: (-f["bytes"], f["ext"]),
    )
    return {
        "total": len(items),
        "bytes": total_bytes,
        "formats": formats,
        "broken": broken, "huge": huge, "noren": nren, "dirty": dirty,
    }


print("=== 格式分布：数量与体积都要对上总数")
items = [
    It("a.jpg", 1000), It("b.jpg", 2000),
    It("c.png", 5000),
    It("d.heic", 3000),
]
r = analyze(items)
cnt = sum(f["count"] for f in r["formats"])
byt = sum(f["bytes"] for f in r["formats"])
print(f"  总数 {r['total']} / 分布合计 {cnt}")
print(f"  总体积 {r['bytes']} / 分布合计 {byt}")
assert cnt == r["total"], "数量对不上"
assert byt == r["bytes"], "体积对不上"
assert r["formats"][0]["ext"] == "png", "应按体积降序（png 5000 最大）"
print("  OK：无遗漏无重复，且按体积排序正确")

print("\n=== 坏文件：边界判定")
cases = [
    (0, False, "0 字节不判坏（size 为 0 可能是未知，不误伤）"),
    (1, True, "1 字节判坏"),
    (1024, True, "1024 判坏（<= 边界）"),
    (1025, False, "1025 不判坏（刚过边界）"),
]
for size, want, desc in cases:
    got = len(analyze([It("x.jpg", size)])["broken"]) == 1
    print(f"  {'OK ' if got == want else '!! '} {size:>6} 字节 -> 判坏={got:<5} {desc}")
    assert got == want

print("\n=== 超大文件边界")
for size, want in [(HUGE, False), (HUGE + 1, True)]:
    got = len(analyze([It("x.jpg", size)])["huge"]) == 1
    print(f"  {'OK ' if got == want else '!! '} {size} -> 超大={got}")
    assert got == want

print("\n=== 三类问题互不混淆")
items = [
    It("bad.jpg", 10, can_rename=True, dirty=False),
    It("nor.jpg", 50_000, can_rename=False, dirty=False),
    It("dirty.jpg", 60_000, can_rename=True, dirty=True),
    It("big.jpg", HUGE + 1, can_rename=True, dirty=False),
]
r = analyze(items)
print(f"  坏={len(r['broken'])} 不能改名={len(r['noren'])} 脏名={len(r['dirty'])} 超大={len(r['huge'])}")
assert len(r["broken"]) == 1 and len(r["noren"]) == 1
assert len(r["dirty"]) == 1 and len(r["huge"]) == 1
print("  OK：各归各，一个文件可以同时命中多类也没错")

print("\n=== 同一文件命中多类时，去重后只算一处")
# 一张 10 字节、又不能改名的图：两个问题，但是同一个文件
r = analyze([It("both.jpg", 10, can_rename=False, dirty=False)])
keys = set()
for g in ("broken", "huge", "noren", "dirty"):
    for it in r[g]:
        keys.add(id(it))
print(f"  坏={len(r['broken'])} 不能改名={len(r['noren'])} -> 去重后 {len(keys)} 个文件")
assert len(keys) == 1, "同一个文件被算成了多个问题"
print("  OK：issueCount 必须按去重后的文件数算")

print("\n=== 空目录不崩")
r = analyze([])
assert r["total"] == 0 and r["formats"] == []
print("  OK")

print("\n=== 无扩展名归入 (无)")
r = analyze([It("noext", 100)])
print(f"  {r['formats']}")
assert r["formats"][0]["ext"] == "(无)"
print("  OK")

print("\n结论：目录体检统计正确，边界判定合理")
