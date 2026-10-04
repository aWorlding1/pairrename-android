"""把 Pairing.kt 的算法逐行复刻成 Python，用真实场景验证配对结果是否正确。
沙盒里没有 kotlinc / javac，这是能做到的最接近真机的验证。
"""

def extract_seq(name: str):
    base = name.rsplit('.', 1)[0] if '.' in name else name
    end = len(base)
    while end > 0 and not base[end - 1].isdigit():
        end -= 1
    if end == 0:
        return None
    start = end
    while start > 0 and base[start - 1].isdigit():
        start -= 1
    raw = base[start:end]
    if len(raw) > 9:
        return None
    v = int(raw)
    return v if 0 <= v <= 2**31 - 1 else None


def compute(left, right):
    """left/right 是文件名列表，返回 (partner映射, 偏移, 按序号配对数, 按顺序配对数)"""
    if not left or not right:
        return {}, 0, 0, 0
    partner = {}
    by_seq = by_order = 0

    # 第 1 步：主文件名相同
    right_by_name = {n.rsplit('.', 1)[0].lower(): i for i, n in enumerate(right)}
    left_by_name = {n.rsplit('.', 1)[0].lower(): i for i, n in enumerate(left)}
    taken_r = set()

    for li, l in enumerate(left):
        key = l.rsplit('.', 1)[0].lower()
        if key in right_by_name:
            ri = right_by_name[key]
            if ri not in taken_r:
                partner[li] = ri
                taken_r.add(ri)

    # 第 2 步：序号相同
    offsets = {}
    right_by_seq = {}
    for i, n in enumerate(right):
        s = extract_seq(n)
        if s is not None:
            right_by_seq.setdefault(s, []).append(i)

    for li, l in enumerate(left):
        if li in partner:
            continue
        s = extract_seq(l)
        if s is None:
            continue
        for ri in right_by_seq.get(s, []):
            if ri not in taken_r:
                partner[li] = ri
                taken_r.add(ri)
                offsets[ri - li] = offsets.get(ri - li, 0) + 1
                by_seq += 1
                break

    # 第 3 步：主流偏移做顺序配对
    offset = max(offsets.items(), key=lambda kv: kv[1])[0] if offsets else 0
    offset = max(-(len(left) - 1), min(offset, len(right) - 1))

    for li in range(len(left)):
        if li in partner:
            continue
        ri = li + offset
        if not (0 <= ri < len(right)):
            continue
        if ri in taken_r:
            continue
        partner[li] = ri
        taken_r.add(ri)
        by_order += 1

    return partner, offset, by_seq, by_order


CASES = [
    ("相机原图 vs 导出图（序号都在，体系不同）",
     ["IMG_0001.jpg", "IMG_0002.jpg", "IMG_0003.jpg", "IMG_0004.jpg"],
     ["DSC_0001.png", "DSC_0002.png", "DSC_0003.png", "DSC_0004.png"]),

    ("右栏整体错位 2 张",
     ["a1.jpg", "a2.jpg", "a3.jpg", "a4.jpg", "a5.jpg"],
     ["x.jpg", "y.jpg", "b1.png", "b2.png", "b3.png"]),

    ("完全无序号，纯靠顺序",
     ["one.jpg", "two.jpg", "three.jpg"],
     ["alpha.png", "beta.png", "gamma.png"]),

    ("部分已统一（第 1 步生效）",
     ["same.jpg", "b.jpg", "c.jpg"],
     ["same.png", "b.png", "zzz.png"]),

    ("数量不等：右栏多 2 张",
     ["p1.jpg", "p2.jpg", "p3.jpg"],
     ["q1.png", "q2.png", "q3.png", "q4.png", "q5.png"]),

    ("数量不等：右栏少 2 张",
     ["p1.jpg", "p2.jpg", "p3.jpg", "p4.jpg", "p5.jpg"],
     ["q1.png", "q2.png", "q3.png"]),

    ("前导零位数不同",
     ["photo1.jpg", "photo2.jpg"],
     ["photo_01.png", "photo_02.png"]),

    ("一栏为空",
     [],
     ["a.png"]),
]

all_ok = True
for title, L, R in CASES:
    partner, offset, by_seq, by_order = compute(L, R)
    print(f"\n=== {title}")
    print(f"    左 {len(L)} 张  右 {len(R)} 张")
    print(f"    配对 {len(partner)} 对（按序号 {by_seq} / 按顺序 {by_order}）  推算偏移 {offset}")
    for li in sorted(partner):
        ri = partner[li]
        ok = "OK " if li < len(L) and ri < len(R) else "BAD"
        if ok == "BAD":
            all_ok = False
        name_ok = ""
        if li < len(L) and ri < len(R):
            sl, sr = extract_seq(L[li]), extract_seq(R[ri])
            if sl is not None and sr is not None:
                name_ok = " <- 序号一致" if sl == sr else " <- !!序号不一致"
                if sl != sr:
                    all_ok = False
        print(f"      {ok} {L[li]:<16} <-> {R[ri]:<16}{name_ok}")

# 不变量检查：配对必须是双射，不能一个右栏文件被配给两个左栏文件
print("\n=== 不变量检查")
for title, L, R in CASES:
    partner, *_ = compute(L, R)
    rights = list(partner.values())
    assert len(rights) == len(set(rights)), f"{title}: 出现重复配对目标！"
    assert all(0 <= v < len(R) for v in rights), f"{title}: 配对目标越界！"
    assert all(0 <= k < len(L) for k in partner), f"{title}: 配对来源越界！"
print("全部用例通过：无重复配对、无越界")

print("\n结论：", "算法正确" if all_ok else "存在问题")
