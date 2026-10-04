"""验证改名历史 CSV 的导出 → 解析往返。

这是「隔天发现改错了还能救回来」的最后一环：
如果 CSV 在中文名、带逗号/引号的名字上往返出错，还原就会改坏文件。
沙盒无 kotlinc，用 Python 复刻同样逻辑做等价验证。
"""

def quote(s):
    if ',' in s or '"' in s or '\n' in s:
        return '"' + s.replace('"', '""') + '"'
    return s


def split_csv_line(line):
    out, cur, in_q, i = [], [], False, 0
    while i < len(line):
        c = line[i]
        if c == '"' and in_q and i + 1 < len(line) and line[i + 1] == '"':
            cur.append('"'); i += 1
        elif c == '"':
            in_q = not in_q
        elif c == ',' and not in_q:
            out.append("".join(cur)); cur = []
        else:
            cur.append(c)
        i += 1
    out.append("".join(cur))
    return out


def to_csv(entries):
    sb = ["\uFEFF", "序号,时间,操作,原文件名,新文件名\n"]
    n = 0
    for e in reversed(entries):
        for old, new in e:
            n += 1
            sb.append(f"{n},{quote('2026-09-15 10:00:00')},{quote('批量')},"
                      f"{quote(old)},{quote(new)}\n")
    return "".join(sb)


def parse_csv(text):
    rows = [l for l in text.replace("\uFEFF", "").split("\n") if l.strip()]
    if not rows:
        return []
    header = [c.strip() for c in rows[0].split(",")]
    if header != ["序号", "时间", "操作", "原文件名", "新文件名"]:
        return []
    out = []
    for raw in rows[1:]:
        cols = split_csv_line(raw)
        if len(cols) < 5:
            continue
        old, new = cols[3], cols[4]
        if old.strip() and new.strip():
            out.append((new, old))   # 新名 -> 旧名，用于还原
    return out


CASES = [
    ("普通英文名", [("IMG_0001.jpg", "DSC_0001.png"), ("IMG_0002.jpg", "DSC_0002.png")]),
    ("中文文件名", [("风景照 一.jpg", "旅行 2026.jpg"), ("家庭合影 (1).jpg", "家人.jpg")]),
    ("名字里含逗号", [("a,b.jpg", "c,d.jpg")]),
    ("名字里含引号", [('say"hi".jpg', 'it"s.jpg')]),
    ("名字里含空格与括号", [("photo (final) (2).jpg", "IMG_0100.jpg")]),
    ("单条", [("only.jpg", "one.png")]),
    ("空记录", []),
]

all_ok = True
for name, pairs in CASES:
    csv_text = to_csv([pairs] if pairs else [])
    got = parse_csv(csv_text)
    # 期望：还原映射 = (新名, 旧名)
    expect = [(new, old) for old, new in pairs]
    ok = got == expect
    all_ok &= ok
    status = "OK  " if ok else "FAIL"
    print(f"{status} {name:<22} 往返 {len(pairs)} 条 -> {len(got)} 条")
    if not ok:
        print(f"       期望 {expect}")
        print(f"       实际 {got}")

print("\n--- 异常输入必须被拒绝，不能误还原 ---")
bad = [
    ("完全无关的文本", "hello world\nfoo,bar"),
    ("表头对不上", "A,B,C\n1,2,3"),
    ("空文件", ""),
    ("只有表头", "序号,时间,操作,原文件名,新文件名"),
]
for name, text in bad:
    got = parse_csv(text)
    ok = len(got) == 0
    all_ok &= ok
    print(f"{'OK  ' if ok else 'FAIL'} {name:<16} -> 解析出 {len(got)} 条（应为 0）")

print("\n结论：", "CSV 往返正确，异常输入已拒绝" if all_ok else "存在问题")
