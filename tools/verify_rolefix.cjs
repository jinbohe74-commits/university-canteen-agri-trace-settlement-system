/* 三项修复的目视 + 坐标复核 */
const path = require('path'); const fs = require('fs')
const ROOT = path.resolve(__dirname, '..'); const BASE = 'http://127.0.0.1:5173'; const API = 'http://127.0.0.1:8080'
const OUT = path.join(ROOT, 'docs', 'roleplay_2026-11-10', 'shots4'); fs.mkdirSync(OUT, { recursive: true })
const G = path.join(process.env.APPDATA, 'npm', 'node_modules'); module.paths.push(G)
const { chromium } = require(path.join(G, 'playwright'))
const sleep = ms => new Promise(r => setTimeout(r, ms))
async function login(u, r) {
  const res = await fetch(API + '/api/auth/login', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ username: u, password: '123456', expectedRole: r }) })
  return (await res.json()).data
}
async function open(b, d) {
  const ctx = await b.newContext({ viewport: { width: 1440, height: 900 } })
  await ctx.addInitScript(([t, u, rn, mn]) => { sessionStorage.setItem('ct_token', t); sessionStorage.setItem('ct_user', u); sessionStorage.setItem('ct_role_name', rn); sessionStorage.setItem('ct_menus', mn) }, [d.token, JSON.stringify(d.user), d.roleName || '', JSON.stringify(d.menus || [])])
  return await ctx.newPage()
}
;(async () => {
  const b = await chromium.launch({ channel: 'msedge' })
  const q = await open(b, await login('inspector01', 'INSPECTOR'))
  await q.goto(BASE + '/inspector/tasks', { waitUntil: 'domcontentloaded' }); await sleep(2200)
  await q.screenshot({ path: path.join(OUT, '1-tasks.png') })
  const links = await q.locator('.el-table__body tbody tr').first().locator('.link').allInnerTexts().catch(() => [])
  console.log('任务首行可见操作:', JSON.stringify(links))
  const box = await q.locator('.el-table__body tbody tr').first().locator('.link-strong').boundingBox().catch(() => null)
  console.log('质检录入链接坐标:', box ? `x=${Math.round(box.x)} y=${Math.round(box.y)}` : '不可见')

  await q.goto(BASE + '/inspector/quality', { waitUntil: 'domcontentloaded' }); await sleep(2600)
  await q.screenshot({ path: path.join(OUT, '2-quality.png') })
  const sb = await q.locator('button:has-text("提交质检")').boundingBox().catch(() => null)
  console.log('提交按钮坐标:', sb ? `y=${Math.round(sb.y)}（视口900，<900 即首屏可见）` : '找不到')
  const qsel = await q.locator('.queue-select').count()
  console.log('待检队列下拉:', qsel ? '存在' : '无（可能只有 1 单）')
  const orderNo = await q.evaluate(() => (document.body.innerText.match(/订单\s*(DD\d+)/) || [])[1] || '')
  console.log('当前载入订单:', orderNo)

  const buyer = await open(b, await login('purchaser01', 'PURCHASER'))
  await buyer.goto(BASE + '/purchaser/inbound', { waitUntil: 'domcontentloaded' }); await sleep(2400)
  await buyer.screenshot({ path: path.join(OUT, '3-inbound.png') })
  const bar = await buyer.locator('.block-bar').first().innerText().catch(() => '')
  console.log('入库原因条:', bar ? bar.replace(/\s+/g, ' ').slice(0, 120) : '未出现（当前单可入库）')

  await b.close()
})().catch(e => { console.error(e); process.exit(1) })
