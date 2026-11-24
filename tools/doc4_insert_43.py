# -*- coding: utf-8 -*-
"""在实验四 4.2 之后插入 4.3 新界面小节。

技巧：始终在同一个锚点 P 插入，并且**按倒序**执行，
这样每次插入都落在 P，先前插入的内容被顶到后面，最终顺序即正序。
避免每次插入后坐标漂移。
"""
import json, subprocess, sys, os

SKILL = r"C:\Users\何金波\AppData\Local\Programs\WorkBuddy\resources\app.asar.unpacked\resources\plugins\workbuddy-builtin\skills\workbuddy-local-office-edit"
FID = sys.argv[1]
P = int(sys.argv[2])
SHOTS = r"C:/Users/何金波/Desktop/管理信息系统/docs/report_2026-11-11/shots"

def call(tool, **kw):
    r = subprocess.run([sys.executable, "edsdk.py", "call", tool, "--json", json.dumps(kw, ensure_ascii=False)],
                       cwd=SKILL, capture_output=True, text=True, encoding="utf-8", errors="replace")
    out = (r.stdout or "").strip()
    line = [l for l in out.splitlines() if l.startswith("{")]
    if not line:
        raise RuntimeError(tool + " failed: " + (out or r.stderr)[:300])
    return json.loads(line[-1])

HEAD_FMT = {"bold": True, "font_size": 14, "font_family": "Times New Roman", "color": "000000"}
HEAD_P = {"spacing_before": 11, "spacing_after": 5, "line_spacing": 1.15, "line_spacing_rule": 1, "jc": "left"}
BODY_FMT = {"font_size": 12, "font_family": "宋体", "color": "000000"}
BODY_P = {"jc": "both", "spacing_before": 0, "spacing_after": 0, "line_spacing": 1.5, "line_spacing_rule": 1}
CAP_FMT = {"font_size": 10.5, "font_family": "宋体", "color": "000000"}
CAP_P = {"jc": "center", "spacing_before": 4, "spacing_after": 4, "line_spacing": 1.15, "line_spacing_rule": 1}

ops = [
    ("h", "4.3  新增界面与协同能力"),
    ("b", "本节补充第 4.2 节之外、实现阶段新增并已投入使用的界面：农户与质检配送员的工作台、采购需求与农户报价比价、配送任务与实时轨迹，以及用户与权限中的账号与密码机制。这些界面集中体现了系统从“能录入”走向“能每天用”的改造。"),
    ("i", "10-农户工作台", 597, 460),
    ("c", "图10  农户工作台（待办、收入与信用一屏可见）"),
    ("b", "农户登录后进入“我的工作台”：顶部横幅给出当日待处理数量与资质状态，四张卡片显示待接单、配送中、本月收入与信用分；中部为近 6 个月销售收入柱状图和待处理订单清单，下方列出在售货源与信用加减分明细。农户不必逐个菜单翻找，在一屏内即可判断“今天该做什么”。"),
    ("i", "11-采购需求与比价", 597, 373),
    ("c", "图11  采购需求与农户报价比价"),
    ("b", "采购员发布需求后，系统按品类推送给相应认证农户；农户在需求大厅报价，采购员在“待比价”页签查看报价家数与剩余交货时间，按价格和信用比价定标。列表按“待比价、已成交、已完成、已撤回”分组并默认停在待比价，避免被历史需求淹没。"),
    ("i", "12-质检任务工作台", 597, 373),
    ("c", "图12  质检配送员任务工作台"),
    ("b", "质检配送员的“我的任务”以今日待质检、已检批次、在途批次与不合格数四张卡片开场，下方直接列出今日待质检订单并给出“质检录入”入口，底部为近 7 日合格率趋势，便于掌握本批作业量与质量波动。"),
    ("i", "15-配送任务与实时轨迹", 597, 611),
    ("c", "图13  配送任务与实时轨迹"),
    ("b", "配送任务页顶部以“待发车／在途／已交付待入库／全部”四段作业状态条切换工作区；选中任务后同屏给出车辆与司机、随车批次、起终点与里程、配送节点进度和车厢温度，下方为实时轨迹与温度上报记录。送达后扫码交付，扫错批次会被拦截。"),
    ("i", "13-用户与权限", 597, 416),
    ("c", "图14  用户与权限（账号与初始密码交付）"),
    ("b", "管理员在“用户与权限”中新增账号时先选角色，农户账号自动出现“绑定农户档案”、采购员出现“绑定食堂”，未绑定不允许保存；系统生成初始密码并弹出凭证卡片，可一键复制转交；用户首次登录会被强制修改初始密码，列表同时标注“待改密”状态。"),
    ("b", "上述界面与实验三的输入输出设计保持一致：列表页默认停在本角色当前需要处理的数据上，任何一条待办或通知都能点开到具体单据，而不再只是跳到一个空列表页。"),
]

ids = []
for op in reversed(ops):
    kind = op[0]
    if kind == "h":
        r = call("doc_insert_paragraph_with_text", file_id=FID, idx=P, text=op[1],
                 text_format=HEAD_FMT, paragraph_property=HEAD_P)
    elif kind == "c":
        r = call("doc_insert_paragraph_with_text", file_id=FID, idx=P, text=op[1],
                 text_format=CAP_FMT, paragraph_property=CAP_P)
    elif kind == "b":
        r = call("doc_insert_paragraph_with_text", file_id=FID, idx=P, text=op[1],
                 text_format=BODY_FMT, paragraph_property=BODY_P)
        pid = r.get("paragraph_id")
        if pid:
            call("doc_modify_paragraph", file_id=FID, paragraph_id=pid, first_line_indent_chars=2)
        else:
            print("  ! 无 paragraph_id", op[1][:20])
    else:
        r = call("doc_insert_image", file_id=FID, idx=P,
                 image_path=SHOTS + "/" + op[1] + ".png", w=op[2], h=op[3])
    ids.append((kind, op[1][:20] if kind != "i" else op[1], r.get("paragraph_id") or r.get("image_url", "")[:40]))
    print(kind, op[1][:24] if kind != "i" else op[1], "->", r.get("paragraph_id") or r.get("position") or "ok")

json.dump(ids, open(r"C:/Users/何金波/Desktop/管理信息系统/.tmpdocs/doc4_43_ids.json", "w", encoding="utf-8"), ensure_ascii=False, indent=1)
print("done")
