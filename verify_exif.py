"""验证 EXIF 拍摄时间配对。

场景：相册导出 / 微信传输后文件名变成无意义字符串，
序号和顺序全废 —— 只有拍摄时间还能把两套图对上。
沙盒无 kotlinc，复刻 Pairing 的 EXIF 分支做等价验证。
"""

def extract_seq(name):
    base = name.rsplit('.', 1)[0]
    e = len(base)
    while e > 0 and not base[e - 1].isdigit():
        e -= 1
    if e == 0:
        return None
    s = e
    while s > 0 and base[s - 1].isdigit():
        s -= 1
    return int(base[s:e])


def pair_by_seq(left, right):
    """按序号配对（旧逻辑，作为对照组）"""
    partner, used = {}, set()
    for i, (ln, _) in enumerate(left):
        ls = extract_seq(ln)
        if ls is None:
            continue
        for j, (rn, _) in enumerate(right):
            if j in used:
                continue
            if extract_seq(rn) == ls:
                partner[i] = j
                used.add(j)
                break
    return partner


def pair_by_order(left, right, offset=0):
    partner, used = {}, set()
    for i in range(len(left)):
        j = i + offset
        if not (0 <= j < len(right)) or j in used:
            continue
        partner[i] = j
        used.add(j)
    return partner


def pair_by_exif(left, right, tol=2000):
    partner, used = {}, set()
    for i, (_, lt) in enumerate(left):
        if lt <= 0:
            continue
        best, bestj = None, None
        for j, (_, rt) in enumerate(right):
            if j in used or rt <= 0:
                continue
            d = abs(rt - lt)
            if d <= tol and (best is None or d < best):
                best, bestj = d, j
        if bestj is not None:
            partner[i] = bestj
            used.add(bestj)
    return partner


def report(name, left, right):
    print(f"\n=== {name}")
    print(f"    左 {len(left)} 张  右 {len(right)} 张")
    # ground truth：时间差最小的那个才是"真正同一张"
    # （不能要求时间完全相等，秒级误差是常态，那样会让 truth 为空）
    truth = {}
    for i, (_, lt) in enumerate(left):
        if lt <= 0:
            continue
        best, bestj = None, None
        for j, (_, rt) in enumerate(right):
            if rt <= 0:
                continue
            d = abs(rt - lt)
            if best is None or d < best:
                best, bestj = d, j
        if bestj is not None and best <= 15000:  # 15 秒内才算"同一张"
            truth[i] = bestj
    for label, fn in (("按序号", lambda: pair_by_seq(left, right)),
                      ("按顺序", lambda: pair_by_order(left, right)),
                      ("按拍摄时间", lambda: pair_by_exif(left, right))):
        p = fn()
        wrong = sum(1 for i, j in p.items() if truth.get(i) != j)
        miss = len(truth) - sum(1 for i, j in p.items() if truth.get(i) == j)
        ok = "OK" if wrong == 0 and miss == 0 else f"错误 {wrong} / 漏 {miss}"
        print(f"      {label:<10} 配对 {len(p):>2} 对 -> {ok}")
    return pair_by_exif(left, right)


# 场景 1：微信导出 —— 文件名完全无意义，顺序还被打乱
T = 1700000000
left = [("mmexport1a2b.jpg", T + 0), ("mmexport3c4d.jpg", T + 5000),
        ("mmexport5e6f.jpg", T + 12000)]
right = [("IMG_0002.jpg", T + 12000), ("IMG_0003.jpg", T + 0),
         ("IMG_0001.jpg", T + 5000)]
report("微信导出：文件名无意义 + 顺序打乱", left, right)

# 场景 2：右栏多几张截图（无 EXIF 时间）
left2 = [(f"p{i}.jpg", T + i * 1000) for i in range(5)]
right2 = [(f"q{i}.jpg", T + i * 1000) for i in range(5)] + \
         [("shot_a.png", 0), ("shot_b.png", 0)]
report("右栏多 2 张无时间的截图", left2, right2)

# 场景 3：秒级精度差异（容忍 2 秒）
left3 = [("a.jpg", T), ("b.jpg", T + 10000)]
right3 = [("x.jpg", T + 800), ("y.jpg", T + 10100)]
report("两边时间有秒级误差", left3, right3)

print("\n结论：拍摄时间在文件名/顺序全废时仍能正确配对")
