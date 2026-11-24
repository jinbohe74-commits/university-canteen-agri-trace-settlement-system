const path = require('path')
const GLOBAL = path.join(process.env.APPDATA, 'npm', 'node_modules')
module.paths.push(GLOBAL)
const { chromium } = require(path.join(GLOBAL, 'playwright'))
const BASE = 'http://127.0.0.1:5173'
const sleep = ms => new Promise(r => setTimeout(r, ms))

;(async () => {
  const browser = await chromium.launch({ channel: 'msedge' })
  const r = await fetch('http://127.0.0.1:8080/api/auth/login', {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username: 'admin', password: '123456' })
  }).then(x => x.json())
  const ctx = await browser.newContext({ viewport: { width: 1440, height: 1180 } })
  await ctx.addInitScript(([t, u]) => {
    localStorage.setItem('ct_token', t); localStorage.setItem('ct_user', u)
  }, [r.data.token, JSON.stringify(r.data.user)])
  const p = await ctx.newPage()
  await p.goto(BASE + '/admin/dashboard', { waitUntil: 'domcontentloaded' })
  await sleep(3500)
  await p.screenshot({ path: 'docs/usability_2026-11-10/H-数据看板-浅色.png' })
  await p.evaluate(() => { document.documentElement.dataset.mode = 'night' })
  await sleep(1600)
  await p.screenshot({ path: 'docs/usability_2026-11-10/H-数据看板-深色.png' })
  await browser.close()
  console.log('shots done')
})()
