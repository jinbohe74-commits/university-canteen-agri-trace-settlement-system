// 前端重构后（2026-11-10）全角色 30 路由回归巡检。
// 与 review_pw_check.cjs 断言完全一致，仅输出到重构验收目录，
// 不覆盖 docs/fix_2026-11-09/ 与 docs/review_2026-11-09/ 的历史结果。
const path = require('path')
const fs = require('fs')
const GLOBAL = path.join(process.env.APPDATA, 'npm', 'node_modules')
module.paths.push(GLOBAL)
const { chromium } = require(path.join(GLOBAL, 'playwright'))

const BASE = 'http://127.0.0.1:5173'
const ROOT = path.resolve(__dirname, '..')
const OUT = path.join(ROOT, 'docs', 'design_2026-11-10', 'ui_acceptance')
fs.mkdirSync(OUT, { recursive: true })

const ROLES = [
  { user: 'admin', pass: '123456', pages: [
    ['admin/dashboard', '数据看板'], ['admin/farmers', '农户'], ['admin/canteens', '食堂'],
    ['admin/products', '农产品'], ['admin/users', '用户'], ['admin/orders', '订单'],
    ['admin/settlements', '结算'], ['admin/warnings', '预警'], ['admin/trace', '溯源'],
    ['admin/config', '参数'], ['admin/logs', '日志']
  ]},
  { user: 'farmer24', pass: '123456', pages: [
    ['farmer/workbench', '工作台'], ['farmer/sources', '货源'], ['farmer/orders', '订单'],
    ['farmer/settlement', '结算'], ['farmer/credit', '信用'], ['farmer/messages', '消息'],
    ['trace', '溯源'] // 公共溯源路由（router 中农户无 farmer/trace）
  ]},
  { user: 'purchaser01', pass: '123456', pages: [
    ['purchaser/demands', '需求'], ['purchaser/sources', '货源'], ['purchaser/match', '匹配'],
    ['purchaser/orders', '订单'], ['purchaser/inbound', '入库'], ['purchaser/evaluation', '评价'],
    ['purchaser/trace', '溯源']
  ]},
  { user: 'inspector01', pass: '123456', pages: [
    ['inspector/tasks', '任务'], ['inspector/quality', '质检'],
    ['inspector/delivery', '配送'], ['inspector/exception', '异常'],
    ['inspector/trace', '溯源']
  ]}
]

// 这些页面必须有真实表格数据，否则判 FAIL
const LIST_PAGES = new Set([
  'admin/farmers', 'admin/canteens', 'admin/products', 'admin/users', 'admin/orders',
  'admin/settlements', 'admin/warnings', 'admin/logs',
  'farmer/orders', 'purchaser/demands', 'purchaser/orders', 'inspector/tasks'
])

;(async () => {
  // 本机 Playwright 缓存版本与全局包不匹配，复用系统 Edge，无需联网下载浏览器
  const browser = await chromium.launch({ channel: 'msedge' })
  const cases = []
  let failures = 0

  const pushSecurity = (name, ok, detail) => {
    if (!ok) failures++
    cases.push({ type: 'security', name, ok, ...detail })
    console.log((ok ? '  OK   ' : ' FAIL  ') + name + ' | ' + JSON.stringify(detail))
  }

  // 未登录访问受保护路由必须跳转登录页（全新上下文）
  {
    const anon = await browser.newContext({ viewport: { width: 1440, height: 900 } })
    const p = await anon.newPage()
    await p.goto(BASE + '/admin/dashboard', { waitUntil: 'domcontentloaded' })
    await p.waitForTimeout(600)
    const url = p.url()
    pushSecurity('未登录访问 /admin/dashboard 跳转登录页',
      url.includes('/login'), { url })
    await anon.close()
  }

  for (const role of ROLES) {
    const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 } })
    const page = await ctx.newPage()
    const errors = []
    page.on('console', (m) => { if (m.type() === 'error') errors.push(m.text()) })
    page.on('pageerror', (e) => errors.push('PAGEERROR: ' + e.message))

    await page.goto(BASE + '/login', { waitUntil: 'domcontentloaded' })
    await page.fill('input[placeholder="请输入账号"]', role.user)
    await page.fill('input[placeholder="请输入密码"]', role.pass)
    await page.click('button.submit')
    await page.waitForTimeout(1500)

    for (const [route, kw] of role.pages) {
      errors.length = 0
      let ok = false, rows = -1, body = '', hasTitle = 0, undef = false, placeholder = false
      try {
        await page.goto(BASE + '/' + route, { waitUntil: 'domcontentloaded', timeout: 20000 })
        await page.waitForTimeout(700)
        body = await page.locator('body').innerText().catch(() => '')
        hasTitle = (await page.locator('.page-title').count()) + (await page.locator('.hero').count())
        placeholder = body.includes('页面建设中')
        undef = /undefined|NaN|\[object Object\]/.test(body)
        rows = await page.locator('.el-table__row').count()
        const needRows = LIST_PAGES.has(route)
        ok = body.includes(kw) && hasTitle > 0 && !placeholder && !undef
          && errors.length === 0 && body.length > 120 && (!needRows || rows > 0)
      } catch (e) {
        errors.push('NAV: ' + e.message.slice(0, 120))
      }
      if (!ok) failures++
      cases.push({ type: 'page', role: role.user, route, ok, rows, hasTitle,
        bodyLen: body.length, undef, placeholder, needRows: LIST_PAGES.has(route),
        errors: errors.slice(0, 3) })
      console.log((ok ? '  OK  ' : ' FAIL ') + role.user.padEnd(12) + route.padEnd(24)
        + ' rows=' + String(rows).padStart(3) + ' bodyLen=' + String(body.length).padStart(5)
        + (errors.length ? ' errors=' + errors.slice(0, 1).join('') : ''))
    }

    // 修复后期望：非管理员把 localStorage 里的 role 改成 ADMIN，
    // 刷新后 router 会向 /auth/me 复核身份并用服务端角色覆盖缓存，无法进入管理路由。
    if (role.user === 'farmer24') {
      await page.evaluate(() => {
        const u = JSON.parse(localStorage.getItem('ct_user') || '{}')
        u.role = 'ADMIN'
        localStorage.setItem('ct_user', JSON.stringify(u))
      })
      await page.goto(BASE + '/admin/dashboard', { waitUntil: 'domcontentloaded' })
      await page.waitForTimeout(1000)
      const url = page.url()
      const text = await page.locator('body').innerText().catch(() => '')
      const entered = url.includes('/admin/dashboard') && text.includes('数据看板')
      pushSecurity('localStorage 篡改 role 后仍被阻断，无法进入 /admin/dashboard',
        !entered, { url, hasDashboardText: text.includes('数据看板') })
      const cached = await page.evaluate(() => {
        try { return JSON.parse(localStorage.getItem('ct_user') || '{}').role } catch (e) { return null }
      })
      pushSecurity('刷新后服务端 /auth/me 复核结果覆盖被篡改的本地角色',
        cached !== 'ADMIN', { cachedRole: cached })
    }
    await ctx.close()
  }

  const summary = {
    totalPages: cases.filter(c => c.type === 'page').length,
    failedPages: cases.filter(c => c.type === 'page' && !c.ok).length,
    securityChecks: cases.filter(c => c.type === 'security').length,
    failedSecurityChecks: cases.filter(c => c.type === 'security' && !c.ok).length,
    tablesWithData: cases.filter(c => c.type === 'page' && c.rows > 0).length,
    consoleErrors: cases.filter(c => c.type === 'page' && c.errors.length > 0).length,
    failures
  }
  fs.writeFileSync(path.join(OUT, 'frontend_check_results_ui.json'),
    JSON.stringify({ summary, cases }, null, 2), 'utf-8')
  console.log('\n=== 重构后前端巡检汇总 ===\n' + JSON.stringify(summary, null, 2))
  console.log('结果文件：' + path.join(OUT, 'frontend_check_results_ui.json'))
  await browser.close()
  process.exit(failures ? 1 : 0)
})().catch(e => { console.error('FATAL', e); process.exit(2) })
