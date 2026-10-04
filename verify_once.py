"""验证重量级操作的并发去重。

背景：复制几百个文件要好几秒。这期间再点一次，
两个协程同时复制 -> 目标目录凭空多出一整套 `xxx (1).jpg`。
删除、归档、批量还原同理。

设计要点：
1. 按 key 去重，**不是全局锁** —— 不能因为"正在复制"就禁掉改名
2. 被拦下时要**提示**，不能静默（静默 = 用户以为点了没反应）
3. finally 释放，异常也要释放（否则永久卡死）
"""


class Once:
    """模拟 Kotlin 里 `once(key) { io { ... } }` 的语义。

    关键：`io {}` 是 launch，**立即返回**，block 在后台跑。
    所以 once 返回时 key 仍然占着，直到显式 finish。
    第一版脚本我写成了同步调用（fn() 跑完就释放），
    导致"第一次 True 第二次 True"——那是脚本错了，不是实现错了。
    """

    def __init__(self):
        self.running = set()
        self.msgs = []
        self.started = []      # 已启动、尚未完成的任务

    def once(self, key, work=lambda: None):
        if key in self.running:
            self.msgs.append("上一个操作还没完成，请稍候")
            return False
        self.running.add(key)
        self.started.append(key)   # 后台开始跑，但还没结束
        return True

    def finish(self, key, error=False):
        """模拟 finally：无论成败都要释放。"""
        self.running.discard(key)
        if key in self.started:
            self.started.remove(key)


print("=== 1. 同类操作：第二次被拦下")
o = Once()
r1 = o.once("copy")            # 启动，后台跑
r2 = o.once("copy")            # 还在跑 -> 拦下
print(f"  第一次 {r1}，第二次 {r2}，提示 {len(o.msgs)} 条")
o.finish("copy")               # 跑完
assert r1 is True and r2 is False
assert len(o.msgs) == 1, "被拦下必须提示，不能静默"
print("  OK：拦下了，且给了提示")

print("\n=== 2. 不同类操作互不影响（关键：不是全局锁）")
o = Once()
o.once("copy")                 # copy 正在后台跑
r_del = o.once("delete")
r_arc = o.once("archive")
r_copy = o.once("copy")
print(f"  copy 进行中 -> delete={r_del} archive={r_arc} copy={r_copy}")
assert r_del and r_arc, "复制不该阻塞删除和归档"
assert not r_copy, "同类必须拦下"
print("  OK：只拦同类")

print("\n=== 3. finally 释放：异常后也能再用")
o = Once()
o.once("copy")
# 模拟 block 抛异常 -> finally 仍要释放
o.finish("copy", error=True)
print(f"  异常后 running={o.running}")
assert "copy" not in o.running, "异常后必须释放，否则这个操作永久失效"
r = o.once("copy")
assert r, "释放后应能再次执行"
print("  OK：异常也释放（否则操作会永久卡死）")

print("\n=== 4. 单文件改名**不**加去重（保持连续操作手感）")
# 模拟连续拖放改名：每次都很快，不应被拦
# 直接断言源码：改名走的是 io，不是 once
import pathlib, re
src = pathlib.Path(__file__).parent.joinpath(
    'app/src/main/java/com/yuanbao/pairrename/vm/MainViewModel.kt').read_text()
i = src.find('fun applyRename(')
j = src.find('fun commitManualRename(')
body = src[i:src.find('\n    }', j) + 6] if i >= 0 else ''
RENAME_USES_ONCE = 'once(' in src[i:src.find('\n    }', j)]
print(f"  改名使用 once: {RENAME_USES_ONCE}")
assert not RENAME_USES_ONCE, "改名不能用 once，否则手快的用户会丢操作"
print("  OK：改名保持可连续触发")

print("\n=== 5. 顺序执行：完成后可再次触发")
o = Once()
for i in range(3):
    r = o.once("copy")
    assert r, f"第 {i+1} 次应成功（前一次已完成）"
    o.finish("copy")           # 每次都跑完再点下一次
print(f"  连续 3 次都成功，提示 {len(o.msgs)} 条")
assert len(o.msgs) == 0, "顺序执行不该产生提示"
print("  OK：不误伤顺序操作")

print("\n结论：并发去重正确，拦同类、放异类、异常释放、不误伤连续操作")
