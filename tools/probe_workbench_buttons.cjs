/* 打开单据工作台抽屉，逐个点击里面的按钮，找出「点了没反应」的那个 */
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
    sessionStorage.setItem('ct_token', t); sessionStorage.setItem('ct_user', u)
    sessionStorage.setItem('ct_role_name', rn); sessionStorage.setItem('ct_menus', mn)
  }, [d.token, JSON.stringify(d.user), d.roleName || '', JSON.stringify(d.menus || [])])
  const page = await c.newPage()
  const errs = []
  page.on('pageerror', (e) => errs.push('pageerror: ' + String(e).slice(0, 200)))
  page.on('console', (m) => { if (m.type() === 'error') errs.push('console: ' + m.text().slice(0, 200)) })

  await page.goto(BASE + '/admin/dashboard', { waitUntil: 'domcontentloaded' })
  await sleep(3000)
  await page.click('.db-primary')
  await sleep(2000)
  const row = await page.$('.sd-drawer .el-table__row')
  await row.click()
  await sleep(2000)

  const titles = await page.evaluate(() => [...document.querySelectorAll('.el-drawer')]
    .filter((e) => e.getClientRects().length).map((e) => (e.querySelector('.el-drawer__title')?.textContent || '').trim()))
  console.log('抽屉:', JSON.stringify(titles))

  // 工作台抽屉里的所有按钮
  const btns = await page.$$('.el-drawer:last-of-type button, .el-drawer .osa button')
  console.log('按钮数:', btns.length)

  const list = await page.evaluate(() => {
    const ds = [...document.querySelectorAll('.el-drawer')].filter((e) => e.getClientRects().length)
    const box = ds[ds.length - 1]
    if (!box) return []
    return [...box.querySelectorAll('button')].map((b) => b.innerText.trim()).filter(Boolean)
  })
  console.log('按钮文字:', JSON.stringify(list))

  const report = []
  for (let i = 0; i < list.length; i++) {
    const sel = `.el-drawer:has(.el-drawer__title) button`
    const snap = () => page.evaluate(() => {
      const dlg = [...document.querySelectorAll('.el-dialog')].filter((e) => e.getClientRects().length)
      return {
        dialog: dlg.length,
        msg: [...document.querySelectorAll('.el-message')].map((e) => e.innerText.trim()).join('|'),
        drawers: [...document.querySelectorAll('.el-drawer')].filter((e) => e.getClientRects().length).length,
        url: location.pathname
      }
    })
    const before = await snap()
    const handles = await page.$$('.el-drawer button')
    const target = handles.filter(async () => true)
    // 按文字定位最后一个抽屉里的按钮
    const btn = page.locator('.el-drawer:visible').last().locator('button', { hasText: list[i] }).first()
    await btn.click({ timeout: 4000 }).catch((e) => errs.push('btn ' + list[i] + ': ' + e.message.slice(0, 60)))
    await sleep(1400)
    const after = await snap()
    const changed = after.dialog !== before.dialog || after.msg !== before.msg || after.drawers !== before.drawers || after.url !== before.url
    report.push({ btn: list[i], changed, before, after })
    // 关掉弹窗
    if (after.dialog > before.dialog) {
      await page.keyboard.press('Escape').catch(() => {})
      await sleep(700)
    }
  }
  console.log('\n===== 按钮响应 =====')
  report.forEach((r) => console.log(`${r.changed ? 'OK  ' : 'NO  '} ${r.btn}`))
  console.log('\n错误:', [...new Set(errs)].join('\n') || '无')
  await browser.close()
})()
