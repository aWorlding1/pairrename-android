"""验证缓存键加入目录后：跨目录不再串味，且改名迁移依然有效。

修复前：缓存只用显示名作键。切到另一个目录时，同名的
`IMG_0001.jpg` 会拿到上一个目录的拍摄时间 / 内容指纹 ——
配对悄悄错掉，用户完全看不出来。
"""
DCIM = "tree:/DCIM/Camera"
WECHAT = "tree:/Pictures/WeChat"


class Cache:
    def __init__(self):
        self.exif, self.hash = {}, {}

    @staticmethod
    def key(tree, name):
        return f"{tree}#{name}"

    def put(self, tree, name, t=None, h=None):
        k = self.key(tree, name)
        if t is not None: self.exif[k] = t
        if h is not None: self.hash[k] = h

    def enrich(self, tree, items):
        return [(n, self.exif.get(self.key(tree, n), t)) for n, t in items]

    def content_keys(self, tree, items):
        return {n: self.hash[self.key(tree, n)] for n in items
                if self.key(tree, n) in self.hash}

    def migrate(self, tree, old, new):
        ok, nk = self.key(tree, old), self.key(tree, new)
        for m in (self.exif, self.hash):
            if ok in m:
                m[nk] = m.pop(ok)


c = Cache()
# 1) 先在 DCIM 读了 EXIF 与内容指纹
c.put(DCIM, "IMG_0001.jpg", t=1700000000, h="ha")
c.put(DCIM, "IMG_0002.jpg", t=1700000100, h="hb")

print("=== 场景 1：切到另一目录（同名文件）")
wechat = ["IMG_0001.jpg", "IMG_0002.jpg"]
got = c.enrich(WECHAT, [(n, 0) for n in wechat])
leaked = sum(1 for _, t in got if t > 0)
print(f"  WeChat 目录同名文件拿到的时间：{got}")
print(f"  串味数量 {leaked}（应为 0）")
assert leaked == 0, "仍然串味！"

ck = c.content_keys(WECHAT, wechat)
print(f"  内容指纹泄漏 {len(ck)} 条（应为 0）")
assert len(ck) == 0, "内容指纹串味！"
print("  OK：跨目录隔离")

print("\n=== 场景 2：原目录的缓存仍然有效")
back = c.enrich(DCIM, [("IMG_0001.jpg", 0), ("IMG_0002.jpg", 0)])
print(f"  切回 DCIM：{back}")
assert all(t > 0 for _, t in back), "切回后原缓存丢失！"
print("  OK：切回原目录缓存仍在")

print("\n=== 场景 3：改名迁移仍正确（不能因为改键而失效）")
c.migrate(DCIM, "IMG_0001.jpg", "DSC_0001.jpg")
after = c.enrich(DCIM, [("DSC_0001.jpg", 0)])
print(f"  改名后：{after}")
assert after[0][1] == 1700000000, "改名后迁移失败！"
ck2 = c.content_keys(DCIM, ["DSC_0001.jpg"])
print(f"  内容指纹：{ck2}")
assert ck2 == {"DSC_0001.jpg": "ha"}, "改名后指纹丢失！"
# 旧名字不应残留
old_left = c.enrich(DCIM, [("IMG_0001.jpg", 0)])
assert old_left[0][1] == 0, "旧名字有残留！"
print("  OK：改名迁移正确，旧键已清除")

print("\n=== 场景 4：两个目录各自改名互不影响")
c.put(WECHAT, "IMG_0001.jpg", t=1800000000)
c.migrate(DCIM, "IMG_0002.jpg", "DSC_0002.jpg")
print(f"  DCIM  DSC_0002: {c.enrich(DCIM, [('DSC_0002.jpg', 0)])[0][1]}")
print(f"  WeChat IMG_0001: {c.enrich(WECHAT, [('IMG_0001.jpg', 0)])[0][1]}")
assert c.enrich(WECHAT, [("IMG_0001.jpg", 0)])[0][1] == 1800000000
assert c.enrich(DCIM, [("DSC_0002.jpg", 0)])[0][1] == 1700000100
print("  OK：两目录各自独立")

print("\n结论：跨目录不再串味，改名迁移与目录切换均正确")
