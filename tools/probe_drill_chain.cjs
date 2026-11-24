/* 验证 KPI 明细抽屉 → 行点击 → 单据工作台 这条链路是否真的响应 */
const path = require('path')
const ROOT = path.resolve(__dirname, '..')
const BASE = 'http://127.0.0.1:5173'
const API = 'http://127.0.0.1:8080'
const GLOBAL = path.join(process.env.APPDATA, 'npm', 'node_modules')
module.paths.push(GLOBAL)
const { chromium } = require(path.join(GLOBAL, 'playwright'))
const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

async function login(u, role) {
  const r = await fetch(API + '/api/auth/login', {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username: u, password: '123456', expectedRole: role })
  })
  return (await r.json()).data
}

;(async () => {
  const browser = await chromium.launch({ channel: 'msedge', headless: true })
  const d = await login('admin', 'ADMIN')
  const c = await browser.newContext({ viewport: { width: 1440, height: 900 }, locale: 'zh-CN' })
  await c.addInitScript(([t, u, rn, mn]) => {
    sessionStorage.setItem('ct_token', t)
    sessionStorage.setItem('ct_user', u)
    sessionStorage.setItem('ct_role_name', rn)
    sessionStorage.setItem('ct_menus', mn)
  }, [d.token, JSON.stringify(d.user), d.roleName || '', JSON.stringify(d.menus || [])])
  const page = await c.newPage()
  const errs = []
  page.on('pageerror', (e) => errs.push('pageerror: ' + String(e).slice(0, 200)))
  page.on('console', (m) => { if (m.type() === 'error') errs.push('console: ' + m.text().slice(0, 200)) })

  await page.goto(BASE + '/admin/dashboard', { waitUntil: 'domcontentloaded' })
  await sleep(3000)

  // 打开主指标抽屉
  await page.click('.db-primary', { timeout: 5000 })
  await sleep(2200)
  const drawer1 = await page.evaluate(() => {
    const ds = [...document.querySelectorAll('.el-drawer')].filter((e) => e.getClientRects().length)
    return ds.map((e) => ({ title: (e.querySelector('.el-drawer__title')?.textContent || '').trim(), rows: e.querySelectorAll('.el-table__row').length }))
  })
  console.log('抽屉1:', JSON.stringify(drawer1))

  // 点抽屉里第一行明细
  const row = await page.$('.sd-drawer .el-table__row')
  if (row) {
    await row.click({ timeout: 4000 }).catch((e) => errs.push('row click: ' + e.message.slice(0, 80)))
    await sleep(2000)
    const after = await page.evaluate(() => {
      const ds = [...document.querySelectorAll('.el-drawer')].filter((e) => e.getClientRects().length)
      return ds.map((e) => (e.querySelector('.el-drawer__title')?.textContent || '').trim())
    })
    console.log('点行后抽屉:', JSON.stringify(after))
  } else {
    console.log('抽屉内没有可点明细行')
  }

  // 抽屉里「查看 ›」按钮（固定列）
  const go = await page.$('.sd-go')
  if (go) {
    await page.evaluate(() => { const ds = [...document.querySelectorAll('.el-drawer')]; ds.forEach((e) => { const b = e.querySelector('.el-drawer__close-btn'); if (b) b.click() }) })
    await sleep(900)
    await page.click('.db-primary', { timeout: 5000 })
    await sleep(1800)
    await page.click('.sd-go', { timeout: 4000 }).catch((e) => errs.push('sd-go: ' + e.message.slice(0, 80)))
    await sleep(1800)
    const t3 = await page.evaluate(() => [...document.querySelectorAll('.el-drawer')].filter((e) => e.getClientRects().length)
      .map((e) => (e.querySelector('.el-drawer__title')?.textContent || '').trim()))
    console.log('点「查看 ›」后抽屉:', JSON.stringify(t3))
  }

  console.log('\n错误:', [...new Set(errs)].join('\n') || '无')
  await browser.close()
})()
