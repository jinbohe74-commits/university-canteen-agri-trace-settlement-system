/* 诊断 el-dialog 的 containing block：为什么水平/垂直都不居中 */
const path = require('path')
const GLOBAL = path.join(process.env.APPDATA, 'npm', 'node_modules')
module.paths.push(GLOBAL)
const { chromium } = require(path.join(GLOBAL, 'playwright'))
const sleep = ms => new Promise(r => setTimeout(r, ms))

;(async () => {
  const browser = await chromium.launch({ channel: 'msedge' })
  const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 } })
  const page = await ctx.newPage()
  await page.goto('http://127.0.0.1:5173/login', { waitUntil: 'domcontentloaded' })
  await sleep(700)
  await page.fill('input[placeholder="请输入账号"]', 'admin')
  await page.fill('input[placeholder="请输入密码"]', '123456')
  await page.click('button.submit')
  await sleep(2000)
  await page.goto('http://127.0.0.1:5173/admin/canteens', { waitUntil: 'domcontentloaded' })
  await sleep(1600)
  await page.locator('button:has-text("新增")').first().click()
  await sleep(800)
  const info = await page.evaluate(() => {
    const d = document.querySelector('.el-dialog')
    if (!d) return { error: 'no dialog' }
    const chain = []
    let n = d
    while (n && n !== document.documentElement) {
      const cs = getComputedStyle(n)
      chain.push({
        tag: n.tagName.toLowerCase(),
        cls: (typeof n.className === 'string' ? n.className : '').slice(0, 70),
        position: cs.position, transform: cs.transform, filter: cs.filter,
        contain: cs.contain, willChange: cs.willChange,
        rect: [Math.round(n.getBoundingClientRect().left), Math.round(n.getBoundingClientRect().top),
               Math.round(n.getBoundingClientRect().width), Math.round(n.getBoundingClientRect().height)]
      })
      n = n.parentElement
    }
    const overlay = document.querySelector('.el-overlay')
    const overlayDialog = document.querySelector('.el-overlay-dialog')
    return {
      chain,
      dialogMargin: getComputedStyle(d).margin,
      overlayParent: overlay ? overlay.parentElement.tagName + '.' + overlay.parentElement.className : null,
      overlayDialogCS: overlayDialog ? {
        position: getComputedStyle(overlayDialog).position,
        display: getComputedStyle(overlayDialog).display,
        inset: [getComputedStyle(overlayDialog).top, getComputedStyle(overlayDialog).left],
        rect: (r => [Math.round(r.left), Math.round(r.top), Math.round(r.width), Math.round(r.height)])(overlayDialog.getBoundingClientRect())
      } : null
    }
  })
  console.log(JSON.stringify(info, null, 2))
  await browser.close()
})().catch(e => { console.error(e); process.exit(1) })
