"""第十一轮验证：元信息缓存的改名迁移。

## 本轮修的两处

### 1) `trimIfNeeded()` 只盯 `exif.size`，上限形同虚设

用户没开「管理所有文件」权限、或图片本来就没有拍摄时间时，`putExif` 一次都不写，
`exif` 永远是空的 —— 于是 `exif.size <= LIMIT` 恒成立，**清理永远不会触发**，
而 `hash`（内容校验）会跟着切换目录一路增长。

### 2) `undoOne()` 的缓存迁移顺序与磁盘执行顺序不一致（真缺陷）

撤销时磁盘是按 `entry.steps.asReversed()` **倒序**执行的，
但喂给缓存迁移的反向步骤列表是**正序**。单步改名正序倒序一样，所以一直没暴露；
一旦多步（**置换**是 `A→temp, B→A, temp→B` 三步）就错位。

## 本脚本要证明的三件事

1. **`migrate` 的顺序语义是承重的**：顺序执行对链式改名和置换都正确，
   而"看起来更安全"的两阶段实现（先全读、再全写）会让**置换丢掉 B 的原值**。
   —— 所以这不是可以顺手"优化"的地方。
2. **撤销顺序缺陷是真的**：正序喂给 migrate 会让置换撤销后的元信息
   全部滞留在那个中间临时名上（A、B 两图同时丢元信息）；改成 `asReversed()` 后
   精确还原成撤销前的映射。
3. **trim 触发条件修复有效**：只涨 `hash` 的场景，旧判断永不触发、新判断会触发。
"""

# ---------------------------------------------------------------- 复刻

LIMIT = 8  # 测试里用小上限，跑得动


def migrate_sequential(cache, steps):
    """复刻 MetaCache.migrate：严格按 steps 顺序「读旧键→写新键→删旧键」。"""
    for frm, to in steps:
        if frm == to:
            continue
        for table in cache.values():
            v = table.get(frm)
            if v is not None:
                table[to] = v
                del table[frm]
    return cache


def migrate_two_phase(cache, steps):
    """**反例**：先把所有旧值读出来、再统一写新键。

    看起来更"安全"（不受顺序影响），实际会破坏置换 —— 见下面的用例。
    """
    moves = [(f, t) for f, t in steps if f != t]
    snapshot = {f: {k: v for k, v in table.items()} for f, table in
                ((f, cache[k]) for f in {f for f, _ in moves} for k in cache)}
    # 读阶段：把每个 from 键在四张表里的值一次性抓下来
    grabbed = {}
    for f, _ in moves:
        grabbed[f] = {name: table.get(f) for name, table in cache.items()}
    # 写阶段
    for f, t in moves:
        for name, table in cache.items():
            v = grabbed[f][name]
            if v is not None:
                table[t] = v
    # 删阶段：只删不在目标集合里的旧键
    targets = {t for _, t in moves}
    for f, _ in moves:
        if f in targets:
            continue
        for table in cache.values():
            table.pop(f, None)
    _ = snapshot
    return cache


NEW_CACHES = ("exif", "camera", "path", "hash")


def fresh(**values):
    """造一个四表同值的缓存（四张表行为一致，同值跑一遍就够）。"""
    return {name: dict(values) for name in NEW_CACHES}


def names_of(cache, table="exif"):
    return {k: v for k, v in cache[table].items()}


problems = []


def check(name, cond, detail=""):
    print(f"  [{'PASS' if cond else 'FAIL'}] {name}" + (f"  {detail}" if detail else ""))
    if not cond:
        problems.append(name)


print("=" * 72)
print("一、顺序迁移：四种真实改名形态都必须正确")
print("=" * 72)

# 1) 单步改名
c = fresh(A="va", B="vb")
migrate_sequential(c, [("A", "C")])
check("单步改名 A→C", names_of(c) == {"B": "vb", "C": "va"}, str(names_of(c)))

# 2) 链式改名 A→B, B→C（磁盘上最终 C 里是原 A 的文件）
c = fresh(A="va", B="vb")
migrate_sequential(c, [("A", "B"), ("B", "C")])
print(f"  链式 A→B→C 顺序迁移结果: {names_of(c)}")
check("链式改名：C 拿到 A 的值，B 的旧值随中间名一起消失",
      names_of(c) == {"C": "va"}, str(names_of(c)))

# 3) 链式 + 复用：A→B, B→C, X→B
c = fresh(A="va", B="vb", X="vx")
migrate_sequential(c, [("A", "B"), ("B", "C"), ("X", "B")])
print(f"  链式 + 复用结果: {names_of(c)}")
check("链式 + 复用：C=A 的值、B=X 的值",
      names_of(c) == {"C": "va", "B": "vx"}, str(names_of(c)))

# 4) 置换（app 的真实做法：中间插一个临时名）
SWAP = [("A", "temp__"), ("B", "A"), ("temp__", "B")]
c = fresh(A="va", B="vb")
migrate_sequential(c, SWAP)
print(f"  置换 A↔B 结果: {names_of(c)}")
check("置换：A 拿 B 的值、B 拿 A 的值",
      names_of(c) == {"A": "vb", "B": "va"}, str(names_of(c)))

print()
print("=" * 72)
print("二、反例：两阶段实现会弄坏置换（所以顺序语义不能改）")
print("=" * 72)
seq = migrate_sequential(fresh(A="va", B="vb"), SWAP)
two = migrate_two_phase(fresh(A="va", B="vb"), SWAP)
print(f"  顺序实现: {names_of(seq)}")
print(f"  两阶段实现: {names_of(two)}")
check("两阶段实现确实给出不同（且错误）的结果", names_of(seq) != names_of(two))
check("顺序实现正确", names_of(seq) == {"A": "vb", "B": "va"})
check("两阶段实现丢掉了 B 的原值（B 拿到 va 而不是 vb）",
      names_of(two).get("B") != "va" or "B" not in names_of(two),
      str(names_of(two)))
# 链式下两阶段同样会错
seq2 = migrate_sequential(fresh(A="va", B="vb"), [("A", "B"), ("B", "C")])
two2 = migrate_two_phase(fresh(A="va", B="vb"), [("A", "B"), ("B", "C")])
print(f"  链式下：顺序 {names_of(seq2)}  /  两阶段 {names_of(two2)}")
check("链式下两阶段也留下 B 的残值（而 B 这个文件已经不在了）",
      "B" in names_of(two2) and "B" not in names_of(seq2))

print()
print("=" * 72)
print("三、撤销：正序喂给 migrate 是缺陷，asReversed 才是对的")
print("=" * 72)
# 注意「撤销前」这个说法要拆成两个不同的状态：
#   * `pre_rename`  —— 改名之前（也就是撤销**应该**回到的状态）
#   * `post_swap`   —— 置换已完成后（也就是撤销的**起点**）
# 上一版这里把 before_undo 设成了 post_swap 却拿它当"应回到的状态"比较，是测试写错了。
pre_rename = {"A": "va", "B": "vb"}
post_swap = {"A": "vb", "B": "va"}
# 磁盘撤销按 asReversed 执行：inv 序列 = [inv(s3), inv(s2), inv(s1)]
inverse_steps = [(t, f) for f, t in SWAP]           # [(temp,A), (A,B), (B,temp)]
disk_order = list(reversed(inverse_steps))          # [(B,temp), (A,B), (temp,A)]

wrong = migrate_sequential(fresh(**post_swap), inverse_steps)   # 缺陷版：正序
right = migrate_sequential(fresh(**post_swap), disk_order)      # 修复版：asReversed
print(f"  撤销起点（置换后）: {post_swap}")
print(f"  正序迁移（缺陷）: {names_of(wrong)}")
print(f"  倒序迁移（修复）: {names_of(right)}")
check("正序迁移把元信息全丢在那个中间临时名上（缺陷复现）",
      names_of(wrong) == {"temp__": "vb"}, str(names_of(wrong)))
check("缺陷版里 A 和 B 两张图的元信息都消失了",
      "A" not in names_of(wrong) and "B" not in names_of(wrong))
check("倒序迁移精确还原成【改名之前】的映射",
      names_of(right) == pre_rename, str(names_of(right)))

# 单步改名时正序倒序必须一致（说明为什么这个缺陷一直没被发现）
one = [("A", "B")]
check("单步改名：正序与倒序结果相同（所以缺陷只在多步时暴露）",
      names_of(migrate_sequential(fresh(A="va"), one))
      == names_of(migrate_sequential(fresh(A="va"), list(reversed(one)))))

print()
print("=" * 72)
print("四、撤销往返：改名 → 撤销 后缓存映射必须回到原样")
print("=" * 72)
# 往返不变式只有这一条是真的：**活到撤销后的键，值必须回到改名前的原值**。
# 被「目标名覆盖」掉的键（改名时它的旧内容就被顶掉了）没有回头路，
# 所以不能拿完整的 original 去比 —— 上一版就是这么比错的。
ORIGINAL = {"A": "va", "B": "vb", "C": "vc", "X": "vx"}
round_trips = [
    # 标签、改名步骤、撤销后应当剩下来的键值（= ORIGINAL 中未被顶掉的那些）
    ("单步", [("A", "C")], {"A": "va", "B": "vb", "X": "vx"}),
    ("链式", [("A", "B"), ("B", "C")], {"A": "va", "X": "vx"}),
    ("置换", SWAP, {"A": "va", "B": "vb", "C": "vc", "X": "vx"}),
    ("链式+复用", [("A", "B"), ("B", "C"), ("X", "B")], {"A": "va", "X": "vx"}),
]
for label, steps, expect in round_trips:
    after = migrate_sequential(fresh(**ORIGINAL), steps)
    # 撤销：反向步骤，按磁盘顺序（倒序）执行
    # 注意 after 是「四张表」的结构，喂给 fresh 前要先取扁平映射（names_of），
    # 否则表名会被当成键塞进去。
    inv_disk = list(reversed([(t, f) for f, t in steps]))
    back = migrate_sequential(fresh(**names_of(after)), inv_disk)
    kept = {k: v for k, v in ORIGINAL.items() if k in names_of(back)}
    print(f"  {label:10s} 原 {ORIGINAL}")
    print(f"  {'':10s} 改后 {names_of(after)}  ->  撤销后 {names_of(back)}")
    check(f"{label}：撤销后的键集合正是「没被顶掉」的那批",
          names_of(back) == expect, str(names_of(back)))
    check(f"{label}：活下来的键值都回到了改名前的原值",
          names_of(back) == kept, str(names_of(back)))

print()
print("=" * 72)
print("五、trim 触发条件：只涨 hash 时旧判断永不触发")
print("=" * 72)


def trim_old(exif_n, other_n):
    """旧实现：只看 exif.size。"""
    return exif_n > LIMIT


def trim_new(counts):
    """新实现：看四张表里最大的那张。"""
    return max(counts.values()) > LIMIT


cases = [
    ("只读 EXIF（exif 涨）", {"exif": LIMIT + 1, "camera": 0, "path": 0, "hash": 0}, True),
    ("只跑内容校验（hash 涨）", {"exif": 0, "camera": 0, "path": 0, "hash": LIMIT + 1}, True),
    ("只读路径（path 涨）", {"exif": 0, "camera": 0, "path": LIMIT + 1, "hash": 0}, True),
    ("都没超", {"exif": LIMIT, "camera": LIMIT, "path": 1, "hash": 1}, False),
]
for label, counts, expect in cases:
    old_fires = trim_old(counts["exif"], 0)
    new_fires = trim_new(counts)
    print(f"  {label:24s} 旧={'触发' if old_fires else '不触发':4s}  "
          f"新={'触发' if new_fires else '不触发'}")
    check(f"{label}：新实现符合预期", new_fires is expect)
check("**只有 hash 增长时旧实现完全不触发（缺陷复现）**",
      trim_old(0, LIMIT + 1) is False)
check("同样场景新实现会触发（已修）",
      trim_new({"exif": 0, "camera": 0, "path": 0, "hash": LIMIT + 1}) is True)

# 清理是整体清空，不留半截状态
c = fresh(A="va", B="vb")
for table in c.values():
    table.clear()
check("清理后四张表全空（不留半截状态）",
      all(len(c[t]) == 0 for t in NEW_CACHES))

print()
print("=" * 72)
print("六、边界")
print("=" * 72)
check("空 steps 不动缓存",
      names_of(migrate_sequential(fresh(A="va"), [])) == {"A": "va"})
check("from == to 的步骤被跳过（不会把自己的值删掉）",
      names_of(migrate_sequential(fresh(A="va"), [("A", "A")])) == {"A": "va"})
check("目标键已有值时被正确覆盖",
      names_of(migrate_sequential(fresh(A="va", B="vb"), [("A", "B")])) == {"B": "va"})
check("源键无值时不产生空目标键",
      names_of(migrate_sequential(fresh(B="vb"), [("A", "B")])) == {"B": "vb"})

print()
print("=" * 72)
if problems:
    print(f"发现问题 {len(problems)} 处：")
    for p in problems:
        print(f"  - {p}")
    raise SystemExit(1)
print("全部通过。")
print("顺序迁移对四种真实形态都正确；两阶段反例证明顺序语义承重；")
print("撤销的正序/倒序差异已复现并修正；trim 的触发条件已覆盖到四张表。")
