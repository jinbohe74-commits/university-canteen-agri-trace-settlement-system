# -*- coding: utf-8 -*-
"""颜色令牌 codemod：把 <style> 块里写死的十六进制色收敛到 --* 令牌。

为什么要做：深色模式下"我的在售货源""信用分明细"的正文几乎看不见——
样式里写死了 #15803d / #1b2a24 这类深绿近黑色，绕过令牌后不会随明度切换。

为什么只改 <style>：ECharts 的 option 在 <script> 里，传 'var(--accent)' 它解析不了，
会导致图表颜色直接失效，必须由 useThemeColors 读取后再传。
"""
import io
import os
import re

ROOT = r'C:\Users\何金波\Desktop\管理信息系统\frontend\src'

MAP = {
    # 文本色
    '#15803d': 'var(--accent)',
    '#16a34a': 'var(--accent)',
    '#0d9488': 'var(--accent)',
    '#0f766e': 'var(--accent)',
    '#1b2a24': 'var(--ink)',
    '#14532d': 'var(--ink)',
    '#111827': 'var(--ink)',
    '#27362f': 'var(--ink)',
    '#374151': 'var(--ink-2)',
    '#4b7a60': 'var(--muted)',
    '#6b7280': 'var(--muted)',
    '#9ca3af': 'var(--muted)',
    '#b45309': 'var(--warn)',
    '#f59e0b': 'var(--warn)',
    '#b91c1c': 'var(--danger)',
    '#dc2626': 'var(--danger)',
    '#22c55e': 'var(--ok)',
    # 浅底
    '#f8fbfa': 'var(--surface-2)',
    '#f2fbf5': 'var(--surface-2)',
    '#f0fdf4': 'var(--surface-2)',
    '#f7faf9': 'var(--surface-2)',
    '#eef3f1': 'var(--surface-2)',
    '#e8f7ee': 'var(--accent-soft)',
    '#e6f6f4': 'var(--accent-soft)',
    '#d6f0e0': 'var(--accent-soft)',
    '#bbe6cb': 'var(--accent-soft)',
}

HEX = re.compile(r'#[0-9a-fA-F]{6}\b')

files = []
for sub in ('views', 'components', 'layout'):
    for dirpath, _, names in os.walk(os.path.join(ROOT, sub)):
        for n in names:
            if n.endswith('.vue'):
                files.append(os.path.join(dirpath, n))

total = 0
touched = 0
left = {}
for f in files:
    s = io.open(f, encoding='utf-8').read()
    i = s.find('<style')
    if i < 0:
        continue
    head, tail = s[:i], s[i:]
    n = [0]

    def rep(m):
        v = m.group(0).lower()
        if v in MAP:
            n[0] += 1
            return MAP[v]
        left[v] = left.get(v, 0) + 1
        return m.group(0)

    tail2 = HEX.sub(rep, tail)
    if n[0]:
        io.open(f, 'w', encoding='utf-8').write(head + tail2)
        total += n[0]
        touched += 1

print('样式块内替换', total, '处，涉及', touched, '个文件')
print('未映射（保留原色）前 10：')
for k, v in sorted(left.items(), key=lambda x: -x[1])[:10]:
    print('  ', k, v)
