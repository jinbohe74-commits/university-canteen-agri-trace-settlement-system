# 端到端业务流程验证：需求→匹配→下单→接单→发货→质检→配送→入库→结算→评价→溯源
$ErrorActionPreference = "Stop"
$base = "http://127.0.0.1:8080/api"
$ok = 0; $bad = 0

function Login($u) {
    $b = @{ username = $u; password = "123456"; remember = $false } | ConvertTo-Json
    $r = Invoke-RestMethod -Uri "$base/auth/login" -Method Post -ContentType "application/json" -Body ([System.Text.Encoding]::UTF8.GetBytes($b))
    return $r.data.token
}
function Hdr($t) { @{ Authorization = "Bearer $t" } }
function Call($method, $path, $token, $bodyObj = $null) {
    $h = Hdr $token
    $p = @{ Uri = "$base$path"; Headers = $h; Method = $method }
    if ($null -ne $bodyObj) {
        $p.ContentType = "application/json; charset=utf-8"
        $p.Body = [System.Text.Encoding]::UTF8.GetBytes(($bodyObj | ConvertTo-Json -Depth 8))
    }
    $r = Invoke-RestMethod @p
    if ($r.code -ne 200) { throw "接口失败 $path : $($r.code) $($r.message)" }
    return $r.data
}
function Step($name) { Write-Output ""; Write-Output "▶ $name" }
function Assert($cond, $msg) {
    if ($cond) { $script:ok++; Write-Output "   ✓ $msg" } else { $script:bad++; Write-Output "   ✗ $msg" }
}

$admin = Login "admin"; $farmer = Login "farmer24"; $buyer = Login "purchaser01"; $insp = Login "inspector01"
Write-Output "四个角色登录成功"

Step "1. 采购员发布采购需求（大白菜 200kg）"
$products = Call GET "/common/products" $buyer
$cabbage = $products | Where-Object { $_.name -eq "大白菜" } | Select-Object -First 1
$deadline = (Get-Date).AddDays(1).ToString("yyyy-MM-ddTHH:mm:ss")
$demand = Call POST "/purchaser/demands" $buyer @{
    productId = $cabbage.id; quantity = 200; expectedPrice = 2.00; deadline = $deadline
    deliveryMode = "冷链配送"; settlementMode = "月结"; qualityReq = "要求新鲜、无黄叶、农残检测合格"
}
Assert ($demand.demandNo -like "X*") "需求创建成功 $($demand.demandNo)"

Step "2. 五因子智能匹配 Top5"
$match = Call GET "/purchaser/match/$($demand.id)" $buyer
Assert ($match.results.Count -ge 1) "匹配出 $($match.results.Count) 条货源，有效货源 $($match.validCount) 家"
$best = $match.results[0]
Write-Output ("   最佳匹配：" + $best.farmerName + " 报价 ¥" + $best.price + " 综合得分 " + $best.score)
Assert ($best.best -eq $true) "排名第一标记为最佳匹配"

Step "3. 采购员下单"
$order = Call POST "/purchaser/orders" $buyer @{
    demandId = $demand.id; sourceId = $best.sourceId; quantity = 200
    expectedDelivery = $deadline
}
Assert ($order.orderNo -like "DD*") "订单生成 $($order.orderNo)，金额 ¥$($order.totalAmount)，状态 $($order.status)"

Step "4. 农户接单并联调乐观锁"
$d1 = Call GET "/admin/orders/$($order.id)" $admin
$ord = $d1.order
Assert ($ord.status -eq 10) "订单初始状态：待接单"
Call POST "/farmer/orders/$($order.id)/accept?version=$($ord.version)" $farmer | Out-Null
$d2 = Call GET "/admin/orders/$($order.id)" $admin
Assert ($d2.order.status -eq 20) "接单后状态：$($d2.order.statusName)"
$conflict = $false
try { Call POST "/farmer/orders/$($order.id)/accept?version=$($ord.version)" $farmer | Out-Null }
catch { $conflict = $true; Write-Output "   并发冲突提示：$($_.Exception.Message)" }
Assert $conflict "重复接单触发乐观锁冲突（version 不匹配）"

Step "5. 农户发货 → 待质检"
Call POST "/farmer/orders/$($order.id)/ship" $farmer | Out-Null
$d3 = Call GET "/admin/orders/$($order.id)" $admin
Assert ($d3.order.status -eq 30) "发货后状态：$($d3.order.statusName)"

Step "6. 质检员录入质检结果（合格）"
$qd = Call GET "/inspector/quality/$($order.id)" $insp
Assert ($qd.batchNo -like "PC*") "批次号生成：$($qd.batchNo)"
$items = @($qd.items)
$res = Call POST "/inspector/quality" $insp @{
    orderId = $order.id; batchNo = $qd.batchNo; sampleQty = 2; result = "合格"; items = $items
    remark = "整体品质良好，农残快检合格，冷链温度正常"
}
Assert ($res.batchNo -eq $qd.batchNo) "质检报告已生成，溯源二维码：$($res.qrCode)"
Assert ($res.result -eq "合格") "质检判定：合格"

Step "7. 配送任务：开始配送 → 扫码交付"
$dvs = Call GET "/inspector/deliveries" $insp
$dv = $dvs.records | Where-Object { $_.orderId -eq $order.id } | Select-Object -First 1
Assert ($null -ne $dv) "找到配送单 $($dv.deliveryNo) 车辆 $($dv.vehicle)"
Call POST "/inspector/deliveries/$($dv.id)/start" $insp | Out-Null
$d4 = Call GET "/admin/orders/$($order.id)" $admin
Assert ($d4.order.status -eq 50) "开始配送后状态：$($d4.order.statusName)"
Call POST "/inspector/deliveries/$($dv.id)/arrive" $insp | Out-Null
$d5 = Call GET "/admin/orders/$($order.id)" $admin
Assert ($d5.order.status -eq 60) "送达后状态：$($d5.order.statusName)"

Step "8. 食堂入库确认（实收 190kg，损耗 10kg）"
$pv = Call GET "/purchaser/inbound?orderNo=$($order.orderNo)" $buyer
Assert ($pv.canInbound -eq $true) "订单可入库，批次 $($pv.batchNo)"
$inb = Call POST "/purchaser/inbound" $buyer @{
    orderId = $order.id; receiveQuantity = 190; qualityResult = "合格"; remark = "运输途中少量损耗"
}
Assert ([double]$inb.lossQuantity -eq 10) "自动核算损耗：$($inb.lossQuantity) kg，损耗率 $($inb.lossRate)%"
Assert ([double]$inb.orderAmount -gt 0) "订单金额 ¥$($inb.orderAmount)，损耗扣款 ¥$($inb.lossDeduction)，实付 ¥$($inb.actualAmount)"
$calc = [math]::Round([double]$inb.orderAmount - [double]$inb.lossDeduction, 2)
Assert ([math]::Abs($calc - [double]$inb.actualAmount) -lt 0.01) "结算校验通过：订单金额 − 损耗扣款 = 实际结算"
Assert ($inb.lossAlarm -eq $true) "损耗率 $($inb.lossRate)% 达到 5% 阈值，已扣减农户信用 -2"
$d6 = Call GET "/admin/orders/$($order.id)" $admin
Assert ($d6.order.status -eq 80) "入库后状态：$($d6.order.statusName)"

Step "9. 管理员审核结算单"
$sets = Call GET "/admin/settlements?page=1&size=50&status=待审核" $admin
$st = $sets.page.records | Where-Object { $_.orderId -eq $order.id } | Select-Object -First 1
Assert ($null -ne $st) "结算单已生成 $($st.settlementNo)，实付 ¥$($st.actualAmount)"
Call POST "/admin/settlements/audit" $admin @{ ids = @($st.id); pass = $true; remark = "核对无误，予以结算" } | Out-Null
$d7 = Call GET "/admin/orders/$($order.id)" $admin
Assert ($d7.order.status -eq 90) "审核通过后状态：$($d7.order.statusName)"

Step "10. 采购评价（5 星）"
$ev = Call POST "/purchaser/evaluations" $buyer @{
    orderId = $order.id; overallScore = 5; qualityScore = 5; freshScore = 5; deliveryScore = 4
    serviceScore = 5; packageScore = 5; tags = @("新鲜水灵", "分量足", "配送及时"); content = "整体满意，品相好，配送准时。"
}
$d8 = Call GET "/admin/orders/$($order.id)" $admin
Assert ($d8.order.status -eq 100) "评价后状态：$($d8.order.statusName)（流程闭环）"

Step "11. 批次溯源全链路"
$tr = Call GET "/admin/trace?batchNo=$($qd.batchNo)" $admin
$t = $tr.trace
Assert ($null -ne $t) "溯源记录可查：$($t.batchNo)"
Assert ($t.hash.Length -eq 64) "SHA-256 存证哈希：$($t.hash.Substring(0,16))..."
Assert ($t.nodes.Count -ge 4) "溯源时间线节点数：$($t.nodes.Count)"
Write-Output ("   节点：" + (($t.nodes | ForEach-Object { $_.title }) -join " → "))
Assert ($t.qualityResult -eq "合格") "质检结果：$($t.qualityResult)，质检员 $($t.inspector)"
Assert ([double]$t.receiveQuantity -eq 190) "实收 $($t.receiveQuantity)kg，损耗率 $($t.lossRate)%"

Step "12. 信用分明细"
$credit = Call GET "/farmer/credit" $farmer
Write-Output ("   当前信用分：" + $credit.farmer.creditScore)
Assert ($credit.records.Count -ge 1) "信用变动明细 $($credit.records.Count) 条（含损耗扣分）"

Write-Output ""
Write-Output "================ 端到端流程：通过 $ok 项，失败 $bad 项 ================"
Write-Output "本次订单：$($order.orderNo)  批次：$($qd.batchNo)"

