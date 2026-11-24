# -*- coding: utf-8 -*-
"""字号令牌 codemod：把各 .vue 里散落的裸 px 字号统一映射到 --fs-* 比例尺。"""
import io
import os
import re

ROOT = r'C:\Users\何金波\Desktop\管理信息系统\frontend\src'


def token_for(v):
    if v <= 10.6:
        return 'var(--fs-2xs)'
    if v <= 11.6:
        return 'var(--fs-xs)'
    if v <= 12.6:
        return 'var(--fs-sm)'
    if v <= 14.0:
        return 'var(--fs-base)'
    if v <= 16.0:
        return 'var(--fs-md)'
    if v <= 19.5:
        return 'var(--fs-lg)'
    if v <= 25.0:
        return 'var(--fs-xl)'
    return 'var(--fs-display)'


PAT = re.compile(r'font-size:\s*([0-9]+(?:\.[0-9]+)?)px')

files = []
for sub in ('views', 'components', 'layout'):
    base = os.path.join(ROOT, sub)
    for dirpath, _, names in os.walk(base):
        for n in names:
            if n.endswith('.vue'):
                files.append(os.path.join(dirpath, n))

changed = {}
total = 0
for f in files:
    s = io.open(f, encoding='utf-8').read()
    hits = PAT.findall(s)
    if not hits:
        continue
    ns = PAT.sub(lambda m: 'font-size: ' + token_for(float(m.group(1))), s)
    io.open(f, 'w', encoding='utf-8').write(ns)
    changed[f.replace(ROOT + os.sep, '')] = len(hits)
    total += len(hits)

print('文件数', len(changed), '替换处数', total)
for k, v in sorted(changed.items(), key=lambda x: -x[1])[:6]:
    print('  ', k, v)
