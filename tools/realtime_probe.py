"""跨角色实时反馈端到端探针（真实后端，非 mock）

覆盖用户明确点名的两条链路：
  1) 农户发布货源 -> 采购员/管理员的轮询接口立刻能取到事件
  2) 管理员停用账号 -> 该账号 token 立即失效 + 定向事件落库

用法：python tools/realtime_probe.py
"""
import json
import sys
import time
import urllib.request
import urllib.error

BASE = "http://127.0.0.1:8080"
OPENER = urllib.request.build_opener(urllib.request.ProxyHandler({}))

results = []


def call(method, path, token=None, body=None, raw=False):
    data = None
    headers = {}
    if body is not None:
        data = json.dumps(body).encode("utf-8")
        headers["Content-Type"] = "application/json"
    if token:
        headers["Authorization"] = "Bearer " + token
    req = urllib.request.Request(BASE + path, data=data, headers=headers, method=method)
    try:
        with OPENER.open(req, timeout=20) as r:
            text = r.read().decode("utf-8")
            return r.status, (text if raw else json.loads(text))
    except urllib.error.HTTPError as e:
        text = e.read().decode("utf-8")
        try:
            return e.code, (text if raw else json.loads(text))
        except Exception:
            return e.code, text


def check(name, ok, detail=""):
    results.append((name, bool(ok), detail))
    print(("  PASS " if ok else "  FAIL ") + name + ("  | " + str(detail)[:220] if detail else ""))
    return ok


def login(username, password="123456", role=None):
    body = {"username": username, "password": password}
    if role:
        body["expectedRole"] = role
    st, r = call("POST", "/api/auth/login", body=body)
    if st != 200 or not isinstance(r, dict) or r.get("code") != 200:
        return None, r
    return r["data"], r


def main():
    print("== 1. 三方登录 ==")
    admin, ar = login("admin")
    farmer, fr = login("farmer24")
    purchaser, pr = login("purchaser01")
    check("管理员登录", admin is not None, ar)
    check("农户登录", farmer is not None, fr)
    check("采购员登录", purchaser is not None, pr)
    if not (admin and farmer and purchaser):
        return finish()

    farm_user_id = farmer["user"]["id"]
    _, cur = call("GET", "/api/common/events/ticket", token=admin["token"])
    base_cursor = cur["data"]["cursor"] if isinstance(cur, dict) and cur.get("code") == 200 else 0
    print("  基线事件游标 =", base_cursor)

    print("\n== 2. 农户发布货源 -> 其他角色实时可见 ==")
    prod_st, prod = call("GET", "/api/farmer/sources", token=farmer["token"])
    product_id = None
    if isinstance(prod, dict) and prod.get("code") == 200:
        recs = prod["data"]["page"]["records"]
        if recs:
            product_id = recs[0].get("productId")
    if not product_id:
        _, plist = call("GET", "/api/admin/products?page=1&size=1", token=admin["token"])
        if isinstance(plist, dict) and plist.get("code") == 200:
            product_id = plist["data"]["page"]["records"][0]["id"]
    check("取得可用商品ID", product_id is not None, product_id)

    payload = {
        "productId": product_id,
        "quantity": 88,
        "price": 3.6,
        "unit": "kg",
        "grade": "一级",
        "origin": "实时联调产地",
        "remark": "realtime-probe",
    }
    st, created = call("POST", "/api/farmer/sources", token=farmer["token"], body=payload)
    new_source_id = created["data"]["id"] if isinstance(created, dict) and created.get("code") == 200 else None
    check("农户发布货源成功", st == 200 and new_source_id is not None, created)

    time.sleep(1.2)
    _, ev = call("GET", "/api/common/events?since=%d&limit=50" % base_cursor, token=purchaser["token"])
    items = ev["data"] if isinstance(ev, dict) and ev.get("code") == 200 else []
    hit = [x for x in items if "货源" in str(x.get("module", "")) or "货源" in str(x.get("content", ""))]
    check("采购员轮询到农户发布事件", len(hit) > 0,
          [x.get("content") for x in hit][:2] or ("事件数=%d" % len(items)))

    _, ev_admin = call("GET", "/api/common/events?since=%d&limit=50" % base_cursor, token=admin["token"])
    items_a = ev_admin["data"] if isinstance(ev_admin, dict) and ev_admin.get("code") == 200 else []
    check("管理员同样实时可见", len(items_a) > 0, [x.get("content") for x in items_a][:2])

    # 采购员页面数据是否已包含新货源（列表接口真刷新）
    _, src_list = call("GET", "/api/purchaser/sources?page=1&size=5", token=purchaser["token"])
    ok_list = False
    if isinstance(src_list, dict) and src_list.get("code") == 200:
        recs = src_list["data"].get("page", {}).get("records", [])
        ok_list = any(r.get("id") == new_source_id for r in recs)
    check("采购员货源列表能读到该新货源", ok_list, (src_list.get("data", {}) if isinstance(src_list, dict) else src_list))

    print("\n== 3. 管理员停用账号 -> 该账号立即失效 + 定向事件 ==")
    st, dis = call("PUT", "/api/admin/users/%d/status?status=0" % farm_user_id, token=admin["token"])
    check("停用农户账号成功", isinstance(dis, dict) and dis.get("code") == 200, dis)

    time.sleep(0.8)
    st2, me = call("GET", "/api/auth/me", token=farmer["token"])
    check("被停用账号的旧 token 立即被拒", st2 in (401, 403), {"http": st2, "body": me})

    relogin_data, relogin_resp = login("farmer24")
    check("被停用账号无法重新登录", relogin_data is None, relogin_resp)

    print("\n== 4. 恢复现场 ==")
    st, en = call("PUT", "/api/admin/users/%d/status?status=1" % farm_user_id, token=admin["token"])
    check("恢复农户账号", isinstance(en, dict) and en.get("code") == 200, en)
    farmer2, _ = login("farmer24")
    check("恢复后可正常登录", farmer2 is not None, None)

    # 定向事件用该账号自己的视角验证：停用通知只对被停用的人可见，管理员不该看到
    if farmer2:
        _, ev2 = call("GET", "/api/common/events?since=%d&limit=50" % base_cursor, token=farmer2["token"])
        acc = [x for x in (ev2["data"] if isinstance(ev2, dict) and ev2.get("code") == 200 else [])
               if x.get("eventType") == "ACCOUNT_DISABLED"]
        check("被停用账号能取到定向停用事件", len(acc) > 0, acc[:1])

    _, ev3 = call("GET", "/api/common/events?since=%d&limit=50" % base_cursor, token=admin["token"])
    leak = [x for x in (ev3["data"] if isinstance(ev3, dict) and ev3.get("code") == 200 else [])
            if x.get("eventType") == "ACCOUNT_DISABLED"]
    check("停用事件不越权泄露给管理员", len(leak) == 0, leak[:1])

    if new_source_id and farmer2:
        from urllib.parse import quote
        st, off = call("PUT", "/api/farmer/sources/%d/status?status=%s" % (new_source_id, quote("下架")),
                       token=farmer2["token"])
        check("探针货源置为下架（清理）", isinstance(off, dict) and off.get("code") == 200, off)

    return finish()


def finish():
    passed = sum(1 for _, ok, _ in results if ok)
    total = len(results)
    print("\n=== 实时联调汇总: %d/%d PASS, %d FAIL ===" % (passed, total, total - passed))
    out = {
        "summary": {"total": total, "pass": passed, "fail": total - passed},
        "cases": [{"name": n, "ok": ok, "detail": str(d)[:400]} for n, ok, d in results],
    }
    import os
    os.makedirs("docs/usability_2026-11-10", exist_ok=True)
    with open("docs/usability_2026-11-10/realtime_probe.json", "w", encoding="utf-8") as f:
        json.dump(out, f, ensure_ascii=False, indent=2)
    return 0 if passed == total else 1


if __name__ == "__main__":
    sys.exit(main())
