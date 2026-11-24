/* 复现「点击方框里的明细没反应」：逐个点页面上的「明细」入口，检测是否出现抽屉/弹窗/跳转 */
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
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username: u, password: '123456', expectedRole: role })
  })
  const j = await r.json()
  if (!j.data) throw new Error('登录失败 ' + u + ' ' + j.message)
  return j.data
}

async function ctxFor(browser, d) {
  const c = await browser.newContext({ viewport: { width: 1440, height: 900 }, locale: 'zh-CN' })
  await c.addInitScript(([t, u, rn, mn]) => {
    sessionStorage.setItem('ct_token', t)
    sessionStorage.setItem('ct_user', u)
    sessionStorage.setItem('ct_role_name', rn)
    sessionStorage.setItem('ct_menus', mn)
  }, [d.token, JSON.stringify(d.user), d.roleName || '', JSON.stringify(d.menus || [])])
  return c
}

const problems = []
const logs = []

async function probe(page, tag) {
  page.on('pageerror', (e) => logs.push(tag + ' pageerror: ' + String(e).slice(0, 200)))
  page.on('console', (m) => { if (m.type() === 'error') logs.push(tag + ' console: ' + m.text().slice(0, 200)) })
}

async function clickAll(page, tag) {
  const nodes = await page.$$('text=明细')
  logs.push(`${tag}: 找到 ${nodes.length} 个「明细」`)
  for (let i = 0; i < nodes.length; i++) {
    const n = nodes[i]
    const visible = await n.isVisible().catch(() => false)
    if (!visible) continue
    const box = await n.boundingBox().catch(() => null)
    if (!box) continue
    const info = await n.evaluate((el) => ({
      cls: el.className && el.className.toString(),
      text: el.textContent.trim().slice(0, 20),
      parentCls: el.parentElement ? el.parentElement.className.toString() : '',
      tag: el.tagName
    })).catch(() => ({}))
    const before = await page.evaluate(() => {
      const vis = (s) => [...document.querySelectorAll(s)].some((e) => e.offsetParent !== null || e.getClientRects().length)
      return { drawer: vis('.el-drawer'), dialog: vis('.el-dialog'), url: location.pathname }
    })
    await n.click({ timeout: 3000 }).catch((e) => logs.push(`${tag}[${i}] click err: ${e.message.slice(0, 80)}`))
    await sleep(1200)
    const after = await page.evaluate(() => {
      const vis = (s) => [...document.querySelectorAll(s)].some((e) => e.offsetParent !== null || e.getClientRects().length)
      return { drawer: vis('.el-drawer'), dialog: vis('.el-dialog'), url: location.pathname }
    })
    const changed = (after.drawer && !before.drawer) || (after.dialog && !before.dialog) || after.url !== before.url
    if (!changed) {
      problems.push({ tag, i, info, box: { x: Math.round(box.x), y: Math.round(box.y) } })
    }
    logs.push(`${tag}[${i}] ${changed ? 'OK' : 'NO-REACTION'} ${info.tag}.${info.cls} "${info.text}"`)
    // 关掉可能打开的浮层，避免干扰下一个
    await page.keyboard.press('Escape').catch(() => {})
    await sleep(400)
  }
}

;(async () => {
  const browser = await chromium.launch({ channel: 'msedge', headless: true })
  const admin = await login('admin', 'ADMIN')
  const c = await ctxFor(browser, admin)
  const page = await c.newPage()
  await probe(page, 'admin')

  for (const url of ['/admin/dashboard', '/admin/settlements', '/admin/orders', '/admin/warnings']) {
    await page.goto(BASE + url, { waitUntil: 'domcontentloaded' })
    await sleep(2800)
    await clickAll(page, url)
  }

  await browser.close()
  console.log('\n===== 日志 =====')
  logs.forEach((l) => console.log(l))
  console.log('\n===== 无反应 =====')
  console.log(JSON.stringify(problems, null, 2))
})()
