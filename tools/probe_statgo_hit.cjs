/* 命中测试：卡片右下角「明细 ›」的真实点击落点是否还在卡片内（决定点了有没有反应） */
const path = require('path')
const ROOT = path.resolve(__dirname, '..')
const BASE = 'http://127.0.0.1:5173'
const API = 'http://127.0.0.1:8080/api'
const GLOBAL = path.join(process.env.APPDATA, 'npm', 'node_modules')
module.paths.push(GLOBAL)
const { chromium } = require(path.join(GLOBAL, 'playwright'))
const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

const ROUTES = ['/admin/dashboard', '/admin/farmers', '/admin/canteens', '/admin/products',
  '/admin/users', '/admin/orders', '/admin/settlements', '/admin/warnings', '/admin/trace',
  '/admin/config', '/admin/logs']

;(async () => {
  const d = (await (await fetch(API + '/auth/login', {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username: 'admin', password: '123456', expectedRole: 'ADMIN' })
  })).json()).data
  const browser = await chromium.launch({ channel: 'msedge', headless: true })
  const c = await browser.newContext({ viewport: { width: 1440, height: 900 }, locale: 'zh-CN' })
  await c.addInitScript(([t, u, rn, mn]) => {
    sessionStorage.setItem('ct_token', t); sessionStorage.setItem('ct_user', u)
    sessionStorage.setItem('ct_role_name', rn); sessionStorage.setItem('ct_menus', mn)
  }, [d.token, JSON.stringify(d.user), d.roleName || '', JSON.stringify(d.menus || [])])
  const page = await c.newPage()

  const bad = []
  for (const url of ROUTES) {
    await page.goto(BASE + url, { waitUntil: 'domcontentloaded' })
    await sleep(2300)
    const res = await page.evaluate(() => {
      const out = []
      document.querySelectorAll('.stat-card').forEach((card, i) => {
        const go = card.querySelector('.stat-go')
        if (!go) return
        const cr = card.getBoundingClientRect()
        const gr = go.getBoundingClientRect()
        // 悬停后「明细」才显形，这里只做几何命中：取「明细」文字中心
        const cx = gr.left + gr.width / 2
        const cy = gr.top + gr.height / 2
        const hit = document.elementFromPoint(cx, cy)
        const inCard = hit ? card.contains(hit) : false
        out.push({
          i,
          label: (card.querySelector('.stat-label')?.textContent || '').trim(),
          goRect: { x: Math.round(gr.left), y: Math.round(gr.top), w: Math.round(gr.width), h: Math.round(gr.height) },
          cardRect: { x: Math.round(cr.left), y: Math.round(cr.top), w: Math.round(cr.width), h: Math.round(cr.height) },
          overflowRight: Math.round(gr.right - cr.right),
          overflowBottom: Math.round(gr.bottom - cr.bottom),
          hitTag: hit ? hit.tagName : null,
          hitCls: hit ? (hit.className || '').toString().slice(0, 40) : null,
          hitIsInCard: inCard
        })
      })
      return out
    })
    res.forEach((r) => { if (!r.hitIsInCard) bad.push({ url, ...r }) })
    console.log(url, '卡片', res.length, '落点异常', res.filter((r) => !r.hitIsInCard).length)
  }
  console.log('\n===== 落点不在卡片内（点了不会触发）=====')
  console.log(JSON.stringify(bad, null, 2))
  await browser.close()
})()
