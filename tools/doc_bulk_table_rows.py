# -*- coding: utf-8 -*-
"""按规格批量给表格追加行并写内容。"""
import json, subprocess, sys, os

SKILL = r"C:\Users\何金波\AppData\Local\Programs\WorkBuddy\resources\app.asar.unpacked\resources\plugins\workbuddy-builtin\skills\workbuddy-local-office-edit"
FID = sys.argv[1]
SPEC = json.load(open(sys.argv[2], encoding="utf-8"))

for t in SPEC["tables"]:
    rows = t["rows"]
    args = {
        "table_id": t["table_id"],
        "file_id": FID,
        "row_inserts": [{"anchor_row": t["anchor_row"], "count": len(rows)}],
        "cells": [],
    }
    for i, row in enumerate(rows):
        for c, text in enumerate(row, start=1):
            args["cells"].append({"insert_index": 0, "row_offset": i, "col": c, "text": text})
    r = subprocess.run([sys.executable, "edsdk.py", "call", "doc_insert_table_rows",
                        "--json", json.dumps(args, ensure_ascii=False)],
                       cwd=SKILL, capture_output=True, text=True, encoding="utf-8", errors="replace")
    out = (r.stdout or "").strip().splitlines()
    msg = out[-1] if out else (r.stderr or "")[:200]
    print(t["name"], "->", msg[:160])
