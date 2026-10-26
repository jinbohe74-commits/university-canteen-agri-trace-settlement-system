# 后端接口自动化冒烟测试
$ErrorActionPreference = "Stop"
$base = "http://127.0.0.1:8080/api"
$script:pass = 0
$script:fail = 0
$script:fails = @()

function Login($u, $p = "123456") {
    $body = @{ username = $u; password = $p; remember = $false } | ConvertTo-Json
    $r = Invoke-RestMethod -Uri "$base/auth/login" -Method Post -ContentType "application/json; charset=utf-8" -Body ([System.Text.Encoding]::UTF8.GetBytes($body))
    if ($r.code -ne 200) { throw "登录失败 $u : $($r.message)" }
    return $r.data.token
}

function Get-Api($name, $path, $token) {
    try {
        $h = @{ Authorization = "Bearer $token" }
        $r = Invoke-RestMethod -Uri "$base$path" -Headers $h -Method Get
        if ($r.code -eq 200) {
            $script:pass++
            $data = $r.data
            $brief = ""
            if ($data -is [System.Management.Automation.PSCustomObject]) {
                $props = $data.PSObject.Properties.Name -join ","
                $brief = "keys=[$props]"
            } elseif ($data -is [array]) {
                $brief = "count=$($data.Count)"
            }
            Write-Output ("  OK   {0,-42} {1}" -f $name, $brief)
        } else {
            $script:fail++
            $script:fails += "$name -> $($r.code) $($r.message)"
            Write-Output ("  FAIL {0,-42} code={1} {2}" -f $name, $r.code, $r.message)
        }
    } catch {
        $script:fail++
        $msg = $_.Exception.Message
        if ($_.ErrorDetails.Message) { $msg = $_.ErrorDetails.Message }
        $script:fails += "$name -> $msg"
        Write-Output ("  ERR  {0,-42} {1}" -f $name, $msg)
    }
}

function Post-Api($name, $path, $token, $bodyObj) {
    try {
        $h = @{ Authorization = "Bearer $token" }
        $json = if ($null -eq $bodyObj) { "{}" } else { $bodyObj | ConvertTo-Json -Depth 6 }
        $r = Invoke-RestMethod -Uri "$base$path" -Headers $h -Method Post -ContentType "application/json; charset=utf-8" -Body ([System.Text.Encoding]::UTF8.GetBytes($json))
        if ($r.code -eq 200) {
            $script:pass++
            Write-Output ("  OK   {0,-42} {1}" -f $name, $r.message)
            return $r.data
        } else {
            $script:fail++
            $script:fails += "$name -> $($r.code) $($r.message)"
            Write-Output ("  FAIL {0,-42} code={1} {2}" -f $name, $r.code, $r.message)
        }
    } catch {
        $script:fail++
        $msg = $_.Exception.Message
        if ($_.ErrorDetails.Message) { $msg = $_.ErrorDetails.Message }
        $script:fails += "$name -> $msg"
        Write-Output ("  ERR  {0,-42} {1}" -f $name, $msg)
    }
}

Write-Output "=== 登录 ==="
$admin = Login "admin"
Write-Output "  admin token ok"
$farmer = Login "farmer24"
Write-Output "  farmer24 token ok"
$purchaser = Login "purchaser01"
Write-Output "  purchaser01 token ok"
$inspector = Login "inspector01"
Write-Output "  inspector01 token ok"

Write-Output "=== 管理员端 ==="
Get-Api "看板" "/admin/dashboard" $admin
Get-Api "农户列表" "/admin/farmers?page=1&size=10" $admin
Get-Api "农户统计" "/admin/farmers?page=1&size=10&type=合作社" $admin
Get-Api "食堂列表" "/admin/canteens?page=1&size=10" $admin
Get-Api "农产品列表" "/admin/products?page=1&size=10" $admin
Get-Api "农产品叶菜类" "/admin/products?page=1&size=10&category=叶菜类" $admin
Get-Api "价格行情" "/admin/products/1/price-history" $admin
Get-Api "用户列表" "/admin/users?page=1&size=10" $admin
Get-Api "角色权限" "/admin/users/roles" $admin
Get-Api "订单监管" "/admin/orders?page=1&size=10" $admin
Get-Api "订单详情" "/admin/orders/1" $admin
Get-Api "结算审核" "/admin/settlements?page=1&size=10" $admin
Get-Api "结算统计" "/admin/settlements?page=1&size=10&status=待审核" $admin
Get-Api "食安预警" "/admin/warnings?page=1&size=10" $admin
Get-Api "预警详情" "/admin/warnings/1" $admin
Get-Api "批次溯源最近" "/admin/trace" $admin
Get-Api "系统参数" "/admin/config" $admin
Get-Api "操作日志" "/admin/logs?page=1&size=10" $admin

Write-Output "=== 农户端 ==="
Get-Api "工作台" "/farmer/workbench" $farmer
Get-Api "货源管理" "/farmer/sources?page=1&size=10" $farmer
Get-Api "订单管理" "/farmer/orders?page=1&size=10" $farmer
Get-Api "结算与收入" "/farmer/settlements?page=1&size=10" $farmer
Get-Api "信用评价" "/farmer/credit" $farmer
Get-Api "消息提醒" "/farmer/messages" $farmer

Write-Output "=== 采购员端 ==="
Get-Api "采购需求" "/purchaser/demands?page=1&size=10" $purchaser
Get-Api "货源大厅" "/purchaser/sources?page=1&size=10" $purchaser
Get-Api "我的订单" "/purchaser/orders?page=1&size=10" $purchaser
Get-Api "入库确认预览" "/purchaser/inbound" $purchaser
Get-Api "近期入库" "/purchaser/inbound/recent" $purchaser
Get-Api "采购评价" "/purchaser/evaluations?page=1&size=10" $purchaser
Get-Api "结算" "/purchaser/settlements?page=1&size=10" $purchaser

$demands = Invoke-RestMethod -Uri "$base/purchaser/demands?page=1&size=10&status=处理中" -Headers @{ Authorization = "Bearer $purchaser" }
$did = $demands.data.page.records[0].id
if ($did) { Get-Api "智能匹配($did)" "/purchaser/match/$did" $purchaser }

Write-Output "=== 质检员端 ==="
Get-Api "我的任务" "/inspector/tasks" $inspector
Get-Api "配送任务" "/inspector/deliveries" $inspector
Get-Api "异常上报配置" "/inspector/exceptions" $inspector

Write-Output "=== 公共 ==="
Get-Api "农产品字典" "/common/products" $admin
Get-Api "分类字典" "/common/categories" $admin
Get-Api "订单状态字典" "/common/order-status" $admin

Write-Output ""
Write-Output "==== 结果：通过 $script:pass，失败 $script:fail ===="
if ($script:fails.Count -gt 0) {
    Write-Output "失败明细："
    $script:fails | ForEach-Object { Write-Output "  - $_" }
}
