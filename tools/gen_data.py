# -*- coding: utf-8 -*-
"""生成 canteen_trace 初始化数据（db/02_data.sql）。
基础档案按《原型设计说明书》的演示数据构造；业务数据保证前后端口径自洽。"""
import datetime as dt
import random
import hashlib
import os

random.seed(20261012)
NOW = dt.datetime.now().replace(microsecond=0)
TODAY = NOW.date()

out = []
w = out.append


def insert(table, cols, rows, chunk=200):
    if not rows:
        return
    head = "INSERT INTO %s (%s) VALUES\n" % (table, ", ".join(cols))
    for i in range(0, len(rows), chunk):
        w(head + ",\n".join(rows[i:i + chunk]) + ";")
        w("")


def q(v):
    if v is None:
        return "NULL"
    return "'" + str(v).replace("\\", "\\\\").replace("'", "''") + "'"


def dtm(days_ago=0, hour=9, minute=0, sec=None):
    x = NOW - dt.timedelta(days=days_ago)
    return x.replace(hour=hour, minute=minute, second=(0 if sec is None else sec)).strftime("%Y-%m-%d %H:%M:%S")


def dstr(days_ago=0):
    return (TODAY - dt.timedelta(days=days_ago)).strftime("%Y-%m-%d")


def sha(s):
    return hashlib.sha256(s.encode("utf-8")).hexdigest()


w("-- =====================================================================")
w("-- 面向高校食堂的农产品直供溯源与结算管理系统 · 初始化数据")
w("-- 由 tools/gen_data.py 生成于 %s" % NOW.strftime("%Y-%m-%d %H:%M:%S"))
w("-- 执行顺序：01_schema.sql -> 02_data.sql")
w("-- =====================================================================")
w("USE canteen_trace;")
w("SET NAMES utf8mb4;")
w("")

# ===================================================================== 角色
w("-- ------------------------------------------------------------------ 角色")
insert("sys_role", ["id", "role_name", "role_code", "description", "data_scope"], [
    "(1,'系统管理员','ADMIN','平台全量数据与系统配置','ALL')",
    "(2,'农户/合作社','FARMER','货源、订单、结算与信用','FARMER')",
    "(3,'食堂采购员','PURCHASER','需求、匹配、订单、入库、评价','CANTEEN')",
    "(4,'质检配送员','INSPECTOR','质检录入与配送任务','SELF')",
])

# ===================================================================== 菜单
w("-- ------------------------------------------------------------------ 菜单")
MENUS = [
    (1, 0, '概览', 'M', '/overview', None, 'DataBoard'),
    (10, 1, '数据看板', 'C', '/admin/dashboard', 'dashboard:view', 'Odometer'),
    (20, 0, '基础档案', 'M', '/basic', None, 'Folder'),
    (21, 20, '农户/合作社管理', 'C', '/admin/farmers', 'farmer:list', 'Avatar'),
    (22, 20, '食堂档案管理', 'C', '/admin/canteens', 'canteen:list', 'Shop'),
    (23, 20, '农产品档案', 'C', '/admin/products', 'product:list', 'Apple'),
    (24, 20, '用户与权限', 'C', '/admin/users', 'user:list', 'User'),
    (30, 0, '业务监管', 'M', '/biz', None, 'Monitor'),
    (31, 30, '订单监管', 'C', '/admin/orders', 'order:monitor', 'Tickets'),
    (32, 30, '结算审核', 'C', '/admin/settlements', 'settlement:audit', 'Money'),
    (33, 30, '食安预警', 'C', '/admin/warnings', 'warning:list', 'Warning'),
    (34, 30, '批次溯源', 'C', '/admin/trace', 'trace:query', 'Search'),
    (40, 0, '系统管理', 'M', '/system', None, 'Setting'),
    (41, 40, '系统参数配置', 'C', '/admin/config', 'config:edit', 'Operation'),
    (42, 40, '操作日志', 'C', '/admin/logs', 'log:list', 'Document'),
    (50, 0, '生产经营', 'M', '/farmer', None, 'Goods'),
    (51, 50, '我的工作台', 'C', '/farmer/workbench', 'farmer:workbench', 'HomeFilled'),
    (52, 50, '货源管理', 'C', '/farmer/sources', 'source:manage', 'Box'),
    (53, 50, '订单管理', 'C', '/farmer/orders', 'order:farmer', 'List'),
    (54, 50, '结算与收入', 'C', '/farmer/settlement', 'settlement:farmer', 'Wallet'),
    (55, 50, '信用评价', 'C', '/farmer/credit', 'credit:view', 'Star'),
    (56, 50, '消息提醒', 'C', '/farmer/messages', 'message:view', 'Bell'),
    (60, 0, '采购协同', 'M', '/purchase', None, 'ShoppingCart'),
    (61, 60, '采购需求', 'C', '/purchaser/demands', 'demand:manage', 'EditPen'),
    (62, 60, '货源大厅', 'C', '/purchaser/sources', 'source:hall', 'Shop'),
    (63, 60, '智能匹配', 'C', '/purchaser/match', 'match:view', 'MagicStick'),
    (64, 60, '我的订单', 'C', '/purchaser/orders', 'order:purchaser', 'Tickets'),
    (65, 60, '入库确认', 'C', '/purchaser/inbound', 'inbound:confirm', 'Box'),
    (66, 60, '采购评价', 'C', '/purchaser/evaluation', 'evaluation:create', 'ChatDotSquare'),
    (67, 60, '批次溯源', 'C', '/purchaser/trace', 'trace:purchaser', 'Search'),
    (70, 0, '质检配送', 'M', '/quality', None, 'Van'),
    (71, 70, '我的任务', 'C', '/inspector/tasks', 'inspect:task', 'List'),
    (72, 70, '质检录入', 'C', '/inspector/quality', 'inspect:input', 'DocumentChecked'),
    (73, 70, '配送任务', 'C', '/inspector/delivery', 'delivery:manage', 'Van'),
    (74, 70, '异常上报', 'C', '/inspector/exception', 'exception:report', 'Warning'),
    (75, 70, '批次溯源', 'C', '/inspector/trace', 'trace:inspector', 'Search'),
]
rows = []
for (mid, parent, name, mtype, path, perms, icon) in MENUS:
    rows.append("(%d,%d,%s,'%s',%s,%s,%s,%d)" % (mid, parent, q(name), mtype, q(path),
                                                  q(perms), q(icon), mid))
insert("sys_menu", ["id", "parent_id", "menu_name", "menu_type", "path", "perms", "icon", "sort"], rows)

role_menus = set()
for (mid, parent, name, mtype, path, perms, icon) in MENUS:
    if mid == 1 or 10 <= mid < 50:
        role_menus.add((1, mid))
    if 50 <= mid < 60:
        role_menus.add((2, mid))
    if 60 <= mid < 70:
        role_menus.add((3, mid))
    if 70 <= mid < 80:
        role_menus.add((4, mid))
insert("sys_role_menu", ["role_id", "menu_id"], ["(%d,%d)" % r for r in sorted(role_menus)])

# ===================================================================== 用户
w("-- ------------------------------------------------------------------ 用户（密码统一 123456）")
BCRYPT_123456 = "$2a$10$kfq5C5P06Os7zAnV/gp2WOTW/MpTJzLLEisML088n6.R/5XVNf83."
user_rows = []
users_by_name = {}
uid = [0]


def add_user(username, real_name, phone, role_type, avatar, status=1, last_login=None, online=0):
    uid[0] += 1
    user_rows.append((uid[0], username, real_name, phone, role_type, avatar, status, online, last_login))
    users_by_name[username] = uid[0]
    return uid[0]


add_user('admin', '王管理', '138****9999', 'ADMIN', '王', 1, dtm(0, 9, 12), 1)
add_user('farmer24', '张大山', '138****6688', 'FARMER', '张', 1, dtm(0, 8, 30), 1)
add_user('farmer01', '王丰收', '139****2233', 'FARMER', '王', 1, dtm(1, 10, 5))
add_user('farmer02', '林晓', '136****5566', 'FARMER', '林', 1, dtm(2, 9, 40))
add_user('farmer03', '王大山', '137****8899', 'FARMER', '王', 1, dtm(3, 8, 15))
add_user('farmer04', '刘德发', '138****7766', 'FARMER', '刘', 1, dtm(4, 7, 55))
add_user('farmer05', '邱永华', '139****9911', 'FARMER', '邱', 1, dtm(5, 9, 20))
add_user('farmer06', '李婶', '135****1177', 'FARMER', '李', 1, dtm(6, 8, 45))
add_user('farmer29', '赵守田', '137****3344', 'FARMER', '赵', 0, None)
add_user('purchaser01', '李建国', '138****9991', 'PURCHASER', '李', 1, dtm(0, 8, 50), 1)
add_user('purchaser02', '周敏', '138****9992', 'PURCHASER', '周', 1, dtm(1, 9, 20))
add_user('purchaser03', '吴海涛', '138****9993', 'PURCHASER', '吴', 1, dtm(2, 8, 10))
add_user('purchaser04', '郑慧', '138****9994', 'PURCHASER', '郑', 1, dtm(3, 9, 30))
add_user('purchaser05', '冯磊', '138****9995', 'PURCHASER', '冯', 0, dtm(9, 10, 0))
add_user('inspector01', '赵质检', '138****9119', 'INSPECTOR', '赵', 1, dtm(0, 8, 0), 1)
add_user('inspector02', '孙质检', '138****9120', 'INSPECTOR', '孙', 1, dtm(1, 8, 10))
add_user('finance01', '钱会计', '138****9229', 'ADMIN', '钱', 1, dtm(0, 10, 5))
add_user('audit01', '孙审计', '138****9339', 'ADMIN', '孙', 0, dtm(30, 11, 0))
for i in range(7, 33):
    if i in (24, 29):
        continue   # farmer24/farmer29 已被张大山、赵守田占用
    add_user('farmer%02d' % i, '农户%02d' % i, '135****%04d' % (1000 + i), 'FARMER', '农', 1,
             dtm(6 + i % 20, 9, 0))

rows = []
for (u, un, rn, ph, rt, av, st, on, ll) in user_rows:
    rows.append("(%d,%s,%s,%s,%s,%s,%s,%d,%d,%s)" % (u, q(un), q(BCRYPT_123456), q(rn), q(ph), q(rt),
                                                     q(av), st, on, q(ll)))
insert("sys_user", ["id", "username", "password", "real_name", "phone", "role_type", "avatar",
                    "status", "online", "last_login_time"], rows)

ADMIN_ACCTS = ['admin', 'finance01', 'audit01']
INSPECTOR_ACCTS = ['inspector01', 'inspector02']
PURCHASER_ACCTS = ['purchaser01', 'purchaser02', 'purchaser03', 'purchaser04', 'purchaser05']
ur = ["(%d,1)" % users_by_name[n] for n in ADMIN_ACCTS]
ur += ["(%d,4)" % users_by_name[n] for n in INSPECTOR_ACCTS]
ur += ["(%d,3)" % users_by_name[n] for n in PURCHASER_ACCTS]
special = set(ADMIN_ACCTS + INSPECTOR_ACCTS + PURCHASER_ACCTS)
ur += ["(%d,2)" % v for k, v in users_by_name.items() if k not in special]
insert("sys_user_role", ["user_id", "role_id"], ur)

# ===================================================================== 系统配置
w("-- ------------------------------------------------------------------ 系统参数")
configs = [
    ('match_weight_category', '0.20', 'MATCH', 'number', 'W1 品类匹配度权重'),
    ('match_weight_price', '0.25', 'MATCH', 'number', 'W2 价格竞争力权重'),
    ('match_weight_quantity', '0.15', 'MATCH', 'number', 'W3 数量满足度权重'),
    ('match_weight_credit', '0.25', 'MATCH', 'number', 'W4 农户信用权重'),
    ('match_weight_distance', '0.15', 'MATCH', 'number', 'W5 距离/历史合作权重'),
    ('credit_init_score', '80', 'CREDIT', 'number', '新农户初始信用分'),
    ('credit_reward_normal', '1', 'CREDIT', 'number', '正常履约加分'),
    ('credit_punish_late', '-3', 'CREDIT', 'number', '超时发货扣分'),
    ('credit_punish_quality', '-5', 'CREDIT', 'number', '质检不合格扣分'),
    ('credit_punish_loss', '-2', 'CREDIT', 'number', '损耗率超阈值扣分'),
    ('warn_price_rate', '15', 'WARNING', 'number', '价格波动预警阈值(%)'),
    ('warn_order_overdue_days', '2', 'WARNING', 'number', '订单逾期预警天数'),
    ('warn_cert_expire_days', '30', 'WARNING', 'number', '证照到期提前提醒天数'),
    ('warn_loss_rate', '5', 'WARNING', 'number', '损耗率阈值(%)'),
    ('business_settle_cycle', '月结', 'BUSINESS', 'string', '结算周期'),
    ('business_settle_day', '每月 5 日', 'BUSINESS', 'string', '结算日'),
    ('business_login_fail_limit', '5', 'BUSINESS', 'number', '登录失败锁定次数'),
    ('business_login_lock_minutes', '10', 'BUSINESS', 'number', '登录锁定分钟'),
    ('match_top_n', '5', 'MATCH', 'number', '推荐货源数量'),
    ('quote_valid_hours', '48', 'BUSINESS', 'number', '报价有效期(小时)'),
    ('switch_sms_warning', 'false', 'BUSINESS', 'bool', '短信/站内预警通知'),
    ('switch_batch_qr_required', 'true', 'BUSINESS', 'bool', '批次二维码强制绑定'),
    ('switch_partial_receipt', 'true', 'BUSINESS', 'bool', '部分收货自动结算'),
]
insert("sys_config", ["config_key", "config_value", "config_group", "value_type", "remark"],
       ["(%s,%s,%s,%s,%s)" % (q(k), q(v), q(g), q(t), q(r)) for (k, v, g, t, r) in configs])

# ===================================================================== 农户
w("-- ------------------------------------------------------------------ 农户/合作社（32 家，已认证 24）")
FARMERS = [
    ('绿源蔬菜合作社', '合作社', '张大山', '138****6688', '叶菜类·根茎类', '5,999 kg/日', '景德镇近郊绿源基地', 92, '已认证', 380, 8.0, 48, 98.0, 1),
    ('丰禾农业合作社', '合作社', '王丰收', '139****2233', '瓜果类·叶菜类', '8,999 kg/日', '昌江区丰禾种植基地', 88, '已认证', 360, 15.0, 36, 96.0, 2),
    ('近郊有机农场', '生产基地', '林晓', '136****5566', '有机叶菜·菌菇', '3,999 kg/日', '浮梁县近郊有机农场', 90, '已认证', 330, 22.0, 30, 97.0, 3),
    ('王大山的农户', '个体农户', '王大山', '137****8899', '黄瓜·应季菜', '899 kg/日', '珠山区王大山菜地', 85, '已认证', 310, 6.0, 26, 94.0, 4),
    ('德兴粮油合作社', '合作社', '刘德发', '138****7766', '粮油·根茎类', '12,999 kg/日', '德兴市粮油合作基地', 86, '已认证', 300, 18.0, 24, 95.0, 5),
    ('昌江食用菌基地', '生产基地', '邱永华', '139****9911', '菌菇类', '1,599 kg/日', '昌江区食用菌产业园', 91, '已认证', 290, 12.0, 22, 98.0, 6),
    ('李婶菜园', '个体农户', '李婶', '135****1177', '青椒·时令菜', '599 kg/日', '湘湖区李婶菜园', 82, '已认证', 280, 11.0, 18, 92.0, 7),
    ('红星果蔬合作社', '合作社', '赵守田', '137****3344', '瓜果类·茄果类', '6,699 kg/日', '乐平市红星果蔬基地', 79, '待审核', 3, 25.0, 0, 100.0, 8),
    ('农垦蔬菜基地', '生产基地', '孙文武', '136****5577', '叶菜类', '2,599 kg/日', '浮梁县农垦基地', 88, '已认证', 43, 20.0, 16, 95.0, 9),
    ('赵氏菜园', '个体农户', '赵守义', '135****8822', '时令叶菜', '499 kg/日', '珠山区赵氏菜园', 76, '已停用', 33, 9.0, 6, 88.0, None),
]
EXTRA = [
    ('浯溪蔬菜合作社', '合作社', '程小军', 89),
    ('虎山果蔬基地', '生产基地', '方海', 93),
    ('庙前蔬菜合作社', '合作社', '汪庙前', 87),
    ('金桥农业合作社', '合作社', '金桥生', 80),
    ('白塔食用菌基地', '生产基地', '白塔生', 95),
    ('五里蔬菜合作社', '合作社', '吴万里', 83),
    ('荷塘莲藕基地', '生产基地', '何荷', 88),
    ('绿水合作社', '合作社', '陆绿', 90),
    ('南乡果蔬合作社', '合作社', '南乡生', 86),
    ('黄泥岗合作社', '合作社', '黄泥生', 84),
    ('清溪家庭农场', '个体农户', '清溪', 92),
    ('高桥蔬菜合作社', '合作社', '高桥生', 81),
    ('周记菌菇合作社', '合作社', '周德海', 89),
    ('双峰家庭农场', '个体农户', '章双峰', 87),
    ('程家菜园', '个体农户', '程水根', 85),
    ('石岭果蔬基地', '生产基地', '石岭峰', 85.8),
    ('钱家菜地', '个体农户', '钱守成', 89),
    ('新田家庭农场', '个体农户', '田新', 90),
    ('糯米寨菜园', '个体农户', '米满仓', 86),
    ('杉树岭农场', '生产基地', '杉树岭', 88),
    ('杨梅岭菜地', '个体农户', '杨梅', 87),
    ('九都蔬菜基地', '生产基地', '九都生', 84),
]
farmer_rows = []
farmer_credit = {}
for i, (name, ftype, contact, phone, cat, cap, addr, credit, status, days, dist, deals, good, usr) in enumerate(FARMERS):
    fid = i + 1
    farmer_credit[fid] = credit
    cert_exp = dstr(-400)
    if fid == 2:
        cert_exp = dstr(-14)      # 丰禾农业合作社证照 14 天后到期，触发证照预警
    elif fid == 8:
        cert_exp = dstr(-120)
    acct = {1: 'farmer24', 2: 'farmer01', 3: 'farmer02', 4: 'farmer03', 5: 'farmer04', 6: 'farmer05', 7: 'farmer06', 8: 'farmer29'}.get(fid)
    usr_uid = users_by_name.get(acct) if acct else None
    farmer_rows.append((fid, 'F9%03d' % (928 + fid), usr_uid, name, ftype, contact, phone, cat, cap, addr,
                        'JX2026%06d' % (100000 + fid * 37), credit, status, cert_exp, dist, deals, good))
for k, (name, ftype, contact, credit) in enumerate(EXTRA):
    fid = len(FARMERS) + k + 1
    status = '已认证' if k < 16 else ('待审核' if k < 21 else '已停用')
    cr = credit if status == '已认证' else 80
    farmer_credit[fid] = cr
    farmer_rows.append((fid, 'F9%03d' % (928 + fid), users_by_name.get('farmer%02d' % (7 + k)), name, ftype, contact,
                        '13%d****%04d' % (6 + fid % 4, 2000 + fid * 13),
                        ['叶菜类', '根茎类', '茄果类', '瓜果类', '菌菇类', '有机菜'][fid % 6],
                        '%d,999 kg/日' % (1 + fid % 6), '景德镇%s乡' % ('东西南北中'[fid % 5]),
                        'JX2026%06d' % (100000 + fid * 41), cr, status,
                        dstr(-(400 - fid * 6) if status == '已认证' else -30),
                        5 + fid % 20, fid * 2, 88 + fid % 12))
rows = []
for (fid, fno, usr, name, ftype, contact, phone, cat, cap, addr, certno, credit, status, cert_exp,
     dist, deals, good) in farmer_rows:
    rows.append("(%d,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s)" % (
        fid, q(fno), q(usr), q(name), q(ftype), q(contact), q(phone), q(cat), q(cap), q(addr), q(certno),
        credit, q(status), q(cert_exp), dist, deals, good, q(dtm(400 - fid, 9, 30)), q(dtm(400 - fid, 9, 30))))
insert("farmer", ["id", "farmer_no", "user_id", "name", "type", "contact", "phone", "main_category",
                  "supply_capacity", "address", "cert_no", "credit_score", "status", "cert_expire",
                  "distance_km", "deal_count", "good_rate", "join_time", "create_time"], rows)

# ===================================================================== 食堂
w("-- ------------------------------------------------------------------ 食堂档案")
CANTEENS = [
    (1, 'C991', 'purchaser01', '第一食堂', '主校区', '李建国', '138****9991', 3000, 12, 128, 32200, '正常'),
    (2, 'C992', 'purchaser02', '第二食堂', '主校区', '周敏', '138****9992', 2800, 10, 76, 18600, '正常'),
    (3, 'C993', 'purchaser03', '第三食堂', '东校区', '吴海涛', '138****9993', 1500, 8, 42, 9800, '正常'),
    (4, 'C994', 'purchaser04', '教工食堂', '主校区', '郑慧', '138****9994', 900, 7, 58, 13600, '正常'),
    (5, 'C995', 'purchaser05', '风味食堂', '东校区', '冯磊', '138****9995', 1100, 6, 42, 8900, '停用'),
]
insert("canteen", ["id", "canteen_no", "user_id", "name", "campus", "manager", "phone", "scale",
                   "supplier_count", "monthly_orders", "monthly_amount", "status"],
       ["(%d,%s,%s,%s,%s,%s,%s,%d,%d,%d,%s,%s)" % (c[0], q(c[1]), q(users_by_name.get(c[2])), q(c[3]), q(c[4]), q(c[5]),
                                                    q(c[6]), c[7], c[8], c[9], c[10], q(c[11])) for c in CANTEENS])

# ===================================================================== 分类
w("-- ------------------------------------------------------------------ 农产品分类（6 大类 / 80 子类）")
CATS = [
    ('叶菜类', ['大白菜', '小白菜', '上海青', '生菜', '菠菜', '油麦菜', '空心菜', '苋菜', '芹菜', '韭菜',
             '香菜', '茼蒿', '芥蓝', '菜心', '娃娃菜', '包菜', '紫甘蓝', '西兰花']),
    ('根茎类', ['白萝卜', '胡萝卜', '马铃薯', '红薯', '山药', '莲藕', '芋头', '洋葱', '大蒜', '生姜',
             '竹笋', '芦笋', '甜菜根', '荸荠']),
    ('茄果类', ['西红柿', '青椒', '茄子', '辣椒', '线椒', '圣女果', '秋葵', '豇豆', '四季豆', '豌豆',
             '甜玉米', '刀豆']),
    ('瓜果类', ['黄瓜', '丝瓜', '苦瓜', '冬瓜', '南瓜', '西葫芦', '佛手瓜', '西瓜', '甜瓜', '葫芦瓜']),
    ('菌菇类', ['香菇', '平菇', '金针菇', '杏鲍菇', '茶树菇', '木耳', '银耳', '双孢菇', '蟹味菇', '竹荪']),
    ('有机菜', ['有机生菜', '有机菠菜', '有机小白菜', '有机西红柿', '有机黄瓜', '有机胡萝卜', '有机土豆',
             '有机芹菜', '有机芥蓝', '有机紫甘蓝', '有机菜心', '有机西兰花', '有机香菇', '有机南瓜',
             '有机甜玉米', '有机豌豆']),
]
cat_rows = []
cat_id = {}
cat_big = {}
cid = 0
cid = 0
for big, subs in CATS:
    cid += 1
    big_id = cid
    cat_rows.append((cid, big, 'C%02d' % cid, 0, cid))
    for j, s in enumerate(subs):
        cid += 1
        cat_id[s] = cid
        cat_big[s] = big
        cat_rows.append((cid, s, 'C%02d' % cid, big_id, cid))
insert("product_category", ["id", "category_name", "category_code", "parent_id", "sort"],
       ["(%d,%s,%s,%d,%d)" % (c[0], q(c[1]), q(c[2]), c[3], c[4]) for c in cat_rows])

# ===================================================================== 农产品
w("-- ------------------------------------------------------------------ 农产品档案（220 个 SKU，在售 214）")
BASE_PRICE = {
    '大白菜': 1.85, '小白菜': 2.30, '上海青': 2.20, '生菜': 3.00, '菠菜': 3.20, '油麦菜': 2.80,
    '空心菜': 2.50, '苋菜': 2.60, '芹菜': 2.70, '韭菜': 3.10, '香菜': 4.20, '茼蒿': 3.30,
    '芥蓝': 3.60, '菜心': 3.40, '娃娃菜': 2.90, '包菜': 1.70, '紫甘蓝': 2.40, '西兰花': 4.50,
    '白萝卜': 2.10, '胡萝卜': 1.60, '马铃薯': 2.30, '红薯': 2.60, '山药': 6.80, '莲藕': 5.20,
    '芋头': 3.90, '洋葱': 1.80, '大蒜': 6.50, '生姜': 7.20, '竹笋': 8.00, '芦笋': 12.00,
    '甜菜根': 4.20, '荸荠': 5.60,
    '西红柿': 2.60, '青椒': 2.80, '茄子': 2.40, '辣椒': 3.60, '线椒': 4.00, '圣女果': 5.20,
    '秋葵': 6.80, '豇豆': 3.90, '四季豆': 4.20, '豌豆': 5.80, '甜玉米': 2.40, '刀豆': 4.60,
    '黄瓜': 2.30, '丝瓜': 3.00, '苦瓜': 3.50, '冬瓜': 1.40, '南瓜': 1.90, '西葫芦': 2.20,
    '佛手瓜': 2.80, '西瓜': 2.10, '甜瓜': 4.60, '葫芦瓜': 2.50,
    '香菇': 8.50, '平菇': 6.20, '金针菇': 5.80, '杏鲍菇': 7.00, '茶树菇': 11.00, '木耳': 18.00,
    '银耳': 22.00, '双孢菇': 9.50, '蟹味菇': 12.50, '竹荪': 45.00,
    '有机生菜': 5.80, '有机菠菜': 6.20, '有机小白菜': 5.40, '有机西红柿': 6.80, '有机黄瓜': 5.60,
    '有机胡萝卜': 4.80, '有机土豆': 4.60, '有机芹菜': 5.90, '有机芥蓝': 6.40, '有机紫甘蓝': 4.90,
    '有机菜心': 6.10, '有机西兰花': 7.20, '有机香菇': 13.50, '有机南瓜': 4.30, '有机甜玉米': 5.10,
    '有机豌豆': 8.60,
}
STORAGE = {'叶菜类': '冷链配送', '根茎类': '常温通风', '茄果类': '冷链配送', '瓜果类': '冷链配送',
           '菌菇类': '冷链保鲜', '有机菜': '冷链保鲜'}
FEATURED = [
    ('大白菜', 'P9191', 1.85, 1.90, 2.10, 12),
    ('西红柿', 'P9192', 2.60, 2.30, 2.80, 9),
    ('黄瓜', 'P9193', 2.30, 2.10, 2.50, 8),
    ('白萝卜', 'P9194', 2.10, 1.90, 2.30, 11),
    ('青椒', 'P9195', 2.80, 2.50, 3.10, 7),
    ('菠菜', 'P9196', 3.20, 2.80, 3.60, 6),
    ('茄子', 'P9197', 2.40, 2.20, 2.60, 5),
    ('香菇', 'P9198', 8.50, 7.80, 9.20, 4),
    ('胡萝卜', 'P9199', 1.60, 1.50, 1.80, 9),
    ('有机生菜', 'P9119', 5.80, 5.20, 6.40, 3),
]
product_rows = []
product_ids = {}
pid = 0
for (name, pno, price, mn, mx, fc) in FEATURED:
    pid += 1
    product_ids[name] = pid
    product_rows.append((pid, pno, name, cat_id[name], price, mn, mx, fc, STORAGE[cat_big[name]], '在售'))

SEQ = 0
for big, subs in CATS:
    for s in subs:
        for v in range(2):
            SEQ += 1
            pid += 1
            suffix = ['', '（精品）'][v]
            pname = s + suffix
            base = BASE_PRICE.get(s, 3.0) * (1 + 0.08 * v)
            price = round(base, 2)
            product_ids[pname] = pid
            product_rows.append((pid, 'P9%03d' % (200 + SEQ), pname, cat_id[s], price,
                                 round(price * 0.88, 2), round(price * 1.14, 2),
                                 3 + (pid * 7) % 12, STORAGE[big], '在售'))
# 再补 50 个（前 50 个子类的优选规格），其中 6 个停售 -> 220 个 SKU，在售 214
SEQ = 0
cnt = 0
for big, subs in CATS:
    for s in subs:
        if cnt >= 50:
            break
        cnt += 1
        SEQ += 1
        pid += 1
        pname = s + '（优选）'
        base = BASE_PRICE.get(s, 3.0) * 1.16
        price = round(base, 2)
        status = '在售' if cnt <= 44 else '停售'
        product_ids[pname] = pid
        product_rows.append((pid, 'P9%03d' % (360 + SEQ), pname, cat_id[s], price,
                             round(price * 0.88, 2), round(price * 1.14, 2),
                             3 + (pid * 5) % 10, STORAGE[big], status))
    if cnt >= 60:
        break

rows = []
for (i2, pno, name, cid2, price, mn, mx, fc, storage, status) in product_rows:
    rows.append("(%d,%s,%s,%d,'kg',%s,%s,%s,%d,%s,%s,%s)" % (
        i2, q(pno), q(name), cid2, price, mn, mx, fc, q(storage), q(status),
        q('%s，产地直供，%s，可扫码溯源' % (name, storage))))
insert("product", ["id", "product_no", "name", "category_id", "unit", "avg_price", "price_min",
                   "price_max", "farmer_count", "storage_req", "status", "description"], rows, chunk=80)
print("products:", len(product_rows), "在售:", sum(1 for p in product_rows if p[9] == '在售'))

# ===================================================================== 货源
w("-- ------------------------------------------------------------------ 货源（绿源 24 条，全平台在售货源充足）")
src_rows = []
sid = 0
farmers_name = {}
for f in farmer_rows:
    farmers_name[f[0]] = f[3]
canteens_name = {}
for c in CANTEENS:
    canteens_name[c[0]] = c[3]


def add_source(farmer_id, pname, qty, price, mode='现货·当日发', storage=None, status='正常',
               sold=0, days_ago=5, remark=None, images=None):
    global sid
    sid += 1
    pid_ = product_ids[pname]
    pr = next(p for p in product_rows if p[0] == pid_)
    storage = storage or pr[8]
    if remark is None:
        remark = '产地：%s；新鲜采摘、农残检测合格，支持食堂直供。' % farmers_name[farmer_id]
    src_rows.append((sid, farmer_id, pid_, qty, price, dstr(30), dstr(-15), mode, storage,
                     remark, images, sold, status, dtm(days_ago, 8, 30)))
    return sid


# 绿源蔬菜合作社 24 条货源：6 在售 + 1 已售罄 + 17 下架
GREEN = [('大白菜', 500, 1.80), ('白萝卜', 900, 1.60), ('马铃薯', 700, 2.00),
         ('菠菜', 300, 3.20), ('上海青', 250, 2.60), ('生菜', 180, 3.50)]
for i, (pn, qty, price) in enumerate(GREEN):
    add_source(1, pn, qty, price, '现货·当日发' if i % 2 == 0 else '现货·次日发',
               remark='产地：景德镇近郊绿源基地；新鲜采摘、农残检测合格，支持食堂直供。')
add_source(1, '有机生菜', 0, 5.80, '已售罄', status='已售罄', sold=260, days_ago=12)
for pn, qty, price in [('小白菜', 400, 2.30), ('芹菜', 320, 2.70), ('韭菜', 200, 3.10),
                       ('胡萝卜', 600, 1.60), ('洋葱', 500, 1.80), ('大蒜', 150, 6.50),
                       ('生姜', 120, 7.20), ('红薯', 350, 2.60), ('山药', 90, 6.80),
                       ('莲藕', 160, 5.20), ('芋头', 180, 3.90), ('香菜', 60, 4.20),
                       ('茼蒿', 90, 3.30), ('菜心', 110, 3.40), ('包菜', 700, 1.70),
                       ('紫甘蓝', 240, 2.40), ('西兰花', 150, 4.50)]:
    add_source(1, pn, qty, price, status='下架', sold=qty, days_ago=40)

# 大白菜 12 家有效货源（智能匹配演示）
CABBAGE = [(2, 600, 1.95), (4, 350, 2.10), (3, 450, 2.30), (7, 200, 1.90), (5, 800, 2.00),
           (6, 520, 2.20), (9, 300, 2.40), (11, 650, 1.85), (12, 400, 2.05), (14, 700, 2.15),
           (16, 480, 2.25), (17, 560, 2.00)]
for (fid, qty, price) in CABBAGE:
    add_source(fid, '大白菜', qty, price, '现货·当日发' if fid % 2 == 0 else '现货·次日发')

# 其他农户的常规货源
OTHER_SRC = [
    (2, '西红柿', 700, 2.60), (2, '黄瓜', 600, 2.30), (2, '甜玉米', 400, 2.40),
    (3, '有机生菜', 260, 5.80), (3, '有机菠菜', 220, 6.20), (3, '有机西红柿', 180, 6.80),
    (3, '香菇', 120, 8.50), (4, '黄瓜', 480, 2.35), (4, '丝瓜', 200, 3.00),
    (5, '白萝卜', 1200, 2.05), (5, '马铃薯', 900, 2.25), (5, '红薯', 600, 2.55),
    (6, '香菇', 300, 8.40), (6, '平菇', 350, 6.20), (6, '金针菇', 280, 5.80),
    (7, '青椒', 300, 2.80), (7, '线椒', 150, 4.00), (7, '茄子', 260, 2.40),
    (9, '小白菜', 380, 2.30), (9, '上海青', 320, 2.20), (9, '油麦菜', 240, 2.80),
    (11, '西红柿', 520, 2.55), (11, '茄子', 340, 2.45), (12, '黄瓜', 700, 2.25),
    (12, '冬瓜', 900, 1.40), (13, '芹菜', 280, 2.65), (13, '空心菜', 260, 2.50),
    (14, '青椒', 340, 2.85), (15, '香菇', 200, 8.60), (16, '南瓜', 800, 1.90),
    (17, '菠菜', 180, 3.15), (18, '包菜', 600, 1.75), (19, '土豆', 500, 2.30),
    (20, '莲藕', 220, 5.30), (21, '秋葵', 90, 6.80), (22, '甜玉米', 400, 2.45),
    (23, '生姜', 100, 7.15), (24, '大蒜', 130, 6.45), (25, '山药', 80, 6.75),
]
for (fid, pn, qty, price) in OTHER_SRC:
    if pn in product_ids:
        add_source(fid, pn, qty, price, '现货·当日发' if fid % 3 else '现货·次日发')

rows = []
for (i3, fid, pid2, qty, price, st, ed, mode, storage, remark, images, sold, status, ct) in src_rows:
    rows.append("(%d,%d,%d,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s)" % (
        i3, fid, pid2, qty, price, q(st), q(ed), q(mode), q(storage), q(remark), q(images),
        sold, q(status), q(ct)))
insert("supply_source", ["id", "farmer_id", "product_id", "quantity", "price", "supply_start",
                         "supply_end", "delivery_mode", "storage_req", "origin_remark", "images",
                         "sold_quantity", "status", "create_time"], rows, chunk=100)
print("sources:", len(src_rows))

# ===================================================================== 采购需求
w("-- ------------------------------------------------------------------ 采购需求（第一食堂 28 条）")
demand_rows = []
did = 0
DEMANDS = [
    (1, '大白菜', 400, 2.00, 1, 18, '处理中', 6),
    (1, '白萝卜', 600, 1.70, 2, 18, '处理中', 5),
    (1, '马铃薯', 1800, 2.10, 1, 12, '已成交', 4),
    (1, '黄瓜', 400, 2.20, 0, 18, '已成交', 3),
    (1, '西红柿', 500, 2.50, 0, 15, '已成交', 4),
    (1, '香菇', 80, 8.00, 0, 12, '已撤回', 1),
    (2, '大白菜', 500, 1.95, 1, 17, '处理中', 3),
    (2, '青椒', 300, 2.70, 2, 16, '已成交', 3),
    (3, '菠菜', 200, 3.10, 1, 18, '处理中', 2),
    (4, '西红柿', 260, 2.55, 2, 18, '已成交', 2),
]
for i in range(18):
    cid2 = [1, 1, 1, 2, 3][i % 5]
    pn = ['大白菜', '西红柿', '黄瓜', '白萝卜', '青椒', '茄子', '马铃薯', '菠菜', '香菇', '生菜'][i % 10]
    st = ['已成交', '已完成', '已撤回', '处理中'][i % 4]
    DEMANDS.append((cid2, pn, [150, 200, 300, 400, 500, 600][i % 6], round(BASE_PRICE.get(pn, 3) * 0.92, 2),
                    1 + i % 3, 10 + i % 9, st, 1 + i % 6))
for (cid2, pn, qty, exp_price, dl_days, hour, status, quotes) in DEMANDS:
    did += 1
    demand_rows.append((did, 'X%04d' % (did + 0) if False else 'X%s%03d' % (NOW.strftime('%Y%m%d'), 1000 + did),
                        cid2, product_ids[pn], qty, exp_price, dtm(-dl_days, hour, 0),
                        '冷链配送', '月结',
                        '要求新鲜、无黄叶、农残检测合格；需提供批次溯源二维码。' if pn in ('大白菜', '菠菜', '青菜', '生菜')
                        else '要求品相完好、无腐烂，冷链到货温度 2~8℃。',
                        quotes, status, dtm(1 if did > 3 else 0, 8, 20 + did % 30)))
rows = []
for (i4, dno, cid2, pid3, qty, price, dl, dmode, smode, req, quotes, status, ct) in demand_rows:
    rows.append("(%d,%s,%d,%d,%s,%s,%s,%s,%s,%s,%d,%s,%s)" % (
        i4, q(dno), cid2, pid3, qty, price, q(dl), q(dmode), q(smode), q(req), quotes, q(status), q(ct)))
insert("purchase_demand", ["id", "demand_no", "canteen_id", "product_id", "quantity", "expected_price",
                           "deadline", "delivery_mode", "settlement_mode", "quality_req", "quote_count",
                           "status", "create_time"], rows)
print("demands:", len(demand_rows))

# ===================================================================== 订单
w("-- ------------------------------------------------------------------ 订单（326 笔，含全流程演示订单）")
# 订单状态与状态机
STATUS_NAME = {10: '待接单', 20: '已接单', 30: '待质检', 40: '质检通过', 41: '质检不通过',
               50: '配送中', 60: '已送达', 70: '已入库', 80: '待结算', 90: '已结算',
               100: '已完成', 99: '已取消'}

order_rows = []
item_rows = []
qr_rows = []
do_rows = []
ir_rows = []
st_rows = []
si_rows = []
tr_rows = []
ev_rows = []
cs_rows = []

DAYS = NOW.day  # 本月至今天数
MONTH_PLAN = [(5, 38), (4, 42), (3, 46), (2, 55), (1, 63), (0, 81)]
MONTH0_STATUS = [100] * 25 + [90] * 3 + [80] * 3 + [10] * 4 + [20] * 6 + [30] * 5 + [40] * 3 + \
                [41] * 3 + [50] * 7 + [60] * 6 + [70] * 5 + [99] * 15

order_id = 0
seq = 0
delivery_seq = 0
settle_seq = 0
price_map = {p[2]: p[4] for p in product_rows}
name_map = {p[0]: p[2] for p in product_rows}
main_farmers = [1, 2, 3, 4, 5, 6, 7, 9, 11, 12, 14, 16]
prods = ['大白菜', '西红柿', '黄瓜', '白萝卜', '青椒', '菠菜', '茄子', '香菇', '马铃薯', '生菜',
         '小白菜', '芹菜', '油麦菜', '红薯', '平菇', '金针菇', '南瓜', '包菜', '豇豆', '甜玉米']


def new_order(farmer_id, canteen_id, pname, qty, price, status, created, batch=None, seq_no=None,
              expected_delivery=None):
    global order_id, delivery_seq, settle_seq, seq
    order_id += 1
    seq += 1
    order_no = 'DD%s%04d' % (created.strftime('%Y%m%d'), seq if seq_no is None else seq_no)
    amount = round(qty * price, 2)
    order_rows.append((order_id, order_no, None, None, farmer_id, canteen_id, product_ids[pname],
                       qty, price, amount, status, batch, expected_delivery, created, created))
    item_rows.append((order_id, product_ids[pname], qty, 0, 0, price, created))
    return order_id, order_no, amount


def add_quality(oid, farmer_id, pname, inspect_time, result='合格', inspector='赵质检',
                inspector_id=None):
    global batch_force
    batch = batch_force or ('PC%sF%07d' % (inspect_time.strftime('%Y%m%d'),
                                           (farmer_id * 1000 + oid) % 10000000))
    sample = 2
    if pname in ('香菇', '平菇', '金针菇', '木耳'):
        items = [{'name': '农药残留', 'std': '≤0.05 mg/kg', 'value': '0.02 mg/kg', 'ok': True},
                 {'name': '重金属(铅/镉)', 'std': '未检出', 'value': '未检出', 'ok': True},
                 {'name': '含水率', 'std': '≤92%', 'value': '89%', 'ok': True},
                 {'name': '新鲜度', 'std': '外观/色泽/气味', 'value': '良好', 'ok': True},
                 {'name': '重量复核', 'std': '复秤与订单核对', 'value': '相符', 'ok': True},
                 {'name': '冷链温度', 'std': '2~8℃', 'value': '5.5℃', 'ok': True}]
    else:
        items = [{'name': '农药残留', 'std': '≤0.05 mg/kg', 'value': '0.02 mg/kg', 'ok': True},
                 {'name': '重金属(铅/镉)', 'std': '未检出', 'value': '未检出', 'ok': True},
                 {'name': '含水率', 'std': '叶菜类 ≤95%', 'value': '92%', 'ok': True},
                 {'name': '新鲜度', 'std': '外观/色泽/气味', 'value': '良好', 'ok': True},
                 {'name': '重量复核', 'std': '复秤与订单核对', 'value': '相符', 'ok': True},
                 {'name': '冷链温度', 'std': '2~8℃', 'value': '6.0℃', 'ok': True}]
    if result == '不合格':
        items[0]['value'] = '0.08 mg/kg'
        items[0]['ok'] = False
    h = sha('%s|%d|%s|%s' % (batch, farmer_id, result, inspect_time))
    qr_rows.append((oid, batch, inspector_id or users_by_name['inspector01'], inspector, sample, result,
                    json_dump(items), json_dump([
                        {'name': '农残限量', 'value': '≤0.05 mg/kg'},
                        {'name': '叶菜含水率', 'value': '≤95%'},
                        {'name': '冷链温度', 'value': '2~8℃'},
                        {'name': '腐烂率', 'value': '≤1%'}]),
                    None, '整批抽检，各项指标符合标准' if result == '合格' else '农残快检超标，整批不通过',
                    h, inspect_time))
    return batch


def json_dump(o):
    import json
    return json.dumps(o, ensure_ascii=False)


batch_force = None
for (m_ago, cnt) in MONTH_PLAN:
    for k in range(cnt):
        day = (TODAY - dt.timedelta(days=m_ago * 30 + (k % 28))) if m_ago > 0 else \
            (TODAY - dt.timedelta(days=k % max(1, DAYS)))
        created = dt.datetime.combine(day.date() if hasattr(day, 'date') else day,
                                      dt.time(6 + k % 12, (k * 7) % 60))
        fid = main_farmers[k % len(main_farmers)]
        pname = prods[(k * 3) % len(prods)]
        qty = [120, 180, 200, 249, 300, 350, 400, 450, 500, 599, 700, 800][(k * 5) % 12]
        price = round(price_map[pname] * (0.95 + ((k * 11) % 9) * 0.012), 2)
        cid2 = 1 if fid in (1, 2, 7, 9) else (2 if fid in (3, 4) else 3)
        if m_ago == 0:
            status = MONTH0_STATUS[(k * 7) % len(MONTH0_STATUS)]
        else:
            status = 100 if k % 13 else (99 if k % 4 == 0 else 90)
        oid, ono, amount = new_order(fid, cid2, pname, qty, price, status, created,
                                    expected_delivery=created + dt.timedelta(days=1))
        # 质检
        if status >= 40 or status == 41:
            it = created + dt.timedelta(hours=4)
            res = '不合格' if status == 41 else '合格'
            batch = add_quality(oid, fid, pname, it, res)
            order_rows[-1] = order_rows[-1][:11] + (batch,) + order_rows[-1][12:]
            # 溯源
            tr_rows.append((oid, batch, fid, product_ids[pname], sha(batch + str(oid)), batch,
                            json_dump([
                                {'title': '农户发布货源 · 安排采摘', 'time': (created + dt.timedelta(hours=1)).strftime('%Y-%m-%d %H:%M'),
                                 'operator': farmers_name[fid], 'desc': '农户按订单需求采摘 %s %skg，初检合格后安排发货。' % (pname, int(qty))},
                                {'title': '第三方质检合格 · 生成批次', 'time': it.strftime('%Y-%m-%d %H:%M'),
                                 'operator': '赵质检（质检配送员）', 'desc': '农残检测合格、含水率 92%%、新鲜度良好；生成批次号 %s。' % batch},
                            ]), it))
        # 配送
        if status >= 50 and status != 41:
            delivery_seq += 1
            st_time = created + dt.timedelta(hours=5)
            arr_time = created + dt.timedelta(hours=6)
            veh = ['赣H·D2867', '赣H·D2912', '赣H·D2688', '赣H·D2791'][delivery_seq % 4]
            drv = ['钱师傅', '孙师傅', '李师傅', '王师傅'][delivery_seq % 4]
            do_rows.append((delivery_seq, 'PS%s%03d' % (st_time.strftime('%Y%m%d'), 100 + delivery_seq % 900),
                            oid, veh, drv, '138****0%03d' % (156 + delivery_seq % 40), 6.0, 8 + delivery_seq % 20,
                            '绿源基地' if fid == 1 else farmers_name[fid] + '基地',
                            canteens_name[cid2], '已送达' if status >= 60 else '配送中',
                            st_time, arr_time if status >= 60 else None,
                            json_dump([{'time': st_time.strftime('%H:%M'), 'title': '绿源基地装货'},
                                       {'time': (st_time + dt.timedelta(minutes=20)).strftime('%H:%M'), 'title': '冷链运输中'},
                                       {'time': arr_time.strftime('%H:%M'), 'title': '到达 ' + canteens_name[cid2]},
                                       {'time': (arr_time + dt.timedelta(minutes=10)).strftime('%H:%M'), 'title': '扫码交付入库'}])))
        # 入库
        if status >= 70:
            loss_rate = [0, 0, 0, 2.5, 5.0, 0, 8.0, 0][(oid * 3) % 8]
            recv = round(qty * (1 - loss_rate / 100), 2)
            loss = round(qty - recv, 2)
            ir_rows.append((oid, users_by_name['inspector01'], cid2, qty, recv, loss, loss_rate, 1 if loss > 0 else 0,
                            '质检合格', '李建国' if cid2 == 1 else canteens_name[cid2] + '采购员',
                            '运输途中水分自然流失' if loss > 0 else '数量相符，无损耗',
                            created + dt.timedelta(hours=7)))
            if loss_rate > 5:
                pass
        # 结算
        if status >= 80:
            settle_seq += 1
            rec = next((r for r in ir_rows if r[0] == oid), None)
            total = round(qty * price, 2)
            loss_ded = round((rec[5] if rec else 0) * price, 2)
            actual = round(total - loss_ded, 2)
            stt = created + dt.timedelta(hours=8)
            if status == 80:
                sstatus = '待审核'
            elif status == 90 and oid % 23 == 0:
                sstatus = '已驳回'
                actual = 0.0
            else:
                sstatus = '已结算'
            st_rows.append((settle_seq, 'JS%s%03d' % (stt.strftime('%Y%m%d'), 100 + settle_seq % 900),
                            oid, fid, cid2, total, loss_ded, 0.0, actual, sstatus,
                            '王管理' if sstatus != '待审核' else None, '损耗按实收数量自动核算',
                            stt, stt + dt.timedelta(hours=2) if sstatus != '待审核' else None))
            si_rows.append((settle_seq, '订单货款', 'ADD', total, '%s × ¥%s' % (qty, price)))
            if loss_ded > 0:
                si_rows.append((settle_seq, '生鲜损耗扣款', 'SUB', loss_ded,
                                '损耗 %s kg × ¥%s' % (round(qty - rec[4], 2), price)))
        # 评价
        if status == 100 and cid2 == 1:
            score = 4.0 + (oid % 3) * 0.5
            ev_rows.append((oid, cid2, fid, score, score, score, score, score, score,
                            '新鲜水灵,分量足,配送及时,农残合格' if oid % 2 else '分量足,包装完好,价格实惠',
                            '货品新鲜、品相好，实收数量与订单基本一致，配送准时，合作愉快！' if oid % 2
                            else '整体满意，香菇品质优良，希望继续保持冷链温度稳定。',
                            created + dt.timedelta(days=2)))
        # 信用分
        if status == 100:
            before = 90.0
            cs_rows.append((fid, oid, before, 1, min(100.0, before + 1), '正常履约，按质完成订单',
                            created + dt.timedelta(days=2)))
        if status == 41:
            cs_rows.append((fid, oid, 88.0, -5, 83.0, '质检不合格，农残检测超标', created + dt.timedelta(hours=4)))

# 强制补一笔“今日待入库”的标准演示订单（大白菜 400kg @1.80，批次按原型）
DEMO_BATCH = 'PC%sF000024157' % TODAY.strftime('%Y%m%d')
batch_force = DEMO_BATCH
oid, ono, amount = new_order(1, 1, '大白菜', 400, 1.80, 60,
                             NOW - dt.timedelta(hours=3),
                             expected_delivery=NOW + dt.timedelta(hours=5),
                             seq_no=52)
DEMO_ORDER_NO = ono
DEMO_ORDER_ID = oid
it = NOW - dt.timedelta(minutes=150)
add_quality(oid, 1, '大白菜', it, '合格')
order_rows[-1] = order_rows[-1][:11] + (DEMO_BATCH,) + order_rows[-1][12:]
delivery_seq += 1
do_rows.append((delivery_seq, 'PS%s%03d' % (TODAY.strftime('%Y%m%d'), 100 + delivery_seq % 900), oid,
                '赣H·D2867', '钱师傅', '138****0156', 6.0, 8.0, '绿源基地', '第一食堂', '配送中',
                NOW - dt.timedelta(hours=2), None,
                json_dump([{'time': (NOW - dt.timedelta(hours=2)).strftime('%H:%M'), 'title': '绿源基地装货'},
                           {'time': (NOW - dt.timedelta(minutes=95)).strftime('%H:%M'), 'title': '冷链运输中'},
                           {'time': (NOW - dt.timedelta(minutes=40)).strftime('%H:%M'), 'title': '到达 第一食堂'},
                           {'time': (NOW - dt.timedelta(minutes=25)).strftime('%H:%M'), 'title': '扫码交付入库'}])))
tr_rows.append((oid, DEMO_BATCH, 1, product_ids['大白菜'], sha(DEMO_BATCH + '|1|合格'), DEMO_BATCH,
                json_dump([
                    {'title': '农户发布货源 · 安排采摘', 'time': (NOW - dt.timedelta(hours=5)).strftime('%Y-%m-%d %H:%M'),
                     'operator': '张大山（绿源蔬菜合作社）',
                     'desc': '农户按订单需求采摘大白菜 400kg，初检合格后安排发货，记录产地、地块与采收时间。'},
                    {'title': '第三方质检合格 · 生成批次', 'time': (NOW - dt.timedelta(hours=4)).strftime('%Y-%m-%d %H:%M'),
                     'operator': '赵质检（质检配送员）',
                     'desc': '农残检测合格、含水率 92%%、新鲜度良好；生成批次号 %s 并绑定溯源二维码。' % DEMO_BATCH},
                    {'title': '冷链配送出库', 'time': (NOW - dt.timedelta(hours=2)).strftime('%Y-%m-%d %H:%M'),
                     'operator': '配送员 钱师傅',
                     'desc': '全程冷链 2~8℃，车牌 赣H·D2867，扫码出库，GPS 轨迹实时上传。'},
                ]), it))
batch_force = None

rows = []
for (i5, ono, did2, sid2, fid, cid2, pid4, qty, price, amount2, status, batch, exp, ct, ut) in order_rows:
    rows.append("(%d,%s,%s,%s,%d,%d,%d,%s,%s,%s,%d,%s,%s,%s,'0')" % (
        i5, q(ono), q(did2), q(sid2), fid, cid2, pid4, qty, price, amount2, status, q(batch),
        q(exp), q(ct)))
insert("orders", ["id", "order_no", "demand_id", "source_id", "farmer_id", "canteen_id", "product_id",
                  "quantity", "unit_price", "total_amount", "status", "batch_no", "expected_delivery",
                  "create_time", "version"], rows, chunk=80)

insert("order_item", ["order_id", "product_id", "order_quantity", "actual_quantity", "loss_quantity",
                      "unit_price", "create_time"],
       ["(%d,%d,%s,0,0,%s,%s)" % (r[0], r[1], r[2], r[5], q(r[6])) for r in item_rows], chunk=100)

rows = []
for (oid2, batch, iid, iname, sample, result, items, stds, rep, remark, h, it2) in qr_rows:
    rows.append("(%d,%s,%d,%s,%s,%s,%s,%s,%s,%s,%s,%s)" % (
        oid2, q(batch), iid, q(iname), sample, q(result), q(items), q(stds), q(rep), q(remark),
        q(h), q(it2)))
insert("quality_report", ["order_id", "batch_no", "inspector_id", "inspector", "sample_qty", "result",
                          "items", "standards", "report_file", "remark", "hash", "inspect_time"],
       rows, chunk=50)

rows = []
for (i6, dno, oid2, veh, drv, phone, temp, mil, org, dest, status, stt, arr, traces) in do_rows:
    rows.append("(%d,%s,%d,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s)" % (
        i6, q(dno), oid2, q(veh), q(drv), q(phone), temp, mil, q(org), q(dest), q(status),
        q(stt), q(arr), q(traces)))
insert("delivery_order", ["id", "delivery_no", "order_id", "vehicle", "driver", "driver_phone",
                          "temperature", "mileage", "origin", "destination", "status", "start_time",
                          "arrive_time", "traces"], rows, chunk=60)

rows = []
for (oid2, iid, cid2, oq, rq, lq, lr, partial, qres, op, remark, it2) in ir_rows:
    rows.append("(%d,%d,%d,%s,%s,%s,%s,%d,%s,%s,%s,%s)" % (
        oid2, iid, cid2, oq, rq, lq, lr, partial, q(qres), q(op), q(remark), q(it2)))
insert("inbound_record", ["order_id", "inspector_id", "operator_canteen_id", "order_quantity",
                          "receive_quantity", "loss_quantity", "loss_rate", "is_partial",
                          "quality_result", "operator_name", "remark", "inbound_time"], rows, chunk=60)

rows = []
for (i7, sno, oid2, fid, cid2, total, loss, ded, actual, status, auditor, remark, ct, cf) in st_rows:
    rows.append("(%d,%s,%d,%d,%d,%s,%s,%s,%s,%s,%s,%s,%s,%s)" % (
        i7, q(sno), oid2, fid, cid2, total, loss, ded, actual, q(status), q(auditor), q(remark),
        q(ct), q(cf)))
insert("settlement", ["id", "settlement_no", "order_id", "farmer_id", "canteen_id", "total_amount",
                      "loss_deduction", "deduction", "actual_amount", "status", "auditor", "remark",
                      "create_time", "confirm_time"], rows, chunk=60)

insert("settlement_item", ["settlement_id", "item_name", "item_type", "amount", "remark"],
       ["(%d,%s,%s,%s,%s)" % (r[0], q(r[1]), q(r[2]), r[3], q(r[4])) for r in si_rows], chunk=100)

rows = []
for (oid2, batch, fid, pid4, h, qr, chain, ct) in tr_rows:
    rows.append("(%s,%d,%d,%d,%s,%s,%s,%s)" % (q(batch), oid2, fid, pid4, q(h), q(qr), q(chain), q(ct)))
insert("trace_record", ["batch_no", "order_id", "farmer_id", "product_id", "hash", "qr_code",
                        "chain", "create_time"], rows, chunk=50)

rows = []
for (oid2, cid2, fid, ov, qs, fs, ds2, ss, ps, tags, content, ct) in ev_rows:
    rows.append("(%d,%d,%d,%s,%s,%s,%s,%s,%s,%s,%s,%s)" % (
        oid2, cid2, fid, ov, qs, fs, ds2, ss, ps, q(tags), q(content), q(ct)))
insert("evaluation", ["order_id", "canteen_id", "farmer_id", "overall_score", "quality_score",
                      "fresh_score", "delivery_score", "service_score", "package_score", "tags",
                      "content", "create_time"], rows, chunk=60)

rows = []
for (fid, oid2, before, change, after, reason, ct) in cs_rows:
    rows.append("(%d,%d,%s,%s,%s,%s,%s)" % (fid, oid2, before, change, after, q(reason), q(ct)))
insert("credit_score", ["farmer_id", "order_id", "before_score", "change_score", "after_score",
                        "reason", "create_time"], rows, chunk=100)

print("orders:", len(order_rows), "settlements:", len(st_rows), "inbound:", len(ir_rows),
      "demo order:", DEMO_ORDER_NO, DEMO_BATCH)

# ===================================================================== 价格行情
w("-- ------------------------------------------------------------------ 价格行情（近 14 天）")
ph_rows = []
HOT = ['大白菜', '西红柿', '黄瓜', '白萝卜', '青椒', '菠菜', '茄子', '香菇', '马铃薯']
trend = {
    '大白菜': [2.10, 2.06, 2.00, 1.96, 1.92, 1.88, 1.85, 1.85, 1.86, 1.85, 1.84, 1.85, 1.85, 1.85],
    '西红柿': [2.30, 2.35, 2.38, 2.42, 2.46, 2.48, 2.52, 2.55, 2.58, 2.60, 2.60, 2.58, 2.60, 2.60],
}
for pn in HOT:
    arr = trend.get(pn) or [round(price_map[pn] * (0.94 + 0.01 * i), 2) for i in range(14)]
    for i, p in enumerate(arr):
        ph_rows.append((product_ids[pn], dstr(13 - i), p, round(p * 0.92, 2), round(p * 1.1, 2)))
rows = []
for (pid5, sd, avg, mn, mx) in ph_rows:
    rows.append("(%d,%s,%s,%s,%s,'平台成交')" % (pid5, q(sd), avg, mn, mx))
insert("price_history", ["product_id", "stat_date", "avg_price", "min_price", "max_price", "source"],
       rows, chunk=100)

# ===================================================================== 预警
w("-- ------------------------------------------------------------------ 食安预警（46 条，四类风险）")
warn_rows = [
    ('高', '证照预警', '丰禾农业合作社',
     '营业执照将于 %s 到期（剩 14 天）' % (TODAY + dt.timedelta(days=14)).strftime('%Y-%m-%d'),
     '待处理', dtm(1, 8, 0, 0)),
    ('高', '质检预警', '批次 PC%s000142' % ((TODAY - dt.timedelta(days=2)).strftime('%Y%m%d')),
     '菠菜农残检测不达标，已驳回并扣减信用 -5', '已完成', dtm(2, 8, 20, 0)),
    ('中', '质检预警', '批次 PC%s000098' % ((TODAY - dt.timedelta(days=3)).strftime('%Y%m%d')),
     '菠菜含水率超标，判定为不合格', '已完成', dtm(3, 7, 50, 0)),
    ('中', '价格预警', '大白菜', '均价 ¥1.85 较周参考 ¥2.20 下跌 15.9%（超 -15%）', '待确认', dtm(0, 9, 0, 0)),
    ('中', '价格预警', '西红柿', '均价 ¥2.60 较周参考 ¥2.25 上涨 15.6%（超 +15%）', '待确认', dtm(0, 9, 0, 0)),
    ('低', '价格预警', '香菇', '均价 ¥8.50 波动 12%，接近阈值', '已忽略', dtm(1, 9, 0, 0)),
    ('高', '逾期预警', '订单 ' + DEMO_ORDER_NO, '超出约定发货时间 2 小时，已通知农户', '待处理', dtm(0, 8, 0, 0)),
    ('中', '逾期预警', '订单 DD%s000048' % TODAY.strftime('%Y%m%d'), '下单后 3 小时未接单，已触发催办', '待处理', dtm(0, 9, 0, 0)),
]
extra_warn = [
    ('中', '证照预警', '赵氏菜园', '食品经营许可证已过期，账号已停用', '已完成'),
    ('低', '证照预警', '九都蔬菜基地', '检测报告将于 25 天后到期，请提前续期', '待确认'),
    ('中', '证照预警', '农垦蔬菜基地', '合作社登记证将于 18 天后到期', '待处理'),
    ('低', '证照预警', '清溪家庭农场', '种植基地备案 28 天后到期', '待确认'),
    ('高', '质检预警', '批次 PC%s000211' % ((TODAY - dt.timedelta(days=5)).strftime('%Y%m%d')),
     '小白菜重金属抽检超限，整批驳回', '已完成'),
    ('中', '质检预警', '批次 PC%s000176' % ((TODAY - dt.timedelta(days=6)).strftime('%Y%m%d')),
     '生菜腐烂率 2.4% 超 1% 标准', '已完成'),
    ('低', '质检预警', '批次 PC%s000159' % ((TODAY - dt.timedelta(days=7)).strftime('%Y%m%d')),
     '香菇含水率接近上限', '已忽略'),
    ('中', '质检预警', '批次 PC%s000148' % ((TODAY - dt.timedelta(days=8)).strftime('%Y%m%d')),
     '冷链到货温度 9.4℃ 超 2~8℃ 范围', '已完成'),
]
for i in range(38):
    t = ['价格预警', '逾期预警', '质检预警', '证照预警'][i % 4]
    lv = ['中', '低', '高', '中'][i % 4]
    if t == '价格预警':
        pn = HOT[i % len(HOT)]
        target = pn
        arrow = '上涨' if i % 2 == 0 else '下跌'
        content = '均价 ¥%.2f 较周参考波动 %d%%（超 ±15%%）' % (price_map[pn], 16 + i % 8)
    elif t == '逾期预警':
        target = '订单 DD%s%04d' % ((TODAY - dt.timedelta(days=i % 20)).strftime('%Y%m%d'), 60 + i)
        content = '超出约定发货时间 %d 小时，已自动催办' % (2 + i % 10)
    elif t == '质检预警':
        target = '批次 PC%s%06d' % ((TODAY - dt.timedelta(days=i % 25)).strftime('%Y%m%d'), 300 + i * 7)
        content = ['农残快检接近限值', '含水率略高于标准', '外观轻微破损', '抽检重量差异 2%'][i % 4]
    else:
        target = ['德兴粮油合作社', '昌江食用菌基地', '李婶菜园', '虎山果蔬基地', '五里蔬菜合作社'][i % 5]
        content = '营业执照将于 %d 天后到期' % (3 + i % 40)
    st = ['已完成', '待确认', '待处理', '已忽略'][i % 4]
    warn_rows.append((lv, t, target, content, st, dtm(i % 22 + 1, 7 + i % 12, (i * 7) % 60, 0)))
rows = []
for i, (lv, t, target, content, st, tt) in enumerate(warn_rows):
    handler = '王管理' if st in ('已完成', '已忽略', '待确认') else None
    wno = 'YJ%s%03d' % (tt[:10].replace('-', ''), i + 1)
    rows.append("(%s,%s,%s,%s,%s,%s,%s,%s,%s,%s)" % (
        q(wno), q(lv), q(t), q(target), q(content), q(st), q(tt),
        q(handler), q(tt if st in ('已完成', '已忽略') else None),
        q('已核实并处置，纳入信用台账' if st == '已完成' else ('评估后忽略，持续观察' if st == '已忽略' else None))))
insert("warning", ["warning_no", "risk_level", "warning_type", "target", "content", "status",
                   "trigger_time", "handler", "handle_time", "handle_remark"], rows, chunk=50)
print("warnings:", len(warn_rows))

# ===================================================================== 操作日志
w("-- ------------------------------------------------------------------ 操作日志（12,486 条）")
LOG_TEMPLATES = [
    ('订单管理', '审核结算单 JS%s%03d', '王管理', '系统管理员', '成功'),
    ('质检配送', '录入质检报告 PC%s000157', '赵质检', '质检配送员', '成功'),
    ('订单管理', '确认接单 DD%s%04d', '张大山', '农户/合作社', '成功'),
    ('采购需求', '发布需求 X%s0018（大白菜 400kg）', '李建国', '食堂采购员', '成功'),
    ('智能匹配', '需求 X%s0018 自动匹配 Top5 货源', '系统定时任务', '系统', '成功'),
    ('参数配置', '调整价格波动阈值 12%% -> 15%%', '王管理', '系统管理员', '成功'),
    ('结算管理', '审核结算单 JS%s00005（通过）', '钱会计', '结算管理员', '成功'),
    ('登录', '账号 admin 连续密码错误 5 次，已锁定', '未知', '—', '失败'),
    ('采购需求', '撤回需求 X%s0012', '周敏', '食堂采购员', '成功'),
    ('货源管理', '新增货源 大白菜 500kg @1.80', '张大山', '农户/合作社', '成功'),
    ('入库确认', '确认入库 DD%s%04d（实收 380kg，损耗 5%%）', '李建国', '食堂采购员', '成功'),
    ('采购评价', '提交评价 DD%s%04d 总体 5 星', '李建国', '食堂采购员', '成功'),
    ('批次溯源', '查询批次 PC%s000157', '赵质检', '质检配送员', '成功'),
    ('配送任务', '更新配送状态 PS%s0003 -> 已送达', '赵质检', '质检配送员', '成功'),
    ('预警管理', '处置证照预警 YJ%s001', '王管理', '系统管理员', '成功'),
    ('用户管理', '新增用户 farmer33 并分配农户角色', '王管理', '系统管理员', '成功'),
    ('基础档案', '审核农户 红星果蔬合作社 资质（待审核）', '王管理', '系统管理员', '成功'),
    ('登录', '用户 purchaser01 登录成功', '李建国', '食堂采购员', '成功'),
    ('农产品档案', '新增农产品 有机紫甘蓝（优选）', '王管理', '系统管理员', '成功'),
    ('结算管理', '导出对账单 2026-10', '钱会计', '结算管理员', '成功'),
]
LOG_IPS = ['19.12.3.21', '27.16.88.12', '19.12.3.45', '127.0.0.1', '19.12.3.58',
           '27.16.88.12', '19.12.3.46', '202.101.xx.xx']
log_rows = []
TOTAL_LOGS = 12486
for i in range(TOTAL_LOGS):
    tpl = LOG_TEMPLATES[i % len(LOG_TEMPLATES)]
    day = TODAY - dt.timedelta(days=0 if i % 5 else (i % 40))
    day_s = day.strftime('%Y%m%d')
    content = tpl[1]
    if '%s' in content:
        n = content.count('%s')
        args = tuple([day_s] + [100 + (i * 3) % 900] * n)[:n] if n > 1 else (day_s,)
        try:
            content = content % args
        except TypeError:
            content = content.replace('%s', day_s)
    ts = dt.datetime.combine(day, dt.time(6 + i % 15, (i * 13) % 60, (i * 29) % 60))
    log_rows.append((tpl[2], tpl[3], tpl[0], content, LOG_IPS[i % len(LOG_IPS)], tpl[4], ts))
rows = []
for (op, role, mod, content, ip, res, ts) in log_rows:
    rows.append("(%s,%s,%s,%s,%s,%s,%s)" % (q(op), q(role), q(mod), q(content), q(ip), q(res), q(ts)))
insert("operation_log", ["operator", "role_name", "module", "content", "ip", "result", "create_time"],
       rows, chunk=500)
print("logs:", len(log_rows))

with open(os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'db', '02_data.sql'),
          'w', encoding='utf-8') as f:
    f.write("\n".join(out))
print("all done, lines", len(out))














