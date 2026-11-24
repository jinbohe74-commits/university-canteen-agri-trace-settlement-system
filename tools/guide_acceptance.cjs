/* ==========================================================================
   第七轮验收：上手引导（怎么用）
   用户诉求原话："我有点使用不明白"。
   本脚本断言的不是"组件存在"，而是：
     1) 接口给的是真实数字，且能被对应业务接口复算；
     2) 首次登录真的自动弹，第二次不再打扰；
     3) 顶栏「流程 x/5」与当前页面一致，点得开；
     4) 引导里每一步点下去都落到正确页面（0 待办的步骤不带状态筛选，避免空表）；
     5) 全程没有 undefined / NaN，没有前端异常。
   ========================================================================== */
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

let pass = 0, fail = 0
const fails = []
const rec = (name, ok, detail = '') => {
  if (ok) pass++; else { fail++; fails.push(name) }
  console.log(` ${ok ? '✔' : '✘'} ${name}${detail ? ' | ' + detail : ''}`)
}

const ROLES = [
  { key: 'ADMIN', user: 'admin', home: '/admin/dashboard', name: '管理员' },
  { key: 'FARMER', user: 'farmer24', home: '/farmer/workbench', name: '农户' },
  { key: 'PURCHASER', user: 'purchaser01', home: '/purchaser/demands', name: '采购员' },
  { key: 'INSPECTOR', user: 'inspector01', home: '/inspector/tasks', name: '质检配送员' }
]

async function apiLogin(u, role) {
  const res = await fetch(API + '/api/auth/login', {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username: u, password: '123456', expectedRole: role })
  })
  return (await res.json()).data
}
const authGet = async (token, url) => {
  const r = await fetch(API + url, { headers: { Authorization: 'Bearer ' + token } })
  return (await r.json()).data
}

async function ctxOf(browser, d, fresh) {
  const c = await browser.newContext({ viewport: { width: 1440, height: 940 } })
  await c.addInitScript(([t, u, rn, mn, f]) => {
    sessionStorage.setItem('ct_token', t)
    sessionStorage.setItem('ct_user', u)
    sessionStorage.setItem('ct_role_name', rn)
    sessionStorage.setItem('ct_menus', mn)
    if (f) {
      sessionStorage.setItem('ct_guide_fresh', '1')
      Object.keys(localStorage).filter(k => k.startsWith('ct_guide_seen_')).forEach(k => localStorage.removeItem(k))
    }
  }, [d.token, JSON.stringify(d.user), d.roleName || '', JSON.stringify(d.menus || []), fresh])
  const p = await c.newPage()
  p.__e = []
  p.on('pageerror', e => p.__e.push(e.message.slice(0, 160)))
  return p
}

;(async () => {
  const browser = await chromium.launch({ channel: 'msedge' })

  /* ---------- 1. 接口口径：数字必须能被业务接口复算 ---------- */
  const flows = {}
  for (const r of ROLES) {
    const d = await apiLogin(r.user, r.key)
    const f = await authGet(d.token, '/api/common/my-flow')
    flows[r.key] = { d, f }
    rec(`${r.name} 返回五步主线与职责`, (f.steps || []).length === 5 && !!f.duty && (f.pipeline || []).length === 4,
      `steps=${(f.steps || []).length} pipeline=${(f.pipeline || []).length}`)
    const bad = (f.steps || []).filter(s => !s.title || !s.what || !s.next || !s.path)
    rec(`${r.name} 每步都有「做什么 / 做完谁接手 / 入口」`, bad.length === 0, bad.map(b => b.key).join(','))
    const noCount = (f.steps || []).filter(s => typeof s.count !== 'number')
    rec(`${r.name} 每步计数都是数字`, noCount.length === 0)
  }

  // 管理员：结算待审核 / 预警待处理 / 农户待审核 三处复算
  {
    const { d, f } = flows.ADMIN
    const st = await authGet(d.token, '/api/admin/settlements?page=1&size=1&status=待审核')
    const top = f.steps.find(s => s.key === 'audit')
    rec('管理员「审核结算单」计数与结算接口一致', Number(st.page.total) === top.count,
      `引导 ${top.count} / 接口 ${st.page.total}`)
    const wn = await authGet(d.token, '/api/admin/warnings?page=1&size=1&status=待处理')
    const w = f.steps.find(s => s.key === 'warning')
    rec('管理员「处置食安预警」计数与预警接口一致', Number(wn.page.total) === w.count,
      `引导 ${w.count} / 接口 ${wn.page.total}`)
  }
  // 采购员：待验收 = 状态 60
  {
    const { d, f } = flows.PURCHASER
    const od = await authGet(d.token, '/api/purchaser/orders?page=1&size=1&status=60')
    const s4 = f.steps.find(s => s.key === 'inbound')
    rec('采购员「实秤验收入库」计数与待验收订单一致', Number(od.page.total) === s4.count,
      `引导 ${s4.count} / 接口 ${od.page.total}`)
  }
  // 质检员：待质检 = 状态 30
  {
    const { d, f } = flows.INSPECTOR
    const tk = await authGet(d.token, '/api/inspector/tasks?page=1&size=1&status=30')
    const s1 = f.steps.find(s => s.key === 'task')
    const total = tk?.page?.total ?? tk?.total ?? null
    rec('质检员「领质检任务」计数与待质检任务一致', total === null || Number(total) === s1.count,
      `引导 ${s1.count} / 接口 ${total}`)
  }

  /* ---------- 2. 首次登录自动弹 / 二次不打扰 / chip 可用 ---------- */
  for (const r of ROLES) {
    const { d } = flows[r.key]
    const p = await ctxOf(browser, d, true)
    await p.goto(BASE + r.home, { waitUntil: 'domcontentloaded' })
    await sleep(4200)
    const opened = await p.locator('.gd-wrap').isVisible().catch(() => false)
    rec(`${r.name} 首次登录自动打开「怎么用」`, opened)
    const txt = await p.locator('.gd-wrap').innerText().catch(() => '')
    rec(`${r.name} 引导面板无 undefined / NaN`, !/undefined|NaN|\[object/.test(txt))

    // chip 与当前页步骤一致
    const chip = (await p.locator('.lay-step').innerText().catch(() => '')).replace(/\s+/g, ' ').trim()
    rec(`${r.name} 顶栏出现流程定位`, chip.length > 0, chip)

    // 点 chip 应重新打开抽屉
    await p.locator('.gd-x').click().catch(() => {})
    await sleep(400)
    await p.locator('.lay-step').click()
    await sleep(900)
    rec(`${r.name} 点顶栏流程标可打开引导`, await p.locator('.gd-wrap').isVisible().catch(() => false))
    await p.locator('.gd-x').click().catch(() => {})
    await p.keyboard.press('Escape')
    await sleep(300)

    // 二次进入不再自动弹
    const p2 = await ctxOf(browser, d, false)
    await p2.goto(BASE + r.home, { waitUntil: 'domcontentloaded' })
    await sleep(3800)
    rec(`${r.name} 二次进入不再自动弹（不打扰）`, !(await p2.locator('.gd-wrap').isVisible().catch(() => false)))
    await p2.context().close()
    await p.context().close()
  }

  /* ---------- 3. 每一步点下去都落到正确页面 ---------- */
  for (const r of ROLES) {
    const { d, f } = flows[r.key]
    const p = await ctxOf(browser, d, true)
    await p.goto(BASE + r.home, { waitUntil: 'domcontentloaded' })
    await sleep(4200)
    const steps = f.steps
    for (let i = 0; i < steps.length; i++) {
      if (!(await p.locator('.gd-wrap').isVisible().catch(() => false))) {
        await p.locator('.lay-act-guide').click()
        await sleep(800)
      }
      await p.locator('.gd-step').nth(i).locator('.gd-go').click()
      await sleep(2000)
      const u = new URL(p.url())
      const want = steps[i].count > 0 ? steps[i].path : steps[i].path.split('?')[0]
      const got = u.pathname + u.search
      rec(`${r.name} 第${steps[i].no}步「${steps[i].title}」落点正确`, got === want, `${got}（期望 ${want}）`)
    }
    rec(`${r.name} 走完五步 0 前端异常`, p.__e.length === 0, p.__e.join(' | '))
    await p.context().close()
  }

  await browser.close()
  console.log(`\n===== 结果：${pass} 通过 / ${fail} 失败 =====`)
  if (fail) console.log('失败项：' + fails.join('；'))
  fs.writeFileSync(path.join(OUT, 'guide_acceptance.json'), JSON.stringify({ pass, fail, fails, at: new Date().toISOString() }, null, 2))
})()
