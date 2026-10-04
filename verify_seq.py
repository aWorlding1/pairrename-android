"""第十轮验证：序号判定统一到唯一实现。

## 改之前的状态（本脚本要证明的"病"）

同一个文件名，两套独立实现给出**相反结论**：

| 文件名 | `Naming.seqOf`（统计用） | `Pairing.extractSeq`（配对用） |
| --- | --- | --- |
| `IMG_1234 (1).jpg` | `null`（正则要求末尾就是数字，`)` 挡住了） | `1234`（剥掉括号） |
| `IMG_2024.jpg` | `null`（4 位落 1900~2099，当成年份） | `2024` |
| `DSC_1234567.jpg` | `234567`（正则只吃最后 6 位！） | `1234567` |

第三行是纯粹的 bug：`seqOf` 会把一个 8 位序号**静默截断成 6 位**再当序号用。

## 改之后

一份实现 `Naming.seqOf(name, treatYearAsSeq)`，差异只通过这一个参数表达：
统计用 `false`（年份不算序号），配对引擎用 `true`（连拍编号会走到 2000+）。

## 本脚本要证明的三件事

1. **病是真的**：旧两套实现在具体文件名上确实矛盾（23+ 例），其中"静默截断"
   是纯粹的 bug（把 8 位序号截成 6 位再当序号用）。
2. **引擎零回归**：`extractSeq` 与第十轮之前的实现逐项一致 ——
   唯一的差异集合必须**恰好**是"以点开头的 dotfile"（详见脚本内说明）。
3. **统计口径只会更准**：`seqOf` 的变化必须全部落在两类可解释的情形上 ——
   补上（旧实现看不见括号前的真序号）与去掉（旧实现把 >9 位长数字截断成假序号），
   且**去掉的每一例都必须证明其数字串 >9 位**。

第 2、3 条是本轮最重要的安全声明：统一实现如果没有零回归 / 只修正的证明，
那就是拿"配对准确性"去换"代码整洁"，不划算。

> 记录一次被推翻的假设：起草时我写的是"Advisor 判定完全不变"，
> 跑出来 2 条 FAIL —— 因为旧 `seqOf` 的正则要求名字末尾就是数字，
> `IMG_1234 (1)` 以 `)` 结尾，于是**带括号副本标记的名字一律被判成没有序号**。
> 假设错了，代码没错。断言改成上面第 3 条之后才有意义。

"""
import re

# ---------------------------------------------------------------- 复刻

ANY_BRACKET = re.compile(r"[\(\（\[\【][^\)\）\]\】]*[\)\）\]\】]")


def split_ext(name):
    """复刻 Naming.splitExt：点在开头（dotfile）或结尾时不拆。"""
    dot = name.rfind(".")
    if dot <= 0 or dot == len(name) - 1:
        return name, ""
    return name[:dot], name[dot + 1:]


def last_digit_run(s):
    """复刻 Naming.lastDigitRun：返回原始**文本**。"""
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


def seq_of(name, treat_year_as_seq=False):
    """复刻 **新** Naming.seqOf（唯一实现）。"""
    base = split_ext(name)[0]
    raw = last_digit_run(ANY_BRACKET.sub(" ", base)) or last_digit_run(base)
    if raw is None:
        return None
    v = int(raw)
    if v > 2**31 - 1:
        return None
    if not treat_year_as_seq and len(raw) == 4 and 1900 <= v <= 2099:
        return None
    return v


def extract_seq(name):
    """复刻 **新** Pairing.extractSeq（= seq_of(treat_year_as_seq=True)）。"""
    return seq_of(name, treat_year_as_seq=True)


def seq_of_old(name):
    """复刻 **旧** Naming.seqOf（正则 + 只吃 6 位）。"""
    base = split_ext(name)[0]
    m = re.search(r"(\d{1,6})\s*$", base)
    if not m:
        return None
    raw = m.group(1)
    v = int(raw)
    if len(raw) == 4 and 1900 <= v <= 2099:
        return None
    return v


def extract_seq_old(name):
    """复刻**第十轮之前**的 Pairing.extractSeq（本地实现，剥括号但不复用 Naming）。"""
    base = name.rsplit(".", 1)[0] if "." in name else name
    clean = ANY_BRACKET.sub(" ", base)

    def run(s):
        return last_digit_run(s)

    v = run(clean) or run(base)
    return int(v) if v is not None else None


# ---------------------------------------------------------------- 语料

CORPUS = [
    # --- 基本 ---
    "IMG_0001.jpg", "IMG_0002.JPG", "photo_02.png", "DSC12345.jpg",
    "a.b.jpg", "noext", "photo.jpg", "1.jpg", "007.jpg",
    # --- 括号副本标记（第八轮的主题）---
    "IMG_1234 (1).jpg", "IMG_1234（12）.jpg", "IMG_1234 [3].jpg",
    "IMG_1234【4】.jpg", "beach (1).jpg", "cat (2).png",
    "photo (100).jpg", "x(1)(2).jpg",
    # --- 年份（两套实现的政策分歧点）---
    "IMG_2024.jpg", "IMG_1999.jpg", "IMG_1900.jpg", "IMG_2099.jpg",
    "IMG_1899.jpg", "IMG_2100.jpg", "2024.jpg", "20240920.jpg",
    # --- 长数字（静默截断的现场）---
    "DSC_1234567.jpg", "DSC_12345678.jpg", "DSC_123456789.jpg",
    "DSC_1234567890.jpg", "abc1234567.png", "hash_0123456789012345.jpg",
    # --- 中文 / 空格 / 分隔符 ---
    "照片 0007.jpg", "照片-0008.jpg", "我的图 2024 年.jpg",
    "  IMG_0011  .jpg", "图片 (3) 副本.jpg",
    # --- 无序号 / 纯文本 ---
    "封面.jpg", "readme.txt", "IMG_.jpg", "____.jpg",
    # --- dotfile（新旧的唯一差异类）---
    ".IMG_0042.jpg", ".hidden.jpg", ".photo_12", ".nomedia",
    # --- 尾部点 / 双扩展名 ---
    "photo.", "photo..jpg", "a.tar.gz",
    # --- 时间戳 / 哈希 ---
    "20240920143015.jpg", "mmexport1a2b3c.jpg", "IMG_20240920_143015.jpg",
]

problems = []


def check(name, cond, detail=""):
    print(f"  [{'PASS' if cond else 'FAIL'}] {name}" + (f"  {detail}" if detail else ""))
    if not cond:
        problems.append(name)


print("=" * 72)
print("一、病是真的：旧的两套实现在具体文件名上互相矛盾")
print("=" * 72)
contradictions = [n for n in CORPUS if seq_of_old(n) != extract_seq_old(n)]
for n in contradictions:
    print(f"  {n:28s} seqOf(旧)={str(seq_of_old(n)):>9s}   extractSeq(旧)={extract_seq_old(n)}")
check("确实存在矛盾（缺陷复现）", len(contradictions) > 0, f"共 {len(contradictions)} 例")

# 点名三类，避免"随便有几例就算数"
print()
print("  三类具体的病：")
print(f"    括号劫持      IMG_1234 (1).jpg   seqOf={seq_of_old('IMG_1234 (1).jpg')}  "
      f"extractSeq={extract_seq_old('IMG_1234 (1).jpg')}")
check("括号劫持：统计说'没序号'，配对说'序号 1234'",
      seq_of_old("IMG_1234 (1).jpg") is None and extract_seq_old("IMG_1234 (1).jpg") == 1234)
print(f"    年份政策      IMG_2024.jpg       seqOf={seq_of_old('IMG_2024.jpg')}  "
      f"extractSeq={extract_seq_old('IMG_2024.jpg')}")
check("年份分歧：统计说'没序号'，配对说'序号 2024'",
      seq_of_old("IMG_2024.jpg") is None and extract_seq_old("IMG_2024.jpg") == 2024)
print(f"    静默截断      DSC_1234567.jpg    seqOf={seq_of_old('DSC_1234567.jpg')}  "
      f"extractSeq={extract_seq_old('DSC_1234567.jpg')}")
check("静默截断：8 位序号被 seqOf 截成后 6 位（纯 bug，不是政策分歧）",
      seq_of_old("DSC_1234567.jpg") == 234567 and extract_seq_old("DSC_1234567.jpg") == 1234567)

print()
print("=" * 72)
print("二、统一后：不再有矛盾，差异只剩显式的政策参数")
print("=" * 72)
mismatch = [n for n in CORPUS if seq_of(n, False) != seq_of(n, True)
            and not (seq_of(n, False) is None and seq_of(n, True) is not None)]
# 允许的唯一差异：默认策略把年份判为 null、引擎策略算出数字
print(f"  默认策略 vs 引擎策略，真正不一致（除'年份算不算'外）的输入：{mismatch or '无'}")
check("两套策略的差别只体现在'年份算不算序号'上", mismatch == [])
year_cases = [n for n in CORPUS if seq_of(n, False) != seq_of(n, True)]
print(f"  差异全部落在年份上：{year_cases}")
check("差异集合非空（政策参数确实在起作用）", len(year_cases) > 0)
check("差异集合里的输入，默认策略一律返回 null",
      all(seq_of(n, False) is None for n in year_cases))
check("差异集合里的输入，引擎策略一律返回数字",
      all(isinstance(seq_of(n, True), int) for n in year_cases))

print()
print("=" * 72)
print("三、引擎零回归：extractSeq 与第十轮之前逐项一致（dotfile 除外）")
print("=" * 72)
engine_diff = [n for n in CORPUS if extract_seq(n) != extract_seq_old(n)]
print(f"  行为有差异的输入：{engine_diff or '无'}")
only_dotfiles = all(n.startswith(".") for n in engine_diff)
check("差异集合 ⊆ {以点开头的 dotfile}", only_dotfiles, f"实际 {engine_diff}")
non_dot_diff = [n for n in engine_diff if not n.startswith(".")]
check("非 dotfile 的名字：逐项完全一致", non_dot_diff == [], f"实际 {non_dot_diff}")
if engine_diff:
    for n in engine_diff:
        print(f"    {n:20s} 旧={extract_seq_old(n)}  新={extract_seq(n)}"
              f"   <- 新行为与 baseOf/extensionOf 的拆分方式一致（点开头不拆扩展名）")
    check("dotfile 的新行为确实是'拆出序号'而不是变 null",
          all(extract_seq(n) is not None for n in engine_diff))

# dotfile 那条改动是好还是坏：应当与 Naming.baseOf 的拆分口径一致
for n in [".IMG_0042.jpg"]:
    base_new = split_ext(n)[0]
    check(f"{n} 的 base 按 splitExt 口径 = {base_new!r}（与 baseOf 一致）",
          base_new == ".IMG_0042")

def seq_run_len(name):
    """按新实现的取法，算出"序号候选"那段连续数字有多长。"""
    base = split_ext(name)[0]
    for cand in (ANY_BRACKET.sub(" ", base), base):
        end = len(cand)
        while end > 0 and not cand[end - 1].isdigit():
            end -= 1
        if end == 0:
            continue
        start = end
        while start > 0 and cand[start - 1].isdigit():
            start -= 1
        return end - start
    return 0


print()
print("=" * 72)
print("四、旧 seqOf 的三类毛病（已修）：补上真序号、去掉假序号、不再截断")
print("=" * 72)
# 这里记一个**被推翻的假设**，免得下次又想当然：
#   ✗ "统一后 Advisor 的判定完全不变"
# 真实情况有两类变化，都是修 bug：
#   (a) 补上 —— 旧正则要求名字末尾就是数字，`IMG_1234 (1)` 以 `)` 结尾，
#       于是**带括号副本标记的名字一律被判成'没有序号'**；
#   (b) 去掉 —— 旧正则只吃最后 6 位，会把 10~16 位的长数字**截断成假序号**
#       （`20240920143015` → "序号 143015"、"16 位哈希" → "序号 12345"）。
lost = [n for n in CORPUS if seq_of_old(n) is not None and seq_of(n, False) is None]
gained = [n for n in CORPUS if seq_of_old(n) is None and seq_of(n, False) is not None]
print(f"  补上序号（旧 null → 新有值）: {len(gained)} 例")
print(f"  去掉序号（旧有值 → 新 null）: {len(lost)} 例")
for n in lost:
    print(f"    {n:30s} 旧={seq_of_old(n):>8d}  新=null   数字串长={seq_run_len(n)}（>9 视为时间戳/哈希）")

check("确实补上了值（政策在起作用）", len(gained) > 0)
# 承重不变式：消失的值**只能**是"截断出来的假序号"
check("去掉的每一例，其数字串长度都 > 9（即确实是截断造出来的假序号）",
      all(seq_run_len(n) > 9 for n in lost),
      f"不符：{[n for n in lost if seq_run_len(n) <= 9]}")
check("没有任何**真实序号**从'有'变成'没有'",
      all(seq_run_len(n) > 9 for n in lost))

# 补上的那批，必须全部是"旧 seqOf 是唯一的异见者"——
# 配对引擎（一直会剥括号）在那批输入上本来就能取到值。
# 这才叫"统一到正确的那一份"，而不是"凭空造出序号"。
engine_agrees = [n for n in gained if extract_seq_old(n) is not None]
print(f"  补上的那批里，配对引擎本来就能取到值的：{len(engine_agrees)} / {len(gained)}")
check("每一例'补上'都是把异见者对齐到引擎（不是凭空造序号）",
      len(engine_agrees) == len(gained),
      f"不符：{[n for n in gained if extract_seq_old(n) is None]}")

# 统一之后，两套口径的分歧必须只剩"年份算不算"这一条显式政策
policy_gap = [n for n in CORPUS
              if (seq_of(n, False) is None) != (extract_seq(n) is None)
              and not (seq_of(n, False) is None and extract_seq(n) is not None)]
check("统一后，统计口径与引擎口径不再有'隐性'分歧（只剩年份政策）",
      policy_gap == [], f"实际 {policy_gap}")

# Advisor 的真实判定方式（>60% 才算"带序号体系"）
print()
print("  按 Advisor 的真实判定跑三组语料：")
for label, names, expect in [
    ("A 序号体系 IMG_0001..", [f"IMG_{i:04d}.jpg" for i in range(1, 11)], "不变"),
    ("B 年份命名 IMG_2024..", [f"IMG_{y}.jpg" for y in range(2015, 2025)], "不变"),
    ("C 括号副本 IMG_x (1)..", [f"IMG_{i} (1).jpg" for i in range(1, 11)], "补上"),
]:
    old_ratio = sum(1 for n in names if seq_of_old(n) is not None) / len(names)
    new_ratio = sum(1 for n in names if seq_of(n, False) is not None) / len(names)
    fires_old, fires_new = old_ratio > 0.6, new_ratio > 0.6
    print(f"    {label:24s} 旧 {old_ratio:3.0%} / 新 {new_ratio:3.0%}   "
          f"{'触发' if fires_old else '不触发'} -> {'触发' if fires_new else '不触发'}")
    if expect == "不变":
        check(f"{label}：判定未变", fires_old == fires_new)
    else:
        check(f"{label}：由'不触发'修正为'触发'（旧实现看不见括号前的序号）",
              (not fires_old) and fires_new)

# 年份政策必须原封不动：一排只有年份的名字不能被当成"带序号体系"
check("年份命名不会被当成序号体系（误报防线仍在）",
      sum(1 for n in [f"IMG_{y}.jpg" for y in range(2015, 2025)]
          if seq_of(n, False) is not None) == 0)


print()
print("=" * 72)
print("五、边界")
print("=" * 72)
check("空名字 → null", seq_of("", True) is None and seq_of_old("") is None)
check("无名后缀 → null", seq_of("noext", True) is None)
check("纯数字名 → 有值", seq_of("1.jpg", True) == 1)
check("超长数字（16 位）→ null（当时间戳）", seq_of("hash_0123456789012345.jpg", True) is None)
check("10 位数字 → null", extract_seq("DSC_1234567890.jpg") is None)
check("9 位数字 → 有值", extract_seq("DSC_123456789.jpg") == 123456789)
check("中文括号同样被剥", extract_seq("IMG_1234（12）.jpg") == 1234)
check("方括号同样被剥", extract_seq("IMG_1234 [3].jpg") == 1234)
check("尾部点不拆扩展名（与 splitExt 一致）", extract_seq("photo.") is None)
check("双扩展名只去最后一段", extract_seq("a.tar.gz") is None)
check("前导空格不影响", extract_seq("  IMG_0011  .jpg") == 11)
check("Int 溢出 → null", seq_of("x999999999999.jpg", True) is None)
check("默认参数就是统计口径（年份→null）", seq_of("IMG_2024.jpg") is None)
check("显式 true 就是引擎口径", seq_of("IMG_2024.jpg", True) == 2024)

print()
print("=" * 72)
if problems:
    print(f"发现问题 {len(problems)} 处：")
    for p in problems:
        print(f"  - {p}")
    raise SystemExit(1)
print("全部通过。")
print("统一后只剩一份实现，政策差异只由 treatYearAsSeq 一个参数表达；")
print("配对引擎除 dotfile 外逐项零回归；")
print("统计口径的两处变化都是修复：补上括号副本名里的真序号、去掉长数字截断出的假序号。")
