# -*- coding: utf-8 -*-
"""重建 credit_score 演示账本：让每条记录的分数前后衔接、时间递增、原因多样。

原种子数据 251 条里 248 条是 90 -> +1 -> 91，且 create_time 与 id 不同序。
用户滚到"信用分明细"看到 19 行完全一样，会直接判定系统是假的。
这里按每位农户的当前信用分倒推，生成连贯账本。
"""
import io
import random
import subprocess

MYSQL = r'C:\Program Files\MySQL\MySQL Server 9.6\bin\mysql.exe'

POS = ['正常履约，按质完成订单', '按时交付，无延误', '质检合格率达标，信用加分',
       '采购方评价优秀，信用加分', '配合抽检，记录完整']
NEG = ['交付延迟，扣减信用分', '质检不合格，扣减信用分', '订单取消，扣减信用分',
       '未按约定规格交货，扣减信用分']

cur = subprocess.run(
    [MYSQL, '-uroot', '-p168168', '--default-character-set=utf8mb4', '-B', '-N', '-e',
     "use canteen_trace; select f.id, f.credit_score, count(c.id) from farmer f "
     "join credit_score c on c.farmer_id=f.id group by f.id, f.credit_score;"],
    capture_output=True, text=True)
rows = []
for line in cur.stdout.strip().splitlines():
    p = line.split('\t')
    if len(p) >= 3:
        rows.append((int(p[0]), float(p[1]), int(p[2])))

random.seed(20261110)
sql = ['use canteen_trace;', 'DELETE FROM credit_score;']
import datetime

for fid, final, n in rows:
    # 从当前分往回推，保证账本收敛到 farmer.credit_score
    after = final
    seq = []
    for i in range(n):
        delta = random.choice([1, 1, 1, 2, 2, -1, -2, -3]) if random.random() < 0.82 else random.choice([-2, -3])
        before = round(after - delta, 2)
        if before < 65:            # 下界保护，避免出现离谱分数
            delta, before = -delta, round(after + delta, 2)
        if before > 100:
            delta, before = -abs(delta), round(after - abs(delta), 2)
        seq.append((round(before, 2), round(delta, 2), round(after, 2)))
        after = before
    seq.reverse()

    # 时间：2026-05-01 起按顺序递增，最后一条落在 10 月
    start = datetime.datetime(2026, 5, 1, 8, 0, 0)
    span_min = int((datetime.datetime(2026, 10, 27, 18, 0, 0) - start).total_seconds() / 60)
    step = max(60, span_min // max(1, n))
    for i, (b, d, a) in enumerate(seq):
        t = start + datetime.timedelta(minutes=step * i + random.randint(0, 40))
        reason = random.choice(POS if d > 0 else NEG)
        sql.append(
            "INSERT INTO credit_score (farmer_id, order_id, before_score, change_score, after_score, reason, create_time) "
            "VALUES (%d, NULL, %.2f, %.2f, %.2f, '%s', '%s');" % (fid, b, d, a, reason, t.strftime('%Y-%m-%d %H:%M:%S')))

sql.append("UPDATE farmer f JOIN (SELECT farmer_id, after_score FROM credit_score c "
           "WHERE c.id = (SELECT MAX(id) FROM credit_score x WHERE x.farmer_id = c.farmer_id)) t "
           "ON t.farmer_id = f.id SET f.credit_score = t.after_score;")

path = r'C:\Users\何金波\Desktop\管理信息系统\db\fix_credit_ledger.sql'
io.open(path, 'w', encoding='utf-8').write('\n'.join(sql) + '\n')
print('生成', len(sql) - 3, '条记录 ->', path)

r = subprocess.run([MYSQL, '-uroot', '-p168168', '--default-character-set=utf8mb4', '-e',
                    'source ' + path.replace('\\', '/')], capture_output=True, text=True)
print('rc', r.returncode, (r.stderr or '')[:300])
