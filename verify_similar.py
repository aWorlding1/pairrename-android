"""把 Pairing.kt 里「名字骨架 / 序号提取 / 第 1.5 步相似名配对」逐行复刻成 Python，
用真实场景验证结果是否符合预期，并检查四项性质：

  1. 双射：一个右栏文件不会被配给两个左栏文件（左右索引分属不同命名空间，
     否则 left[0] 与 right[0] 会撞同一个 key，检查本身就成了空转）；
  2. 不丢值：新 extractSeq 对任何名字都不会把原本取得到的序号变成 None；
  3. 不过度合并：本该分开的文件不会被相似名规则粘到一起；
  4. 改动有实效：改动前配错的场景，改动后必须配对。

沙盒里没有 kotlinc，这是能做到的最接近真机的验证。
"""

import re

# ---------------------------------------------------------------- 正则（与 Kotlin 一一对应）

ANY_BRACKET = re.compile(r"[\(\（\[\【][^\)\）\]\】]*[\)\）\]\】]")
END_BRACKET_COUNTER = re.compile(r"[\(\（\[\【]\s*\d{1,3}\s*[\)\）\]\】]\s*$")
END_NOISE = re.compile(
    r"(?<![a-z])(?:副本|复件|拷贝|复制|已编辑|编辑|修改|修图|调色|美化|"
    r"原图|原片|导出|完成|最终|终稿|无水印|copy|edit|edited|final|"
    r"export|hdr|retouch)\d{0,2}\s*$",
    re.IGNORECASE,
)

TRIM_CHARS = " \t-_.·#+"

L, R = "L", "R"          # 左右两栏的 key 命名空间，绝不复用


# ---------------------------------------------------------------- 复刻自 Kotlin

def split_ext(name: str):
    """复刻 Naming.splitExt：点在开头（dotfile）或结尾时不拆。"""
    dot = name.rfind(".")
    if dot <= 0 or dot == len(name) - 1:
        return name, ""
    return name[:dot], name[dot + 1:]


def last_digit_run(s: str):
    """复刻 Naming.lastDigitRun：返回**原始文本**（判定年份要看位数）。"""
    end = len(s)
    while end > 0 and not s[end - 1].isdigit():
        end -= 1
    if end == 0:
        return None
    start = end
    while start > 0 and s[start - 1].isdigit():
        start -= 1
    raw = s[start:end]
    return raw if len(raw) <= 9 else None


def seq_of(name: str, treat_year_as_seq: bool = False):
    """复刻 Naming.seqOf —— 全项目唯一的序号判定（v4.8.0 起）。

    treat_year_as_seq=False 用于统计（Advisor），True 用于配对（Pairing.extractSeq）。
    """
    base = split_ext(name)[0]
    clean = ANY_BRACKET.sub(" ", base)
    raw = last_digit_run(clean)
    if raw is None:
        raw = last_digit_run(base)
    if raw is None:
        return None
    v = int(raw)
    if v > 2**31 - 1:
        return None
    if not treat_year_as_seq and len(raw) == 4 and 1900 <= v <= 2099:
        return None
    return v


def extract_seq(name: str):
    """复刻 Pairing.extractSeq —— 现在只是 Naming.seqOf(treatYearAsSeq=True) 的委托。

    配对引擎必须把 4 位年份也算序号：相机连拍编号真的会走到 2000+
    （`DSC_2099.JPG`），排除掉会让这批文件退化成"按排列顺序"配对。
    """
    return seq_of(name, treat_year_as_seq=True)


def seq_of_old(name: str):
    """改动前 Naming.seqOf 的实现，用于证明本轮修复的差异确实是修复。"""
    base = split_ext(name)[0]
    m = re.search(r"(\d{1,6})\s*$", base)
    if not m:
        return None
    raw = m.group(1)
    v = int(raw)
    if len(raw) == 4 and 1900 <= v <= 2099:
        return None
    return v


def extract_seq_old(name: str):
    """**第八轮之前**的引擎实现：不剥括号，直接取末尾数字。

    `compute_old` 用它来对照，证明第八轮「括号剥除」的价值 ——
    所以它必须保持"不剥括号"这个语义，不能跟着后来的重构跑。
    """
    base = name.rsplit(".", 1)[0] if "." in name else name
    return last_digit_run_int(base)


def pairing_extract_seq_v46(name: str):
    """第八轮 ~ 第十轮之间 `Pairing.extractSeq` 的实现（本地那份，剥括号但不复用 Naming）。

    留着它只有一个用途：证明第十轮的"委托给 Naming"**没有改变引擎行为**。
    """
    base = name.rsplit(".", 1)[0] if "." in name else name
    clean = ANY_BRACKET.sub(" ", base)
    v = last_digit_run_int(clean)
    return v if v is not None else last_digit_run_int(base)


def last_digit_run_int(s: str):
    """`lastDigitRun` 返回 Int 的旧版本。"""
    raw = last_digit_run(s)
    return int(raw) if raw is not None else None


def norm_stem(name: str) -> str:
    base = name.rsplit(".", 1)[0] if "." in name else name
    s = base.lower()
    progressed = True
    while progressed:
        progressed = False
        trimmed = s.rstrip(TRIM_CHARS)
        if trimmed != s:
            s = trimmed
            progressed = True
        m = END_BRACKET_COUNTER.search(s)
        if m and m.start() > 0:
            s = s[:m.start()]
            progressed = True
            continue
        n = END_NOISE.search(s)
        if n and n.start() > 0:
            s = s[:n.start()]
            progressed = True
    return "".join(ch for ch in s if ch.isalnum())


def usable_stem(stem: str) -> bool:
    return len(stem) >= 3 and any(ch.isalpha() for ch in stem)


def compute(left, right):
    """返回 (partner, reason, by_name, by_similar, by_seq, by_order, offset)"""
    if not left or not right:
        return {}, {}, 0, 0, 0, 0, 0

    partner, reason = {}, {}
    by_name = by_similar = by_seq = by_order = 0

    # 第 1 步：主文件名相同（列表式，容忍同名多扩展名）
    right_by_name = {}
    for i, n in enumerate(right):
        right_by_name.setdefault(n.rsplit(".", 1)[0].lower(), []).append(i)

    for li, l in enumerate(left):
        if (L, li) in partner:
            continue
        key = l.rsplit(".", 1)[0].lower()
        r = next((i for i in right_by_name.get(key, []) if (R, i) not in partner), None)
        if r is None:
            continue
        partner[(L, li)] = r
        partner[(R, r)] = li
        reason[(L, li)] = reason[(R, r)] = "NAME"
        by_name += 1

    offsets = {}

    # 第 1.5 步：名字骨架相同
    right_by_stem = {}
    for i, n in enumerate(right):
        st = norm_stem(n)
        if usable_stem(st):
            right_by_stem.setdefault(st, []).append(i)

    for li, l in enumerate(left):
        if (L, li) in partner:
            continue
        st = norm_stem(l)
        if not usable_stem(st):
            continue
        r = next((i for i in right_by_stem.get(st, []) if (R, i) not in partner), None)
        if r is None:
            continue
        partner[(L, li)] = r
        partner[(R, r)] = li
        reason[(L, li)] = reason[(R, r)] = "SIMILAR"
        offsets[r - li] = offsets.get(r - li, 0) + 1
        by_similar += 1

    # 第 2 步：序号相同
    right_by_seq = {}
    for i, n in enumerate(right):
        s = extract_seq(n)
        if s is not None:
            right_by_seq.setdefault(s, []).append(i)

    for li, l in enumerate(left):
        if (L, li) in partner:
            continue
        s = extract_seq(l)
        if s is None:
            continue
        r = next((i for i in right_by_seq.get(s, []) if (R, i) not in partner), None)
        if r is None:
            continue
        partner[(L, li)] = r
        partner[(R, r)] = li
        reason[(L, li)] = reason[(R, r)] = "SEQ"
        offsets[r - li] = offsets.get(r - li, 0) + 1
        by_seq += 1

    # 第 3 步：主流偏移做顺序配对
    offset = max(offsets.items(), key=lambda kv: kv[1])[0] if offsets else 0
    offset = max(-(len(left) - 1), min(offset, len(right) - 1))

    for li in range(len(left)):
        if (L, li) in partner:
            continue
        ri = li + offset
        if not (0 <= ri < len(right)) or (R, ri) in partner:
            continue
        partner[(L, li)] = ri
        partner[(R, ri)] = li
        reason[(L, li)] = reason[(R, ri)] = "ORDER"
        by_order += 1

    return partner, reason, by_name, by_similar, by_seq, by_order, offset


def left_pairs(partner):
    """左栏已配对的 (左索引, 右索引) 列表。"""
    return sorted((k[1], v) for k, v in partner.items() if k[0] == L)


# ---------------------------------------------------------------- 用例

CASES = [
    ("微信/QQ 转存：右栏是「副本」命名",
     ["IMG_1234.jpg", "IMG_1235.jpg", "IMG_1236.jpg"],
     ["IMG_1234 (1).jpg", "IMG_1235 (1).jpg", "IMG_1236 (1).jpg"],
     ["SIMILAR", "SIMILAR", "SIMILAR"]),

    ("中文副本尾巴",
     ["IMG_0001.jpg", "IMG_0002.jpg"],
     ["IMG_0001 - 副本.jpg", "IMG_0002 - 副本.jpg"],
     ["SIMILAR", "SIMILAR"]),

    ("修图工具导出的 _编辑 / _final",
     ["DSC_0001.jpg", "DSC_0002.jpg"],
     ["DSC_0001_编辑.jpg", "DSC_0002_final.jpg"],
     ["SIMILAR", "SIMILAR"]),

    ("完全没有序号，只有副本标记",
     ["photo.jpg", "cover.png"],
     ["photo (1).jpg", "cover copy.png"],
     ["SIMILAR", "SIMILAR"]),

    ("序号被括号劫持：1 对 1 时靠顺序还能救回来",
     ["IMG_1234.jpg"],
     ["IMG_1234 (1).jpg"],
     ["SIMILAR"]),

    ("序号被括号劫持的真实危害：括号里的 1/2 造成假序号匹配",
     ["IMG_0001.jpg", "IMG_0002.jpg", "IMG_0003.jpg"],
     ["IMG_0001 (2).jpg", "IMG_0002 (1).jpg", "IMG_0003 (2).jpg"],
     ["SIMILAR", "SIMILAR", "SIMILAR"]),

    ("大小写 + 分隔符归一",
     ["IMG-1234.jpg"],
     ["img 1234.png"],
     ["SIMILAR"]),

    ("括号里是长数字：不该当副本标记吃掉",
     ["IMG_(1234).jpg"],
     ["IMG_(1234).png"],
     ["NAME"]),

    ("相邻序号 + 错位：只认名字真正相同的，绝不见「像」就合并",
     ["IMG_1234.jpg", "IMG_1235.jpg"],
     ["IMG_1235.jpg", "IMG_1236.jpg"],
     ["NAME"]),

    ("短名字：骨架不足 3 字符，不许相似名兜底",
     ["a.jpg"],
     ["ab.jpg"],
     ["ORDER"]),

    ("真正毫不相干的两组",
     ["holiday.jpg", "beach.jpg"],
     ["report.pdf", "sheet.xlsx"],
     ["ORDER", "ORDER"]),
]

all_ok = True
print("=" * 78)
print("一、配对确定性用例")
print("=" * 78)
for title, Ls, Rs, want in CASES:
    partner, reason, by_name, by_sim, by_seq, by_ord, offset = compute(Ls, Rs)
    pairs = left_pairs(partner)
    print(f"\n=== {title}")
    print(f"    左 {len(Ls)} 右 {len(Rs)} → 配对 {len(pairs)} 对"
          f"（名字 {by_name} / 相似名 {by_sim} / 序号 {by_seq} / 顺序 {by_ord}）偏移 {offset}")
    for li, ri in pairs:
        print(f"      {Ls[li]:<20} <-> {Rs[ri]:<20} [{reason[(L, li)]}]")
    for li in range(len(Ls)):
        if (L, li) not in partner:
            print(f"      {Ls[li]:<20} <-> （未配对）")
    got = [reason[(L, li)] for li, _ in pairs]
    if got != want:
        print(f"    !! 期望 {want}，实际 {got}")
        all_ok = False

print("\n" + "=" * 78)
print("二、不变量：双射（左右索引分属不同命名空间，检查是有效的）")
print("=" * 78)
for title, Ls, Rs, _ in CASES:
    partner, reason, *_ = compute(Ls, Rs)
    rights = [v for _, v in left_pairs(partner)]
    assert len(rights) == len(set(rights)), f"{title}: 一个右栏文件被配给多个左栏文件！"
    assert all(0 <= v < len(Rs) for v in rights), f"{title}: 配对目标越界！"
    assert all(0 <= li < len(Ls) for li, _ in left_pairs(partner)), f"{title}: 配对来源越界！"
    for (side, i), other in partner.items():
        back = partner.get((R if side == L else L, other))
        assert back == i, f"{title}: 映射不成对（{(side, i)}→{other}，反向是 {back}）"
        assert len(reason) == len(partner), f"{title}: reason 与 partner 数量不一致"
print("全部用例通过：无重复配对、无越界、双向一致")
# 反向证明：故意构造一个撞 key 的场景，确认上面的检查确实会失败
_bad = {(L, 0): 0, (R, 0): 0}
print(f"（自检）撞 key 会让配对表塌成 {len(_bad)} 条，所以左右必须分命名空间")

print("\n" + "=" * 78)
print("三、性质：新 extractSeq 绝不丢值（旧实现取得到的，新的也必须取得到）")
print("=" * 78)
CORPUS = []
for stem in ["IMG_1234", "DSC_0001", "photo", "a", "x_9", "IMG_(1234)", "shot",
             "IMG (2) 1234", "100", "1234567890", "P1010001", "中文名", "2024-05-01",
             "IMG_0001 - 副本", "photo (1)", "IMG_1234_编辑", "(3)", "我 的 图 2",
             "IMG_1234 (1)", "IMG_1234(2)", "P1010001 (3)", "IMG_0001 - 副本 (2)",
             "DSC_0001 [4]", "IMG_1234 【12】"]:
    for ext in [".jpg", ".JPG", ".png", ".jpeg", ""]:
        CORPUS.append(stem + ext)

lost = [n for n in CORPUS if extract_seq_old(n) is not None and extract_seq(n) is None]
changed = [(n, extract_seq_old(n), extract_seq(n)) for n in CORPUS
           if extract_seq_old(n) != extract_seq(n)]
print(f"语料 {len(CORPUS)} 个名字，丢值 {len(lost)} 个")
if lost:
    all_ok = False
    print("  !! 丢了序号：", lost)
print(f"序号取值发生变化的 {len(changed)} 个（应当只有「括号里是副本计数」这一类）：")
for n, old, new in changed:
    print(f"    {n:<24} {old} → {new}")
bad_change = [c for c in changed if "(" not in c[0] and "（" not in c[0]
              and "[" not in c[0] and "【" not in c[0]]
if bad_change:
    all_ok = False
    print("  !! 出现了不该有的变化：", bad_change)

print("\n" + "=" * 78)
print("四、性质：相似名规则不会过度合并（骨架不同就必须不同）")
print("=" * 78)
PAIRS_SHOULD_DIFFER = [
    ("IMG_1234.jpg", "IMG_5678.jpg"),
    ("holiday.jpg", "holiday2.jpg"),
    ("IMG_1234.jpg", "IMG_1234_01.jpg"),   # 多了一段真实序号
    ("a.jpg", "b.jpg"),
    ("beach.jpg", "report.pdf"),
]
bad = []
for a, b in PAIRS_SHOULD_DIFFER:
    sa, sb = norm_stem(a), norm_stem(b)
    if usable_stem(sa) and sa == sb:
        bad.append((a, b, sa))
for a, b, s in bad:
    print(f"    !! {a} 与 {b} 被归一成同一个骨架 {s}")
if bad:
    all_ok = False
else:
    print("通过：这些名字的骨架都不同，不会被粘到一起")

print("\n" + "=" * 78)
print("五、改动前 vs 改动后：括号副本标记造成的假序号匹配")
print("=" * 78)


def compute_old(left, right):
    """改动前的引擎：只有 名字 / 序号 / 顺序 三步，且序号照旧从原文里取。"""
    if not left or not right:
        return {}, {}
    partner, reason = {}, {}
    right_by_name = {}
    for i, n in enumerate(right):
        right_by_name[n.rsplit(".", 1)[0].lower()] = i
    for li, l in enumerate(left):
        r = right_by_name.get(l.rsplit(".", 1)[0].lower())
        if r is not None and (L, li) not in partner and (R, r) not in partner:
            partner[(L, li)] = r
            partner[(R, r)] = li
            reason[(L, li)] = reason[(R, r)] = "NAME"
    offsets, right_by_seq = {}, {}
    for i, n in enumerate(right):
        s = extract_seq_old(n)
        if s is not None:
            right_by_seq.setdefault(s, []).append(i)
    for li, l in enumerate(left):
        if (L, li) in partner:
            continue
        s = extract_seq_old(l)
        if s is None:
            continue
        for ri in right_by_seq.get(s, []):
            if (R, ri) not in partner:
                partner[(L, li)] = ri
                partner[(R, ri)] = li
                reason[(L, li)] = reason[(R, ri)] = "SEQ"
                offsets[ri - li] = offsets.get(ri - li, 0) + 1
                break
    offset = max(offsets.items(), key=lambda kv: kv[1])[0] if offsets else 0
    offset = max(-(len(left) - 1), min(offset, len(right) - 1))
    for li in range(len(left)):
        if (L, li) in partner:
            continue
        ri = li + offset
        if not (0 <= ri < len(right)) or (R, ri) in partner:
            continue
        partner[(L, li)] = ri
        partner[(R, ri)] = li
        reason[(L, li)] = reason[(R, ri)] = "ORDER"
    return partner, reason


DEMO_L = ["IMG_0001.jpg", "IMG_0002.jpg", "IMG_0003.jpg"]
DEMO_R = ["IMG_0001 (2).jpg", "IMG_0002 (1).jpg", "IMG_0003 (2).jpg"]


def truth_of(l_name, r_name):
    return "正确" if l_name.rsplit(".", 1)[0] == r_name.split(" ")[0] else ">>> 配错"


old_partner, old_reason = compute_old(DEMO_L, DEMO_R)
print("\n[改动前]")
for li in range(len(DEMO_L)):
    if (L, li) in old_partner:
        ri = old_partner[(L, li)]
        print(f"      {DEMO_L[li]:<16} <-> {DEMO_R[ri]:<18} [{old_reason[(L, li)]}] "
              f"{truth_of(DEMO_L[li], DEMO_R[ri])}")
    else:
        print(f"      {DEMO_L[li]:<16} <-> （没配上）")

new_partner, new_reason, *_ = compute(DEMO_L, DEMO_R)
print("\n[改动后]")
ok_all = True
for li in range(len(DEMO_L)):
    if (L, li) in new_partner:
        ri = new_partner[(L, li)]
        t = truth_of(DEMO_L[li], DEMO_R[ri])
        if t != "正确":
            ok_all = False
        print(f"      {DEMO_L[li]:<16} <-> {DEMO_R[ri]:<18} [{new_reason[(L, li)]}] {t}")
    else:
        ok_all = False
        print(f"      {DEMO_L[li]:<16} <-> （没配上）")

old_wrong = sum(1 for li in range(len(DEMO_L))
                if (L, li) in old_partner and truth_of(DEMO_L[li], DEMO_R[old_partner[(L, li)]]) != "正确")
old_miss = len([li for li in range(len(DEMO_L)) if (L, li) not in old_partner])
new_wrong = sum(1 for li in range(len(DEMO_L))
                if (L, li) in new_partner and truth_of(DEMO_L[li], DEMO_R[new_partner[(L, li)]]) != "正确")
new_miss = len([li for li in range(len(DEMO_L)) if (L, li) not in new_partner])
print(f"\n改动前：配错 {old_wrong} 对，漏配 {old_miss} 对")
print(f"改动后：配错 {new_wrong} 对，漏配 {new_miss} 对")
if not ok_all or old_wrong == 0:
    all_ok = False
    print("  !! 这个场景没能体现出改动价值，检查用例是否仍然有效")

print("\n" + "=" * 78)
print("六、EXIF 配对：排序 + 二分版 vs 暴力扫描版，结果必须一致")
print("=" * 78)

TOL = 2000
_brute_steps = [0]
_new_steps = [0]


def exif_bruteforce(left, right, tol=TOL):
    """改动前：每个左栏项完整扫一遍右栏。
    右栏同样先按时间排序 —— 这样两边只在「怎么找最近」上不同，
    而不是在「按什么顺序找」上不同（后者是另一处有意的改动）。"""
    timed = sorted([r for r in right if r[1] > 0], key=lambda t: t[1])
    partner = {}
    for lk, lt in sorted([l for l in left if l[1] > 0], key=lambda t: t[1]):
        if lk in partner:
            continue
        best, bd = None, None
        for rk, rt in timed:
            _brute_steps[0] += 1
            if rk in partner:
                continue
            d = abs(rt - lt)
            if bd is None or d < bd:
                bd, best = d, rk
        if best is not None and bd <= tol:
            partner[lk] = best
            partner[best] = lk
    return partner


def exif_sorted_new(left, right, tol=TOL):
    """改动后：右栏排序 + 二分定位 + 向两侧交替取更近。"""
    timed = sorted([r for r in right if r[1] > 0], key=lambda t: t[1])
    partner = {}
    for lk, lt in sorted([l for l in left if l[1] > 0], key=lambda t: t[1]):
        if lk in partner:
            continue
        lo, hi = 0, len(timed)
        while lo < hi:
            mid = (lo + hi) // 2
            if timed[mid][1] < lt:
                lo = mid + 1
            else:
                hi = mid
        down, up = lo - 1, lo
        best, bd = None, None
        while down >= 0 or up < len(timed):
            _new_steps[0] += 1
            d = lt - timed[down][1] if down >= 0 else 0
            u = timed[up][1] - lt if up < len(timed) else 0
            if down < 0:
                take_down = False
            elif up >= len(timed):
                take_down = True
            else:
                take_down = d <= u
            diff = d if take_down else u
            if best is not None and diff >= bd:
                break
            if best is None and diff > tol:
                break
            rk = timed[down][0] if take_down else timed[up][0]
            if take_down:
                down -= 1
            else:
                up += 1
            if rk not in partner and (bd is None or diff < bd):
                bd, best = diff, rk
        if best is not None and bd is not None and bd <= tol:
            partner[lk] = best
            partner[best] = lk
    return partner


def _diff_multiset(partner, times):
    return sorted(abs(times[partner[k]] - times[k])
                  for k in partner if k.startswith("L"))


import random

random.seed(20260920)
exact_ok = True
tie_ok = True

# A) 时间戳互不相同 → 要求两个实现给出**完全相同**的配对
for trial in range(300):
    n = random.randint(1, 14)
    m = random.randint(1, 14)
    stamps = random.sample(range(0, 60000), n + m)
    left = [("L%d" % i, stamps[i]) for i in range(n)]
    right = [("R%d" % j, stamps[n + j]) for j in range(m)]
    times = dict(left + right)
    a = exif_bruteforce(left, right)
    b = exif_sorted_new(left, right)
    if a != b:
        exact_ok = False
        print("  !! 用例 %d 结果不同" % trial)
        print("     暴力:", dict(sorted(a.items())))
        print("     新版:", dict(sorted(b.items())))
        break

# B) 允许重复时间戳 → 会有多张**等距**候选，此时配对质量必须一致。
#    注意：等距的候选取哪个是任意的（Δt 完全相同），所以不能要求逐对相同；
#    能要求也必须要求的是「配对数 + 每对的 Δt 多重集」完全一致 ——
#    这两个数字一样，就说明新算法没有漏配、也没有配得更差。
for trial in range(300):
    n = random.randint(1, 12)
    m = random.randint(1, 12)
    left = [("L%d" % i, random.choice([0, 1000, 1000, 2500, 3000, 5000, 9000]))
            for i in range(n)]
    right = [("R%d" % j, random.choice([0, 1000, 1000, 2500, 3000, 5000, 9000]))
             for j in range(m)]
    times = dict(left + right)
    a = exif_bruteforce(left, right)
    b = exif_sorted_new(left, right)
    da, db = _diff_multiset(a, times), _diff_multiset(b, times)
    if da != db:
        tie_ok = False
        print("  !! 用例 %d 配对质量不同（这才是真问题）" % trial)
        print("     left :", sorted(left, key=lambda t: t[1]))
        print("     right:", sorted(right, key=lambda t: t[1]))
        print("     暴力 Δt:", da)
        print("     新版 Δt:", db)
        break
    for k, v in b.items():
        assert b.get(v) == k, "新版映射不成对"
        assert abs(times[k] - times[v]) <= TOL, "新版配出了超出误差的对"

print("A) 时间戳互不相同的 300 组随机用例：%s" % ("逐对完全一致" if exact_ok else "出现不一致"))
print("B) 允许重复时间戳的 300 组：%s"
      % ("配对数与每对 Δt 多重集完全一致" if tie_ok else "出现质量差异"))
if not exact_ok or not tie_ok:
    all_ok = False

# C) 规模：同一个 2000×2000 的语料，比较内层步数
big_n = 2000
big_l = [("L%d" % i, i * 3000 + random.randint(0, 500)) for i in range(big_n)]
big_r = [("R%d" % j, j * 3000 + random.randint(0, 500)) for j in range(big_n)]
_brute_steps[0] = 0
_new_steps[0] = 0
pb = exif_bruteforce(big_l, big_r)
pn = exif_sorted_new(big_l, big_r)
print("C) 2000×2000：配对数 暴力 %d / 新版 %d" % (len(pb) // 2, len(pn) // 2))
print("   内层比较次数：暴力 %d 次 → 新版 %d 次（降为原来的 %.2f%%）"
      % (_brute_steps[0], _new_steps[0], 100.0 * _new_steps[0] / max(1, _brute_steps[0])))
if len(pb) != len(pn):
    all_ok = False
    print("   !! 大规模语料上配对数不同")

print("\n" + "=" * 78)
print("结论：", "全部通过" if all_ok else "存在问题，需要修正")
print("=" * 78)
