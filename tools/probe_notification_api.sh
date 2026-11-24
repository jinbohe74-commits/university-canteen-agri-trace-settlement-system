#!/usr/bin/env bash
# 通知删除接口自测（HTTP 200 + body.code 约定）
API=http://127.0.0.1:8080/api
J='Content-Type: application/json'

login() {
  curl -s --noproxy '*' -X POST $API/auth/login -H "$J" \
    -d "{\"username\":\"$1\",\"password\":\"123456\",\"expectedRole\":\"$2\"}" \
    | python -c "import sys,json;print(json.load(sys.stdin)['data']['token'])"
}

T=$(login purchaser01 PURCHASER)
echo "token_len=${#T}"

echo "--- 1) 初始计数 ---"
curl -s --noproxy '*' "$API/notifications/recent?limit=100" -H "Authorization: Bearer $T" \
  | python -c "import sys,json;d=json.load(sys.stdin)['data'];print('unread=',d['unread'],'read=',d['read'],'items=',len(d['items']))"

UNREAD_ID=$(curl -s --noproxy '*' "$API/notifications/recent?limit=100" -H "Authorization: Bearer $T" \
  | python -c "import sys,json;d=json.load(sys.stdin)['data'];print(next(x['id'] for x in d['items'] if not x['isRead']))")
READ_ID=$(curl -s --noproxy '*' "$API/notifications/recent?limit=100" -H "Authorization: Bearer $T" \
  | python -c "import sys,json;d=json.load(sys.stdin)['data'];print(next(x['id'] for x in d['items'] if x['isRead']))")
echo "unread_id=$UNREAD_ID read_id=$READ_ID"

echo "--- 2) 删未读（应拒绝）---"
curl -s --noproxy '*' -X DELETE "$API/notifications/$UNREAD_ID" -H "Authorization: Bearer $T"; echo

echo "--- 3) 删单条已读（应成功）---"
curl -s --noproxy '*' -X DELETE "$API/notifications/$READ_ID" -H "Authorization: Bearer $T"; echo

echo "--- 4) 一键删除已读 ---"
curl -s --noproxy '*' -X DELETE "$API/notifications/read" -H "Authorization: Bearer $T"; echo

echo "--- 5) 再删已读（应提示没有）---"
curl -s --noproxy '*' -X DELETE "$API/notifications/read" -H "Authorization: Bearer $T"; echo

echo "--- 6) 最终计数（未读应保持不变）---"
curl -s --noproxy '*' "$API/notifications/recent?limit=100" -H "Authorization: Bearer $T" \
  | python -c "import sys,json;d=json.load(sys.stdin)['data'];print('unread=',d['unread'],'read=',d['read'],'items=',len(d['items']))"

echo "--- 7) 越权：admin 删 purchaser01 的通知（应拒绝）---"
TA=$(login admin ADMIN)
curl -s --noproxy '*' -X DELETE "$API/notifications/$UNREAD_ID" -H "Authorization: Bearer $TA"; echo
