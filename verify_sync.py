"""验证「按配对统一」计划生成：这是本轮的主操作。
把它和旧的「顺序对齐」在同样的场景下对比，确认真的补上了缺口。
沙盒无 kotlinc，用 Python 复刻同样的逻辑做等价验证。
"""

def base(n): return n.rsplit('.', 1)[0] if '.' in n else n
def ext(n): return n.rsplit('.', 1)[1] if '.' in n else ''

def extract_seq(name):
    b = base(name)
    end = len(b)
    while end > 0 and not b[end - 1].isdigit():
        end -= 1
    if end == 0:
        return None
    start = end
    while start > 0 and b[start - 1].isdigit():
        start -= 1
    raw = b[start:end]
    return int(raw) if len(raw) <= 9 else None


def compute(left, right):
    """复刻 Pairing.compute：名字 -> 序号 -> 顺序"""
    partner, taken_r = {}, set()
    r_by_name = {base(n).lower(): i for i, n in enumerate(right)}
    for li, l in enumerate(left):
        k = base(l).lower()
        if k in r_by_name and r_by_name[k] not in taken_r:
            partner[li] = r_by_name[k]
            taken_r.add(r_by_name[k])

    offsets, r_by_seq = {}, {}
    for i, n in enumerate(right):
        s = extract_seq(n)
        if s is not None:
            r_by_seq.setdefault(s, []).append(i)

    for li, l in enumerate(left):
        if li in partner:
            continue
        s = extract_seq(l)
        if s is None:
            continue
        for ri in r_by_seq.get(s, []):
            if ri not in taken_r:
                partner[li] = ri
                taken_r.add(ri)
                offsets[ri - li] = offsets.get(ri - li, 0) + 1
                break

    offset = max(offsets.items(), key=lambda kv: kv[1])[0] if offsets else 0
    offset = max(-(len(left) - 1), min(offset, len(right) - 1))
    for li in range(len(left)):
        if li in partner:
            continue
        ri = li + offset
        if 0 <= ri < len(right) and ri not in taken_r:
            partner[li] = ri
            taken_r.add(ri)
    return partner, offset


def build_pair_plan(left, right, partner):
    """复刻 Pairing.buildPairPlan（左->右方向，保留目标扩展名）"""
    rows, used = [], set(right)
    for li, ri in sorted(partner.items()):
        src, tgt = left[li], right[ri]
        if base(src) == base(tgt):
            continue  # 已统一，跳过
        new_name = base(src) + '.' + ext(tgt) if ext(tgt) else base(src)
        used.discard(tgt)
        n, final = 1, new_name
        while final in used or final in set(left):
            final = f"{base(src)} ({n}).{ext(tgt)}" if ext(tgt) else f"{base(src)} ({n})"
            n += 1
        used.add(final)
        rows.append((tgt, final, src))
    return rows


CASES = [
    ("理想：一一对应",
     ["IMG_1.jpg", "IMG_2.jpg", "IMG_3.jpg", "IMG_4.jpg"],
     ["DSC_1.png", "DSC_2.png", "DSC_3.png", "DSC_4.png"]),

    ("右栏缺 2 张（中间删过）",
     ["IMG_1.jpg", "IMG_2.jpg", "IMG_3.jpg", "IMG_4.jpg"],
     ["DSC_1.png", "DSC_4.png"]),

    ("右栏多 3 张（含截图）",
     ["IMG_1.jpg", "IMG_2.jpg", "IMG_3.jpg"],
     ["DSC_1.png", "DSC_2.png", "DSC_3.png", "shot_a.png", "shot_b.png", "shot_c.png"]),

    ("部分已统一过",
     ["same.jpg", "IMG_2.jpg", "IMG_3.jpg"],
     ["same.png", "DSC_2.png", "DSC_3.png"]),
]

print(f"{'场景':<26}{'配对':>5}{'按配对统一':>12}{'改名后统一数':>14}  验证")
print("-" * 74)
all_ok = True
for name, L, R in CASES:
    partner, off = compute(L, R)
    rows = build_pair_plan(L, R, partner)
    # 应用改名后重新计算，看是否全部统一
    renamed = list(R)
    for old, new, _ in rows:
        renamed[renamed.index(old)] = new
    p2, _ = compute(L, renamed)
    unified = sum(1 for li, ri in p2.items() if base(L[li]) == base(renamed[ri]))
    ok = unified == len(partner)
    all_ok &= ok
    print(f"{name:<26}{len(partner):>5}{len(rows):>12}{unified:>14}  {'OK' if ok else 'FAIL'}")

print("\n--- 顺序对齐在同一批场景下的表现（对比）---")
for name, L, R in CASES:
    partner, off = compute(L, R)
    # 顺序对齐：按索引一一对应，只能覆盖 min(len) 个
    naive = min(len(L), len(R))
    # 其中真正配对正确的
    correct = sum(1 for i in range(naive) if partner.get(i) == i)
    print(f"{name:<26} 盲目对齐 {naive} 对，其中配对正确 {correct} 对"
          f"{'  <-- 会改错!' if naive and correct < naive else ''}")

print("\n结论：", "按配对统一在所有场景下都正确" if all_ok else "存在问题")
