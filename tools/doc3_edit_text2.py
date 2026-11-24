# -*- coding: utf-8 -*-
"""实验三第二轮：表6 主要功能补全 + 6.2 说明 + 12.2 表述。"""
import json, subprocess, sys

SKILL = r"C:\Users\何金波\AppData\Local\Programs\WorkBuddy\resources\app.asar.unpacked\resources\plugins\workbuddy-builtin\skills\workbuddy-local-office-edit"
FID = r"C:/Users/何金波/Desktop/大三上/管理信息系统/交付版/242806040121何金波——实验三_系统设计_实验报告.docx"


def call(tool, **kw):
    r = subprocess.run([sys.executable, "edsdk.py", "call", tool, "--json", json.dumps(kw, ensure_ascii=False)],
                       cwd=SKILL, capture_output=True, text=True, encoding="utf-8", errors="replace")
    lines = [l for l in (r.stdout or "").strip().splitlines() if l.startswith("{")]
    if not lines:
        txt = (r.stdout or r.stderr or "").strip()
        if "open completed" in txt:
            return {"raw": txt}
        raise RuntimeError(tool + ": " + txt[:400])
    return json.loads(lines[-1])


r = call("open_file", file_path=FID, file_type="doc", wait=True)
print("open:", json.dumps(r, ensure_ascii=False)[:200])

# ---------- 1) 表6 单元格补全 ----------
CELLS = [
    ("用户管理、角色权限、菜单管理、系统参数配置、操作日志",
     "用户管理、角色权限、菜单管理、系统参数配置、操作日志、站内通知与待办"),
    ("采购需求发布与关闭",
     "采购需求发布与关闭、需求报价与比价定标"),
    ("质检结果录入、批次号生成、配送状态跟踪、异常上报",
     "质检结果录入、批次号生成、配送状态跟踪、异常上报、车厢温度上报"),
    ("入库确认、损耗与部分收货、结算单生成、结算审核",
     "入库确认、损耗与部分收货、库存与出库流水、结算单生成、结算审核"),
]
for old, new in CELLS:
    r = call("doc_find_and_replace", file_id=FID, old_text=old, new_text=new)
    print("cell:", old[:12], "->", json.dumps(r, ensure_ascii=False)[:160])

# ---------- 2) 整段替换 ----------
PARAS = [
    ("分解出",
     "各子系统继续向下分解为若干功能模块，形成“系统—子系统—功能模块”三级层次模块结构，共分解出 40 个功能模块，与实验二确定的功能性需求一一对应；进入实现阶段后，在保持 9 个子系统划分不变的前提下，于相应子系统内补充了需求报价与比价、库存与出库流水、车厢温度上报、站内通知与待办等能力。层次模块结构如图 4 所示。"),
    ("E-R 图布局",
     "（2）E-R 图布局：实体和联系较多时图面拥挤、连线交叉。解决方法是按业务域把 25 张核心表拆成 4 个分图绘制，每个分图内只保留本域实体，跨域实体用虚线框引用；"),
]
for prefix, new in PARAS:
    loc = call("doc_find", file_id=FID, text=prefix)
    if not loc.get("locations"):
        print("para NOTFOUND:", prefix, json.dumps(loc, ensure_ascii=False)[:200])
        continue
    b = loc["locations"][0]["begin"]
    n = call("doc_resolve_document_structure", file_id=FID, idx=b)["nodes"][0]
    r = call("doc_replace_text", file_id=FID, text=new,
             ranges=[{"begin": n["start_index"], "end": n["end_index"]}])
    print("para:", prefix[:10], "->", json.dumps(r, ensure_ascii=False)[:160])

print("save:", json.dumps(call("save_file", file_id=FID), ensure_ascii=False)[:200])
print("close:", json.dumps(call("close_file", file_id=FID), ensure_ascii=False)[:200])
