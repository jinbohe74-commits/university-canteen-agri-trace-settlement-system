"""把「只弹 toast」的假导出统一接成真实文件下载（L-006 机制层批量修）。

每个文件只改一个函数体 + 加一条 import，业务 DOM / 数据逻辑零改动。
先 --only 试跑一个，目检后 --apply 全量，最后逐文件断言替换成功。
"""
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
VIEWS = ROOT / 'frontend' / 'src' / 'views'
APPLY = '--apply' in sys.argv
only = sys.argv[sys.argv.index('--only') + 1] if '--only' in sys.argv else None

TABLE_EXPORTS = {
    'admin/Canteens.vue': ('exportRoster', '食堂名册'),
    'admin/Farmers.vue': ('exportRoster', '农户合作社名册'),
    'farmer/Orders.vue': ('exportOrders', '农户订单'),
    'farmer/Settlement.vue': ('exportBill', '农户对账单'),
    'farmer/Sources.vue': ('exportSources', '供货货源'),
    'purchaser/Demands.vue': ('exportDemands', '采购需求'),
    'purchaser/Orders.vue': ('exportOrders', '采购订单'),
}

IMPORT_LINE = "import { exportTableDom, stamped } from '@/utils/export'"

def add_import(lines):
    if any(IMPORT_LINE in l for l in lines):
        return lines
    idx = [i for i, l in enumerate(lines) if l.startswith('import ')]
    if not idx:
        raise RuntimeError('no import block')
    out = lines[:]
    out.insert(max(idx) + 1, IMPORT_LINE)
    return out

def replace_table_export(src, fn, label):
    pat = re.compile(
        r'(const ' + re.escape(fn) + r' = \(\) => \{\n)  ElMessage\.success\([^\n]*\)\n(\})')
    body = (f"  const n = exportTableDom(stamped('{label}'))\n"
            f"  ElMessage.success(n ? `已导出当前页 ${{n}} 条记录` : '当前没有可导出的数据')")
    new, cnt = pat.subn(lambda m: m.group(1) + body + '\n' + m.group(2), src, count=1)
    if cnt != 1:
        raise RuntimeError('函数体未匹配: ' + fn)
    return new

report = []
for rel, (fn, label) in TABLE_EXPORTS.items():
    if only and rel != only:
        continue
    f = VIEWS / rel
    src = f.read_text(encoding='utf-8')
    try:
        new = replace_table_export(src, fn, label)
        lines = add_import(new.split('\n'))
        if APPLY:
            f.write_text('\n'.join(lines), encoding='utf-8')
        report.append((rel, 'OK', fn))
    except Exception as e:
        report.append((rel, 'FAIL', str(e)))

print(('APPLIED' if APPLY else 'DRY-RUN') + f' | {len(report)} 个文件')
for r in report:
    print('  ' + ' | '.join(r))

if APPLY:
    print('\n逐文件断言：')
    for rel, (fn, label) in TABLE_EXPORTS.items():
        src = (VIEWS / rel).read_text(encoding='utf-8')
        ok = IMPORT_LINE in src and f'exportTableDom(stamped(' in src and 'exportTableDom' in src
        print(f'  {"PASS" if ok else "FAIL"}  {rel}')
