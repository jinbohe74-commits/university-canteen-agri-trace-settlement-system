/* 「下一步入口是否在可视区」探针：
   判据不是 scrollWidth，而是「操作列（含末列）右边界是否超出容器可视右边界」。
   fixed="right" 的列永远可见，不算问题。 */
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
  ADMIN: ['/admin/farmers', '/admin/canteens', '/admin/products', '/admin/users', '/admin/orders', '/admin/settlements', '/admin/warnings', '/admin/logs'],
  FARMER: ['/farmer/sources', '/farmer/orders', '/farmer/settlement'],
  PURCHASER: ['/purchaser/demands', '/purchaser/sources', '/purchaser/orders', '/purchaser/inbound'],
  INSPECTOR: ['/inspector/tasks', '/inspector/quality', '/inspector/delivery']
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
      await p.goto(BASE + url, { waitUntil: 'domcontentloaded' }); await sleep(1600)
      const r = await p.evaluate(() => {
        const res = []
        document.querySelectorAll('.el-table').forEach(t => {
          const wrap = t.querySelector('.el-scrollbar__wrap') || t.querySelector('.el-table__body-wrapper')
          const ths = [...t.querySelectorAll('.el-table__header th')]
          if (!wrap || !ths.length) return
          const wrapRight = wrap.getBoundingClientRect().right
          const last = ths[ths.length - 1]
          const fixedRight = last.className.includes('is-last-column') && !!t.querySelector('.el-table-fixed-column--right')
          const lastRight = last.getBoundingClientRect().right
          // 末列里的链接文字，判断是不是「操作」类
          const lastLabel = last.innerText.trim()
          res.push({ lastLabel, cut: lastRight > wrapRight + 1, hidden: lastRight > wrapRight + 40, fixedRight, over: wrap.scrollWidth - wrap.clientWidth })
        })
        return res
      })
      for (const x of r) {
        const tag = x.hidden ? '整列看不见' : x.cut ? '部分被裁' : 'ok'
        if (x.hidden || x.cut) {
          bad.push({ url, ...x })
          console.log(`  ✘ ${url} 末列「${x.lastLabel}」${tag}${x.fixedRight ? '(已固定)' : ''}`)
        } else console.log(`  ✔ ${url} 末列「${x.lastLabel}」完整可见`)
      }
    }
    await ctx.close()
  }
  fs.writeFileSync(path.join(OUT, 'actioncol.json'), JSON.stringify(bad, null, 2), 'utf8')
  console.log('\n末列被裁 ' + bad.length + ' 处')
  await b.close()
})().catch(e => { console.error(e); process.exit(1) })
