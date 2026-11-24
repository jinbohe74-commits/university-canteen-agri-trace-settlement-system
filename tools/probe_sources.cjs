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
    body: JSON.stringify({ username: 'purchaser01', password: '123456' })
  }).then(x => x.json())
  const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 } })
  await ctx.addInitScript(([t, u]) => {
    localStorage.setItem('ct_token', t); localStorage.setItem('ct_user', u)
  }, [r.data.token, JSON.stringify(r.data.user)])
  const p = await ctx.newPage()
  const api = []
  p.on('response', async res => {
    if (res.url().includes('/api/purchaser/sources')) {
      let n = -1
      try { const j = await res.json(); n = (j.data && j.data.page && j.data.page.total) } catch (e) {}
      api.push({ url: res.url(), status: res.status(), total: n })
    }
  })
  p.on('console', m => console.log('  [console.' + m.type() + ']', m.text().slice(0, 200)))
  await p.goto(BASE + '/purchaser/sources', { waitUntil: 'domcontentloaded' })
  await sleep(4000)
  const info = await p.evaluate(() => ({
    cards: document.querySelectorAll('.source-card').length,
    empty: document.querySelectorAll('.empty-card').length,
    text: document.body.innerText.replace(/\s+/g, ' ').slice(0, 300)
  }))
  console.log('API:', JSON.stringify(api, null, 2))
  console.log('DOM:', JSON.stringify(info, null, 2))
  await p.screenshot({ path: 'docs/usability_2026-11-10/_probe-sources.png' })
  await browser.close()
})()
