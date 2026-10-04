"""验证命名预设与 EXIF 方向修正。

两个关键点：

1. **缺数据时必须返回 None，不能编造假日期**
   —— 没有拍摄时间却编出 `19700101_xxx`，用户扫一眼以为是对的，
   实际上整批名字都错了。这类错误比不改更难发现。

2. **EXIF 方向映射要正确**
   —— 照片是"横存竖显"的，方向错了会躺倒，
   一张躺倒一张正立会让人误判成两张不同的图。
"""

import datetime

PRESETS = ["DATETIME", "DATE", "YEAR_MONTH", "CAMERA_SEQ", "ORIGINAL_SEQ"]


def padded(seq, digits):
    return str(seq).zfill(max(1, min(digits, 8)))


def preset_name(preset, base, taken_at, camera, seq, digits):
    n = padded(seq, digits)
    if preset in ("DATETIME", "DATE", "YEAR_MONTH"):
        if taken_at <= 0:
            return None          # 缺数据 -> 不编造假日期
        dt = datetime.datetime.fromtimestamp(taken_at / 1000)
        fmt = {"DATETIME": "%Y%m%d_%H%M%S",
               "DATE": "%Y%m%d",
               "YEAR_MONTH": "%Y-%m"}[preset]
        return dt.strftime(fmt)
    if preset == "CAMERA_SEQ":
        cam = camera.replace(" ", "_").strip("_")
        return None if not cam else f"{cam}_{n}"
    if preset == "ORIGINAL_SEQ":
        head = base[:12].strip("_")
        return None if not head else f"{head}_{n}"
    return None


TS = int(datetime.datetime(2024, 3, 15, 14, 30, 22).timestamp() * 1000)

print("=== 关键：没有拍摄时间时拒绝命名")
for p in ("DATETIME", "DATE", "YEAR_MONTH"):
    got = preset_name(p, "IMG_0001", 0, "", 1, 3)
    print(f"  {'OK ' if got is None else '!! '} {p} + 无时间 -> {got}")
    assert got is None, f"{p} 在无时间时编造了名字 {got!r}"
print("  OK：不产出 19700101 这类假日期")

print("\n=== 有拍摄时间时格式正确")
cases = [
    ("DATETIME", "20240315_143022"),
    ("DATE", "20240315"),
    ("YEAR_MONTH", "2024-03"),
]
for p, want in cases:
    got = preset_name(p, "x", TS, "", 1, 3)
    ok = got == want
    print(f"  {'OK ' if ok else '!! '} {p} -> {got!r} (期望 {want!r})")
    assert ok

print("\n=== 相机+序号：序号递增、空格转下划线")
a = preset_name("CAMERA_SEQ", "x", 0, "Canon EOS R6", 1, 4)
b = preset_name("CAMERA_SEQ", "x", 0, "Canon EOS R6", 2, 4)
print(f"  {a} / {b}")
assert a == "Canon_EOS_R6_0001" and b == "Canon_EOS_R6_0002"
print("  OK")
# 相机为空 -> 拒绝
assert preset_name("CAMERA_SEQ", "x", 0, "", 1, 3) is None
print("  OK：相机为空时拒绝（不产出 _0001 这种残缺名）")

print("\n=== 原名+序号：截取 12 字")
got = preset_name("ORIGINAL_SEQ", "2024年春节家庭聚会照片", 0, "", 7, 3)
print(f"  {got}")
assert got.startswith("2024年春节家庭聚会照片"[:12]) and got.endswith("_007")
print("  OK")

print("\n=== EXIF 方向 -> 旋转角度")
def rotation_degrees(o):
    return {90: 90.0, 180: 180.0, 270: 270.0}.get(o, 0.0)

for o, want in [(0, 0.0), (90, 90.0), (180, 180.0), (270, 270.0), (5, 0.0), (-1, 0.0)]:
    got = rotation_degrees(o)
    ok = got == want
    print(f"  {'OK ' if ok else '!! '} orientation={o} -> {got}°")
    assert ok
print("  OK：未知值一律按 0 处理（不转，不会显示错乱）")

print("\n=== 缩放边界（模拟 ZoomableImage 的 coerceIn）")
def clamp(cur, zoom, lo=1.0, hi=6.0):
    return max(lo, min(hi, cur * zoom))

for cur, z in [(1.0, 0.5), (1.0, 10.0), (2.0, 2.0), (6.0, 2.0)]:
    got = clamp(cur, z)
    print(f"  {cur}x * {z} -> {got}x")
    assert 1.0 <= got <= 6.0
print("  OK：缩不小也放不大，始终在 [1, 6] 内")

print("\n结论：命名预设与方向修正正确，缺数据不编造假名字")
