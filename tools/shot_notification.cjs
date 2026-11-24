/* 只截图、不改数据：通知面板未读 / 已读 / 详情弹窗三张图
   运行：node tools/shot_notification.cjs */
const path = require('path')
const GLOBAL = path.join(process.env.APPDATA, 'npm', 'node_modules')
module.paths.push(GLOBAL)
const { chromium } = require(path.join(GLOBAL, 'playwright'))

const API = 'http://127.0.0.1:8080/api'
const BASE = 'http://127.0.0.1:5173'
const SHOT = path.join(__dirname, '..', 'docs', 'fix_2026-11-16')
const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

;(async () => {
  const login = await fetch(API + '/auth/login', {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username: 'purchaser01', password: '123456', expectedRole: 'PURCHASER' })
  })
  const d = (await login.json()).data
  const b = await chromium.launch({ channel: 'msedge', headless: true })
  const ctx = await b.newContext({ viewport: { width: 1440, height: 820 }, deviceScaleFactor: 2 })
  await ctx.addInitScript(([t, u, rn, mn]) => {
    sessionStorage.setItem('ct_token', t)
    sessionStorage.setItem('ct_user', u)
    sessionStorage.setItem('ct_role_name', rn)
    sessionStorage.setItem('ct_menus', mn)
  }, [d.token, JSON.stringify(d.user), d.roleName || '', JSON.stringify(d.menus || [])])
  const p = await ctx.newPage()
  await p.goto(BASE + '/purchaser/orders', { waitUntil: 'domcontentloaded' })
  await sleep(2600)

  // 未读分组
  await p.locator('.lay-notify .lay-act').click()
  await sleep(1000)
  await p.locator('.lnp-tabs button').nth(0).click()
  await sleep(500)
  await p.screenshot({ path: path.join(SHOT, 'notif_panel_unread.png') })

  // 已读分组
  await p.locator('.lnp-tabs button').nth(1).click()
  await sleep(600)
  await p.screenshot({ path: path.join(SHOT, 'notif_panel_read.png') })

  // 详情（点已读条目，不影响未读数）
  await p.locator('.lnp-item').first().click()
  await sleep(1100)
  await p.screenshot({ path: path.join(SHOT, 'notif_detail_final.png') })

  const stat = await p.evaluate(() => ({
    tabs: [...document.querySelectorAll('.lnp-tabs button')].map((e) => e.innerText.trim()),
    detail: !!document.querySelector('.el-dialog .ntf'),
    title: document.querySelector('.ntf-title')?.innerText.trim()
  }))
  console.log(JSON.stringify(stat, null, 2))
  await b.close()
})()
