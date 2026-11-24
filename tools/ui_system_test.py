#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
修复后系统回归测试（黑盒）：面向运行中的 canteen-trace 前后端。

前置：后端 http://127.0.0.1:8080（java -jar ... --server.port=8080）、前端 5173 已启动。
运行：PYTHONIOENCODING=utf-8 python tools/review_system_test.py

与审查阶段的版本差异：
- 原脚本用「defect()」特征化复现已知缺陷；修复后全部翻转为「assert_ok()」期望断言，
  任何一项不满足即判 FAIL（退出码 1），可直接用于回归门禁。
- 结果写入 docs/fix_2026-11-09/，审查阶段原始结果保留在 docs/review_2026-11-09/ 便于对比。

设计约定：
- 所有本地请求强制绕过 HTTP_PROXY（本机会话设置了代理）。
- 唯一的两处写操作（发布采购需求）在末尾按 demand_no 精确清理，脚本可重复执行。
- 上传探针文件与目录在 finally 中清理，不留垃圾。
"""
import base64
import hashlib
import hmac
import json
import os
import re
import shutil
import subprocess
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

for _k in ("HTTP_PROXY", "HTTPS_PROXY", "http_proxy", "https_proxy", "ALL_PROXY", "all_proxy"):
    os.environ.pop(_k, None)
OPENER = urllib.request.build_opener(urllib.request.ProxyHandler({}))

BASE = "http://127.0.0.1:8080"
ROOT = Path(__file__).resolve().parent.parent
OUT_DIR = ROOT / "docs" / "usability_2026-11-10"
OUT_DIR.mkdir(parents=True, exist_ok=True)
MYSQL = r"C:\Program Files\MySQL\MySQL Server 9.6\bin\mysql.exe"
# 审查报告中泄露过的硬编码密钥；现仅用于验证「用旧密钥伪造的 token 必须被拒绝」
LEAKED_JWT_SECRET = "canteen-trace-direct-supply-trace-settlement-2026-secret-key"
PNG = bytes([0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3, 4])

RESULTS = []
CLEANUP = {"files": [], "dirs": [], "demand_nos": []}


def code_of(r):
    return r.get("code") if isinstance(r, dict) else None


def brief(s, r):
    return f"HTTP {s}, code={code_of(r)}, msg={str((r or {}).get('message'))[:60]!r}"


def record(group, name, status, observed, expected):
    RESULTS.append({"group": group, "name": name, "status": status,
                    "observed": str(observed), "expected": expected})
    print(f"[{status:<6}] {group} | {name} | 实测={observed} | 期望={expected}")


def assert_ok(group, name, condition, observed, expected):
    record(group, name, "PASS" if condition else "FAIL", observed, expected)


def call(method, path, token=None, body=None, raw_data=None, content_type="application/json"):
    data = raw_data if raw_data is not None else (
        json.dumps(body, ensure_ascii=False).encode("utf-8") if body is not None else None)
    req = urllib.request.Request(BASE + path, data=data, method=method)
    if data is not None:
        req.add_header("Content-Type", content_type)
    if token:
        req.add_header("Authorization", "Bearer " + token)
    try:
        with OPENER.open(req, timeout=20) as resp:
            status, raw = resp.status, resp.read()
    except urllib.error.HTTPError as e:
        status, raw = e.code, e.read()
    except Exception as e:  # noqa: BLE001 - 黑盒测试需要吞掉网络异常并记录
        return -1, {"error": repr(e)}, b""
    try:
        parsed = json.loads(raw.decode("utf-8"))
    except Exception:  # noqa: BLE001
        parsed = None
    return status, parsed, raw


def multipart(fields, filename, content, field_name="file"):
    boundary = "----review" + str(int(time.time() * 1000))
    chunks = []
    for k, v in fields.items():
        chunks.append((f"--{boundary}\r\nContent-Disposition: form-data; name=\"{k}\"\r\n\r\n{v}\r\n").encode())
    chunks.append((f"--{boundary}\r\nContent-Disposition: form-data; name=\"{field_name}\"; "
                   f"filename=\"{filename}\"\r\nContent-Type: image/png\r\n\r\n").encode()
                  + content + b"\r\n")
    chunks.append(f"--{boundary}--\r\n".encode())
    return b"".join(chunks), "multipart/form-data; boundary=" + boundary


def b64u(b):
    return base64.urlsafe_b64encode(b).rstrip(b"=").decode("ascii")


def forge_admin_token_with_leaked_secret(user_id=1):
    """用审查阶段泄露的硬编码 secret 离线签发管理员 token（HS384），修复后必须被拒绝。"""
    header = {"alg": "HS384"}
    payload = {"role": "ADMIN", "userId": user_id,
              "iat": int(time.time()), "exp": int(time.time()) + 3600}
    signing_input = (b64u(json.dumps(header, separators=(",", ":")).encode())
                      + "." + b64u(json.dumps(payload, separators=(",", ":")).encode()))
    sig = hmac.new(LEAKED_JWT_SECRET.encode(), signing_input.encode("ascii"), hashlib.sha384).digest()
    return signing_input + "." + b64u(sig)


def tamper_payload(token, new_role="ADMIN"):
    """保留原签名、只篡改 payload 里的 role，验证签名校验生效。"""
    try:
        h, p, sig = token.split(".")
        payload = json.loads(base64.urlsafe_b64decode(p + "=" * (-len(p) % 4)))
        payload["role"] = new_role
        return h + "." + b64u(json.dumps(payload, separators=(",", ":")).encode()) + "." + sig
    except Exception:  # noqa: BLE001
        return token + ".tampered"


def sql(query):
    env = os.environ.copy()
    env["MYSQL_PWD"] = "168168"
    p = subprocess.run([MYSQL, "-uroot", "-D", "canteen_trace", "--default-character-set=utf8mb4",
                        "-N", "-B", "-e", query],
                       capture_output=True, text=True, env=env, encoding="utf-8", errors="replace")
    if p.returncode != 0:
        return "SQL_ERROR:" + p.stderr.strip()[:200]
    return p.stdout.strip()


def cleanup():
    for f in CLEANUP["files"]:
        try:
            Path(f).unlink(missing_ok=True)
        except Exception:  # noqa: BLE001
            pass
    for d in CLEANUP["dirs"]:
        try:
            p = Path(d)
            if p.exists():
                shutil.rmtree(p, ignore_errors=True)
        except Exception:  # noqa: BLE001
            pass
    for no in CLEANUP["demand_nos"]:
        sql(f"DELETE FROM purchase_demand WHERE demand_no = '{no}'")
        sql(f"DELETE FROM operation_log WHERE content LIKE '%{no}%'")


def main():
    tokens = {}
    try:
        # ---------------- A 可用性与认证 ----------------
        s, r, _ = call("GET", "/api/common/health")
        assert_ok("A 可用性", "健康检查返回 UP",
                  s == 200 and code_of(r) == 200 and r.get("data", {}).get("status") == "UP",
                  brief(s, r), "HTTP 200 / code 200 / status UP")

        for u in ("admin", "farmer24", "purchaser01", "inspector01"):
            s, r, _ = call("POST", "/api/auth/login", body={"username": u, "password": "123456"})
            ok = code_of(r) == 200 and bool((r or {}).get("data", {}).get("token"))
            if ok:
                tokens[u] = r["data"]["token"]
            assert_ok("A 可用性", f"{u} 登录成功", ok, brief(s, r), "code 200 + token")

        s, r1, _ = call("POST", "/api/auth/login",
                        body={"username": "review_no_such_user", "password": "123456"})
        s, r2, _ = call("POST", "/api/auth/login",
                        body={"username": "admin", "password": "definitely_wrong"})
        m1 = (r1 or {}).get("message", "")
        m2 = (r2 or {}).get("message", "")
        assert_ok("A 认证", "账号不存在 / 密码错误统一文案（不可枚举账号）",
                  code_of(r1) == 401 and code_of(r2) == 401 and m1 == m2 and bool(m1),
                  f"code={code_of(r1)}/{code_of(r2)}, msg={m1!r}/{m2!r}", "同为 401 且文案一致")

        s, r, _ = call("GET", "/api/admin/dashboard")
        assert_ok("A 认证", "未登录访问管理接口返回真实 HTTP 401",
                  s == 401 and code_of(r) == 401, brief(s, r), "HTTP 401 + code 401")

        s, r, _ = call("GET", "/api/auth/roles")
        assert_ok("A 认证", "未鉴权的角色字典不再泄露演示账号",
                  code_of(r) == 200 and "demoAccount" not in json.dumps(r, ensure_ascii=False),
                  f"含 demoAccount={'demoAccount' in json.dumps(r, ensure_ascii=False)}", "不含演示账号字段")

        # ---------------- B 服务端角色鉴权矩阵 ----------------
        prefixes = {
            "ADMIN": "/api/admin/dashboard",
            "FARMER": "/api/farmer/workbench",
            "PURCHASER": "/api/purchaser/demands?page=1&size=3",
            "INSPECTOR": "/api/inspector/tasks?page=1&size=3",
        }
        role_of = {"admin": "ADMIN", "farmer24": "FARMER",
                   "purchaser01": "PURCHASER", "inspector01": "INSPECTOR"}
        for u, own in role_of.items():
            if u not in tokens:
                continue
            for target, path in prefixes.items():
                s, r, _ = call("GET", path, token=tokens[u])
                should_pass = own == target
                ok = (s == 200 and code_of(r) == 200) if should_pass \
                    else (s == 403 and code_of(r) == 403)
                assert_ok("B 越权", f"{u} → {target} 前缀 {'放行' if should_pass else '403 阻断'}",
                          ok, brief(s, r), "code 200" if should_pass else "HTTP 403 + code 403")
        for target, path in prefixes.items():
            s, r, _ = call("GET", path)
            assert_ok("B 越权", f"未登录 → {target} 前缀 401",
                      s == 401 and code_of(r) == 401, brief(s, r), "HTTP 401")

        # ---------------- C 文件上传与受控下载 ----------------
        data, ct = multipart({"type": "review_probe"}, "probe.png", PNG)
        s, r, _ = call("POST", "/api/files/upload", raw_data=data, content_type=ct)
        assert_ok("C 文件", "无 token 上传被拒绝", s == 401 and code_of(r) == 401,
                  brief(s, r), "HTTP 401")

        s, r, _ = call("POST", "/api/files/upload", token=tokens.get("admin"),
                       raw_data=data, content_type=ct)
        up_ok = code_of(r) == 200
        assert_ok("C 文件", "已登录用户可正常上传合法 PNG", up_ok, brief(s, r), "code 200 + url")
        url = (r or {}).get("data", {}).get("url") if up_ok else None
        if url:
            rel = url.replace("/api/files/", "")
            # upload.dir = ./uploads，相对后端进程工作目录 backend/
            saved = ROOT / "backend" / "uploads" / rel
            CLEANUP["files"].append(saved)
            CLEANUP["dirs"].append(saved.parent)
            s2, _, raw2 = call("GET", url)
            assert_ok("C 文件", "上传文件免鉴权读取被拒绝", s2 == 401,
                      f"HTTP {s2}, {len(raw2)} bytes", "HTTP 401")
            s3, _, raw3 = call("GET", url, token=tokens.get("admin"))
            assert_ok("C 文件", "已登录用户受控下载内容一致", s3 == 200 and raw3 == PNG,
                      f"HTTP {s3}, {len(raw3)} bytes", "HTTP 200 + 原始字节")

        data, ct = multipart({"type": "../../review_escape"}, "probe.png", PNG)
        s, r, _ = call("POST", "/api/files/upload", token=tokens.get("admin"),
                       raw_data=data, content_type=ct)
        escaped = ROOT / "review_escape"
        CLEANUP["dirs"].append(escaped)
        assert_ok("C 文件", "type 路径穿越被拒绝且未写出目录外文件",
                  code_of(r) == 400 and not (escaped.exists() and any(escaped.iterdir())),
                  f"{brief(s, r)}, 逃逸目录存在={escaped.exists()}", "code 400 + 无越界文件")

        data, ct = multipart({"type": "review_probe"}, "probe.exe", PNG, field_name="file")
        s, r, _ = call("POST", "/api/files/upload", token=tokens.get("admin"),
                       raw_data=data, content_type=ct)
        assert_ok("C 文件", "非白名单扩展名被拒绝", code_of(r) == 400,
                  brief(s, r), "code 400")

        data, ct = multipart({"type": "review_probe"}, "fake.png", b"this is not a png")
        s, r, _ = call("POST", "/api/files/upload", token=tokens.get("admin"),
                       raw_data=data, content_type=ct)
        assert_ok("C 文件", "扩展名与文件内容不符被拒绝（魔术字节校验）", code_of(r) == 400,
                  brief(s, r), "code 400")

        # ---------------- D JWT 签名与密钥 ----------------
        s, r, _ = call("GET", "/api/admin/dashboard", token=forge_admin_token_with_leaked_secret(1))
        assert_ok("D 认证", "用旧硬编码密钥伪造的管理员 token 被拒绝",
                  s == 401 and code_of(r) == 401, brief(s, r), "HTTP 401")

        if "farmer24" in tokens:
            s, r, _ = call("GET", "/api/admin/dashboard",
                           token=tamper_payload(tokens["farmer24"], "ADMIN"))
            assert_ok("D 认证", "篡改 payload 角色但保留旧签名被拒绝",
                      s == 401 and code_of(r) == 401, brief(s, r), "HTTP 401")

        # ---------------- E 注销与 token 吊销 ----------------
        s, r, _ = call("POST", "/api/auth/login", body={"username": "farmer24", "password": "123456"})
        old = (r or {}).get("data", {}).get("token") if code_of(r) == 200 else None
        if old:
            call("POST", "/api/auth/logout", token=old)
            s, r2, _ = call("GET", "/api/auth/me", token=old)
            assert_ok("E 认证", "注销后旧 token 立即失效", s == 401 and code_of(r2) == 401,
                      brief(s, r2), "HTTP 401")
            s, r3, _ = call("POST", "/api/auth/login",
                            body={"username": "farmer24", "password": "123456"})
            new = (r3 or {}).get("data", {}).get("token") if code_of(r3) == 200 else None
            s4, r4, _ = call("GET", "/api/auth/me", token=new)
            assert_ok("E 认证", "重新登录的新 token 未被吊销误杀",
                      bool(new) and s4 == 200 and code_of(r4) == 200, brief(s4, r4), "code 200")

        # ---------------- F 输入校验与异常信息 ----------------
        if "inspector01" in tokens:
            s, r, _ = call("POST", "/api/inspector/quality", token=tokens["inspector01"], body={})
            assert_ok("F 校验", "空质检请求返回 400 参数校验错误（非 404/500）",
                      code_of(r) == 400, brief(s, r), "code 400")

        if "admin" in tokens:
            s, r, _ = call("GET", "/api/admin/orders?page=abc", token=tokens["admin"])
            assert_ok("F 校验", "非法分页参数返回 400 而非 500", code_of(r) == 400,
                      brief(s, r), "code 400")

        if "purchaser01" in tokens:
            space_dt = {"productId": 11, "quantity": 200, "expectedPrice": 1.8,
                        "deadline": "2026-12-31 12:00:00", "qualityReq": "REVIEW_FIX_SPACE_DT"}
            s, r, _ = call("POST", "/api/purchaser/demands", token=tokens["purchaser01"], body=space_dt)
            no = ((r or {}).get("data") or {}).get("demandNo")
            if no:
                CLEANUP["demand_nos"].append(no)
            assert_ok("F 校验", "空格分隔日期被正确接受（LocalDateTime 双格式契约）",
                      code_of(r) == 200 and bool(no), f"{brief(s, r)}, demandNo={no}", "code 200")

            bad_dt = {**space_dt, "deadline": "not-a-date", "qualityReq": "REVIEW_FIX_BAD_DT"}
            s, r, _ = call("POST", "/api/purchaser/demands", token=tokens["purchaser01"], body=bad_dt)
            msg = (r or {}).get("message", "") if isinstance(r, dict) else ""
            leaked_stack = any(k in msg for k in ("Cannot deserialize", "Exception", "SQL", "stacktrace"))
            assert_ok("F 校验", "非法日期返回 400 且不回显内部异常堆栈",
                      code_of(r) == 400 and not leaked_stack,
                      f"{brief(s, r)}, 含堆栈={leaked_stack}", "code 400 + 固定文案")

        # ---------------- G 查询安全 ----------------
        if "admin" in tokens:
            _, wild, _ = call("GET", "/api/admin/orders?page=1&size=5&keyword=%25", token=tokens["admin"])
            _, none, _ = call("GET", "/api/admin/orders?page=1&size=5&keyword=REVIEW_NO_SUCH_9981",
                              token=tokens["admin"])
            _, quote, _ = call("GET", "/api/admin/orders?page=1&size=5&keyword=%27%20OR%201%3D1",
                               token=tokens["admin"])
            tw = ((wild or {}).get("data") or {}).get("page", {}).get("total", -1)
            tn = ((none or {}).get("data") or {}).get("page", {}).get("total", -1)
            assert_ok("G 查询", "keyword=% 不再绕过筛选命中全表（LIKE 通配符已转义）",
                      tw < 10 and tn < 10, f"wild={tw}, 不存在关键词={tn}", "两者均接近 0")
            assert_ok("G 查询", "keyword 含 SQL 片段时接口正常返回（参数化查询）",
                      code_of(quote) == 200, f"code={code_of(quote)}", "code 200")

        # ---------------- H 业务闭环（只读一致性） ----------------
        if "admin" in tokens:
            _, r, _ = call("GET", "/api/admin/orders?page=1&size=50", token=tokens["admin"])
            recs = ((r or {}).get("data") or {}).get("page", {}).get("records", [])
            done = next((x for x in recs if x.get("status") == 100 and x.get("batchNo")), None)
            assert_ok("H 闭环", "存在已完成且带批次号的订单", done is not None,
                      f"扫描 {len(recs)} 条", "至少 1 条")
            if done:
                _, detail, _ = call("GET", f"/api/admin/orders/{done['id']}", token=tokens["admin"])
                assert_ok("H 闭环", "订单详情可查询",
                          code_of(detail) == 200 and bool(detail.get("data")),
                          f"code={code_of(detail)}", "code 200 + data")
                bn = done["batchNo"]
                _, tr, raw_tr = call("GET", f"/api/admin/trace?batchNo={bn}", token=tokens["admin"])
                text = raw_tr.decode("utf-8", errors="replace")
                assert_ok("H 闭环", "批次溯源返回批次号与 64 位存证哈希",
                          code_of(tr) == 200 and bn in text and bool(re.search(r"[a-f0-9]{64}", text)),
                          f"code={code_of(tr)}, 含批次号={bn in text}", "code 200 + SHA-256")
            _, st, _ = call("GET", "/api/common/order-status", token=tokens["admin"])
            assert_ok("H 闭环", "订单状态字典完整（12 个状态）",
                      isinstance(st, dict) and len(st.get("data", [])) >= 12,
                      f"{len((st or {}).get('data', []))} 个", ">= 12")

        for role, path in (("farmer24", "/api/farmer/workbench"),
                           ("purchaser01", "/api/purchaser/demands?page=1&size=5"),
                           ("inspector01", "/api/inspector/tasks?page=1&size=5")):
            if role in tokens:
                _, r, _ = call("GET", path, token=tokens[role])
                assert_ok("H 闭环", f"{role} 主页面接口返回业务数据",
                          code_of(r) == 200 and bool(r.get("data")),
                          f"code={code_of(r)}", "code 200 + 非空 data")

        # ---------------- I 受控写路径 + 精确清理 ----------------
        if "purchaser01" in tokens:
            payload = {"productId": 11, "quantity": 200, "expectedPrice": 1.8,
                       "deadline": "2026-12-31T12:00:00", "deliveryMode": "冷链配送",
                       "settlementMode": "按实收结算", "qualityReq": "REVIEW_FIX_SYSTEM_TEST_DELETE_ME"}
            s, r, _ = call("POST", "/api/purchaser/demands", token=tokens["purchaser01"], body=payload)
            created = (r or {}).get("data") or {}
            no, did = created.get("demandNo"), created.get("id")
            if no:
                CLEANUP["demand_nos"].append(no)
            assert_ok("I 写路径", "发布采购需求成功",
                      code_of(r) == 200 and bool(no), f"{brief(s, r)}, demandNo={no}", "code 200 + demandNo")
            if did:
                _, d, _ = call("GET", f"/api/purchaser/demands/{did}", token=tokens["purchaser01"])
                assert_ok("I 写路径", "新需求可按 id 查询", code_of(d) == 200,
                          f"code={code_of(d)}", "code 200")
                _, m, _ = call("GET", f"/api/purchaser/match/{did}", token=tokens["purchaser01"])
                results = ((m or {}).get("data") or {}).get("results", [])
                assert_ok("I 写路径", "智能匹配返回不超过 Top5 的结果",
                          code_of(m) == 200 and len(results) <= 5,
                          f"code={code_of(m)}, results={len(results)}", "code 200 + <=5")
            _, neg, _ = call("POST", "/api/purchaser/demands", token=tokens["purchaser01"],
                             body={**payload, "quantity": -5})
            assert_ok("I 写路径", "负数需求数量被拒绝", code_of(neg) == 400,
                      brief(0, neg), "code 400")
            _, zero, _ = call("GET", f"/api/purchaser/match/999999", token=tokens["purchaser01"])
            assert_ok("I 写路径", "不存在的需求匹配返回业务错误而非 500",
                      code_of(zero) not in (None, 500), f"code={code_of(zero)}", "非 500")

        # ---------------- L 数据库一致性 ----------------
        bad = sql("SELECT COUNT(*) FROM settlement WHERE ABS(total_amount - loss_deduction - deduction - actual_amount) > 0.01")
        assert_ok("L 数据", "全量结算单满足金额公式", bad == "0", f"不一致 {bad} 条", "0 条")
        dups = sql("SELECT COUNT(*) FROM (SELECT order_no FROM orders GROUP BY order_no HAVING COUNT(*) > 1) t")
        assert_ok("L 数据", "订单号唯一", dups == "0", f"重复 {dups} 组", "0 组")
        tables = sql("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='canteen_trace'")
        # 25 张业务表 + sys_event（2026-11-10 实时事件表）
        has_event = sql("SELECT COUNT(*) FROM information_schema.tables "
                        "WHERE table_schema='canteen_trace' AND table_name='sys_event'") == "1"
        assert_ok("L 数据", "数据库 26 张表（25 业务 + 1 实时事件）",
                  tables == "26" and has_event, f"{tables} 张, sys_event={has_event}", "26 张且含 sys_event")
        orders_cnt = sql("SELECT COUNT(*) FROM orders")
        record("L 数据", "演示库订单总量（观察项）", "INFO", f"{orders_cnt} 条", "与修复前基线 330 条一致")
        residue = sql("SELECT COUNT(*) FROM purchase_demand WHERE quality_req LIKE 'REVIEW_FIX_%' OR quality_req LIKE 'REVIEW_PROBE_%'")
        record("L 数据", "清理前测试需求数量（观察项）", "INFO", f"{residue} 条", "脚本结束时应为 0")

        # ---------------- M 配置与运维静态核查 ----------------
        yml = (ROOT / "backend" / "src" / "main" / "resources" / "application.yml").read_text(encoding="utf-8")
        assert_ok("M 配置", "application.yml 不再包含明文 JWT 密钥",
                  LEAKED_JWT_SECRET not in yml, "含硬编码密钥=" + str(LEAKED_JWT_SECRET in yml), "使用环境变量/随机密钥文件")
        assert_ok("M 配置", "不再配置与表结构脱节的全局逻辑删除字段",
                  "logic-delete-field" not in yml, "含 logic-delete-field=" + str("logic-delete-field" in yml), "未配置")
        assert_ok("M 配置", "CORS 白名单化，不再允许任意来源",
                  "allowedOrigins(\"*\")" not in yml and "allowed-origins: *" not in yml
                  and "127.0.0.1:5173" in yml, "已配置具体来源", "显式白名单")
        login_vue = (ROOT / "frontend" / "src" / "views" / "login" / "index.vue").read_text(encoding="utf-8")
        assert_ok("M 配置", "登录页不再展示明文默认口令", "123456" not in login_vue,
                  "含 123456=" + str("123456" in login_vue), "不展示")
        stop_bat = (ROOT / "stop.bat").read_bytes().decode("gbk", errors="replace")
        assert_ok("M 运维", "stop.bat 改为按进程命令行精确识别",
                  "stop_services.ps1" in stop_bat, "调用精确停止脚本", "不再按端口无差别 taskkill")
    finally:
        cleanup()
        probe_dir = ROOT / "backend" / "uploads" / "review_probe"
        leftover = len(list(probe_dir.glob("*"))) if probe_dir.exists() else 0
        assert_ok("C 文件", "上传探针文件已清理（不留垃圾文件）", leftover == 0,
                  f"残留 {leftover} 个", "0 个")
        left = sql("SELECT COUNT(*) FROM purchase_demand WHERE quality_req LIKE 'REVIEW_FIX_%' OR quality_req LIKE 'REVIEW_PROBE_%'")
        assert_ok("I 写路径", "测试需求已精确清理（可重复执行）", left == "0",
                  f"残留 {left} 条", "0 条")

    summary = {"total": len(RESULTS),
               "pass": sum(1 for x in RESULTS if x["status"] == "PASS"),
               "fail": sum(1 for x in RESULTS if x["status"] == "FAIL"),
               "info": sum(1 for x in RESULTS if x["status"] == "INFO")}
    out = OUT_DIR / "system_test_results_usability.json"
    out.write_text(json.dumps({"summary": summary, "cases": RESULTS}, ensure_ascii=False, indent=2),
                   encoding="utf-8")
    print("\n=== 修复后系统回归测试汇总 ===")
    print(json.dumps(summary, ensure_ascii=False))
    print(f"结果文件：{out}")
    return 1 if summary["fail"] else 0


if __name__ == "__main__":
    sys.exit(main())
