/* 横向溢出探针：找出「操作列被挤出可视区」的表格（1440×900） */
const path = require('path'); const fs = require('fs')
const ROOT = path.resolve(__dirname, '..'); const BASE = 'http://127.0.0.1:5173'; const API = 'http://127.0.0.1:8080'
const OUT = path.join(ROOT, 'docs', 'roleplay_2026-11-10')
const G = path.join(process.env.APPDATA, 'npm', 'node_modules'); module.paths.push(G)
const { chromium } = require(path.join(G, 'playwright'))
const sleep = ms => new Promise(r => setTimeout(r, ms))
async function login(u, r) {
  const res = await fetch(API + '/api/auth/login', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ username: u, password: '123456', expectedRole: r }) })
  return (await res.json()).data
}
const PAGES = {
  ADMIN: ['/admin/dashboard', '/admin/farmers', '/admin/canteens', '/admin/products', '/admin/users', '/admin/orders', '/admin/settlements', '/admin/warnings', '/admin/trace', '/admin/config', '/admin/logs'],
  FARMER: ['/farmer/workbench', '/farmer/sources', '/farmer/orders', '/farmer/settlement', '/farmer/credit', '/farmer/messages'],
  PURCHASER: ['/purchaser/demands', '/purchaser/sources', '/purchaser/match', '/purchaser/orders', '/purchaser/inbound', '/purchaser/evaluation'],
  INSPECTOR: ['/inspector/tasks', '/inspector/quality', '/inspector/delivery', '/inspector/exception']
}
const ACC = { ADMIN: 'admin', FARMER: 'farmer01', PURCHASER: 'purchaser01', INSPECTOR: 'inspector01' }
;(async () => {
  const b = await chromium.launch({ channel: 'msedge' })
  const bad = []
  for (const role of Object.keys(PAGES)) {
    const d = await login(ACC[role], role)
    const ctx = await b.newContext({ viewport: { width: 1440, height: 900 } })
    await ctx.addInitScript(([t, u, rn, mn]) => { sessionStorage.setItem('ct_token', t); sessionStorage.setItem('ct_user', u); sessionStorage.setItem('ct_role_name', rn); sessionStorage.setItem('ct_menus', mn) }, [d.token, JSON.stringify(d.user), d.roleName || '', JSON.stringify(d.menus || [])])
    const p = await ctx.newPage()
    for (const url of PAGES[role]) {
      await p.goto(BASE + url, { waitUntil: 'domcontentloaded' }); await sleep(1500)
      const r = await p.evaluate(() => {
        const out = []
        document.querySelectorAll('.el-table').forEach(t => {
          const body = t.querySelector('.el-scrollbar__wrap') || t.querySelector('.el-table__body-wrapper')
          if (!body) return
          const over = body.scrollWidth - body.clientWidth
          // 表头最右列是否超出可视区
          const cols = [...t.querySelectorAll('.el-table__header th')].map(th => th.innerText.trim()).filter(Boolean)
          out.push({ over, cols, visibleRight: body.getBoundingClientRect().right, tableRight: t.getBoundingClientRect().right })
        })
        return out
      })
      r.forEach(x => {
        if (x.over > 2) {
          bad.push({ role, url, over: x.over, cols: x.cols })
          console.log(`  ✘ ${url} 横向溢出 ${x.over}px  列:${x.cols.join('/')}`)
        } else if (x.cols.length) {
          console.log(`  ✔ ${url} 无溢出  列:${x.cols.join('/')}`)
        }
      })
    }
    await ctx.close()
  }
  fs.writeFileSync(path.join(OUT, 'overflow.json'), JSON.stringify(bad, null, 2), 'utf8')
  console.log('\n横向溢出表格 ' + bad.length + ' 处')
  await b.close()
})().catch(e => { console.error(e); process.exit(1) })
