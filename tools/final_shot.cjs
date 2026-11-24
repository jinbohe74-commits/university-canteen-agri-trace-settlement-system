const path=require('path'),fs=require('fs')
const BASE='http://127.0.0.1:5173',API='http://127.0.0.1:8080'
const OUT=path.join(__dirname,'..','docs','roleplay_2026-11-10','after')
const G=path.join(process.env.APPDATA,'npm','node_modules');module.paths.push(G)
const {chromium}=require(path.join(G,'playwright'));const sleep=ms=>new Promise(r=>setTimeout(r,ms))
;(async()=>{const b=await chromium.launch({channel:'msedge'})
const res=await fetch(API+'/api/auth/login',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({username:'inspector01',password:'123456',expectedRole:'INSPECTOR'})})
const d=(await res.json()).data
const c=await b.newContext({viewport:{width:1440,height:900}})
await c.addInitScript(([t,u,rn,mn])=>{sessionStorage.setItem('ct_token',t);sessionStorage.setItem('ct_user',u);sessionStorage.setItem('ct_role_name',rn);sessionStorage.setItem('ct_menus',mn)},[d.token,JSON.stringify(d.user),d.roleName||'',JSON.stringify(d.menus||[])])
const p=await c.newPage()
await p.goto(BASE+'/inspector/tasks',{waitUntil:'domcontentloaded'});await sleep(2600)
await p.screenshot({path:path.join(OUT,'0-质检任务-入口可见.png')})
await b.close();console.log('ok')})()
