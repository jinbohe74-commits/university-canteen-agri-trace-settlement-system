const path = require('path')
const G = path.join(process.env.APPDATA, 'npm', 'node_modules')
module.paths.push(G)
const { chromium } = require(path.join(G, 'playwright'))

;(async () => {
  const b = await chromium.launch()
  const ctx = await b.newContext({ viewport: { width: 1440, height: 900 } })
  const p = await ctx.newPage()
  const errs = []
  p.on('pageerror', (e) => errs.push(e.message))

  await p.goto('http://127.0.0.1:5173/login', { waitUntil: 'networkidle' })
  await p.fill('input[placeholder="请输入账号"]', 'admin')
  await p.fill('input[placeholder="请输入密码"]', '123456')
  await p.click('button.submit')
  await p.waitForTimeout(2200)

  const menu = await p.locator('.menu-item').allInnerTexts()
  console.log('侧栏菜单(' + menu.length + ' 项)：' + menu.join(' | '))
  console.log('落地页：' + (await p.locator('.page-title').innerText().catch(() => '?')))

  for (const name of menu) {
    await p.locator('.menu-item', { hasText: name }).first().click()
    await p.waitForTimeout(850)
    const t = await p.locator('.page-title').innerText().catch(() => '(无标题)')
    const hero = await p.locator('.hero').count()
    const url = p.url().replace('http://127.0.0.1:5173', '')
    console.log('  → ' + name.padEnd(12) + ' ' + url.padEnd(24) + ' 标题: ' + t + (hero ? '（含横幅）' : ''))
  }
  console.log('页面错误：' + (errs.length ? errs.join(' | ') : '无'))
  await b.close()
})()
