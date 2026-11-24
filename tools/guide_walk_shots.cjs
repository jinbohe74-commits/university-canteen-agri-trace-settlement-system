/* 按「怎么用」主线逐步落地，每步截一张图 + 记录该页真实数据情况
   用途：① 检验引导里的每一步点进去是不是有东西可做（空页面=引导在骗人）② 手册配图 */
const path = require('path')
const fs = require('fs')
const BASE = 'http://127.0.0.1:5173'
const API = 'http://127.0.0.1:8080'
const OUT = path.join(__dirname, '..', 'docs', 'reform_2026-11-11', 'shots7')
fs.mkdirSync(OUT, { recursive: true })
const G = path.join(process.env.APPDATA, 'npm', 'node_modules')
module.paths.push(G)
const { chromium } = require(path.join(G, 'playwright'))
const sleep = (ms) => new Promise(r => setTimeout(r, ms))

const ROLES = [
  { key: 'ADMIN', user: 'admin', home: '/admin/dashboard', label: '管理员' },
  { key: 'FARMER', user: 'farmer24', home: '/farmer/workbench', label: '农户' },
  { key: 'PURCHASER', user: 'purchaser01', home: '/purchaser/demands', label: '采购员' },
  { key: 'INSPECTOR', user: 'inspector01', home: '/inspector/tasks', label: '质检配送员' }
]

async function login(u, role) {
  const res = await fetch(API + '/api/auth/login', {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username: u, password: '123456', expectedRole: role })
  })
  return (await res.json()).data
}

const probe = (p) => p.evaluate(() => {
  const txt = (s) => (document.querySelector(s)?.innerText || '').trim().replace(/\s+/g, ' ')
  return {
    title: txt('.page-title'),
    sub: txt('.page-sub'),
    rows: document.querySelectorAll('.el-table__body tbody tr').length,
    empty: txt('.el-table__empty-text') || txt('.empty'),
    buttons: [...document.querySelectorAll('.page-actions button')].map(b => (b.innerText || '').trim()).filter(Boolean),
    chip: txt('.lay-step')
  }
})

;(async () => {
  const browser = await chromium.launch({ channel: 'msedge' })
  const out = []
  for (const r of ROLES) {
    const d = await login(r.user, r.key)
    const flow = await (await fetch(API + '/api/common/my-flow', {
      headers: { Authorization: 'Bearer ' + d.token }
    })).json()

    const c = await browser.newContext({ viewport: { width: 1500, height: 940 } })
    await c.addInitScript(([t, u, rn, mn]) => {
      sessionStorage.setItem('ct_token', t)
      sessionStorage.setItem('ct_user', u)
      sessionStorage.setItem('ct_role_name', rn)
      sessionStorage.setItem('ct_menus', mn)
    }, [d.token, JSON.stringify(d.user), d.roleName || '', JSON.stringify(d.menus || [])])
    const p = await c.newPage()
    await p.goto(BASE + r.home, { waitUntil: 'domcontentloaded' })
    await sleep(4200)
    await p.screenshot({ path: path.join(OUT, `${r.key}-0-首页与流程.png`) })

    for (const st of flow.data.steps) {
      await p.goto(BASE + st.path, { waitUntil: 'domcontentloaded' })
      await sleep(3200)
      const info = await probe(p)
      const file = `${r.key}-${st.no}-${st.title.replace(/[\/\s]+/g, '')}.png`
      await p.screenshot({ path: path.join(OUT, file) })
      out.push({ role: r.label, no: st.no, title: st.title, count: st.countText, path: st.path, file, ...info })
      const flag = info.rows === 0 ? ' ⚠空表' : ''
      console.log(`${r.label} 第${st.no}步 ${st.title} → ${info.rows} 行${flag} | ${info.empty || ''}`)
    }
    await c.close()
  }
  await browser.close()
  fs.writeFileSync(path.join(OUT, 'walk7.json'), JSON.stringify(out, null, 2), 'utf8')
})()
