// 前端页面自动巡检（Playwright，全局安装）
const path = require('path')
const GLOBAL = path.join(process.env.APPDATA, 'npm', 'node_modules')
module.paths.push(GLOBAL)
const { chromium } = require(path.join(GLOBAL, 'playwright'))

const BASE = 'http://127.0.0.1:5173'
const OUT = path.join(__dirname, 'shots')
const fs = require('fs')
if (!fs.existsSync(OUT)) fs.mkdirSync(OUT, { recursive: true })

const ROLES = [
  { user: 'admin', pass: '123456', pages: [
    ['admin/dashboard', '数据看板'], ['admin/farmers', '农户'], ['admin/canteens', '食堂'],
    ['admin/products', '农产品'], ['admin/users', '用户'], ['admin/orders', '订单'],
    ['admin/settlements', '结算'], ['admin/warnings', '预警'], ['admin/trace', '溯源'],
    ['admin/config', '参数'], ['admin/logs', '日志']
  ]},
  { user: 'farmer24', pass: '123456', pages: [
    ['farmer/workbench', '工作台'], ['farmer/sources', '货源'], ['farmer/orders', '订单'],
    ['farmer/settlement', '结算'], ['farmer/credit', '信用'], ['farmer/messages', '消息']
  ]},
  { user: 'purchaser01', pass: '123456', pages: [
    ['purchaser/demands', '需求'], ['purchaser/sources', '货源'], ['purchaser/match', '匹配'],
    ['purchaser/orders', '订单'], ['purchaser/inbound', '入库'], ['purchaser/evaluation', '评价']
  ]},
  { user: 'inspector01', pass: '123456', pages: [
    ['inspector/tasks', '任务'], ['inspector/quality', '质检'],
    ['inspector/delivery', '配送'], ['inspector/exception', '异常']
  ]}
]

;(async () => {
  const browser = await chromium.launch()
  const results = []
  let failures = 0

  for (const role of ROLES) {
    const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 }, deviceScaleFactor: 1 })
    const page = await ctx.newPage()
    const errors = []
    page.on('console', (m) => { if (m.type() === 'error') errors.push(m.text()) })
    page.on('pageerror', (e) => errors.push('PAGEERROR: ' + e.message))

    // 登录页
    await page.goto(BASE + '/login', { waitUntil: 'networkidle' })
    if (role.user === 'admin') {
      await page.screenshot({ path: path.join(OUT, '00-login.png'), fullPage: true })
      const t = await page.locator('.brand-title').innerText().catch(() => '')
      console.log('[login] brand=' + t.replace(/\s+/g, ' '))
    }
    await page.fill('input[placeholder="请输入账号"]', role.user)
    await page.fill('input[placeholder="请输入密码"]', role.pass)
    await page.click('button.submit')
    await page.waitForTimeout(1600)

    for (const [route, kw] of role.pages) {
      errors.length = 0
      await page.goto(BASE + '/' + route, { waitUntil: 'networkidle' })
      await page.waitForTimeout(1100)
      const body = await page.locator('body').innerText().catch(() => '')
      const hasKw = body.includes(kw)
      const hasTitle = (await page.locator('.page-title').count()) + (await page.locator('.hero').count())
      const placeholder = body.includes('页面建设中')
      const rows = await page.locator('.el-table__row').count()
      const undef = /undefined|NaN|\[object Object\]/.test(body)
      const ok = hasKw && hasTitle > 0 && !placeholder && !undef && errors.length === 0
      if (!ok) failures++
      const name = route.replace('/', '_')
      const shot = path.join(OUT, role.user + '-' + name + '.png')
      await page.screenshot({ path: shot, fullPage: true })
      results.push({ role: role.user, route, ok, hasKw, hasTitle, placeholder, errors: errors.slice(0, 3) })
      console.log(
        (ok ? '  OK  ' : ' FAIL ') + role.user.padEnd(12) + route.padEnd(26) +
        'kw=' + hasKw + ' title=' + hasTitle + ' rows=' + String(rows).padStart(3) + ' 异常文本=' + undef +
        (errors.length ? ' errors=' + errors.slice(0, 2).join(' | ') : '')
      )
    }
    await ctx.close()
  }

  fs.writeFileSync(path.join(OUT, 'report.json'), JSON.stringify(results, null, 2))
  console.log('\n=== 巡检完成：失败 ' + failures + ' / ' + results.length + ' ===')
  await browser.close()
})()

