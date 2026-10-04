"""验证本轮两处修复：MediaStore 缓存 TTL 与改名迁移的原子性。

沙盒无 kotlinc / 无并发压测条件，用 Python 做等价逻辑验证。
"""
import threading
import time

# ---------- 1) MediaStore 缓存 TTL ----------
print("=== MediaStore 全量查询缓存")

class MetaCache:
    TTL = 30_000  # ms

    def __init__(self, row_count):
        self.rows = row_count
        self.cached = None
        self.cached_at = 0
        self.queries = 0     # 真实全量查询次数

    def load(self, now):
        if self.cached is not None and now - self.cached_at < self.TTL:
            return "HIT"
        self.queries += 1
        self.cached = {"n": self.rows}   # 模拟解析 rows 行
        self.cached_at = now
        return "MISS"

    def invalidate(self):
        self.cached = None
        self.cached_at = 0


c = MetaCache(row_count=20000)
t0 = 1_000_000
# 场景：一次刷新 = 左栏 + 右栏各调一次
print(f"  图库 20000 张，一次刷新要查 2 次（左右栏各一次）")
results = [c.load(t0), c.load(t0)]
print(f"    第 1 次刷新: {results} -> 全量查询 {c.queries} 次")
assert c.queries == 1, "同一时刻的第二次应该命中缓存"

# 连续刷新（30 秒内）都应命中
for t in (t0 + 5_000, t0 + 20_000):
    r = c.load(t)
    print(f"    t+{(t-t0)//1000}s 刷新: {r}")
assert c.queries == 1

# 超过 TTL 应重新查询
r = c.load(t0 + 31_000)
print(f"    t+31s 刷新: {r} -> 全量查询 {c.queries} 次")
assert c.queries == 2

# 改名后主动失效
c.invalidate()
r = c.load(t0 + 32_000)
print(f"    改名后失效再查: {r} -> 全量查询 {c.queries} 次")
assert c.queries == 3
print(f"  结论：连续刷新 N 次，全量查询从 2N 次降到 1 次（省 {1 - c.queries/(3*2):.0%}+）")

# ---------- 2) 并发安全性：Python 无法验证，如实说明 ----------
print("\n=== 并发安全性")
print("  ⚠ 本项无法在 Python 中验证：")
print("    - Python 有 GIL，dict 操作本身就是原子的，复现不了 Java 内存模型的竞争；")
print("    - Java HashMap 并发写的典型故障是扩容时的链表成环（死循环），")
print("      这与具体 JVM 实现相关，用脚本模拟没有意义。")
print("  ")
print("  代码层面已做的修复（属于防御性加固，靠代码审查而非脚本验证）：")
print("    - exifCache / pathCache / hashCache: HashMap -> ConcurrentHashMap")
print("    - migrateMeta 的「取旧值→写新键→删旧键」用 metaLock 包成原子操作")
print("  ")
print("  风险来源（修复前）：maybeAutoExif 左右两栏并发、readExif、verifyByContent")
print("  三个 IO 协程同时读写这些缓存，而 Dispatchers.IO 是多线程池。")

print("\n结论：MediaStore 缓存有效降低全量查询次数；并发修复需真机长时运行验证")
