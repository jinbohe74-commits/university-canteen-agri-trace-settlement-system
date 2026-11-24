# -*- coding: utf-8 -*-
"""把「图片+图注」同一段拆成两段，并让图注居中、图片段居中。"""
import json, subprocess, sys

SKILL = r"C:\Users\何金波\AppData\Local\Programs\WorkBuddy\resources\app.asar.unpacked\resources\plugins\workbuddy-builtin\skills\workbuddy-local-office-edit"
FID = sys.argv[1]
CAPS = ["图10  农户工作台", "图11  采购需求与农户报价比价", "图12  质检配送员任务工作台",
        "图13  配送任务与实时轨迹", "图14  用户与权限"]

def call(tool, **kw):
    r = subprocess.run([sys.executable, "edsdk.py", "call", tool, "--json", json.dumps(kw, ensure_ascii=False)],
                       cwd=SKILL, capture_output=True, text=True, encoding="utf-8", errors="replace")
    out = (r.stdout or "").strip()
    lines = [l for l in out.splitlines() if l.startswith("{")]
    if not lines:
        raise RuntimeError(tool + " failed: " + (out or r.stderr)[:300])
    return json.loads(lines[-1])

# 1) 逐个在“图注首字”处插入段落分隔符（图片留在上一段）
for cap in CAPS:
    loc = call("doc_find", file_id=FID, text=cap)["locations"][0]
    b = loc["begin"]
    r = call("doc_insert_paragraph", file_id=FID, idx=b)
    print("split at", b, cap[:10], "->", {k: v for k, v in r.items() if k in ("position", "last_edit_index")})

# 2) 重新解析，按文本定位图注段并居中
struct = call("doc_resolve_document_structure", file_id=FID, mode="compact", text_preview_length=80, limit=0)
nodes = struct["nodes"]
for cap in CAPS:
    hit = [n for n in nodes if (n.get("text_preview") or "").startswith(cap)]
    if not hit:
        print("！未找到", cap)
        continue
    n = hit[0]
    # 图注段：居中 + 上下 4pt + 1.15 倍行距
    call("doc_modify_paragraph", file_id=FID,
         ranges=[{"begin": n["start_index"], "end": n["end_index"]}],
         jc="center", spacing_before=4, spacing_after=4, line_spacing=1.15, line_spacing_rule=1)
    # 图片段（上一节点）：居中
    i = nodes.index(n)
    prev = nodes[i - 1]
    call("doc_modify_paragraph", file_id=FID,
         ranges=[{"begin": prev["start_index"], "end": prev["end_index"]}],
         jc="center", spacing_before=6, spacing_after=2)
    print("formatted", cap[:12], "image@", prev["start_index"], "cap@", n["start_index"])
print("done")
