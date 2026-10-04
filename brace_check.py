import sys

BS = chr(92)  # backslash

def strip_code(s):
    out = []
    i = 0
    n = len(s)
    while i < n:
        c = s[i]
        if c == '/' and i + 1 < n and s[i + 1] == '/':
            while i < n and s[i] != chr(10):
                i += 1
        elif c == '/' and i + 1 < n and s[i + 1] == '*':
            i += 2
            while i + 1 < n and not (s[i] == '*' and s[i + 1] == '/'):
                i += 1
            i += 2
        elif s[i:i + 3] == '"""':
            i += 3
            while i + 2 < n and s[i:i + 3] != '"""':
                i += 1
            i += 3
        elif c == '"' or c == "'":
            q = c
            i += 1
            while i < n:
                if s[i] == BS:
                    i += 2
                    continue
                if s[i] == q:
                    i += 1
                    break
                i += 1
        else:
            out.append(c)
            i += 1
    return ''.join(out)


def main(paths):
    ok = True
    for f in paths:
        s = open(f, encoding='utf-8').read()
        t = strip_code(s)
        b = t.count('{') - t.count('}')
        p = t.count('(') - t.count(')')
        mark = 'OK' if (b == 0 and p == 0) else 'BROKEN'
        if mark != 'OK':
            ok = False
        print('%-7s braces=%+d parens=%+d  %s' % (mark, b, p, f))
    print('ALL BALANCED' if ok else '!! FIX NEEDED')


if __name__ == '__main__':
    main(sys.argv[1:])
