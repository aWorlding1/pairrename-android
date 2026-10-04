"""验证确认对话框的收敛纪律（判据 D：一个动作一个名字/一条流程）。

四处「问一次再执行」的对话框（改名确认 / 批量计划 / 归档预览 / 冲突三选一）
加删除确认与回收站清空，收敛成两条可检查的纪律：

  D1 确认动词 = 后果 —— 按钮写「应用 / 删除 / 覆盖 / 确认清空」这类具体动词，
     不用「确定 / OK」这种说了等于没说的词。
D2 不可恢复且尚无安全退路的操作：必须 fail-closed，不向用户提供执行入口。
     「覆盖」尚未把冲突文件纳入回收站/撤销栈，当前从弹窗和设置中禁用；
     「清空回收站」是显式永久删除，仍须保持 error 色。
     可恢复的操作（删除 → 回收站）用普通色即可，满屏 error 反而稀释警觉。

另钉一处文案谎言的修复（判据 C 的反面教材）：
  删除确认正文曾写着「此操作不可撤销」——而 deleteChecked 是移入回收站、
  可恢复的。**提示比代码更危险的地方在于：它劝阻的是可以安全做的事。**

全部用源码扫描断言（沙盒无 kotlinc）；每条都附「为什么」。
"""
import re
import pathlib

BASE = pathlib.Path(__file__).parent
SRC = BASE / "app/src/main/java/com/yuanbao/pairrename"
STRINGS = (BASE / "app/src/main/res/values/strings.xml").read_text(encoding="utf-8")
DIALOGS = (SRC / "ui/dialogs/Dialogs.kt").read_text(encoding="utf-8")
SETTINGS = (SRC / "ui/dialogs/SettingsDialog.kt").read_text(encoding="utf-8")
VIEW_MODEL = (SRC / "vm/MainViewModel.kt").read_text(encoding="utf-8")
TRASH = (SRC / "ui/dialogs/TrashDialog.kt").read_text(encoding="utf-8")


def body_of(text: str, fun: str) -> str:
    """取指定 composable 的函数体（大括号配平）。"""
    m = re.search(r"fun " + re.escape(fun) + r"\(", text)
    assert m, f"找不到 {fun}"
    i = text.find("{", m.end())
    depth, j = 0, i
    while j < len(text):
        depth += text[j] == "{"
        depth -= text[j] == "}"
        if depth == 0:
            break
        j += 1
    return text[i:j + 1]


def string_value(name: str) -> str:
    m = re.search(r'<string name="' + re.escape(name) + r'">(.*?)</string>', STRINGS, re.S)
    assert m, f"strings.xml 里没有 {name}"
    return m.group(1)


n_fail = 0


def check(label, cond):
    global n_fail
    print(f"  [{'OK' if cond else 'FAIL'}]   {label}")
    if not cond:
        n_fail += 1


print("=" * 64)
print("D1 确认动词 = 后果（不用「确定」这类空词）")
print("=" * 64)

# 全部确认按钮动词都在 strings.xml 里定义；空词一旦混进来立即暴露
for word in ("确定", "OK", "好 的", "是的"):
    check(f"strings.xml 里不存在空词「{word}」", word not in STRINGS)
for name, verb in (("apply", "应用"), ("skip", "跳过"),
                   ("auto_rename", "自动改名"), ("action_delete", "删除")):
    check(f"{name} = 「{verb}」（动词即后果）", string_value(name) == verb)

print()
print("=" * 64)
print("D2 无安全退路的操作：fail-closed，无入口")
print("=" * 64)

ask = body_of(DIALOGS, "AskConflictDialog")
# 覆盖此前没有可撤销退路；现在不应在弹窗或设置页提供入口。
check("冲突弹窗不暴露覆盖操作", "onChoose(ConflictPolicy.OVERWRITE)" not in ask)
check("设置页不提供覆盖策略入口", "settings.copy(conflictPolicy = ConflictPolicy.OVERWRITE)" not in SETTINGS)
check("弹窗明确说明覆盖暂不可用", "R.string.conflict_overwrite_hint" in ask)
hint = string_value("conflict_overwrite_hint")
check("覆盖限制引导改用自动改名或跳过", "暂不可用" in hint and "自动改名" in hint and "跳过" in hint)
check("旧版本保存的 OVERWRITE 设置仍会被 fail-closed 拒绝",
      re.search(r"ConflictPolicy\.OVERWRITE\s*->\s*\{[^}]*msg_overwrite_unsafe", VIEW_MODEL, re.S) is not None)

# 回归钉：回收站清空的两步确认保持 error 色（它是最早做对的那个）
check("清空回收站确认按钮保持 error 色（回归钉）",
      'Text("确认清空", color = MaterialTheme.colorScheme.error)' in TRASH)

print()
print("=" * 64)
print("C 判据反面教材：删除确认的文案谎言（已修）")
print("=" * 64)

body = string_value("confirm_delete_body")
print(f"  confirm_delete_body = 「{body}」")
check("不再声称「不可撤销」（删除是进回收站的，可恢复）",
      "不可撤销" not in body and "不可恢复" not in body)
check("说清了去向（回收站）与退路（可恢复）",
      "回收站" in body and "恢复" in body)

# 反例记录：这句话为什么危险 —— 它劝阻的是可以安全做的事
print("""
  修复前的文案：「此操作不可撤销，确定继续吗？」
  实际行为    ：deleteChecked → trash.movePathToTrash（可恢复，撤销栈也记账）
  → 提示与行为相反。用户因为怕不可撤销而拒绝用一个安全功能，
    这比没有提示更糟：提示成了功能的反宣传。""")

print()
print("=" * 64)
if n_fail:
    print(f"结论：{n_fail} 条不达标")
    raise SystemExit(1)
print("结论：确认对话框纪律成立（动词=后果；无安全退路的操作无入口）")
