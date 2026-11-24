/* 全角色全页面探测：「明细」类入口点击后是否真有反应（抽屉/弹窗/跳转/内容变化） */
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
  const j = await r.json()
  if (!j.data) throw new Error('登录失败 ' + u + ' ' + j.message)
  return j.data
}

const ROLES = [
  { u: 'admin', r: 'ADMIN', routes: ['/admin/dashboard', '/admin/farmers', '/admin/canteens', '/admin/products', '/admin/users', '/admin/orders', '/admin/settlements', '/admin/warnings', '/admin/trace', '/admin/config', '/admin/logs'] },
  { u: 'farmer01', r: 'FARMER', routes: ['/farmer/workbench', '/farmer/sources', '/farmer/demands', '/farmer/orders', '/farmer/settlement', '/farmer/credit', '/farmer/messages'] },
  { u: 'purchaser01', r: 'PURCHASER', routes: ['/purchaser/demands', '/purchaser/sources', '/purchaser/match', '/purchaser/orders', '/purchaser/inbound', '/purchaser/stock', '/purchaser/evaluation', '/purchaser/trace'] },
  { u: 'inspector01', r: 'INSPECTOR', routes: ['/inspector/tasks', '/inspector/quality', '/inspector/delivery', '/inspector/exception', '/inspector/trace'] }
]

const problems = []
const logs = []

;(async () => {
  const browser = await chromium.launch({ channel: 'msedge', headless: true })
  for (const role of ROLES) {
    const d = await login(role.u, role.r)
    const c = await browser.newContext({ viewport: { width: 1440, height: 900 }, locale: 'zh-CN' })
    await c.addInitScript(([t, u, rn, mn]) => {
      sessionStorage.setItem('ct_token', t)
      sessionStorage.setItem('ct_user', u)
      sessionStorage.setItem('ct_role_name', rn)
      sessionStorage.setItem('ct_menus', mn)
    }, [d.token, JSON.stringify(d.user), d.roleName || '', JSON.stringify(d.menus || [])])
    const page = await c.newPage()
    page.on('pageerror', (e) => logs.push(role.u + ' pageerror: ' + String(e).slice(0, 180)))
    page.on('console', (m) => { if (m.type() === 'error') logs.push(role.u + ' console: ' + m.text().slice(0, 180)) })

    for (const url of role.routes) {
      await page.goto(BASE + url, { waitUntil: 'domcontentloaded' })
      await sleep(2400)
      const nodes = await page.$$('text=明细')
      for (let i = 0; i < nodes.length; i++) {
        const n = nodes[i]
        if (!(await n.isVisible().catch(() => false))) continue
        const box = await n.boundingBox().catch(() => null)
        if (!box) continue
        const info = await n.evaluate((el) => ({
          tag: el.tagName,
          cls: (el.className || '').toString(),
          text: el.textContent.trim().slice(0, 24),
          parent: el.parentElement ? (el.parentElement.className || '').toString().slice(0, 40) : ''
        })).catch(() => ({}))
        const snap = () => page.evaluate(() => {
          const vis = (s) => [...document.querySelectorAll(s)].some((e) => e.getClientRects().length)
          const dlg = [...document.querySelectorAll('.el-dialog, .el-drawer')].filter((e) => e.getClientRects().length)
          return {
            drawer: vis('.el-drawer'), dialog: vis('.el-dialog'),
            overlayText: dlg.map((e) => (e.textContent || '').replace(/\s+/g, '').slice(0, 30)).join('|'),
            url: location.pathname + location.search,
            bodyLen: document.body.innerText.length
          }
        })
        const before = await snap()
        await n.click({ timeout: 3000 }).catch((e) => logs.push(`${url}[${i}] click err: ${e.message.slice(0, 60)}`))
        await sleep(1200)
        const after = await snap()
        const changed = after.drawer !== before.drawer || after.dialog !== before.dialog ||
          after.url !== before.url || after.bodyLen !== before.bodyLen
        logs.push(`${role.u} ${url}[${i}] ${changed ? 'OK' : 'NO-REACTION'} <${info.tag} class="${info.cls}"> "${info.text}"`)
        if (!changed) problems.push({ role: role.u, url, i, info })
        await page.keyboard.press('Escape').catch(() => {})
        await sleep(350)
      }
    }
    await c.close()
  }
  await browser.close()
  console.log('\n===== 无反应 =====')
  console.log(JSON.stringify(problems, null, 2))
  console.log('\n===== 错误日志 =====')
  console.log([...new Set(logs)].join('\n'))
})()
