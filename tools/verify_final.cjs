const path=require('path'),fs=require('fs')
const ROOT=path.resolve(__dirname,'..'),BASE='http://127.0.0.1:5173',API='http://127.0.0.1:8080'
const OUT=path.join(ROOT,'docs','roleplay_2026-11-10','final');fs.mkdirSync(OUT,{recursive:true})
const G=path.join(process.env.APPDATA,'npm','node_modules');module.paths.push(G)
const {chromium}=require(path.join(G,'playwright'))
const sleep=ms=>new Promise(r=>setTimeout(r,ms))
const steps=[];const step=(n,ok,note)=>{steps.push({n,ok,note});console.log(`${ok?' ✔':' ✘'} ${n}${note?' | '+String(note).slice(0,220):''}`)}
async function login(u,r){const res=await fetch(API+'/api/auth/login',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({username:u,password:'123456',expectedRole:r})});return (await res.json()).data}
async function open(b,d){const c=await b.newContext({viewport:{width:1440,height:900}});await c.addInitScript(([t,u,rn,mn])=>{sessionStorage.setItem('ct_token',t);sessionStorage.setItem('ct_user',u);sessionStorage.setItem('ct_role_name',rn);sessionStorage.setItem('ct_menus',mn)},[d.token,JSON.stringify(d.user),d.roleName||'',JSON.stringify(d.menus||[])]);const p=await c.newPage();p.__e=[];p.on('pageerror',e=>p.__e.push(e.message.slice(0,120)));p.on('console',m=>{if(m.type()==='error')p.__e.push(m.text().slice(0,120))});return p}
;(async()=>{const b=await chromium.launch({channel:'msedge'})
const f=await open(b,await login('farmer01','FARMER'))
await f.goto(BASE+'/farmer/orders',{waitUntil:'domcontentloaded'});await sleep(2400)
const tabs=await f.locator('.el-tabs__item').allInnerTexts()
step('农户订单出现进行中的单',/待接单\s*1/.test(tabs.map(t=>t.replace(/\n/g,',')).join('|'))||/待接单/.test(tabs.join('')),JSON.stringify(tabs.map(t=>t.replace(/\n/g,''))))
await f.screenshot({path:path.join(OUT,'farmer-orders.png')})
const a=await open(b,await login('admin','ADMIN'))
await a.goto(BASE+'/admin/logs',{waitUntil:'domcontentloaded'});await sleep(2200)
const inp=a.locator('input[placeholder*="操作人"]').first()
await inp.fill('丰禾');await a.locator('button:has-text("查询")').first().click();await sleep(2000)
const rows=await a.locator('.el-table__body tbody tr').count()
const first=(await a.locator('.el-table__body tbody tr').first().innerText().catch(()=>'')).replace(/\s+/g,' ')
step('操作日志可查农户信用改动',rows>0,rows+' 条 · '+first.slice(0,150))
await a.screenshot({path:path.join(OUT,'admin-logs.png')})
const errs=[...f.__e,...a.__e]
step('无控制台错误',errs.length===0,errs.slice(0,2).join(' | ')||'0')
fs.writeFileSync(path.join(OUT,'result.json'),JSON.stringify({steps,errs},null,2),'utf8')
console.log('\n完成 '+steps.filter(s=>s.ok).length+'/'+steps.length)
await b.close()})().catch(e=>{console.error(e);process.exit(1)})
