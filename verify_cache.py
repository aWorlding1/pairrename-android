"""验证元信息缓存：EXIF 时间 / 内容指纹能否跨「刷新」与「改名」存活。

这是本轮修的核心缺陷：listImages 每次返回全新对象，
原来刷新一次辛辛苦苦读出来的拍摄时间就全清零，配对悄悄退回推算。
沙盒无 kotlinc，用 Python 复刻缓存与迁移逻辑做等价验证。
"""


class Cache:
    """复刻 ViewModel 里 exifCache / hashCache + migrateMeta"""

    def __init__(self):
        self.exif = {}
        self.hash = {}

    def put(self, name, taken, digest=None):
        self.exif[name] = taken
        if digest:
            self.hash[name] = digest

    def migrate(self, old, new):
        """改名后迁移（对应 migrateMeta）"""
        if old == new:
            return
        for m in (self.exif, self.hash):
            if old in m:
                m[new] = m.pop(old)

    def enrich(self, items):
        """用缓存回填一次扫描结果（对应 enrich）"""
        return [(n, self.exif.get(n, t)) for n, t in items]

    def content_keys(self, items):
        """内容指纹按名字派生（对应 deriveContentKeys）"""
        return {n: self.hash[n] for n, _ in items if n in self.hash}


# ---- 场景 1：刷新后 EXIF 是否存活 ----
print("=== 场景 1：读取 EXIF 后刷新目录")
c = Cache()
scanned = [("a.jpg", 0), ("b.jpg", 0), ("c.jpg", 0)]
c.put("a.jpg", 1000, "h1")
c.put("b.jpg", 2000, "h2")
c.put("c.jpg", 3000, "h3")

# 模拟刷新：listImages 返回全新对象，takenAt 全为 0
rescanned = [("a.jpg", 0), ("b.jpg", 0), ("c.jpg", 0)]
after = c.enrich(rescanned)
survived = sum(1 for _, t in after if t > 0)
print(f"  刷新前已读 3 张 -> 刷新后仍有 {survived} 张带时间")
assert survived == 3, "刷新后 EXIF 丢失！"
print("  OK：跨刷新存活")

# ---- 场景 2：改名后是否存活（关键）----
print("\n=== 场景 2：按配对统一改名后")
c2 = Cache()
c2.put("IMG_0001.jpg", 1000, "ha")
c2.put("IMG_0002.jpg", 2000, "hb")
# 用户执行统一：IMG_0001.jpg -> DSC_0001.jpg
c2.migrate("IMG_0001.jpg", "DSC_0001.jpg")
c2.migrate("IMG_0002.jpg", "DSC_0002.jpg")
after_rename = [("DSC_0001.jpg", 0), ("DSC_0002.jpg", 0)]
got = c2.enrich(after_rename)
print(f"  改名并刷新后：{got}")
survived2 = sum(1 for _, t in got if t > 0)
assert survived2 == 2, "改名后 EXIF 丢失！"
print("  OK：改名后跟着迁移，没有丢")

# 内容指纹同样存活（这是内容配对跨改名有效的关键）
ck = c2.content_keys(after_rename)
print(f"  内容指纹派生出 {len(ck)} 条 -> {ck}")
assert len(ck) == 2, "改名后内容指纹丢失！"
print("  OK：内容配对在改名后依然可用")

# ---- 场景 3：撤销（反向改名）也要迁回 ----
print("\n=== 场景 3：撤销后迁回原名")
c3 = Cache()
c3.put("a.jpg", 1000)
c3.migrate("a.jpg", "b.jpg")      # 改名
c3.migrate("b.jpg", "a.jpg")      # 撤销
print(f"  回到原名后时间：{c3.exif}")
assert c3.exif.get("a.jpg") == 1000, "撤销后时间没迁回！"
assert "b.jpg" not in c3.exif, "撤销后残留了新名字的条目"
print("  OK：撤销能正确迁回")

# ---- 场景 4：没读过的文件不受影响 ----
print("\n=== 场景 4：未读过 EXIF 的文件")
c4 = Cache()
c4.put("a.jpg", 1000)
got4 = c4.enrich([("a.jpg", 0), ("z.jpg", 0)])
print(f"  {got4}")
assert dict(got4) == {"a.jpg": 1000, "z.jpg": 0}
print("  OK：只回填有缓存的，没读过的保持 0")

print("\n结论：元信息缓存跨刷新/改名/撤销均正确存活")
