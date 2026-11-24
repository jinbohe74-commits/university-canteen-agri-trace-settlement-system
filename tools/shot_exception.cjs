const path=require('path'),fs=require('fs')
const ROOT=path.resolve(__dirname,'..'),BASE='http://127.0.0.1:5173',API='http://127.0.0.1:8080'
const OUT=path.join(ROOT,'docs','reform_2026-11-11','shots11')
const G=path.join(process.env.APPDATA,'npm','node_modules');module.paths.push(G)
const {chromium}=require(path.join(G,'playwright'))
const sleep=ms=>new Promise(r=>setTimeout(r,ms))
;(async()=>{
  const r=await fetch(API+'/api/auth/login',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({username:'inspector01',password:'123456',expectedRole:'INSPECTOR'})})
  const d=(await r.json()).data
  const b=await chromium.launch({channel:'msedge'})
  const c=await b.newContext({viewport:{width:1440,height:900}})
  await c.addInitScript(([t,u,rn,mn])=>{sessionStorage.setItem('ct_token',t);sessionStorage.setItem('ct_user',u);sessionStorage.setItem('ct_role_name',rn);sessionStorage.setItem('ct_menus',mn)},[d.token,JSON.stringify(d.user),d.roleName||'',JSON.stringify(d.menus||[])])
  const p=await c.newPage()
  await p.goto(BASE+'/inspector/exception',{waitUntil:'domcontentloaded'})
  await sleep(2500)
  await p.screenshot({path:path.join(OUT,'INSPECTOR-异常上报-真实上传.png')})
  console.log('shot ok')
  await b.close()
})()
