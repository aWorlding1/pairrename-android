#!/usr/bin/env python3
"""沙盒无法联网安装 kotlinc / Android SDK，这里做一次静态一致性自检：
1) 括号平衡  2) 项目内对象的方法调用是否存在  3) R.string 引用是否都已定义
4) data class 属性引用是否存在  5) Gradle version catalog 别名是否齐全
"""
import re, sys, pathlib

# 让 read_text() 默认按 UTF-8 读。
# Windows 上默认是本地代码页（简体中文是 cp936），把 UTF-8 源码按 cp936
# 解码会得到乱码 —— 正则碰巧还工作，但只要注释里出现一个 cp936 无法映射的
# 字节序列就会直接抛异常，而且报的还是"文件读不了"这种看不出原因的错误。
_orig_read_text = pathlib.Path.read_text


def _read_text_utf8(self, *args, **kwargs):
    kwargs.setdefault("encoding", "utf-8")
    return _orig_read_text(self, *args, **kwargs)


pathlib.Path.read_text = _read_text_utf8

# 路径相对脚本自身推导，别写死沙盒里的绝对路径 ——
# 写死之后换台机器 / 换个解压目录就会 FileNotFoundError，
# 而自检脚本跑不起来，比没有自检更危险（会让人以为"检查过了"）。
ROOT = pathlib.Path(__file__).resolve().parent
APP = ROOT / "app/src/main"
KTS = sorted((APP / "java").rglob("*.kt"))
errors, warns = [], []


def strip_comments_and_strings(src):
    out, i, n = [], 0, len(src)
    while i < n:
        c = src[i]
        if c == '/' and i + 1 < n and src[i + 1] == '/':
            while i < n and src[i] != '\n':
                i += 1
        elif c == '/' and i + 1 < n and src[i + 1] == '*':
            i += 2
            while i + 1 < n and not (src[i] == '*' and src[i + 1] == '/'):
                i += 1
            i += 2
        elif c == '"':
            i += 1
            while i < n and src[i] != '"':
                if src[i] == '\\':
                    i += 1
                i += 1
            i += 1
        elif c == "'":
            i += 1
            while i < n and src[i] != "'":
                if src[i] == '\\':
                    i += 1
                i += 1
            i += 1
        else:
            out.append(c)
            i += 1
    return ''.join(out)


# 1) 括号平衡
for f in KTS:
    code = strip_comments_and_strings(f.read_text())
    for a, b in (('{', '}'), ('(', ')'), ('[', ']')):
        if code.count(a) != code.count(b):
            errors.append("[括号] %s: %s%s 不平衡 %d vs %d" % (f.name, a, b, code.count(a), code.count(b)))


def funcs_of(fragment):
    names = set()
    for f in KTS:
        # 必须用 as_posix()：str(Path) 在 Windows 上是反斜杠，
        # 而 fragment 写的是正斜杠（"util/Naming.kt"）——
        # 不做归一化的话这个 `in` 判断**恒为假**，符号表永远是空的，
        # 于是每一个调用都被报成"未定义"（301 条噪音，自检彻底失效）。
        if fragment in f.as_posix():
            names |= set(re.findall(r"\bfun\s+(\w+)\s*\(", f.read_text()))
    return names


def props_of(class_name):
    props = set()
    for f in KTS:
        # 支持嵌套 data class：缩进可以是任意空白
        m = re.search(r"data class %s\s*\((.*?)\n\s*\)" % class_name, f.read_text(), re.S)
        if m:
            for line in m.group(1).split('\n'):
                mm = re.match(r"\s*(?:val|var)\s+(\w+)\s*:", line)
                if mm:
                    props.add(mm.group(1))
        # 顶层（非嵌套）写法：结尾 \) 顶格
        m2 = re.search(r"(?<!\n    )data class %s\s*\((.*?)\n\)" % class_name, f.read_text(), re.S)
        if m2:
            for line in m2.group(1).split('\n'):
                mm = re.match(r"\s*(?:val|var)\s+(\w+)\s*:", line)
                if mm:
                    props.add(mm.group(1))
    for f in KTS:
        for m in re.finditer(r"\bval\s+(\w+)\s*:\s*[\w<>?,.\s\[\]()]+\s+get\(\)", f.read_text()):
            props.add(m.group(1))
    return props


# 2) 方法调用
targets = {
    "Naming": (funcs_of("util/Naming.kt"), r"\bNaming\.(\w+)\s*\("),
    "Batch": (funcs_of("util/Batch.kt"), r"\bBatch\.(\w+)\s*\("),
    "vm": (funcs_of("vm/MainViewModel.kt"), r"\bvm\.(\w+)\s*\("),
    "docs": (funcs_of("data/DocsRepository.kt"), r"\bdocs\.(\w+)\s*\("),
    "settingsRepo": (funcs_of("data/SettingsRepository.kt"), r"\bsettingsRepo\.(\w+)\s*\("),
}
for f in KTS:
    src = f.read_text()
    for owner, (defined, pattern) in targets.items():
        for call in re.findall(pattern, src):
            if call not in defined:
                errors.append("[方法] %s: %s.%s() 未定义" % (f.name, owner, call))

# 3) 属性引用
#
# `item.side` 是属性访问；`item.takeIf { … }` 是**长得一模一样的调用** ——
# 接收者扩展函数用尾 lambda 写就是 `x.f { … }`，正则里的 `(?!\s*\()` 只挡得住
# `x.f(...)` 那一种写法，挡不住 `{`。这类名字（let/also/run/use/…）
# 在任何类型上都不可能是本项目的属性，所以逐个列出；
# 比把前瞻放宽成 `(?!\s*[\(\{])` 更安全 —— 后者会漏掉
# "属性本身是个函数、用尾 lambda 调用" 的真错误。
_STDLIB_EXT_FNS = {
    "let", "also", "apply", "run", "with", "use", "takeIf", "takeUnless",
    "repeat", "onEach", "forEach", "ifEmpty", "orEmpty",
}
prop_targets = {
    "ui": (props_of("UiState"), r"(?<![\w.])ui\.(\w+)\b(?!\s*\()"),
    "state": (props_of("PaneState"), r"(?<![\w.])state\.(\w+)\b(?!\s*\()"),
    "settings": (props_of("AppSettings"), r"(?<![\w.])settings\.(\w+)\b(?!\s*\()"),
    "item": (props_of("ImageItem"), r"(?<![\w.])item\.(\w+)\b(?!\s*\()"),
}
for f in KTS:
    src = '\n'.join(l for l in f.read_text().split('\n') if not l.strip().startswith("import "))
    for owner, (defined, pattern) in prop_targets.items():
        for used in re.findall(pattern, src):
            if (used not in defined
                    and used not in ("copy", "value", "key")
                    and used not in _STDLIB_EXT_FNS):
                warns.append("[属性] %s: %s.%s 不在定义中" % (f.name, owner, used))

# 4) R.string 引用
strings_xml = (APP / "res/values/strings.xml").read_text()
defined_strings = set(re.findall(r'<string name="(\w+)"', strings_xml))
for f in KTS:
    for used in re.findall(r"R\.string\.(\w+)", f.read_text()):
        if used not in defined_strings:
            errors.append("[资源] %s: R.string.%s 未在 strings.xml 定义" % (f.name, used))

# 4b) 用了 R.xxx 就必须能解析到 R
#     R 生成在 com.yuanbao.pairrename 下：同包文件直接可见，子包文件必须
#     import com.yuanbao.pairrename.R。上面那条只查"名字在 strings.xml 里有没有"，
#     查不到"这个名字根本解析不到"—— v5.17.0 就是在这里栽的：
#     ToolsHub.kt 新加了 stringResource(R.string.action_diagnostics) 却漏了 import R，
#     静态自检报"全对"，4 分钟后的 Kotlin 编译才炸出 Unresolved reference 'R'。
#     一条零成本可提前发现的错，不该被推到构建阶段。
R_TYPES = ("string", "drawable", "style", "id", "color", "mipmap", "xml", "layout",
           "array", "font", "raw", "menu", "anim", "plurals", "bool", "integer",
           "dimen", "attr")
R_USE = re.compile(r"(?<![\w.])R\.(%s)\b" % "|".join(R_TYPES))
for f in KTS:
    code = strip_comments_and_strings(f.read_text())
    if not R_USE.search(code):
        continue
    m = re.search(r"^\s*package\s+([\w.]+)", code, re.M)
    if m and m.group(1) == "com.yuanbao.pairrename":
        continue
    if re.search(r"^\s*import\s+com\.yuanbao\.pairrename\.R\s*$", code, re.M):
        continue
    errors.append(
        "[资源] %s: 用了 R.xxx，但既不在 com.yuanbao.pairrename 包内，"
        "也没 import com.yuanbao.pairrename.R —— 编译必报 Unresolved reference 'R'" % f.name
    )

manifest = (APP / "AndroidManifest.xml").read_text()
for ref in re.findall(r'@drawable/(\w+)', manifest):
    if not list(APP.rglob("drawable/%s.*" % ref)):
        errors.append("[资源] AndroidManifest: @drawable/%s 不存在" % ref)
for ref in re.findall(r'@style/(\w+)', manifest):
    found = any(ref in p.read_text() for p in (APP / "res/values").glob("*.xml"))
    if not found:
        errors.append("[资源] AndroidManifest: @style/%s 不存在" % ref)
for ref in re.findall(r'@string/(\w+)', manifest):
    if ref not in defined_strings:
        errors.append("[资源] AndroidManifest: @string/%s 未定义" % ref)

# 5) Gradle version catalog
toml = (ROOT / "gradle/libs.versions.toml").read_text()
toml_libs = set(re.findall(r"^([\w-]+)\s*=", toml, re.M))
toml_plugins = set(re.findall(r"^([\w-]+)\s*=\s*\{\s*id", toml, re.M))
for f in sorted(ROOT.rglob("*.kts")):
    for alias in re.findall(r"libs\.([\w.]+)", f.read_text()):
        if alias == "versions.toml":
            continue
        if alias.startswith("plugins."):
            key, pool, kind = alias[len("plugins."):].replace('.', '-'), toml_plugins, "plugins"
        else:
            key, pool, kind = alias.replace('.', '-'), toml_libs, "libraries"
        if key not in pool:
            errors.append("[Gradle] %s: libs.%s → %s.%s 缺失" % (f.name, alias, kind, key))

# 6) 重复命名参数 —— Kotlin 硬错误，会直接编译失败
def _enum_values():
    out = {}
    for f in sorted(ROOT.rglob("*.kt")):
        t = re.sub(r"/\*.*?\*/", "", f.read_text(), flags=re.S)
        t = re.sub(r"//[^\n]*", "", t)
        for m in re.finditer(r"enum class (\w+)\s*\{([^}]*)\}", t):
            vals = [v.strip().split("(")[0].strip() for v in m.group(2).split(",")]
            vals = [v for v in vals if v and re.match(r"^\w+$", v)]
            if vals:
                out[m.group(1)] = vals
    return out


for f in KTS:
    lines = f.read_text().split("\n")
    i = 0
    while i < len(lines):
        line = lines[i]
        if line.rstrip().endswith("(") and "(" in line:
            depth, j, blk = 0, i, []
            while j < len(lines) and j < i + 60:
                blk.append(lines[j])
                depth += lines[j].count("(") - lines[j].count(")")
                if depth <= 0 and j > i:
                    break
                j += 1
            base = len(re.match(r"^\s*", line).group(0))
            names = [m.group(2) for b in blk[1:]
                     for m in [re.match(r"^(\s+)(\w+)\s*=\s*", b)]
                     if m and len(m.group(1)) == base + 4]
            for dup in sorted({n for n in names if names.count(n) > 1}):
                errors.append("[重复参数] %s:%d 命名参数 '%s' 写了两次" % (f.name, i + 1, dup))
            i = j
        i += 1

# 7) when 表达式未穷举枚举 —— Kotlin 硬错误
_enum_cache = _enum_values()
for f in KTS:
    t = f.read_text()
    for m in re.finditer(r"when\s*[({]", t):
        start, depth, i = m.end(), 1, m.end()
        while i < len(t) and depth > 0:
            if t[i] == '{':
                depth += 1
            elif t[i] == '}':
                depth -= 1
            i += 1
        block_txt = t[m.start():i]
        if re.search(r"\belse\s*->", block_txt):
            continue
        for en, vals in _enum_cache.items():
            # 只统计「分支标签」：Enum.X 后面紧跟 -> （或逗号分隔后跟 ->）。
            # 不能简单搜 Enum.X —— 分支**体**里也可能出现（比如
            # runAdvice 里 copy(screen = Screen.COMPARE)），那会产生误报。
            lab_re = (r"(?:(?:" + en + r"\.\w+)\s*,\s*)*(?:" + en + r"\.\w+)\s*(?=->)")
            found = set()
            for lab in re.findall(lab_re, block_txt):
                found |= set(re.findall(r"\b" + en + r"\.(\w+)", lab))
            if not found:
                continue
            missing = set(vals) - found
            if missing and 1 <= len(found) < len(vals):
                ln = t[:m.start()].count("\n") + 1
                errors.append("[枚举穷举] %s:%d when(%s) 缺 %s"
                              % (f.name, ln, en, sorted(missing)))

# 8) 跨包使用项目类却没有 import —— 编译错误
def _project_classes():
    out = {}
    for f in KTS:
        c = strip_comments_and_strings(f.read_text())
        pkg = re.search(r'^package\s+([\w.]+)', c, re.M)
        if not pkg:
            continue
        for m in re.finditer(r'^(?:@\w+\s+)?(?:public\s+|internal\s+|private\s+)?'
                             r'(?:data\s+|sealed\s+|enum\s+|value\s+)?'
                             r'(class|object|interface)\s+(\w+)', c, re.M):
            out.setdefault(m.group(2), pkg.group(1))
    return out


_proj = _project_classes()
for f in KTS:
    raw = f.read_text()
    c = strip_comments_and_strings(raw)
    pkg = re.search(r'^package\s+([\w.]+)', c, re.M)
    if not pkg:
        continue
    imports = set(re.findall(r'^import\s+([\w.]+)', raw, re.M))
    body = '\n'.join(l for l in c.split('\n')
                      if not l.strip().startswith(('import ', 'package ')))
    for name, cp in _proj.items():
        if cp == pkg.group(1):
            continue
        # 排除全限定名（前面紧跟 . 的不是裸引用）
        if not re.search(r'(?<![\w.])' + name + r'\b', body):
            continue
        if any(i.endswith('.' + name) for i in imports):
            continue
        errors.append("[缺 import] %s: 用了 %s.%s 但没导入" % (f.name, cp, name))

# 9) 调用点参数问题：
#    a) 命名参数重复（不论缩进）
#    b) 必需参数缺失
def _split_args(block_text):
    """
    把调用块按顶层逗号切分。
    用括号深度判断"顶层"，这样 lambda 内部的逗号不会干扰。
    """
    parts, cur, depth = [], [], 0
    for ch in block_text:
        if ch in '([{':
            depth += 1
        elif ch in ')]}':
            depth -= 1
        # block 是括号内部的内容，所以"顶层"是 depth == 0
        if ch == ',' and depth == 0:
            parts.append(''.join(cur)); cur = []
        else:
            cur.append(ch)
    if ''.join(cur).strip():
        parts.append(''.join(cur))
    return parts


def _named_args(block_text):
    """提取调用块**顶层**的命名参数名（深度 1，忽略 lambda 内部）。"""
    names = []
    for part in _split_args(block_text):
        m = re.match(r'\s*(\w+)\s*=\s*(?!=)', part)
        if m:
            names.append(m.group(1))
    return names


def _fn_defs():
    """解析函数/Composable 定义：名字 -> 必需参数名集合（无默认值的）。"""
    defs = {}
    for f in KTS:
        c = strip_comments_and_strings(f.read_text())
        for m in re.finditer(r'\nfun\s+(\w+)\s*\(', c):
            name = m.group(1)
            i = m.end() - 1
            depth = 0
            j = i
            while j < len(c):
                if c[j] == '(':
                    depth += 1
                elif c[j] == ')':
                    depth -= 1
                    if depth == 0:
                        break
                j += 1
            inner = c[i + 1:j]
            req = []
            for part in _split_args(inner):
                pm = re.match(r'\s*(?:@\w+\s+)*(?:val\s+|var\s+)?(\w+)\s*:', part)
                if pm and '=' not in part:
                    req.append(pm.group(1))
            # 保留顺序：最后一个参数可能是 lambda，便于排除 trailing lambda
            cur = defs.get(name)
            if cur is None:
                defs[name] = req
            elif len(req) > len(cur):
                defs[name] = req
    return defs


for f in KTS:
    t = f.read_text()
    for m in re.finditer(r'\b(\w+)\s*\(', t):
        name = m.group(1)
        i = m.end() - 1
        depth = 0
        j = i
        while j < len(t):
            if t[j] == '(':
                depth += 1
            elif t[j] == ')':
                depth -= 1
                if depth == 0:
                    break
            j += 1
        if j >= len(t):
            continue
        block = t[i + 1:j]
        # a) 重复命名参数（不限缩进）
        seen, dup = set(), set()
        for n in _named_args(block):
            if n in seen:
                dup.add(n)
            seen.add(n)
        for d in sorted(dup):
            ln = t[:m.start()].count('\n') + 1
            errors.append('[重复参数] %s:%d %s() 的命名参数 [%s] 写了两次'
                          % (f.name, ln, name, d))

# b) 必需参数缺失（只检查"全部用命名参数"的调用，位置参数无法判断）
_fn_req = _fn_defs()
for f in KTS:
    t = f.read_text()
    for m in re.finditer(r'\b(\w+)\s*\(', t):
        name = m.group(1)
        req = _fn_req.get(name)
        if not req:
            continue
        i = m.end() - 1
        depth = 0
        j = i
        while j < len(t):
            if t[j] == '(':
                depth += 1
            elif t[j] == ')':
                depth -= 1
                if depth == 0:
                    break
            j += 1
        if j >= len(t):
            continue
        block = t[i + 1:j]
        parts = [x for x in _split_args(block)]
        if not parts:
            continue
        # 全是命名参数才检查（末尾允许 trailing lambda）
        named, has_positional = [], False
        for part in parts:
            st = part.strip()
            if not st:
                continue
            mm = re.match(r'^(\w+)\s*=\s*(?!=)', st)
            if mm:
                named.append(mm.group(1))
            elif st.startswith('}') or st.startswith(')'):
                continue          # 收尾符号
            else:
                has_positional = True
                break
        if has_positional or not named:
            continue
        req2 = list(req)
        # 调用后紧跟 { 说明用了 trailing lambda，
        # 它对应函数定义的最后一个参数（Composable 的常见写法）
        tail = t[j + 1:j + 40].lstrip()
        if tail.startswith('{') and req2:
            req2 = req2[:-1]
        missing = sorted(set(req2) - set(named))
        if missing:
            ln = t[:m.start()].count('\n') + 1
            errors.append('[缺参数] %s:%d %s() 缺少必需参数 %s'
                          % (f.name, ln, name, missing))

# 10) remember(...) 的 key 是否覆盖块内用到的外部变量
#     漏 key = 该变的时候不变，界面显示陈旧数据
_REM_USUAL = {
    'mutableStateOf', 'mutableFloatStateOf', 'mutableIntStateOf', 'mutableLongStateOf',
    'let', 'also', 'apply', 'run', 'fun', 'else', 'return', 'key',
    'toImmutableList', 'emptyList', 'emptyMap', 'emptySet', 'true', 'false', 'null',
    # CompositionLocal 取出的值本身是稳定的（context / density 等）
    'context', 'density', 'configuration', 'lifecycleOwner',
}


for f in KTS:
    t = f.read_text()
    for m in re.finditer(r'\bremember\s*\(([^)]*)\)\s*\{', t):
        keys = set(k.strip() for k in m.group(1).split(',') if k.strip())
        i = t.index('{', m.end() - 1)
        depth, j = 0, i
        while j < len(t):
            if t[j] == '{':
                depth += 1
            elif t[j] == '}':
                depth -= 1
                if depth == 0:
                    break
            j += 1
        body = t[i:j]
        scope = t[:m.start()]
        tail = scope[-2500:]
        # 排除 by-delegate 声明的 State（`var x by remember { mutableStateOf }`）——
        # 它们是稳定的持有者，不需要进 key
        for mm in re.finditer(r'(?<!\.)\b([a-z][\w]*)\b(?!\s*\()', body):
            u = mm.group(1)
            # 除了「整个标识符就是 key」，还要认「key 是它的成员访问」：
            # 块里读的是 `state.items`，key 里写的也正是 `state.items`，
            # 这时裸的 `state` 不该再被判成漏 key。
            # （原来只做全等比较，于是 PaneColumn 那处正确的代码天天报假警 ——
            #   而天天报假警的自检，最后一定会被无视。）
            if u in _REM_USUAL or u in keys or any(k.startswith(u + '.') for k in keys):
                continue
            # 跳过属性访问（`item.side` 里的 side 不算独立变量）
            if mm.start() and body[mm.start() - 1] == '.':
                continue
            # 跳过「被赋值」：lambda 里写 `over = true` 只是改 State 的值，
            # 不需要进 key。但**读取** State 的值参与计算是要进 key 的。
            after = body[mm.end():mm.end() + 3]
            if re.match(r'\s*=(?!=)', after):
                continue
            # 只看本函数作用域内的参数 / 局部变量
            if (re.search(r'\b(?:val|var)\s+' + u + r'\b', tail)
                    or re.search(r'^\s*' + u + r'\s*:', tail, re.M)):
                # 若它本身就是 remember / 常量派生的，跳过
                if re.search(r'\b(?:val|var)\s+' + u + r'\s*=\s*remember', tail):
                    continue
                ln = t[:m.start()].count('\n') + 1
                errors.append('[remember] %s:%d remember 的 key 缺少 [%s]'
                              % (f.name, ln, u))

# 11) 引用了 sealed 类型里不存在的成员 —— 硬编译错误
#     例：`UiEvent.ConfirmPlan(...)` 但 sealed interface 里没有 ConfirmPlan。
#     这一类错误最阴险：括号平衡、import、资源引用检查全都发现不了，
#     只有编译器会报"unresolved reference"。
for f in KTS:
    t = f.read_text()
    c = strip_comments_and_strings(t)
    for m in re.finditer(r'\bsealed\s+(?:interface|class)\s+(\w+)', c):
        name = m.group(1)
        i = c.index('{', m.end() - 1)
        depth, j = 0, i
        while j < len(c):
            if c[j] == '{':
                depth += 1
            elif c[j] == '}':
                depth -= 1
                if depth == 0:
                    break
            j += 1
        block = c[i:j]
        defined = set(re.findall(r'(?:data class|data object|object|class)\s+(\w+)', block))
        if not defined:
            continue
        for u in set(re.findall(r'\b' + name + r'\.(\w+)', c)):
            if u not in defined:
                # 找具体行号（在未去注释的原文里找）
                mm = re.search(r'\b' + name + r'\.' + u + r'\b', t)
                ln = t[:mm.start()].count('\n') + 1 if mm else 0
                errors.append('[未定义成员] %s:%d %s.%s 在 sealed 声明里不存在'
                              % (f.name, ln, name, u))

# 12) return@标签 是否指向真实存在的 lambda —— 硬编译错误
#     典型错误：在 `io { }` 里写 `return@launch`。
#     lambda 的隐式标签是**接收它的函数名**（这里是 io），
#     而 launch 这个标签在当前作用域里根本不存在，编译器直接报错。
#     本项目曾一次性存在 34 处这种错误，必须常驻检测。
# 通用匹配：任何「标识符 + { / (」都可能是 lambda 标签。
# 用枚举列表会漏掉 Compose 的 TopAppBar{ }、LaunchedEffect{ } 等，造成误报。
_KW = r'(?!(?:if|for|while|when|catch|try|else|do|return|fun|val|var|class|object|interface|enum|import|package|super|this|new|throw|break|continue)\b)(\w+)'
_STOP = {'if', 'for', 'while', 'when', 'catch', 'try', 'else', 'do', 'return',
         'fun', 'val', 'var', 'class', 'object', 'interface', 'enum', 'import',
         'package', 'super', 'this', 'new', 'throw', 'break', 'continue'}


def _bad_return_labels(src):
    bad = []
    stack = []          # [(depth, label)] 当前还在体内的 lambda
    depth = 0
    # 每层括号记「这个 ( 属于哪个函数调用的参数表」。
    #
    # 必须用**栈**而不是单槽：`registerForActivityResult(A(...)) { }` 里，
    # 内层 `A(` 会把外层名字覆盖掉，于是尾随 lambda 被挂到 A 名下，
    # `return@registerForActivityResult` 就成了"未知标签"（假警报）。
    paren = []
    # 最近一个刚闭合的「函数调用参数表」的函数名 —— 它就是紧随其后的尾随 lambda 的标签
    just_closed = None
    for raw in src.split('\n'):
        line = re.sub(r'//.*$', '', raw)
        line = re.sub(r'"(?:[^"\\]|\\.)*"', '""', line)
        i = 0
        while i < len(line):
            m = re.match(r'\b' + _KW + r'\s*\{', line[i:])
            m2 = re.match(r'\b' + _KW + r'\s*\(', line[i:])
            if m and m.group(1) in _STOP:
                m = None
            if m2 and m2.group(1) in _STOP:
                m2 = None
            if m:
                # 形如 `io {` / `launch {`：直接就是 lambda
                stack.append((depth, m.group(1)))
                just_closed = None
                depth += 1
                i += m.end()
                continue
            if m2:
                # 参数列表开始；真正的 lambda 体是后面那个 {
                paren.append(m2.group(1))
                just_closed = None
                depth += 1
                i += m2.end()
                continue
            ch = line[i]
            if ch == '(':
                paren.append(None)      # if / while / 纯分组
                just_closed = None
                depth += 1
            elif ch == '{':
                if just_closed:
                    # `foo(...) {` —— 尾随 lambda，标签是 foo
                    stack.append((depth, just_closed))
                elif paren and paren[-1]:
                    # `Foo(actions = {` —— 具名 lambda 参数，标签也是 Foo
                    stack.append((depth, paren[-1]))
                just_closed = None
                depth += 1
            elif ch == ')':
                depth -= 1
                just_closed = paren.pop() if paren else None
                while stack and stack[-1][0] >= depth:
                    stack.pop()
            elif ch == '}':
                depth -= 1
                just_closed = None
                while stack and stack[-1][0] >= depth:
                    stack.pop()
            i += 1
        if 'return@' in raw:
            for mr in re.finditer(r'return@(\w+)', line):
                lab = mr.group(1)
                if lab not in [l for _, l in stack]:
                    ln = src[:src.index(raw)].count('\n') + 1
                    bad.append((ln, lab, [l for _, l in stack]))
    return bad


# 这一项只作为提示，不阻断：标签识别本质上靠启发式，宁可漏报也不要误报成错误。
# 它已经兑现过价值 —— 一次性抓出 34 处 `io { return@launch }`。
# （早先的两类误报 —— 跨行参数列表 `registerForActivityResult(...)\n{ }`
#   和具名 lambda 参数 `TopAppBar(actions = { } )` —— 已经由上面的括号栈修掉。）
for f in KTS:
    for ln, lab, have in _bad_return_labels(f.read_text()):
        warns.append('[return标签?] %s:%d return@%s 作用域内未见该标签（可用: %s）'
                     % (f.name, ln, lab, ', '.join(have[-4:]) or '无'))

# 13) res/values 里重复的资源名 —— 后面的静默覆盖前面的
#     最阴险的一类：不报错，但某个提示语会变成另一个文案，
#     参数还可能对不上（常见表现：提示语里的文件名不见了）。
import collections as _collections
import xml.etree.ElementTree as _ET

_RES_DIRS = list(pathlib.Path(ROOT).rglob('app/src/main/res/values*'))


def _res_names():
    """返回 {目录: [(name, text)]}"""
    out = {}
    for d in _RES_DIRS:
        names = []
        for f in sorted(d.glob('*.xml')):
            try:
                txt = f.read_text()
            except Exception:
                continue
            for m in re.finditer(r'<string\s+name="([^"]+)"[^>]*>(.*?)</string>', txt, re.S):
                names.append((m.group(1), m.group(2)))
        if names:
            out[str(d)] = names
    return out


_all_res = _res_names()
for d, pairs in _all_res.items():
    cnt = _collections.Counter(n for n, _ in pairs)
    for name, c in cnt.items():
        if c > 1:
            texts = [t for n, t in pairs if n == name]
            errors.append('[重复资源] %s 中 <string name="%s"> 定义了 %d 次：%s'
                          % (d.replace(str(pathlib.Path(ROOT)) + '/', ''), name, c,
                             ' | '.join(x[:24] for x in texts)))

# 14) getString(R.string.X, args...) 的实参个数 vs 格式串占位符个数
_res_map = {}
for _pairs in _all_res.values():
    for n, t in _pairs:
        _res_map.setdefault(n, t)

for f in KTS:
    t = f.read_text()
    for m in re.finditer(r'getString\(\s*R\.string\.(\w+)', t):
        name = m.group(1)
        spec = _res_map.get(name)
        if spec is None:
            continue
        # getString 自己的 '(' 位置：从 'getString' 之后找，
        # 不能从匹配末尾往前找 —— 那样会跳到别的函数调用上
        i = t.index('(', m.start() + len('getString') - 1)
        depth, j = 0, i
        while j < len(t):
            if t[j] == '(':
                depth += 1
            elif t[j] == ')':
                depth -= 1
                if depth == 0:
                    break
            j += 1
        inner = t[i + 1:j]
        # 首个顶层逗号之后才是额外实参
        depth, k = 0, -1
        for idx, ch in enumerate(inner):
            if ch in '([{':
                depth += 1
            elif ch in ')]}':
                depth -= 1
            elif ch == ',' and depth == 0:
                k = idx
                break
        args = 0
        if k >= 0:
            rest = inner[k + 1:]
            depth, cur, parts = 0, [], []
            for ch in rest + ',':
                if ch in '([{':
                    depth += 1
                elif ch in ')]}':
                    depth -= 1
                if ch == ',' and depth == 0:
                    parts.append(''.join(cur))
                    cur = []
                else:
                    cur.append(ch)
            args = len([x for x in parts if x.strip()])
        want = len(set(re.findall(r'%(\d+)\$', spec))) or len(re.findall(r'%[sdf]', spec))
        if want != args:
            ln = t[:m.start()].count('\n') + 1
            errors.append('[格式串] %s:%d R.string.%s 需要 %d 个参数，实际传了 %d 个（"%s"）'
                          % (f.name, ln, name, want, args, spec[:30]))

# 15) 用了实验性 API 却没有 @OptIn —— 硬编译错误
#     这类错误我踩过两次：加 UI 时漏了文件级注解，整个模块编译不过。
#     用全限定名写 @file:OptIn 可以避免额外 import，这里也一并支持识别。
_EXPER_API = {
    'combinedClickable': 'ExperimentalFoundationApi',
    'dragAndDropSource': 'ExperimentalFoundationApi',
    'dragAndDropTarget': 'ExperimentalFoundationApi',
    'Marquee': 'ExperimentalFoundationApi',
    'SegmentedButton': 'ExperimentalMaterial3Api',
    'SingleChoiceSegmentedButtonRow': 'ExperimentalMaterial3Api',
    'FilterChip': 'ExperimentalMaterial3Api',
    'ModalNavigationDrawer': 'ExperimentalMaterial3Api',
    'NavigationBar': 'ExperimentalMaterial3Api',
    'FlowRow': 'ExperimentalLayoutApi',
    'FlowColumn': 'ExperimentalLayoutApi',
}

for f in KTS:
    t = f.read_text()
    c = strip_comments_and_strings(t)
    # 注解里允许换行、允许多条（逗号分隔）、允许全限定名。
    # 原来的正则要求 `@file:OptIn(` 之后**紧跟** `Xxx::class`，
    # 一旦把多条 OptIn 合并成多行写法就识别不到 —— 会误报"缺 @OptIn"。
    opted = set()
    for block in re.findall(r'@(?:file:)?OptIn\(([^)]*)\)', c, re.S):
        opted |= set(re.findall(r'(?:\w+\.)*(\w+)::class', block))
    # 文件级注解的作用域是整文件；函数级只覆盖该函数，这里不细分（宁可多报）
    for kw, api in _EXPER_API.items():
        if re.search(r'\b' + kw + r'\b', c) and api not in opted:
            ln = t.find(kw)
            ln = t[:ln].count('\n') + 1 if ln >= 0 else 0
            errors.append('[缺 OptIn] %s:%d 用了实验性 API [%s]，但文件里没有 @OptIn(%s::class)'
                          % (f.name, ln, kw, api))

# 16) 在 lambda 里引用了本函数没声明的变量 —— 硬编译错误
#     我自己刚犯过：写返回键处理时凭印象写了 archivePlan / duplicatesFor，
#     而实际状态叫 showArchive / showDuplicates。
def _declared_vars(t):
    """提取 Composable 函数体内的 var/val 声明（粗略但够用）。"""
    out = set()
    # var x by remember {...} —— by-delegate 声明，最常见
    for m in re.finditer(r'\bvar\s+(\w+)\s+by\s+', t):
        out.add(m.group(1))
    for m in re.finditer(r'\bvar\s+(\w+)\s*[=:]', t):
        out.add(m.group(1))
    for m in re.finditer(r'\bval\s+(\w+)\s*[=:]', t):
        out.add(m.group(1))
    # 函数参数
    for m in re.finditer(r'\n\s*(?:@\w+(?:\([^)]*\))?\s*)*(?:val\s+)?(\w+)\s*:\s*[A-Z]', t):
        out.add(m.group(1))
    return out


for f in KTS:
    t = f.read_text()
    c = strip_comments_and_strings(t)
    declared = _declared_vars(c)
    # 只查 X = ... 这种赋值语句的左值（在 BackHandler / onClick 这类块里）
    for m in re.finditer(r'\n\s*(\w+)\s*=\s*(?:null|false|true|emptyList\(\)|emptySet\(\)|emptyMap\(\))\s*\n', c):
        name = m.group(1)
        if name in declared:
            continue
        # 可能是别的文件的字段 / 属性，跳过（无法确认）
        if re.search(r'\.\s*' + name + r'\b', c):
            continue
        ln = c[:m.start()].count('\n') + 1
        warns.append('[未声明变量?] %s:%d 给 [%s] 赋值，但本文件未见其声明'
                     % (f.name, ln, name))

# i) 同文件「裸调用」未定义。
#    补这条的直接原因：只会检查 `Xxx.method()` 的检查器看不见 `normStem()`。
#    Pairing.kt 里 normStem 的整段定义丢失、调用还在，brace/static 双双报 OK ——
#    直到读代码才发现。一个只看得见一类调用的检查器，和看不见没有区别。
#
#    判定收得很紧（小写开头 + 全项目找不到任何声明 + 不在标准库白名单），
#    宁可漏报也不制造噪音：噪音一多，人就会开始无视输出，检查器就白做了。
_STDLIB_FNS = {
    "arrayListOf", "mutableListOf", "listOf", "setOf", "mapOf", "hashMapOf",
    "hashSetOf", "mutableMapOf", "mutableSetOf", "emptyList", "emptyMap",
    "emptySet", "linkedMapOf", "linkedSetOf", "sortedMapOf", "sortedSetOf",
    "require", "requireNotNull", "check", "checkNotNull", "println", "print",
    "error", "lazy", "remember", "rememberSaveable", "mutableStateOf",
    "derivedStateOf", "produceState", "repeat", "runCatching", "regex",
    "intArrayOf", "floatArrayOf", "doubleArrayOf", "longArrayOf", "arrayOf",
    "byteArrayOf", "charArrayOf", "booleanArrayOf", "withContext", "launch",
    "delay", "invoke", "synchronized",
    # 顶层工具函数
    "minOf", "maxOf", "buildString", "listOfNotNull", "sortedWith", "trim",
    # kotlin.comparisons.compareBy(vararg selectors) 是唯一常见带括号形式的
    # 比较器构造函数（lambda 形式 `compareBy<T>{…}` 因后面是 `{` 不会被匹配）。
    "compareBy",
    # 接收者作用域调用：`edit { put(…) }`、`catch { emit(…) }`、
    # Activity 上的 startActivity() 等。没有类型信息就分不清"隐式接收者的
    # 成员"和"本该在本文件定义的函数"，为了不制造噪音，只能白名单放过。
    "put", "emit", "startActivity", "startTransfer", "getApplication",
    "getString", "setContent", "addView", "removeView", "setOnClickListener",
}
_KW = {
    "if", "for", "while", "when", "catch", "return", "is", "in", "as",
    "else", "do", "try", "fun", "val", "var", "class", "object", "interface",
    "super", "this", "throw", "assert", "with", "apply", "let", "run",
    "also", "takeIf", "takeUnless", "use", "typeof", "true", "false", "null",
}


def _declared_names():
    """全项目里出现过「声明」的标识符：函数 / 类型 / 属性 / 参数 / 注解。"""
    names = set()
    for f in KTS:
        c = strip_comments_and_strings(f.read_text())
        # `fun <reified T : Enum<T>> enumOr(` 这种带泛型参数的也要认出来。
        # 非贪婪 + 回溯：先在 `Enum<T>` 里那个 `>` 处试一次，不成立再扩到最后一个 `>`
        names |= set(re.findall(r"\bfun\s*(?:<[^(){}]*?>\s*)?(\w+)", c))
        names |= set(re.findall(r"\b(?:class|interface|object|enum|typealias)\s+(\w+)", c))
        names |= set(re.findall(r"\b(?:val|var)\s+(\w+)", c))
        names |= set(re.findall(r"@(\w+)", c))
        # 形如 `name:` 的参数 / 属性声明
        names |= set(re.findall(r"(?<![.\w])(\w+)\s*:", c))
        # 顶层函数可能来自 import（如 `import ...io`）
        names |= set(re.findall(r"^import\s+[\w.]*?(\w+)\s*$", c, re.M))
    return names


_DECLARED = _declared_names()
for f in KTS:
    t = f.read_text()
    c = strip_comments_and_strings(t)
    missing = {}
    for m in re.finditer(r"(?<![\w.@$])([a-z]\w*)[ \t]*\(", c):
        name = m.group(1)
        # `val isEmpty: Boolean get() = …` 是属性访问器声明，不是调用。
        # m.end() 正好落在那个 '(' 之后，所以后面应当是 `)` 再接 `=` 或 `{`。
        if name in ("get", "set") and re.match(r"\)[ \t\n]*[={]", c[m.end():]):
            continue
        if name in _KW or name in _STDLIB_FNS or name in _DECLARED:
            continue
        missing[name] = missing.get(name, 0) + 1
    for name, cnt in sorted(missing.items()):
        errors.append("[未定义函数] %s: %s() 全项目找不到定义（调用 %d 次）"
                      % (f.name, name, cnt))

print("=" * 60)
print("Kotlin 文件：%d 个" % len(KTS))
if errors:
    print("\n发现 %d 个问题：" % len(errors))
    for e in errors:
        print("  x", e)
else:
    print("\n[OK] 括号平衡 / 方法与属性引用 / R.string / Gradle 别名 全部一致")
if warns:
    print("\n%d 条提示：" % len(warns))
    for w in warns:
        print("  -", w)
sys.exit(1 if errors else 0)
