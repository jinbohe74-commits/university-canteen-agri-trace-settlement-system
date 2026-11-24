const path=require('path');const G=path.join(process.env.APPDATA,'npm','node_modules');module.paths.push(G)
const { chromium } = require(path.join(G,'playwright'))
const API='http://127.0.0.1:8080'
async function login(u){const r=await fetch(API+'/api/auth/login',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({username:u,password:'123456'})});return (await r.json()).data}
;(async()=>{
 const b=await chromium.launch({channel:'msedge'})
 const out=[]
 // 1) admin 订单：状态下拉能筛
 {
  const d=await login('admin'); const c=await b.newContext({viewport:{width:1440,height:900}})
  await c.addInitScript(([t,u,rn,mn])=>{sessionStorage.setItem('ct_token',t);sessionStorage.setItem('ct_user',u);sessionStorage.setItem('ct_role_name',rn);sessionStorage.setItem('ct_menus',mn)},[d.token,JSON.stringify(d.user),d.roleName||'',JSON.stringify(d.menus||[])])
  const p=await c.newPage(); const reqs=[]
  p.on('request',r=>{if(r.url().includes('/api/'))reqs.push(r.url())})
  await p.goto('http://127.0.0.1:5173/admin/orders',{waitUntil:'domcontentloaded'}); await p.waitForTimeout(2000)
  const before=await p.locator('.el-table__body tr').count()
  await p.locator('.filter-bar .el-select').nth(1).click(); await p.waitForTimeout(400)
  await p.locator('.el-select-dropdown__item:visible').filter({hasText:'已完成'}).first().click(); await p.waitForTimeout(1500)
  const after=await p.locator('.el-table__body tr').count()
  const hit=reqs.filter(u=>u.includes('status=100')).length
  out.push(['admin订单状态筛选', after>0 && after!==before || hit>0, {before,after,status100:hit}])
  await c.close()
 }
 // 2) 农户订单：标签切换能筛
 {
  const d=await login('farmer24'); const c=await b.newContext({viewport:{width:1440,height:900}})
  await c.addInitScript(([t,u,rn,mn])=>{sessionStorage.setItem('ct_token',t);sessionStorage.setItem('ct_user',u);sessionStorage.setItem('ct_role_name',rn);sessionStorage.setItem('ct_menus',mn)},[d.token,JSON.stringify(d.user),d.roleName||'',JSON.stringify(d.menus||[])])
  const p=await c.newPage(); const reqs=[]
  p.on('request',r=>{if(r.url().includes('/api/farmer/orders'))reqs.push(r.url())})
  await p.goto('http://127.0.0.1:5173/farmer/orders',{waitUntil:'domcontentloaded'}); await p.waitForTimeout(2000)
  const tabs=await p.locator('.el-tabs__item').count()
  await p.locator('.el-tabs__item', {hasText:'已取消'}).first().click(); await p.waitForTimeout(1500)
  const rows=await p.locator('.el-table__body tr').count()
  const statuses=await p.locator('.el-table__body tr td:nth-child(6)').allInnerTexts()
  out.push(['农户订单已取消标签', rows>0 && statuses.every(s=>s.includes('已取消')), {tabs,rows,sample:statuses.slice(0,2)}])
  await c.close()
 }
 // 3) 农户列表：待审核卡片可点
 {
  const d=await login('admin'); const c=await b.newContext({viewport:{width:1440,height:900}})
  await c.addInitScript(([t,u,rn,mn])=>{sessionStorage.setItem('ct_token',t);sessionStorage.setItem('ct_user',u);sessionStorage.setItem('ct_role_name',rn);sessionStorage.setItem('ct_menus',mn)},[d.token,JSON.stringify(d.user),d.roleName||'',JSON.stringify(d.menus||[])])
  const p=await c.newPage(); const reqs=[]
  p.on('request',r=>{if(r.url().includes('/api/admin/farmers'))reqs.push(r.url())})
  await p.goto('http://127.0.0.1:5173/admin/farmers',{waitUntil:'domcontentloaded'}); await p.waitForTimeout(2000)
  // 2026-11-11 起所有 StatCard 都带 stat-click（统一钻取），必须按标题定位「待审核」
  await p.locator('.stat-card', { hasText: '待审核' }).first().click(); await p.waitForTimeout(1500)
  const hit=reqs.filter(u=>decodeURIComponent(u).includes('待审核')).length
  out.push(['农户待审核卡片可点筛', hit>0, {hit}])
  await c.close()
 }
 // 4) 产品页：品类标签筛
 {
  const d=await login('admin'); const c=await b.newContext({viewport:{width:1440,height:900}})
  await c.addInitScript(([t,u,rn,mn])=>{sessionStorage.setItem('ct_token',t);sessionStorage.setItem('ct_user',u);sessionStorage.setItem('ct_role_name',rn);sessionStorage.setItem('ct_menus',mn)},[d.token,JSON.stringify(d.user),d.roleName||'',JSON.stringify(d.menus||[])])
  const p=await c.newPage(); const reqs=[]
  p.on('request',r=>{if(r.url().includes('/api/admin/products'))reqs.push(r.url())})
  await p.goto('http://127.0.0.1:5173/admin/products',{waitUntil:'domcontentloaded'}); await p.waitForTimeout(2000)
  const n=await p.locator('.tab-btn').count()
  await p.locator('.tab-btn').nth(1).click(); await p.waitForTimeout(1400)
  const rows=await p.locator('.el-table__body tr').count()
  out.push(['产品品类标签可筛', n>=3 && rows>0, {tabs:n,rows}])
  await c.close()
 }
 await b.close()
 console.log('\n=== 筛选入口功能校验 ===')
 out.forEach(([k,ok,d])=>console.log((ok?'PASS':'FAIL').padEnd(5),k,JSON.stringify(d)))
 console.log('通过', out.filter(x=>x[1]).length, '/', out.length)
})().catch(e=>console.error(e.message))
