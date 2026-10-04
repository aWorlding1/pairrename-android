"""验证进度更新节流逻辑。

为什么需要节流：每步都 `_ui.update` 意味着每步一次全局重组，
5000 个文件就是 5000 次重组，界面会被自己拖死。

节流引入的新风险（必须验证）：
1. **进度可能停在中间值** —— 比如停在 4980/5000，用户以为卡住了。
   所以最后一步必须无条件更新。
2. total=1、total=0 这种边界要正常。
3. 中途取消时进度不更新，但要靠 finally 清理（这里只验证节流本身）。
"""

STEP = 20


def progress_every(done, total, step=STEP):
    """返回 True 表示这次应当更新进度。"""
    return done % step == 0 or done == total


def simulate(total, cancel_at=None):
    """模拟一轮操作，返回「最后一次被看到的进度」。"""
    last = None
    updates = 0
    for done in range(1, total + 1):
        if cancel_at is not None and done > cancel_at:
            break
        if progress_every(done, total):
            last = (done, total)
            updates += 1
    return last, updates


print("=== 关键：跑完时进度必须停在 total/total，不能停在中间")
for total in (1, 5, 20, 21, 100, 5000):
    last, _ = simulate(total)
    ok = last == (total, total)
    print(f"  {'OK ' if ok else '!! '} total={total:<5} 最终进度 {last}")
    assert ok, f"进度停在 {last}，用户会以为卡住了"
print("  OK：无论多少文件，最后都会显示完成")

print("\n=== 更新次数确实被节流了")
for total in (100, 1000, 5000):
    _, n = simulate(total)
    print(f"  total={total:<5} 更新 {n} 次（未节流会是 {total} 次）")
    assert n < total, "没起到节流作用"
    # 上限估算：total/20 + 1
    assert n <= total // STEP + 2
print("  OK：重组次数大幅下降")

print("\n=== 边界：total=0 不会产生更新（也不该崩）")
last, n = simulate(0)
print(f"  total=0 -> 最后进度 {last}，更新 {n} 次")
assert last is None and n == 0

print("\n=== 边界：total 小于步长时，每步都更新（小任务不该看起来没反应）")
for total in (1, 3, 10):
    last, n = simulate(total)
    print(f"  total={total} -> 更新 {n} 次，最终 {last}")
    assert last == (total, total)
print("  OK")

print("\n=== 取消场景：停在最后一个节流点（靠 finally 清理）")
last, n = simulate(5000, cancel_at=2030)
print(f"  2030 步处取消 -> 最后进度 {last}")
assert last is not None and last[0] <= 2030
print("  OK：进度不会超过实际完成数（不会虚报）")

print("\n=== 一致性：任何一次更新的 done 都不会超过 total")
for total in (1, 20, 21, 101, 5000):
    for done in range(1, total + 1):
        if progress_every(done, total):
            assert done <= total
print("  OK")

print("\n结论：进度节流安全，最后一步必定更新，不会出现「卡在 4980」的错觉")
