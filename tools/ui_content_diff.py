"""前端重构内容保真比对（2026-11-10）

口径（经验库 L-020）：业务字段一个字都不重敲。
1) 对每个 .vue 去掉 <style>...</style>，比对剩余 template+script 源码逐字；
2) 再去标签取纯文本，比对可见业务文案；
3) views/ 必须 100% 一致；components/ 允许 BaseChart / StatCard 两处已知的外壳修复。

输出 docs/design_2026-11-10/ui_acceptance/content_diff.json
"""
import json
import re
import pathlib
import difflib

ROOT = pathlib.Path(__file__).resolve().parent.parent
BACKUP = ROOT / "backup_before_ui_2026-11-10" / "frontend-src"
CUR = ROOT / "frontend" / "src"
OUT = ROOT / "docs" / "design_2026-11-10" / "ui_acceptance"
OUT.mkdir(parents=True, exist_ok=True)

STYLE = re.compile(r"<style[^>]*>.*?</style>", re.S)
TAG = re.compile(r"<[^>]+>")
WS = re.compile(r"\s+")

# 允许差异的外壳文件：BaseChart 主题感知重绘 / StatCard 硬编码色改 token /
# AppLayout 是本次布局层重构主体（三态导航 + 命令面板 + 交互层）。
# 对 AppLayout 另加「业务动作契约」断言，防止换外壳时把鉴权与防 XSS 逻辑弄丢。
ALLOWED = {
    "components/BaseChart.vue",
    "components/StatCard.vue",
    "layout/AppLayout.vue",
}

LAYOUT_CONTRACT = {
    "刷新后服务端复核身份": "store.refresh()",
    "退出登录走 store.logout": "store.logout()",
    "待办数量走管理员接口": "adminApi.dashboard()",
    "账号信息用 VNode 防 XSS（非 HTML 拼接）": "ElMessageBox.alert(h(",
    "角色待办分流保留": "store.role === 'ADMIN'",
    "交互层按要求安装": "installEnhancements()",
}


def business_code(text):
    return STYLE.sub("", text).strip()


def visible_text(text):
    code = business_code(text)
    return WS.sub(" ", TAG.sub(" ", code)).strip()


def main():
    rows = []
    for old in sorted(BACKUP.rglob("*.vue")):
        rel = old.relative_to(BACKUP).as_posix()
        new = CUR / rel
        if not new.exists():
            rows.append({"file": rel, "status": "MISSING", "code_equal": False, "text_equal": False})
            continue
        a, b = old.read_text(encoding="utf-8"), new.read_text(encoding="utf-8")
        code_equal = business_code(a) == business_code(b)
        text_equal = visible_text(a) == visible_text(b)
        if code_equal:
            status = "IDENTICAL"
        elif rel in ALLOWED:
            status = "ALLOWED_SHELL_CHANGE"
        else:
            status = "UNEXPECTED_CHANGE"
        rows.append({
            "file": rel,
            "status": status,
            "code_equal": code_equal,
            "text_equal": text_equal,
            "diff": [] if code_equal else list(difflib.unified_diff(
                business_code(a).splitlines(), business_code(b).splitlines(),
                fromfile="backup/" + rel, tofile="current/" + rel, lineterm="", n=1
            ))[:14],
        })

    views = [r for r in rows if r["file"].startswith("views/")]
    bad = [r for r in rows if r["status"] == "UNEXPECTED_CHANGE" or r["status"] == "MISSING"]
    # 纯文本口径只约束业务 view；布局/组件本就要新增控件文案
    text_bad = [r for r in views if not r["text_equal"]]
    layout_src = (CUR / "layout" / "AppLayout.vue").read_text(encoding="utf-8")
    layout_contract = {name: (token in layout_src) for name, token in LAYOUT_CONTRACT.items()}
    summary = {
        "total_vue": len(rows),
        "views": len(views),
        "views_identical": sum(1 for r in views if r["code_equal"]),
        "components_and_layout": len(rows) - len(views),
        "allowed_shell_changes": [r["file"] for r in rows if r["status"] == "ALLOWED_SHELL_CHANGE"],
        "unexpected_changes": [r["file"] for r in bad],
        "visible_text_mismatches_in_views": [r["file"] for r in text_bad],
        "layout_business_contract": layout_contract,
        "pass": (not bad
                 and not text_bad
                 and all(layout_contract.values())
                 and sum(1 for r in views if r["code_equal"]) == len(views)),
    }
    (OUT / "content_diff.json").write_text(
        json.dumps({"summary": summary, "files": rows}, ensure_ascii=False, indent=2), encoding="utf-8"
    )
    print(json.dumps(summary, ensure_ascii=False, indent=2))
    for r in bad:
        print("UNEXPECTED:", r["file"])
        print("\n".join(r["diff"][:10]))
    print("结果文件：", OUT / "content_diff.json")
    return 0 if summary["pass"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
