# -*- coding: utf-8 -*-
"""实验三：按段落全文替换 + 短语替换。"""
import json, subprocess, sys, os

SKILL = r"C:\Users\何金波\AppData\Local\Programs\WorkBuddy\resources\app.asar.unpacked\resources\plugins\workbuddy-builtin\skills\workbuddy-local-office-edit"
FID = r"C:/Users/何金波/Desktop/大三上/管理信息系统/交付版/242806040121何金波——实验三_系统设计_实验报告.docx"

def call(tool, **kw):
    r = subprocess.run([sys.executable, "edsdk.py", "call", tool, "--json", json.dumps(kw, ensure_ascii=False)],
                       cwd=SKILL, capture_output=True, text=True, encoding="utf-8", errors="replace")
    lines = [l for l in (r.stdout or "").strip().splitlines() if l.startswith("{")]
    if not lines:
        raise RuntimeError(tool + ": " + (r.stdout or r.stderr)[:300])
    return json.loads(lines[-1])

# ---------- 1) 短语替换 ----------
PAIRS = [
    ("系统共设计25张数据表，清单如表10所示。",
     "系统共设计 33 张数据表：其中 25 张为 9.3 节详述的核心业务表，另 8 张为实现阶段补充的支撑表（采购单、需求报价、分品类质检标准、食堂库存、出库流水、冷链温度上报、定向通知、跨角色事件），清单如表10所示。"),
]
for old, new in PAIRS:
    print("replace:", call("doc_find_and_replace", file_id=FID, old_text=old, new_text=new).get("message"))

# ---------- 2) 整段替换 ----------
PARAS = [
    ("用户密码采用BCrypt",
     "用户密码采用 BCrypt 加盐哈希后存储，数据库中不保存明文密码，即使数据库泄露也难以还原。密码策略统一收敛在后端一处实现：长度 8～24 位且须同时包含字母与数字，并过滤 12345678、admin123 等常见弱口令。账号新建或被管理员重置后置“待改密”标记，登录后除查看本人信息与修改密码外，其余请求一律返回 428，前端弹出不可关闭的强制改密框，改密完成前不加载业务页面；自助改密须校验原密码，改密成功后立即吊销该账号此前签发的全部令牌（其他设备自动下线），当前会话换发新令牌；同一账号连续输错 5 次锁定 10 分钟。"),

    ("系统界面遵循简洁",
     "系统界面遵循简洁、统一、易用的原则，采用“顶部导航栏 + 左侧功能菜单 + 右侧内容区”的管理后台布局，并按角色只呈现与本岗位相关的菜单。顶部常驻搜索、待办、通知与当前用户：待办数字与各业务列表同源，点开即按分组列出待办条目，点击直达具体单据。主要界面包括登录页、管理员数据看板、采购需求与比价、质检录入、配送任务与实时轨迹、批次溯源查询等，如图 6 所示。"),

    ("（1）登录页：居中显示系统名称和登录卡片",
     "（1）登录页：左侧为品牌与系统定位说明，右侧为登录卡片，先选择角色再输入账号与密码，支持记住账号、同一浏览器多标签页分别登录不同角色；"),

    ("（2）数据看板：顶部为系统导航和当前用户",
     "（2）数据看板：以统计卡片、订单趋势折线、采购品类环形图与可自选品类的价格行情曲线展示运营态势，右侧列示实时通知，下方给出待办事项、本月结算概况、农户信用 Top5 与各食堂采购占比；"),

    ("（3）订单管理：提供筛选栏和订单表格",
     "（3）采购需求与比价：按“待比价／已成交／已完成／已撤回”分组，列出需求编号、品项与品类、数量、期望单价、交货截止与剩余天数、报价家数与状态，可直接比价定标；"),

    ("（4）入库确认：以弹窗形式录入实收数量",
     "（4）质检录入：按分品类质检标准（检测项、方法与限值）逐项录入实测值，系统即时判定“在范围内／超标”并汇总结论，全部合格后提交并生成溯源批次二维码；"),

    ("（5）溯源查询：支持输入或扫描批次码",
     "（5）配送任务与实时轨迹：顶部为“待发车／在途／已交付／全部”作业状态条，中部展示车辆与司机、随车批次、节点进度和车厢温度，下方为实时轨迹与温度上报记录，送达扫码交付；"),

    ("（6）货源管理（农户端）",
     "（6）批次溯源查询：输入或扫描批次码后，展示批次基础信息、溯源二维码、全链路时间线（农户备货→质检合格→冷链配送→食堂入库→结算）与质检报告，并给出 SHA-256 数据存证。"),
]

for prefix, new in PARAS:
    loc = call("doc_find", file_id=FID, text=prefix)["locations"][0]
    n = call("doc_resolve_document_structure", file_id=FID, idx=loc["begin"])["nodes"][0]
    r = call("doc_replace_text", file_id=FID, text=new,
             ranges=[{"begin": n["start_index"], "end": n["end_index"]}])
    print("para:", prefix[:16], "->", r.get("message"), r.get("version"))

print(call("save_file", file_id=FID).get("message"))
