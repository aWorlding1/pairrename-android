"""第十三轮验证：批量执行的单点失败隔离与结果报告。

## 本轮修的

两条批量路径（`executePlan` 批量改名、`applyNamesInOrder` 按配对统一）
原来都是 **all-or-nothing**：

```kotlin
if (uri == null) { rollback(steps, row.oldName); emitMsg(...); return@cancellable }
```

一个文件失败 → **整批回滚 + 中止**，并且只弹一句"改名失败：IMG_0042.jpg"。
后果是双重的：

* 200 张图里有 1 张改不了 → 另外 199 张**全部白改**（已改的还被倒着改回去）；
* 真有 5 张没成功时，用户**不知道是哪 5 张**，也不知道能重来。

## 本脚本要证明的四件事

1. **反例成立**：旧实现下"第 47 个文件失败"会让 199 个本来能成功的全部落空；
   新实现只损失那 1 个。这是本轮全部的动机。
2. **账目守恒**：`成功 + 无需改动 + 问题 + 未处理 == 总数`，
   且每一项都不为负 —— 计数写重或写漏都会立刻破掉这个恒等式。
3. **熔断只认"真失败"**：只读 / 冲突是预期内的跳过，不能当"环境坏了"的证据；
   而"跳过夹在失败之间"**不重置**连续失败计数（跳过不构成 provider 正常的证据）。
4. **只有真失败可重试**：只读和冲突重试一次还是同样结果，
   给它们一个重试按钮只会让用户点两次得到同样的失望。
"""

# ---------------------------------------------------------------- 复刻
# 与 BatchReport.kt 一一对应。改了 Kotlin 就要同步改这里。

CONSECUTIVE_FAILURE_LIMIT = 5

READONLY = "READONLY"
CONFLICT = "CONFLICT"
FAILED = "FAILED"


class Issue:
    def __init__(self, row, kind, detail=""):
        self.row = row
        self.kind = kind
        self.detail = detail

    @property
    def old_name(self):
        return self.row["old"]

    @property
    def new_name(self):
        return self.row["new"]

    def __repr__(self):
        return f"Issue({self.old_name}->{self.new_name}, {self.kind})"


class Ledger:
    """复刻 BatchLedger。"""

    def __init__(self, total, failure_limit=CONSECUTIVE_FAILURE_LIMIT):
        self.total = total
        self.failure_limit = failure_limit
        self.collected = []
        self.consecutive_failures = 0
        self.changed = 0
        self.unchanged = 0
        self.aborted = False

    def on_unchanged(self):
        self.unchanged += 1

    def on_changed(self):
        self.changed += 1
        self.consecutive_failures = 0      # 只有这里重置

    def on_issue(self, issue):
        self.collected.append(issue)
        if issue.kind != FAILED:
            return True
        self.consecutive_failures += 1
        if self.consecutive_failures >= self.failure_limit:
            self.aborted = True
            return False
        return True

    def report(self, stopped):
        return Report(self)


class Report:
    """复刻 BatchReport 的派生属性。"""

    def __init__(self, ledger):
        self.total = ledger.total
        self.changed = ledger.changed
        self.unchanged = ledger.unchanged
        self.issues = list(ledger.collected)
        self.aborted = ledger.aborted
        self.stopped_manual = False       # 由 run 填入
        self._stopped = False

    @property
    def readonly(self):
        return sum(1 for i in self.issues if i.kind == READONLY)

    @property
    def conflict(self):
        return sum(1 for i in self.issues if i.kind == CONFLICT)

    @property
    def failed(self):
        return sum(1 for i in self.issues if i.kind == FAILED)

    @property
    def retry_rows(self):
        return [i.row for i in self.issues if i.kind == FAILED]

    @property
    def has_issues(self):
        return len(self.issues) > 0

    @property
    def nothing_changed(self):
        return self.changed == 0

    @property
    def not_attempted(self):
        return max(0, self.total - self.changed - self.unchanged - len(self.issues))

    @property
    def accounted(self):
        return self.changed + self.unchanged + len(self.issues) + self.not_attempted


def rows_of(n, prefix="IMG_", ext=".jpg"):
    return [
        {"old": f"{prefix}{i:04d}{ext}", "new": f"new_{i:04d}{ext}",
         "can_rename": True, "side": "L"}
        for i in range(n)
    ]


def execute_plan(rows, fail_at=(), cancel_at=None, limit=CONSECUTIVE_FAILURE_LIMIT):
    """复刻 executePlan 的循环（剥掉 IO）。

    fail_at   —— 这些下标上的 rename 会返回 null（真失败）
    cancel_at —— 到这个下标时用户点了停止（!isActive）
    """
    ledger = Ledger(len(rows), limit)
    stopped = False
    changed = []
    for i, row in enumerate(rows):
        if cancel_at is not None and i >= cancel_at:
            stopped = True
            break
        if row["new"] == row["old"]:
            ledger.on_unchanged()
        elif not row["can_rename"]:
            ledger.on_issue(Issue(row, READONLY))
        elif i in fail_at:
            if not ledger.on_issue(Issue(row, FAILED)):
                # 熔断：**不设 stopped**（与 Kotlin 一致）
                break
        else:
            changed.append(row["new"])
            ledger.on_changed()
    rep = ledger.report(stopped)
    rep.stopped_manual = stopped
    return rep, changed


def execute_plan_old(rows, fail_at=(), cancel_at=None):
    """**反例**：旧实现 —— 第一个真失败就回滚整批并中止。"""
    changed = []
    for i, row in enumerate(rows):
        if cancel_at is not None and i >= cancel_at:
            return {"changed": 0, "reported": 0, "rolled_back": len(changed)}, []
        if row["new"] == row["old"]:
            continue
        if not row["can_rename"]:
            continue
        if i in fail_at:
            # rollback(steps, row.oldName) + return@cancellable
            return {"changed": 0, "reported": 1, "rolled_back": len(changed)}, []
        changed.append(row["new"])
    return {"changed": len(changed), "reported": 0, "rolled_back": 0}, changed


problems = []


def check(name, cond, detail=""):
    print(f"  [{'PASS' if cond else 'FAIL'}] {name}" + (f"  {detail}" if detail else ""))
    if not cond:
        problems.append(name)


print("=" * 72)
print("一、反例：一张改不了的图会吃掉多少？")
print("=" * 72)

N = 200
BAD = 46                      # 第 47 个文件失败（正好在中间）
rows = rows_of(N)

old, old_changed = execute_plan_old(rows, fail_at={BAD})
new, new_changed = execute_plan(rows, fail_at={BAD})

print(f"  旧实现：成功 {old['changed']}，回滚掉 {old['rolled_back']} 个已改的，"
      f"只报出 {old['reported']} 个失败")
print(f"  新实现：成功 {new.changed}，报出 {len(new.issues)} 个失败")
print(f"  本可以成功却落空的：{new.changed - old['changed']} 个")

check("★ 旧实现下已改成的会被全部回滚（成功数为 0）", old["changed"] == 0)
check("★ 旧实现浪费掉了其余全部可改名项", old["rolled_back"] == BAD)
check("★ 新实现只损失那一个失败项", new.changed == N - 1, str(new.changed))
check("★ 新旧差距 = 被旧实现连累的文件数（本轮动机）",
      new.changed - old["changed"] == N - 1, str(new.changed - old["changed"]))
check("新实现能报出完整的失败清单（不是只报一个）",
      len(new.issues) == 1 and new.issues[0].old_name == rows[BAD]["old"])
check("失败项的名字正确", new.issues[0].old_name == "IMG_0046.jpg")
check("失败项带着「想改成什么」（报告要显示、重试要用）",
      new.issues[0].new_name == "new_0046.jpg")

# 多个失败点
bad_multi = {1, 50, 199}
new2, _ = execute_plan(rows_of(200), fail_at=bad_multi)
check("多个失败点全部报出来（旧实现只报第一个）",
      len(new2.issues) == 3 and new2.changed == 197, str(len(new2.issues)))
check("旧实现在多个失败点下仍然只报 1 个",
      execute_plan_old(rows_of(200), fail_at=bad_multi)[0]["reported"] == 1)

print()
print("=" * 72)
print("二、账目守恒：成功 + 无需改动 + 问题 + 未处理 == 总数")
print("=" * 72)


def conserved(rep):
    return (rep.accounted == rep.total
            and rep.changed >= 0 and rep.unchanged >= 0
            and len(rep.issues) >= 0 and rep.not_attempted >= 0)


cases = [
    ("全部成功", rows_of(50), {}, None, 5),
    ("中间一个失败", rows_of(50), {20}, None, 5),
    ("多个失败", rows_of(50), {1, 2, 40, 47}, None, 5),
    ("用户中途停止", rows_of(50), set(), 17, 5),
    ("连续失败熔断", rows_of(50), set(range(10, 15)), None, 5),
    ("熔断 + 已经改过一些", rows_of(50), set(range(3, 8)), None, 5),
    ("有只读文件", [{**r, "can_rename": i % 3 == 0} for i, r in enumerate(rows_of(30))],
     set(), None, 5),
]
for label, rs, bad, cancel, limit in cases:
    rep, _ = execute_plan(rs, fail_at=bad, cancel_at=cancel, limit=limit)
    print(f"  {label:16s} 总 {rep.total} = 成功 {rep.changed} + 无需改 {rep.unchanged}"
          f" + 问题 {len(rep.issues)} + 未处理 {rep.not_attempted}")
    check(f"{label}：账目守恒", conserved(rep), f"{rep.accounted} vs {rep.total}")

# 「无需改动」也要记账 —— 否则总数对不上
same = [{"old": "a.jpg", "new": "a.jpg", "can_rename": True, "side": "L"},
        {"old": "b.jpg", "new": "b.jpg", "can_rename": True, "side": "L"}]
rep_same, _ = execute_plan(same)
check("名字本来就一样的算「无需改动」而不是消失",
      rep_same.unchanged == 2 and rep_same.accounted == 2, str(rep_same.unchanged))

print()
print("=" * 72)
print("三、熔断：只认真失败，跳过不算、也不重置")
print("=" * 72)

# 1) 连续 5 个真失败 → 熔断
rep_ab, _ = execute_plan(rows_of(100), fail_at=set(range(10, 15)))
print(f"  连续 5 个失败后：aborted={rep_ab.aborted} 成功={rep_ab.changed} "
      f"问题={len(rep_ab.issues)} 未处理={rep_ab.not_attempted}")
check("连续 5 个真失败 → 熔断", rep_ab.aborted)
check("熔断时已成功的部分保留（不回滚）", rep_ab.changed == 10, str(rep_ab.changed))
check("熔断的停止原因与「用户手动停止」区分开", rep_ab.stopped_manual is False)
check("熔断后剩余项记为「未处理」而不是「失败」",
      rep_ab.not_attempted == 100 - 10 - 5, str(rep_ab.not_attempted))

# 2) 4 个失败不熔断（差一个）
rep_4, _ = execute_plan(rows_of(100), fail_at={0, 1, 2, 3})
check("连续 4 个失败不熔断（阈值是 5）", not rep_4.aborted and rep_4.changed == 96)

# 3) 失败之间夹着成功 → 计数被重置，不熔断
interleaved = set()
for start in range(0, 60, 5):
    interleaved.update({start, start + 1, start + 2, start + 3})   # 每组 4 连败后接 1 个成功
rep_int, _ = execute_plan(rows_of(60), fail_at=interleaved)
check("失败被成功打断时不熔断（说明重置生效）", not rep_int.aborted, str(rep_int.aborted))

# 4) ★ 失败之间夹着**只读跳过** → 不重置（跳过不构成 provider 正常的证据）
mixed_rows = rows_of(20)
for i in (2, 4, 6, 8):
    mixed_rows[i] = {**mixed_rows[i], "can_rename": False}     # 只读跳过
nested = set()
for start, end in ((0, 3), (3, 7), (7, 11), (11, 15), (15, 19)):
    nested.update(range(start, end))                            # 每段 4 个失败，段间夹 1 个只读
rep_mix, _ = execute_plan(mixed_rows, fail_at=nested)
print(f"  失败/只读交替：aborted={rep_mix.aborted} 失败={rep_mix.failed} "
      f"只读={rep_mix.readonly} 成功={rep_mix.changed}")
check("★ 只读跳过**不重置**连续失败计数（跳过不算证据）", rep_mix.aborted,
      f"failed={rep_mix.failed} readonly={rep_mix.readonly}")

# 5) 只读 / 冲突**永远不会**触发熔断
all_ro = [{**r, "can_rename": False} for r in rows_of(50)]
rep_ro, _ = execute_plan(all_ro)
check("全是只读文件也不会熔断（预期内的跳过）",
      not rep_ro.aborted and rep_ro.readonly == 50 and rep_ro.changed == 0)
check("全只读时「一项都没成功」是可见的", rep_ro.nothing_changed)

# 6) 阈值可配（复刻里必须能改，否则无法验证边界）
rep_t3, _ = execute_plan(rows_of(20), fail_at={0, 1, 2}, limit=3)
check("阈值可配：limit=3 时 3 连败即熔断", rep_t3.aborted)

print()
print("=" * 72)
print("四、只有真失败值得重试")
print("=" * 72)

mixed = rows_of(10)
mixed[1] = {**mixed[1], "can_rename": False}                 # 只读
mkdir = {"old": "x.jpg", "new": "y.jpg", "can_rename": True, "side": "L"}
rep_mixed, _ = execute_plan(mixed, fail_at={3})
# 手动塞一条冲突（executePlan 路径不会产生冲突，按配对统一路径会）
rep_mixed.issues.append(Issue(mkdir, CONFLICT))

print(f"  只读 {rep_mixed.readonly} / 冲突 {rep_mixed.conflict} / 失败 {rep_mixed.failed}")
print(f"  可重试 {len(rep_mixed.retry_rows)} 项：{[r['old'] for r in rep_mixed.retry_rows]}")
check("三类分别计数正确",
      rep_mixed.readonly == 1 and rep_mixed.conflict == 1 and rep_mixed.failed == 1)
check("★ 可重试的只有真失败（只读 / 冲突不进来）",
      len(rep_mixed.retry_rows) == 1 and rep_mixed.retry_rows[0]["old"] == "IMG_0003.jpg")
check("重试用的是原样的 row（名字和目标都保留，不重新推导）",
      rep_mixed.retry_rows[0]["new"] == "new_0003.jpg")
check("has_issues 正确", rep_mixed.has_issues)
check("没有失败项时没有可重试项",
      len(execute_plan(rows_of(10), fail_at=set())[0].retry_rows) == 0)
ro_only = [{**r, "can_rename": False} for r in rows_of(5)]
check("只有只读时没有可重试项（重试还是同样结果）",
      len(execute_plan(ro_only)[0].retry_rows) == 0)

print()
print("=" * 72)
print("五、用户停止：不说清「未处理多少」就会被误读")
print("=" * 72)
rep_c, _ = execute_plan(rows_of(100), cancel_at=30)
print(f"  停止于第 30 项：成功 {rep_c.changed} 未处理 {rep_c.not_attempted} 总数 {rep_c.total}")
check("手动停止时标记为 stopped（不是熔断）",
      rep_c.stopped_manual is True and rep_c.aborted is False)
check("未处理数算对", rep_c.not_attempted == 70, str(rep_c.not_attempted))
check("账目仍然守恒", conserved(rep_c))
check("停止时已改成的保留", rep_c.changed == 30)

# 前面还有失败 + 手动停止
rep_c2, _ = execute_plan(rows_of(100), fail_at={5, 6}, cancel_at=30)
check("失败 + 手动停止共存时账目守恒", conserved(rep_c2))
# 成功 28（跳过 5、6 两个失败点）+ 问题 2 + 未处理 70 = 100
check("失败 + 手动停止时两类都报",
      len(rep_c2.issues) == 2 and rep_c2.not_attempted == 70,
      f"issues={len(rep_c2.issues)} notAttempted={rep_c2.not_attempted}")

print()
print("=" * 72)
print("六、边界")
print("=" * 72)
rep_e, _ = execute_plan([])
check("空计划：账目守恒且没有任何问题",
      conserved(rep_e) and rep_e.total == 0 and not rep_e.has_issues)
rep_one, _ = execute_plan(rows_of(1), fail_at={0})
check("只有一个文件且失败：成功 0、问题 1、守恒",
      rep_one.changed == 0 and len(rep_one.issues) == 1 and conserved(rep_one))
check("熔断阈值 1：第一个失败就停",
      execute_plan(rows_of(10), fail_at={0}, limit=1)[0].aborted)
check("熔断阈值大于总数：永远不熔断",
      not execute_plan(rows_of(3), fail_at={0, 1, 2}, limit=99)[0].aborted)
check("全是失败且未达阈值：全部走完、全部报出",
      len(execute_plan(rows_of(4), fail_at={0, 1, 2, 3}, limit=4)[0].issues) == 4)
check("熔断点上的那一项也算进问题里（不能吞掉）",
      execute_plan(rows_of(100), fail_at=set(range(5)))[0].failed == 5)

print()
print("=" * 72)
if problems:
    print(f"发现问题 {len(problems)} 处：")
    for p in problems:
        print(f"  - {p}")
    raise SystemExit(1)
print("全部通过。")
print("单点失败只影响它自己（旧实现会连累其余 199 个并全部回滚）；")
print("账目恒等；熔断只认真失败且跳过不重置；只有真失败可重试。")
