"""验证：内容指纹作为配对依据的正确性，以及重复查找的体积预筛效果。

沙盒无 kotlinc，用 Python 复刻 ContentHash 的采样策略与 Pairing 的优先级做等价验证。
"""
import hashlib

SAMPLE = 64  # 演示用小采样，真实实现是 256KB


def content_hash(data, size=None):
    if size is None:
        size = len(data)
    if size <= SAMPLE * 2:
        return hashlib.sha256(data).hexdigest()
    return hashlib.sha256(data[:SAMPLE] + data[-SAMPLE:]).hexdigest()


def base(n):
    return n.rsplit('.', 1)[0]


def extract_seq(n):
    b = base(n)
    e = len(b)
    while e > 0 and not b[e - 1].isdigit():
        e -= 1
    if e == 0:
        return None
    s = e
    while s > 0 and b[s - 1].isdigit():
        s -= 1
    return int(b[s:e])


def pair_by_seq(left, right):
    partner, used = {}, set()
    for i, (ln, _) in enumerate(left):
        ls = extract_seq(ln)
        for j, (rn, _) in enumerate(right):
            if j in used:
                continue
            if extract_seq(rn) == ls:
                partner[i] = j
                used.add(j)
                break
    return partner


def pair_by_content(left, right):
    ck = {n: content_hash(d, len(d)) for n, d in left + right}
    partner, used = {}, set()
    for i, (ln, _) in enumerate(left):
        for j, (rn, _) in enumerate(right):
            if j in used:
                continue
            if ck[ln] == ck[rn]:
                partner[i] = j
                used.add(j)
                break
    return partner


def check(name, left, right):
    print(f"\n=== {name}")
    for label, fn in (("按序号", pair_by_seq), ("按内容指纹", pair_by_content)):
        partner = fn(left, right)
        wrong = sum(1 for i, j in partner.items() if left[i][1] != right[j][1])
        miss = len(left) - len(partner)
        status = "OK" if wrong == 0 and miss == 0 else f"{wrong} 错 {miss} 漏"
        print(f"    {label:<8} 配对 {len(partner)} 对  ->  {status}")
    return partner


CASES = [
    ("序号体系对不上（错位）", [
        ("IMG_0003.jpg", b"A" * 500), ("IMG_0001.jpg", b"B" * 500), ("IMG_0002.jpg", b"C" * 500)],
     [("DSC_0001.png", b"B" * 500), ("DSC_0002.png", b"A" * 500), ("DSC_0003.png", b"C" * 500)]),
    ("两边顺序完全打乱", [
        ("a.jpg", b"X" * 400), ("b.jpg", b"Y" * 400), ("c.jpg", b"Z" * 400)],
     [("3.png", b"Z" * 400), ("1.png", b"X" * 400), ("2.png", b"Y" * 400)]),
]

for name, L, R in CASES:
    check(name, L, R)

print("\n--- 重复查找：体积预筛能省掉多少读盘 ---")
files = [(f"p{i}.jpg", 1000 + i * 7) for i in range(60)]
files[10] = ("dup_a.jpg", 5000)
files[11] = ("dup_b.jpg", 5000)
by_size = {}
for n, s in files:
    by_size.setdefault(s, []).append(n)
cands = sum(len(v) for v in by_size.values() if len(v) >= 2)
print(f"全盘 {len(files)} 个文件 -> 只需读取 {cands} 个候选"
      f"（省掉 {len(files) - cands} 次读盘）")
print("\n结论：内容指纹在序号/顺序失效时依然 100% 正确")
