# -*- coding: utf-8 -*-
"""批量执行 doc_find_and_replace（一次一条，便于定位失败项）。"""
import subprocess, sys, json, os

SKILL = r"C:\Users\何金波\AppData\Local\Programs\WorkBuddy\resources\app.asar.unpacked\resources\plugins\workbuddy-builtin\skills\workbuddy-local-office-edit"
FID = sys.argv[1] if len(sys.argv) > 1 else None
PAIRS = json.load(open(sys.argv[2], encoding="utf-8"))

ok = fail = 0
for i, (old, new) in enumerate(PAIRS, 1):
    r = subprocess.run(
        [sys.executable, "edsdk.py", "call", "doc_find_and_replace",
         "file_id=" + FID, "old_text=" + old, "new_text=" + new],
        cwd=SKILL, capture_output=True, text=True, encoding="utf-8", errors="replace")
    out = (r.stdout or "") + (r.stderr or "")
    if "find_and_replace ok" in out or '"total"' in out:
        print(f"[{i}] OK  {old[:26]}")
        ok += 1
    else:
        print(f"[{i}] FAIL  {old[:40]}\n     {out.strip()[:220]}")
        fail += 1
print(f"--- ok={ok} fail={fail}")
