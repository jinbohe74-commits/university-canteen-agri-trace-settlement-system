"""批量给业务 view 接入 useAutoRefresh(load)。

原则（L-007）：先 dry-run 盘点 → 单文件试跑 → 全量 → 逐文件断言。
每个文件只加 2 行：一条 import、一条 composable 调用；业务 DOM / 数据逻辑零改动。
"""
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
VIEWS = ROOT / 'frontend' / 'src' / 'views'
APPLY = '--apply' in sys.argv

SKIP = {
    'admin/Logs.vue',        # 已手工接入
    'admin/Dashboard.vue',   # 稍后重写时自带
    'admin/Trace.vue',       # 没有独立 load，手工补
    'login/index.vue',       # 登录页无需实时刷新
}
# 这些页面首个 load* 函数带副作用或需要参数，改绑安全的初始化函数
OVERRIDE = {
    'inspector/Quality.vue': 'boot',         # loadByOrderId 必须带 orderId
    'purchaser/Inbound.vue': 'loadRecent',   # loadPreview 会重置正在填写的入库表单
}

only = None
if '--only' in sys.argv:
    only = sys.argv[sys.argv.index('--only') + 1]

rows = []
for f in sorted(VIEWS.rglob('*.vue')):
    rel = f.relative_to(VIEWS).as_posix()
    if only and rel != only:
        continue
    src = f.read_text(encoding='utf-8')
    already = 'useAutoRefresh' in src
    load_fn = None
    m = re.search(r'const\s+(load\w*)\s*=\s*(?:async\s*)?\(', src)
    if m:
        load_fn = m.group(1)
    if rel in OVERRIDE:
        load_fn = OVERRIDE[rel]
    has_mount = 'onMounted(' in src
    has_fn = bool(load_fn) and (f'const {load_fn} =' in src)
    status = 'SKIP_ALREADY' if already else (
        'SKIP_MANUAL' if rel in SKIP else (
            'OK' if (has_fn and has_mount) else 'NEED_MANUAL'))
    rows.append((rel, status, load_fn, has_mount))

    if status != 'OK' or not APPLY:
        continue
    lines = src.split('\n')
    import_idx = [i for i, l in enumerate(lines) if l.startswith('import ')]
    if not import_idx:
        print('NO_IMPORT ' + rel)
        continue
    lines.insert(max(import_idx) + 1, "import { useAutoRefresh } from '@/utils/useAutoRefresh'")
    mount_idx = next((i for i, l in enumerate(lines) if 'onMounted(' in l), None)
    if mount_idx is None:
        print('NO_MOUNT ' + rel)
        continue
    lines.insert(mount_idx, f'useAutoRefresh({load_fn})')
    f.write_text('\n'.join(lines), encoding='utf-8')

print(f"{'APPLIED' if APPLY else 'DRY-RUN'} | 文件总数 {len(rows)}")
for rel, status, fn, mount in rows:
    print(f'  {status:15s} {rel:38s} fn={str(fn):14s} onMounted={mount}')
print('\n汇总:')
for s in ('OK', 'SKIP_ALREADY', 'SKIP_MANUAL', 'NEED_MANUAL'):
    print(f'  {s}: {sum(1 for r in rows if r[1] == s)}')
