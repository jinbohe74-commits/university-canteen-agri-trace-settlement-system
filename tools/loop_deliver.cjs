const path=require('path'),fs=require('fs')
const ROOT=path.resolve(__dirname,'..'),BASE='http://127.0.0.1:5173',API='http://127.0.0.1:8080'
const OUT=path.join(ROOT,'docs','roleplay_2026-11-10','shots5')
const G=path.join(process.env.APPDATA,'npm','node_modules');module.paths.push(G)
const {chromium}=require(path.join(G,'playwright'))
const sleep=ms=>new Promise(r=>setTimeout(r,ms))
const steps=[];const step=(n,ok,note)=>{steps.push({n,ok,note});console.log(`${ok?' ✔':' ✘'} ${n}${note?' | '+String(note).slice(0,200):''}`)}
async function login(u,r){const res=await fetch(API+'/api/auth/login',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({username:u,password:'123456',expectedRole:r})});return (await res.json()).data}
async function open(b,d){const c=await b.newContext({viewport:{width:1440,height:900}});await c.addInitScript(([t,u,rn,mn])=>{sessionStorage.setItem('ct_token',t);sessionStorage.setItem('ct_user',u);sessionStorage.setItem('ct_role_name',rn);sessionStorage.setItem('ct_menus',mn)},[d.token,JSON.stringify(d.user),d.roleName||'',JSON.stringify(d.menus||[])]);const p=await c.newPage();p.__e=[];p.on('pageerror',e=>p.__e.push(e.message.slice(0,120)));return p}
const shot=(p,n)=>p.screenshot({path:path.join(OUT,n+'.png')})
async function toast(p,ms=4000){try{const t=p.locator('.el-message').first();await t.waitFor({state:'visible',timeout:ms});return (await t.innerText()).replace(/\s+/g,' ').trim()}catch{return ''}}
const ORDER='DD202611100330'
;(async()=>{const b=await chromium.launch({channel:'msedge'})
const q=await open(b,await login('inspector01','INSPECTOR'))
const p=await open(b,await login('purchaser01','PURCHASER'))
const f=await open(b,await login('farmer01','FARMER'))
await q.goto(BASE+'/inspector/delivery',{waitUntil:'domcontentloaded'});await sleep(2600)
await shot(q,'F1-delivery')
const row=q.locator('.el-table__body tbody tr').filter({hasText:'PS20261110406'}).first()
step('配送列表能定位到这一趟',!!(await row.count()),'PS20261110406')
const startLink=row.locator('.link-strong')
if(await startLink.count()){await startLink.first().click();await sleep(1800);step('按行发起配送',!!(await toast(q)),await toast(q))}
else step('按行发起配送',false,'行内没有可点的推进操作')
await shot(q,'F2-started')
await sleep(800)
const row2=q.locator('.el-table__body tbody tr').filter({hasText:'PS20261110406'}).first()
const arriveLink=row2.locator('.link-strong')
if(await arriveLink.count()){await arriveLink.first().click();await sleep(1800);step('按行扫码交付',!!(await toast(q)),await toast(q))}
else step('按行扫码交付',false,'行内没有交付操作')
await shot(q,'F3-arrived')
await sleep(1200)
await p.goto(BASE+'/purchaser/inbound?orderNo='+ORDER,{waitUntil:'domcontentloaded'});await sleep(2600)
const num=p.locator('.el-input-number input').first()
const dis=await num.isDisabled()
step('该单已可入库',!dis,dis?'仍被禁用':'可编辑')
if(!dis){
 await num.fill('130');await sleep(900)
 const t=await p.evaluate(()=>document.body.innerText.replace(/\s+/g,' '))
 const loss=t.match(/损耗率\s*([\d.]+)%/)
 step('实收130→自动算损耗',!!loss,'损耗率 '+(loss?loss[1]:'?')+'%')
 await p.locator('button:has-text("确认入库")').click();await sleep(1600)
 const bx=p.locator('.el-message-box');if(await bx.count()){await bx.locator('button').filter({hasText:/确定|确认/}).last().click();await sleep(2400)}
 step('确认入库',!!(await toast(p)),await toast(p))
}
await shot(p,'F4-inbound')
await f.goto(BASE+'/farmer/settlement',{waitUntil:'domcontentloaded'});await sleep(2400)
const sb=await f.evaluate(()=>document.body.innerText.replace(/\s+/g,' '))
step('农户结算出现该单',sb.includes(ORDER),sb.includes(ORDER)?'找到':'没找到')
await shot(f,'F5-settlement')
await p.goto(BASE+'/purchaser/evaluation',{waitUntil:'domcontentloaded'});await sleep(2400)
const eb=await p.evaluate(()=>document.body.innerText.replace(/\s+/g,' '))
step('评价页出现该单',eb.includes(ORDER),eb.includes(ORDER)?'找到':'没找到')
await shot(p,'F6-eval')
fs.writeFileSync(path.join(OUT,'result3.json'),JSON.stringify(steps,null,2),'utf8')
console.log('\n完成 '+steps.filter(s=>s.ok).length+'/'+steps.length)
await b.close()})().catch(e=>{console.error(e);process.exit(1)})
