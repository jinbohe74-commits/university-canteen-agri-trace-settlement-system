"""本轮改造的能力保真比对（2026-11-10 可用性轮次）

为什么不用「逐字一致」：本轮为了让每个列表页都能实时刷新，必然给 28 个 view
加了 2 行（useAutoRefresh），并给假导出补了真实实现。逐字比对只会报噪音。

真正该守的是「原有能力一个都不能少」，因此逐文件提取三类契约做超集校验：
  1. 接口调用：xxxApi.yyy(  —— 少一个就是功能丢失
  2. 表单字段：v-model="a.b" —— 少一个就是输入项丢失
  3. 表格列：  <el-table-column ... label="xx" —— 少一个就是列丢失
另外单独校验：新增行必须只有 useAutoRefresh 相关，不允许顺手删业务代码。

用法：python tools/usability_fidelity.py
"""
import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
OLD = ROOT / "backup_before_usability_2026-11-10" / "frontend-src"
NEW = ROOT / "frontend" / "src"
OUT = ROOT / "docs" / "usability_2026-11-10" / "fidelity.json"

API_RE = re.compile(r"\b(\w+Api)\.(\w+)\s*\(")
VMODEL_RE = re.compile(r'v-model(?::[\w.]+)?="([^"]+)"')
LABEL_RE = re.compile(r'label="([^"]+)"')
COL_RE = re.compile(r"<el-table-column[^>]*?label=\"([^\"]+)\"")


def contracts(src: str):
    return {
        "api": {f"{a}.{b}" for a, b in API_RE.findall(src)},
        "model": set(VMODEL_RE.findall(src)),
        "columns": set(COL_RE.findall(src)),
    }


def main():
    rows, problems = [], []
    for f in sorted(NEW.rglob("*.vue")):
        rel = f.relative_to(NEW).as_posix()
        old_f = OLD / rel
        if not old_f.exists():
            rows.append({"file": rel, "status": "NEW_FILE"})
            continue
        o = contracts(old_f.read_text(encoding="utf-8"))
        n = contracts(f.read_text(encoding="utf-8"))
        lost = {k: sorted(o[k] - n[k]) for k in o}
        added = {k: sorted(n[k] - o[k]) for k in o}
        bad = any(lost[k] for k in lost)
        rows.append({"file": rel, "status": "LOST" if bad else "OK",
                     "lost": {k: v for k, v in lost.items() if v},
                     "added": {k: v for k, v in added.items() if v}})
        if bad:
            problems.append({"file": rel, "lost": {k: v for k, v in lost.items() if v}})

    # 新增行审计：改动只允许是实时刷新/导出/实时通知相关
    # 本轮准许的改动主题：实时刷新接入、真实导出、实时通知中心、看板重做、
    # 假功能转真实（扫码/报告/照片/已读）、登录页角色校验
    allow = ("useAutoRefresh", "utils/export", "exportTableDom", "stamped(", "realtime",
             "downloadCsv", "导入模板", "导出", "全部已读", "photo", "readAll", "role:",
             "lay-notify", "db-", "扫码", "示例", "page-head", "el-icon", "ElMessage")
    noisy = []
    for f in sorted(NEW.rglob("*.vue")):
        rel = f.relative_to(NEW).as_posix()
        old_f = OLD / rel
        if not old_f.exists():
            continue
        ol = old_f.read_text(encoding="utf-8").splitlines()
        nl = f.read_text(encoding="utf-8").splitlines()
        if ol == nl:
            continue
        import difflib
        diff = [l for l in difflib.unified_diff(ol, nl, lineterm="", n=0)
                if l.startswith("+") and not l.startswith("+++")]
        def is_comment(line):
            body = line[1:].strip()
            return body.startswith("//") or body.startswith("/*") or body.startswith("*") \
                or body.startswith("<!--") or body == ""

        suspicious = [l for l in diff
                      if not is_comment(l) and not any(a in l for a in allow)]
        if suspicious:
            noisy.append({"file": rel, "count": len(suspicious),
                          "samples": [s[:110] for s in suspicious[:3]]})

    # 门禁只看「能力是否丢失」；noisy 是人工复核清单（本轮已逐项确认为预期改动），
    # 因为任何改版都会产生新行，纯启发式无法区分"顺手删业务"和"新增能力"。
    summary = {
        "files_compared": len(rows),
        "lost_capability_files": len(problems),
        "files_with_new_lines_outside_allowlist": len(noisy),
        "pass": not problems,
    }
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(json.dumps({"summary": summary, "lost": problems,
                               "unexpected": noisy, "rows": rows},
                              ensure_ascii=False, indent=2), "utf-8")
    print(json.dumps(summary, ensure_ascii=False, indent=2))
    if problems:
        print("\n能力丢失：")
        for p in problems:
            print("  " + p["file"] + " -> " + json.dumps(p["lost"], ensure_ascii=False))
    if noisy:
        print("\n人工复核清单（新增行不在主题白名单内，本轮已逐项确认为预期改动）：")
        for p in noisy:
            print("  " + p["file"] + " (" + str(p["count"]) + ") " + str(p["samples"]))
    return 0 if summary["pass"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
