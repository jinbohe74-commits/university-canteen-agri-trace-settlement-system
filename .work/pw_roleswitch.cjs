const path = require('path')
const GLOBAL = path.join(process.env.APPDATA, 'npm', 'node_modules')
module.paths.push(GLOBAL)
const { chromium } = require(path.join(GLOBAL, 'playwright'))

const BASE = 'http://127.0.0.1:5173'

async function login(page, user, pwd = '123456') {
  await page.goto(BASE + '/login', { waitUntil: 'networkidle' })
  await page.evaluate(() => localStorage.clear())
  await page.goto(BASE + '/login', { waitUntil: 'networkidle' })
  await page.fill('input[placeholder="请输入账号"]', user)
  await page.fill('input[placeholder="请输入密码"]', pwd)
  await page.click('button.submit')
  await page.waitForTimeout(1800)
  const url = page.url()
  const u = await page.evaluate(() => localStorage.getItem('ct_user'))
  console.log('login ' + user + ' -> url=' + url + ' user=' + String(u).slice(0, 90))
}

;(async () => {
  const browser = await chromium.launch()
  const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 } })
  const page = await ctx.newPage()

  await login(page, 'purchaser01')
  await login(page, 'admin')
  await page.goto(BASE + '/admin/trace', { waitUntil: 'networkidle' })
  await page.waitForTimeout(1000)
  console.log('after goto /admin/trace url=' + page.url())
  console.log('title=' + (await page.locator('.page-title').first().innerText().catch(() => 'NONE')))

  await browser.close()
})()
