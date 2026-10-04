"""验证本轮两处上限：撤销栈总步数、缓存条目数。

目标：极端批量操作不会吃光内存，同时超大操作仍可撤销。
"""
MAX_ENTRIES = 64
MAX_STEPS = 20_000
CACHE_LIMIT = 20_000


def push(undo, entry_steps):
    """复刻 pushUndo 的双重上限"""
    while undo and (len(undo) >= MAX_ENTRIES
                    or sum(undo) + entry_steps > MAX_STEPS):
        undo.pop(0)
    undo.append(entry_steps)
    return undo


print("=== 场景 1：常规小操作（不应被裁掉）")
u = []
for i in range(10):
    u = push(u, 2)
print(f"  10 次 × 2 步 -> 栈内 {len(u)} 条，共 {sum(u)} 步")
assert len(u) == 10 and sum(u) == 20

print("\n=== 场景 2：64 次大批量（旧逻辑 64×5000=32万步）")
u = []
for i in range(64):
    u = push(u, 5000)
print(f"  64 次 × 5000 步 -> 栈内 {len(u)} 条，共 {sum(u)} 步")
print(f"  内存占用估算：{sum(u)} 步 × ~200B ≈ {sum(u)*200/1024/1024:.1f} MB")
assert sum(u) <= MAX_STEPS, "总步数超限！"
print(f"  旧逻辑会是 {64*5000*200/1024/1024:.0f} MB -> 现在 {sum(u)*200/1024/1024:.1f} MB")

print("\n=== 场景 3：单次超大操作（必须仍可撤销！）")
u = []
u = push(u, 300_000)   # 单条就远超上限
print(f"  单次 30 万步 -> 栈内 {len(u)} 条")
assert len(u) == 1, "超大操作被丢弃，无法撤销！"
print("  仍然可撤销（接受它占内存，正确性强于省内存）")

print("\n=== 场景 4：混合操作")
u = []
for i in range(70):
    u = push(u, 1)
print(f"  70 次 × 1 步 -> 栈内 {len(u)} 条（应被条目上限截到 64）")
assert len(u) == 64

print("\n=== 场景 5：缓存上限")
class Cache:
    def __init__(self): self.n = 0
    def put(self, k):
        self.n += 1
        if self.n > CACHE_LIMIT:
            self.n = 0        # 清空重来
    def size(self): return self.n

c = Cache()
for i in range(CACHE_LIMIT + 5_000):
    c.put(i)
print(f"  写入 {CACHE_LIMIT+5000} 条 -> 缓存 {c.size()} 条")
assert c.size() <= CACHE_LIMIT
print("  结论：缓存不会无限增长；清空只影响加速效果，不影响正确性")

print("\n结论：两处上限均有效，且超大操作仍可撤销")
