const path = require('path')
const GLOBAL = path.join(process.env.APPDATA, 'npm', 'node_modules')
module.paths.push(GLOBAL)
const { chromium } = require(path.join(GLOBAL, 'playwright'))

const BASE = 'http://127.0.0.1:5173'
const TARGETS = [
  { user: 'purchaser01', route: 'purchaser/sources', label: '货源大厅' },
  { user: 'purchaser01', route: 'purchaser/evaluation', label: '采购评价' },
  { user: 'purchaser01', route: 'purchaser/match', label: '智能匹配' },
  { user: 'admin', route: 'admin/trace', label: '批次溯源' },
  { user: 'admin', route: 'admin/config', label: '系统参数' },
  { user: 'farmer24', route: 'farmer/messages', label: '消息提醒' },
  { user: 'inspector01', route: 'inspector/quality', label: '质检录入' },
  { user: 'admin', route: 'admin/dashboard', label: '数据看板' }
]

;(async () => {
  const browser = await chromium.launch()
  const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 } })
  const page = await ctx.newPage()
  const errors = []
  page.on('pageerror', (e) => errors.push(e.message))

  // 登录 admin
  await page.goto(BASE + '/login', { waitUntil: 'networkidle' })
  await page.fill('input[placeholder="请输入账号"]', 'admin')
  await page.fill('input[placeholder="请输入密码"]', '123456')
  await page.click('button.submit')
  await page.waitForTimeout(1500)

  for (const t of TARGETS) {
    {
      await page.evaluate(() => { localStorage.clear() })
      await page.goto(BASE + '/login', { waitUntil: 'networkidle' })
      await page.fill('input[placeholder="请输入账号"]', t.user)
      await page.fill('input[placeholder="请输入密码"]', '123456')
      await page.click('button.submit')
      await page.waitForTimeout(1500)
    }
    await page.goto(BASE + '/' + t.route, { waitUntil: 'networkidle' })
    await page.waitForTimeout(1200)
    const txt = (await page.locator('.content').innerText().catch(() => ''))
      .replace(/\s+/g, ' ').slice(0, 260)
    console.log('\n### ' + t.label + ' (' + t.route + ')\n' + txt)
  }

  if (errors.length) console.log('\nPAGE ERRORS: ' + errors.join(' | '))
  await browser.close()
})()

