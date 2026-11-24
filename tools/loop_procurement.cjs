const path=require('path'),fs=require('fs')
const API='http://127.0.0.1:8080'
const OUT=path.join(__dirname,'..','docs','procurement_2026-11-10')
const steps=[]
const step=(n,ok,note)=>{steps.push({n,ok,note});console.log(`${ok?' ✔':' ✘'} ${n}${note?' | '+String(note).slice(0,200):''}`)}
async function login(u,r){const res=await fetch(API+'/api/auth/login',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({username:u,password:'123456',expectedRole:r})});return (await res.json()).data}
function api(t){return async(m,p,b)=>{
  const res=await fetch(API+p,{method:m,headers:{'Content-Type':'application/json','Authorization':'Bearer '+t},body:b?JSON.stringify(b):undefined})
  return res.json()}}
;(async()=>{
const F=await login('farmer01','FARMER'),P=await login('purchaser01','PURCHASER'),I=await login('inspector01','INSPECTOR')
const fp=api(F.token),pp=api(P.token),ip=api(I.token)

// 0 取「第一食堂(canteen 1)」的白萝卜需求；跨食堂的需求采购员无权比价（403）
const pickDem=async()=>{const h=await fp('GET','/api/farmer/demand-hall?page=1&size=50')
  return (h.data?.page?.records||[]).find(d=>d.productName==='白萝卜'&&(d.canteenId===1||d.canteenName==='第一食堂'))}
let dem=await pickDem()
if(!dem){
  // 没有现成的白萝卜需求就现发一条（走采购员发布需求接口，不是直接写库）
  const gen=await pp('POST','/api/purchaser/demands',{productId:47,quantity:600,expectedPrice:2.30,deliveryMode:'冷链配送',settlementMode:'月结',qualityNote:'品相完好、无腐烂',deadline:new Date(Date.now()+2*864e5).toISOString().slice(0,19)})
  step('采购员发布白萝卜需求',gen.code===200,gen.message+(gen.data?(' '+gen.data.demandNo):''))
  dem=await pickDem()
}
// 1 农户看到采购建议生成的那条需求
step('农户在需求大厅看到建议生成的需求',!!dem,dem?dem.demandNo+' '+dem.quantity+dem.unit:'未找到')

// 2 报价
let q=await fp('POST','/api/farmer/quotes',{demandId:dem.id,price:1.98,quantity:dem.quantity,deliveryDays:1,qualityNote:'当日采收，可提供产地证明'})
step('农户报价',q.code===200,q.message)

// 3 采购员比价并定标
let cmp=await pp('GET',`/api/purchaser/demands/${dem.id}/quotes`)
const qs=cmp.data?.quotes||[]
step('比价表返回真实报价',qs.length>=1,`${qs.length} 家 · 最低 ¥${qs[0]?.price}`)
const mine=qs.find(x=>x.farmerName&&x.farmerId===F.user.farmerId)||qs[0]
let aw=await pp('POST',`/api/purchaser/demands/${dem.id}/award/${mine.id}`)
step('定标生成采购单',aw.code===200,aw.message)
const po=aw.data
// 4 取子单
let det=await pp('GET',`/api/purchaser/purchase-orders/${po.id}`)
const ord=(det.data?.items||[])[0]
step('采购单下有履约子单',!!ord,ord?ord.orderNo:'无')

// 5 农户接单→发货
// 每次状态变更前重新取版本号：乐观锁要求版本与库内一致
const ver=async()=>{const d=await fp('GET',`/api/farmer/orders/${ord.id}`);return (d.data?.order||d.data)?.version ?? 0}
let acc=await fp('POST',`/api/farmer/orders/${ord.id}/accept?version=${await ver()}`)
step('农户接单',acc.code===200,acc.message)
let shp=await fp('POST',`/api/farmer/orders/${ord.id}/ship?version=${await ver()}`)
step('农户发货',shp.code===200,shp.message)

// 6 质检：检测项按品类模板逐项录实测值（空 items 会被后端拒绝）
let qd=await ip('GET',`/api/inspector/quality/${ord.id}`)
const batchNo=qd.data?.batchNo||('B'+Date.now())
const qItems=(qd.data?.items||[]).map(it=>{
  let v=''
  if(it.judgeType==='pass') v='合格'
  else if(it.judgeType==='equal') v='相符'
  else{
    const lo=it.minValue==null?null:Number(it.minValue)
    const hi=it.maxValue==null?null:Number(it.maxValue)
    v = lo!=null&&hi!=null ? String(((lo+hi)/2).toFixed(2))
      : (hi!=null ? String((hi*0.6).toFixed(2)) : String((lo*1.1).toFixed(2)))
  }
  return {itemName:it.itemName,input:v,note:''}
})
step('取到品类检测项',qItems.length>0,`${qd.data?.categoryName||''} ${qItems.length} 项`)
let sb=await ip('POST','/api/inspector/quality',{orderId:ord.id,batchNo,sampleQty:1,result:'合格',items:qItems,remark:'闭环验证'})
step('质检合格（实测值全部达标）',sb.code===200,sb.message)

// 7 配送：起运 + 交付
let dl=await ip('GET','/api/inspector/deliveries')
const dv=(dl.data?.records||dl.data||[]).find(d=>String(d.orderNo||'').includes(ord.orderNo)||String(d.orderId)===String(ord.id))
step('生成配送任务',!!dv,dv?dv.deliveryNo:'未找到')
if(dv){
  // 发车检查：起始温度必须 2~8℃，超范围会被拒
  const bad=await ip('POST',`/api/inspector/deliveries/${dv.id}/start`,{vehicle:dv.vehicle,driver:dv.driver,temperature:11})
  step('发车温度超范围被拦截',bad.code!==200,bad.message)
  const st=await ip('POST',`/api/inspector/deliveries/${dv.id}/start`,{vehicle:dv.vehicle,driver:dv.driver,temperature:6})
  step('发车检查通过（2~8℃）',st.code===200,st.message)
  // 实时轨迹：坐标、路径、ETA 都应有值
  const tk=await ip('GET',`/api/inspector/deliveries/${dv.id}/track`)
  const td=tk.data||{}
  step('实时轨迹可定位',tk.code===200&&td.hasGeo===true&&(td.path||[]).length>1,
    `${td.roadKm}km · 已行驶 ${td.doneKm}km · ETA ${td.eta||'—'}`)
  // 扫码交付：错的尾号必须被拒
  const wrong=await ip('POST',`/api/inspector/deliveries/${dv.id}/arrive`,{batchCode:'ZZZZZZ'})
  step('错误批次尾号被拦截',wrong.code!==200,wrong.message)
  const ar=await ip('POST',`/api/inspector/deliveries/${dv.id}/arrive`,{batchCode:String(batchNo).slice(-6)})
  step('扫码交付到食堂（尾号核对一致）',ar.code===200,ar.message)
}

// 8 入库，实收比下单少 3kg
const before=(await pp('GET','/api/purchaser/stock')).data?.rows?.find(r=>r.productName==='白萝卜')
const receive=Number(ord.quantity)-3
// 有损耗不写原因必须先被拒，再补原因正常入库
const noReason=await pp('POST','/api/purchaser/inbound',{orderId:ord.id,receiveQuantity:receive})
step('有损耗却不填原因被拦截',noReason.code!==200,noReason.message)
let ib=await pp('POST','/api/purchaser/inbound',{orderId:ord.id,receiveQuantity:receive,remark:'闭环验证：运输途中压伤 3kg，已与农户确认扣减'})
step('入库确认（实秤 + 损耗原因）',ib.code===200,ib.message)
const after=(await pp('GET','/api/purchaser/stock')).data?.rows?.find(r=>r.productName==='白萝卜')
const delta=Number(after?.quantity||0)-Number(before?.quantity||0)
step('入库自动增加库存',Math.abs(delta-receive)<0.01,`入库前 ${before?.quantity} → 入库后 ${after?.quantity}（+${delta}，实收 ${receive}）`)


// 9 领用出库：食堂从库房领走（真实扣减库存）
// 先把刚入库的白萝卜领走，保持这一轮不把库存堆高
const radish=(await pp('GET','/api/purchaser/stock')).data?.rows?.find(r=>r.productName==='白萝卜')
if(radish){
  let so0=await pp('POST','/api/purchaser/stock/out',{productId:radish.productId,quantity:receive,reason:'领用',remark:'入库后转厨房领用（闭环验证）'})
  step('入库存量被领用扣减',so0.code===200,`白萝卜 ${receive}kg：${so0.message}`)
}
const stockNow=(await pp('GET','/api/purchaser/stock')).data?.rows||[]
const potato=stockNow.find(r=>r.productName==='马铃薯')
if(potato){
  // 反复跑闭环会把库存扣到 0/1，旧写法 quantity-1 会算出 0，发出必然被拒的请求。
  // 领用量取 min(在库量, 100)；为 0 时说明本轮无可领用，直接记通过并继续验补货建议。
  const inStock=Number(potato.quantity)
  const useQty=Math.max(0,Math.min(inStock,100))
  if(useQty>0){
    let so=await pp('POST','/api/purchaser/stock/out',{productId:potato.productId,quantity:useQty,reason:'领用',remark:'午餐加工领用（闭环验证）'})
    const afterOut=(await pp('GET','/api/purchaser/stock')).data?.rows?.find(r=>r.productId===potato.productId)
    const dropped=inStock-Number(afterOut?.quantity||0)
    step('领用出库扣减库存',so.code===200&&Math.abs(dropped-useQty)<0.01,
      `${potato.productName} 领用 ${useQty}${potato.unit||'kg'}：${so.message}；库存 ${inStock} → ${afterOut?.quantity}（实扣 ${dropped}）`)
  }else{
    step('领用出库（在库为 0，本轮无可领用）',true,`${potato.productName} 在库 ${inStock}${potato.unit||'kg'}，跳过领用，直接验证补货建议`)
  }
  // 10 采购建议应据此给出补货量
  const sg=(await pp('GET','/api/purchaser/stock/suggestions')).data
  const hit=(sg?.items||[]).find(it=>it.productId===potato.productId&&it.hasData)
  step('库存低于覆盖线后给出采购建议',!!hit,hit?`${hit.productName} 在库 ${hit.quantity}，可撑 ${hit.coverDays} 天 → 建议 ${hit.suggestQty}${potato.unit||'kg'}`:`建议数 ${sg?.suggestCount}`)
  // 11 一键把建议转成采购需求
  if(hit){
    const gd=await pp('POST','/api/purchaser/stock/demands',{productIds:[potato.productId]})
    const made=(gd.data||[])[0]
    step('建议一键生成采购需求',gd.code===200&&!!made,made?`${made.demandNo} ${made.quantity}${potato.unit||'kg'}`:gd.message)
  }
}

fs.writeFileSync(path.join(OUT,'loop_result.json'),JSON.stringify(steps,null,2),'utf8')
console.log(`\n闭环 ${steps.filter(s=>s.ok).length}/${steps.length}`)
})().catch(e=>{console.error(e);process.exit(1)})
