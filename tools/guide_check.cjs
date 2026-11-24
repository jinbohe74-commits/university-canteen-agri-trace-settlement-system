/* 引导可用性验证：① 首登自动弹 ② 顶栏流程 chip ③ 五步都在，点每一步真的落到对应页面 */
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
  { key: 'ADMIN', user: 'admin', home: '/admin/dashboard' },
  { key: 'FARMER', user: 'farmer24', home: '/farmer/workbench' },
  { key: 'PURCHASER', user: 'purchaser01', home: '/purchaser/demands' },
  { key: 'INSPECTOR', user: 'inspector01', home: '/inspector/tasks' }
]

async function login(u, role) {
  const res = await fetch(API + '/api/auth/login', {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username: u, password: '123456', expectedRole: role })
  })
  return (await res.json()).data
}

;(async () => {
  const browser = await chromium.launch({ channel: 'msedge' })
  for (const r of ROLES) {
    const d = await login(r.user, r.key)
    const c = await browser.newContext({ viewport: { width: 1440, height: 940 } })
    await c.addInitScript(([t, u, rn, mn, role]) => {
      sessionStorage.setItem('ct_token', t)
      sessionStorage.setItem('ct_user', u)
      sessionStorage.setItem('ct_role_name', rn)
      sessionStorage.setItem('ct_menus', mn)
      // 模拟"刚从登录页进来"的一次真会话，用于验证首次登录自动引导
      sessionStorage.setItem('ct_guide_fresh', '1')
      localStorage.removeItem('ct_guide_seen_' + role)
    }, [d.token, JSON.stringify(d.user), d.roleName || '', JSON.stringify(d.menus || []), r.key])
    const p = await c.newPage()
    const errs = []
    p.on('pageerror', e => errs.push(e.message.slice(0, 160)))
    await p.goto(BASE + r.home, { waitUntil: 'domcontentloaded' })
    await sleep(4200)

    const autoOpen = await p.locator('.gd-wrap').isVisible().catch(() => false)
    const chip = await p.locator('.lay-step').innerText().catch(() => '')
    console.log(`${r.key} 首登自动弹=${autoOpen} chip="${chip.replace(/\s+/g, ' ')}" err=${errs.length}`)
    if (autoOpen) await p.screenshot({ path: path.join(OUT, `${r.key}-1-怎么用.png`) })

    // 逐步点：每一步都应该离开抽屉并落到目标路径
    const steps = await p.locator('.gd-step').count()
    for (let i = 0; i < steps; i++) {
      if (!(await p.locator('.gd-wrap').isVisible().catch(() => false))) {
        await p.locator('.lay-act-guide').click()
        await sleep(700)
      }
      const btn = p.locator('.gd-step').nth(i).locator('.gd-go')
      const label = (await p.locator('.gd-step').nth(i).locator('.gd-title').innerText()).trim()
      await btn.click()
      await sleep(2200)
      const url = new URL(p.url()).pathname + new URL(p.url()).search
      const drawerGone = !(await p.locator('.gd-wrap').isVisible().catch(() => false))
      console.log(`   ${i + 1}. ${label} → ${url} ${drawerGone ? '' : '（抽屉未关闭）'}`)
    }
    await p.context().close()
  }
  await browser.close()
})()
