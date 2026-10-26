const path = require('path')
const G = path.join(process.env.APPDATA, 'npm', 'node_modules')
module.paths.push(G)
const { chromium } = require(path.join(G, 'playwright'))
const BASE = 'http://127.0.0.1:5173'

const ROLES = [
  ['admin', '系统管理员', 'admin/dashboard'],
  ['farmer24', '农户/合作社', 'farmer/workbench'],
  ['purchaser01', '食堂采购员', 'purchaser/demands'],
  ['inspector01', '质检配送员', 'inspector/tasks']
]

;(async () => {
  const b = await chromium.launch()
  const ctx = await b.newContext({ viewport: { width: 1440, height: 900 } })
  const p = await ctx.newPage()
  const errs = []
  p.on('pageerror', (e) => errs.push(e.message))

  for (const [user, label, home] of ROLES) {
    await p.goto(BASE + '/login', { waitUntil: 'networkidle' })
    await p.evaluate(() => localStorage.clear())
    await p.goto(BASE + '/login', { waitUntil: 'networkidle' })
    await p.fill('input[placeholder="请输入账号"]', user)
    await p.fill('input[placeholder="请输入密码"]', '123456')
    await p.click('button.submit')
    await p.waitForTimeout(2000)
    const url = p.url().replace(BASE, '')
    const menu = await p.locator('.menu-item').allInnerTexts()
    const groups = await p.locator('.menu-group-label').allInnerTexts()
    const footer = await p.locator('.sidebar-foot').innerText().catch(() => '')
    const cards = await p.locator('.stat-card').count()
    const okUrl = url === '/' + home
    console.log('[' + label + '] 账号 ' + user)
    console.log('   登录后落地：' + url + (okUrl ? ' ✓' : ' ✗ 期望 /' + home))
    console.log('   菜单分组：' + groups.join(' / '))
    console.log('   菜单项(' + menu.length + ')：' + menu.join(' | '))
    console.log('   用户信息：' + footer.replace(/\s+/g, ' ').trim() + ' ｜ 统计卡 ' + cards + ' 张')
  }
  console.log('页面错误：' + (errs.length ? errs.join(' | ') : '无'))
  await b.close()
})()
