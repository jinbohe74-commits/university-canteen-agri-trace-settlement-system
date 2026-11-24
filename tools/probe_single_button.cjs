/* 单点验证：单据工作台里的「查看结算单」等按钮，点一次看一次反应（避免互相干扰） */
const path = require('path')
const ROOT = path.resolve(__dirname, '..')
const BASE = 'http://127.0.0.1:5173'
const API = 'http://127.0.0.1:8080/api'
const GLOBAL = path.join(process.env.APPDATA, 'npm', 'node_modules')
module.paths.push(GLOBAL)
const { chromium } = require(path.join(GLOBAL, 'playwright'))
const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

async function login(u, role) {
  const r = await fetch(API + '/auth/login', {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username: u, password: '123456', expectedRole: role })
  })
  return (await r.json()).data
}

;(async () => {
  const browser = await chromium.launch({ channel: 'msedge', headless: true })
  const d = await login('admin', 'ADMIN')
  const results = []

  for (const label of ['查看结算单', '查看溯源记录', '导出结算明细']) {
    const c = await browser.newContext({ viewport: { width: 1440, height: 900 }, locale: 'zh-CN' })
    await c.addInitScript(([t, u, rn, mn]) => {
      sessionStorage.setItem('ct_token', t); sessionStorage.setItem('ct_user', u)
      sessionStorage.setItem('ct_role_name', rn); sessionStorage.setItem('ct_menus', mn)
    }, [d.token, JSON.stringify(d.user), d.roleName || '', JSON.stringify(d.menus || [])])
    const page = await c.newPage()
    const errs = []
    page.on('pageerror', (e) => errs.push(String(e).slice(0, 160)))
    page.on('console', (m) => { if (m.type() === 'error') errs.push(m.text().slice(0, 160)) })

    await page.goto(BASE + '/admin/dashboard', { waitUntil: 'domcontentloaded' })
    await sleep(2800)
    await page.click('.db-primary')
    await sleep(1800)
    await page.click('.sd-drawer .el-table__row')
    await sleep(1800)

    const before = await page.evaluate(() => ({
      dialogs: [...document.querySelectorAll('.el-dialog')].filter((e) => e.getClientRects().length)
        .map((e) => (e.querySelector('.el-dialog__title')?.textContent || '').trim()),
      msg: [...document.querySelectorAll('.el-message')].map((e) => e.innerText.trim()).join('|')
    }))

    const btn = page.locator('.el-drawer:visible').last().locator('button', { hasText: label }).first()
    const exists = await btn.count()
    if (!exists) { results.push({ label, status: '按钮不存在' }); await c.close(); continue }
    await btn.click({ timeout: 5000 }).catch((e) => errs.push('click: ' + e.message.slice(0, 60)))
    await sleep(1500)

    const after = await page.evaluate(() => ({
      dialogs: [...document.querySelectorAll('.el-dialog')].filter((e) => e.getClientRects().length)
        .map((e) => (e.querySelector('.el-dialog__title')?.textContent || '').trim()),
      msg: [...document.querySelectorAll('.el-message')].map((e) => e.innerText.trim()).join('|'),
      dlgText: ([...document.querySelectorAll('.el-dialog')].filter((e) => e.getClientRects().length)[0]?.innerText || '').replace(/\s+/g, ' ').slice(0, 80)
    }))
    const changed = after.dialogs.length !== before.dialogs.length || after.msg !== before.msg
    results.push({ label, changed, before, after, errs: [...new Set(errs)] })
    await c.close()
  }

  console.log(JSON.stringify(results, null, 2))
  await browser.close()
})()
