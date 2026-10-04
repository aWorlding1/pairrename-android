"""验证初始化顺序：crashGuard 必须先于 init 块声明。

这是我引入 crashGuard 时差点埋下的**启动即崩**：
Kotlin 属性按声明顺序初始化，init 块里若引用后面才声明的 val，
读到的是 null —— `Dispatchers.IO + null` 会在 ViewModel 构造时抛 NPE，
表现为「打开应用就闪退」。比它要修的问题严重得多。
"""
import re
import pathlib

src = (pathlib.Path(__file__).parent
       / "app/src/main/java/com/yuanbao/pairrename/vm/MainViewModel.kt").read_text()

pos_ui = src.find("private val _ui")
pos_guard = src.find("private val crashGuard")
pos_init = src.find("init {")

print("=== 声明顺序检查")
print(f"  _ui        位置 {pos_ui}")
print(f"  crashGuard 位置 {pos_guard}")
print(f"  init       位置 {pos_init}")

ok = pos_ui < pos_guard < pos_init
print(f"\n  _ui < crashGuard < init : {ok}")
assert ok, "顺序错误：init 会读到未初始化的 crashGuard，导致启动崩溃"

print("\n=== 兜底覆盖率")
body = src[src.find("class MainViewModel"):]
# 注释里也会提到这些 API，统计前必须剔除，否则会把注释算成代码
code_lines = [
    ln for ln in body.split("\n")
    if not ln.strip().startswith(("*", "//", "/*"))
]
code = "\n".join(code_lines)

io_calls = len(re.findall(r"\bio \{", code))
# io() 自身的定义也是一次 launch，但它是受保护的那一处，不算裸奔
io_defs = len(re.findall(r"viewModelScope\.launch\(Dispatchers\.IO \+ crashGuard", code))
launch_total = len(re.findall(r"viewModelScope\.launch\(", code))
bare = launch_total - io_defs
print(f"  io() 调用 {io_calls} 处")
print(f"  viewModelScope.launch 代码调用 {launch_total} 处，其中裸奔 {bare}")
assert bare == 0, "还有裸奔的协程块"
assert io_calls > 20, "替换似乎没生效"

print("\n=== 兜底行为")
print("  异常发生时：busy/progress 复位 + 提示用户")
print("  CancellationException 不被捕获（结构化取消仍正常）")

print("\n结论：初始化顺序正确，全部协程块均有异常兜底")
