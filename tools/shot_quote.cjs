const path=require('path');const BASE='http://127.0.0.1:5173',API='http://127.0.0.1:8080'
const OUT=path.join(__dirname,'..','docs','procurement_2026-11-10','shots')
const G=path.join(process.env.APPDATA,'npm','node_modules');module.paths.push(G)
const {chromium}=require(path.join(G,'playwright'));const sleep=ms=>new Promise(r=>setTimeout(r,ms))
async function login(u,r){const res=await fetch(API+'/api/auth/login',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({username:u,password:'123456',expectedRole:r})});return (await res.json()).data}
async function open(b,d){const c=await b.newContext({viewport:{width:1440,height:900}});await c.addInitScript(([t,u,rn,mn])=>{sessionStorage.setItem('ct_token',t);sessionStorage.setItem('ct_user',u);sessionStorage.setItem('ct_role_name',rn);sessionStorage.setItem('ct_menus',mn)},[d.token,JSON.stringify(d.user),d.roleName||'',JSON.stringify(d.menus||[])]);const p=await c.newPage();p.__e=[];p.on('pageerror',e=>p.__e.push(e.message.slice(0,140)));return p}
;(async()=>{const b=await chromium.launch({channel:'msedge'})
const f=await open(b,await login('farmer01','FARMER'))
await f.goto(BASE+'/farmer/demands',{waitUntil:'domcontentloaded'});await sleep(2800)
console.log('农户需求大厅行数',await f.locator('.el-table__body tbody tr').count())
await f.screenshot({path:path.join(OUT,'4-农户-需求大厅.png')})
// 打开报价弹窗
const link=f.locator('.el-table__body tbody tr').first().locator('.link-strong')
if(await link.count()){await link.click();await sleep(1200);await f.screenshot({path:path.join(OUT,'5-农户-提交报价.png')});await f.keyboard.press('Escape');await sleep(500)}
// 切我的报价
await f.locator('.el-radio-button__inner',{hasText:'我的报价'}).click();await sleep(900)
console.log('我的报价行数',await f.locator('.el-table__body tbody tr').count())
await f.screenshot({path:path.join(OUT,'6-农户-我的报价.png')})
const pd=await login('purchaser01','PURCHASER')
const p=await open(b,pd)
await p.goto(BASE+'/purchaser/demands',{waitUntil:'domcontentloaded'});await sleep(2600)
// 打开一条「已有真实报价」的需求详情（报价比价），否则比价区只会是空态
const lr=await fetch(API+'/api/purchaser/demands?page=1&size=50',{headers:{Authorization:'Bearer '+pd.token}})
const ld=(await lr.json()).data
const recs=ld?.records||ld?.page?.records||ld?.list||[]
const withQ=(recs||[]).find(d=>Number(d.quoteCount||d.realQuoteCount||0)>0&&d.status==='处理中')
console.log('有报价的需求',withQ?withQ.demandNo:'无')
const rows=p.locator('.el-table__body tbody tr')
const cand=withQ?rows.filter({hasText:withQ.demandNo}).first():rows.first()
const det=p.locator('.link-strong')
console.log('详情入口数',await det.count(),'目标行',await cand.count())
await ((await cand.count())?cand.locator('.link-strong').first():det.first()).click()
await sleep(1800);await p.screenshot({path:path.join(OUT,'7-采购员-报价比价.png')})
console.log('errors',f.__e.slice(0,2),p.__e.slice(0,2))
await b.close()})().catch(e=>{console.error(e);process.exit(1)})
