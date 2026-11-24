const path=require('path');const BASE='http://127.0.0.1:5173',API='http://127.0.0.1:8080'
const G=path.join(process.env.APPDATA,'npm','node_modules');module.paths.push(G)
const {chromium}=require(path.join(G,'playwright'));const sleep=ms=>new Promise(r=>setTimeout(r,ms))
;(async()=>{const b=await chromium.launch({channel:'msedge'})
const res=await fetch(API+'/api/auth/login',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({username:'inspector01',password:'123456',expectedRole:'INSPECTOR'})})
const d=(await res.json()).data
const c=await b.newContext({viewport:{width:1440,height:900}})
await c.addInitScript(([t,u,rn,mn])=>{sessionStorage.setItem('ct_token',t);sessionStorage.setItem('ct_user',u);sessionStorage.setItem('ct_role_name',rn);sessionStorage.setItem('ct_menus',mn)},[d.token,JSON.stringify(d.user),d.roleName||'',JSON.stringify(d.menus||[])])
const p=await c.newPage()
await p.goto(BASE+'/inspector/delivery',{waitUntil:'domcontentloaded'});await sleep(2800)
const rows=p.locator('.el-table__body tbody tr')
const n=await rows.count()
for(let i=0;i<n;i++){const t=(await rows.nth(i).innerText()).replace(/\s+/g,' ')
 if(/待发车|配送中/.test(t)){console.log(i,t.slice(0,80),'|',JSON.stringify(await rows.nth(i).locator('.link,.link-strong').allInnerTexts()))}}
await p.locator('.el-table').first().scrollIntoViewIfNeeded();await sleep(600)
await p.screenshot({path:'docs/roleplay_2026-11-10/after/2c-配送表-待发车行.png'})
await b.close()})()
