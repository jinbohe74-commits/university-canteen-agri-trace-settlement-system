/* 第十一轮改动截图（2026-11-11）：确认视觉上没有新的排版问题 */
const path = require('path')
const fs = require('fs')
const ROOT = path.resolve(__dirname, '..')
const BASE = 'http://127.0.0.1:5173'
const API = 'http://127.0.0.1:8080'
const OUT = path.join(ROOT, 'docs', 'reform_2026-11-11', 'shots11')
fs.mkdirSync(OUT, { recursive: true })
const GLOBAL = path.join(process.env.APPDATA, 'npm', 'node_modules')
module.paths.push(GLOBAL)
const { chromium } = require(path.join(GLOBAL, 'playwright'))
const sleep = ms => new Promise(r => setTimeout(r, ms))

async function login(u, role) {
  const r = await fetch(API + '/api/auth/login', {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username: u, password: '123456', expectedRole: role })
  })
  return (await r.json()).data
}
async function ctxFor(browser, d) {
  const c = await browser.newContext({ viewport: { width: 1440, height: 900 } })
  await c.addInitScript(([t, u, rn, mn]) => {
    sessionStorage.setItem('ct_token', t)
    sessionStorage.setItem('ct_user', u)
    sessionStorage.setItem('ct_role_name', rn)
    sessionStorage.setItem('ct_menus', mn)
  }, [d.token, JSON.stringify(d.user), d.roleName || '', JSON.stringify(d.menus || [])])
  return c
}

;(async () => {
  const browser = await chromium.launch({ channel: 'msedge' })
  const admin = await login('admin', 'ADMIN')
  const ac = await ctxFor(browser, admin)
  const ap = await ac.newPage()

  await ap.goto(BASE + '/admin/settlements', { waitUntil: 'domcontentloaded' })
  await sleep(2600)
  await ap.screenshot({ path: path.join(OUT, 'ADMIN-结算审核-默认待审核.png') })
  const st = await ap.evaluate(() => {
    const sel = document.querySelector('.filter-bar .el-select .el-select__placeholder, .filter-bar .el-select input')
    return { status: sel ? (sel.textContent || sel.value || '').trim() : '', rows: document.querySelectorAll('.el-table__body tr').length }
  })
  console.log('结算页默认状态:', JSON.stringify(st))

  await ap.goto(BASE + '/admin/warnings', { waitUntil: 'domcontentloaded' })
  await sleep(2600)
  await ap.screenshot({ path: path.join(OUT, 'ADMIN-预警列表-未处置优先.png') })
  const wst = await ap.evaluate(() => Array.from(document.querySelectorAll('.el-table__body tr')).slice(0, 5)
    .map(tr => (tr.querySelector('.el-tag')?.textContent || '').trim()))
  console.log('预警前 5 行状态:', JSON.stringify(wst))

  const pur = await login('purchaser01', 'PURCHASER')
  const pc = await ctxFor(browser, pur)
  const pp = await pc.newPage()
  await pp.goto(BASE + '/purchaser/match', { waitUntil: 'domcontentloaded' })
  await sleep(2800)
  const btn = pp.locator('button:has-text("查看权重")')
  if (await btn.count()) {
    await btn.first().click()
    await sleep(900)
    await pp.screenshot({ path: path.join(OUT, 'PURCHASER-匹配权重说明.png') })
    const ok = await pp.evaluate(() => ({
      dialog: document.querySelectorAll('.el-dialog').length,
      bars: document.querySelectorAll('.weight-fill').length
    }))
    console.log('权重弹窗:', JSON.stringify(ok))
  } else {
    console.log('权重按钮未找到')
  }

  await pp.goto(BASE + '/purchaser/inbound', { waitUntil: 'domcontentloaded' })
  await sleep(2600)
  await pp.screenshot({ path: path.join(OUT, 'PURCHASER-入库确认.png') })

  await browser.close()
})()
