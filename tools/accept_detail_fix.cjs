/* 验收：「明细」点击无反馈 bug 的修复 + 卡片明细入口常显
   运行：node tools/accept_detail_fix.cjs
   依赖：后端 8080 / 前端 5173 已启动 */
const path = require('path')
const GLOBAL = path.join(process.env.APPDATA, 'npm', 'node_modules')
module.paths.push(GLOBAL)
const { chromium } = require(path.join(GLOBAL, 'playwright'))

const API = 'http://127.0.0.1:8080/api'
const BASE = 'http://127.0.0.1:5173'
const sleep = (ms) => new Promise((r) => setTimeout(r, ms))
const fails = []
function check(name, ok, detail = '') {
  console.log((ok ? 'PASS' : 'FAIL') + ' | ' + name + ' | ' + detail)
  if (!ok) fails.push(name)
}
const visibleDrawers = (p) => p.evaluate(() =>
  [...document.querySelectorAll('.el-drawer')].filter((e) => e.getClientRects().length).length)
const msgs = (p) => p.evaluate(() =>
  [...document.querySelectorAll('.el-message')].map((e) => e.innerText.trim()).join(' | '))
const closeAll = async (p) => {
  for (let i = 0; i < 4; i++) {
    if ((await visibleDrawers(p)) === 0 && !(await p.locator('.el-dialog:visible').count())) break
    await p.keyboard.press('Escape')
    await sleep(500)
  }
}

;(async () => {
  const login = await fetch(API + '/auth/login', {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username: 'admin', password: '123456', expectedRole: 'ADMIN' })
  })
  const d = (await login.json()).data
  if (!d?.token) { console.log('登录失败'); process.exit(1) }

  const b = await chromium.launch({ channel: 'msedge', headless: true })
  const ctx = await b.newContext({ viewport: { width: 1440, height: 900 } })
  await ctx.addInitScript(([t, u, rn, mn]) => {
    sessionStorage.setItem('ct_token', t)
    sessionStorage.setItem('ct_user', u)
    sessionStorage.setItem('ct_role_name', rn)
    sessionStorage.setItem('ct_menus', mn)
  }, [d.token, JSON.stringify(d.user), d.roleName || '', JSON.stringify(d.menus || [])])
  const p = await ctx.newPage()
  const errors = []
  p.on('pageerror', (e) => errors.push('PAGEERROR ' + String(e).slice(0, 180)))
  p.on('console', (m) => { if (m.type() === 'error') errors.push('CONSOLE ' + m.text().slice(0, 180)) })

  // ---------- A. 运营总览：明细入口常显 + 大卡可点 ----------
  await p.goto(BASE + '/admin/dashboard', { waitUntil: 'domcontentloaded' })
  await sleep(3000)
  const dbGo = await p.evaluate(() => [...document.querySelectorAll('.db-drill-go')].map((e) => ({
    text: e.innerText.trim(), opacity: Number(getComputedStyle(e).opacity)
  })))
  check('A1 看板「明细 ›」常显', dbGo.length > 0 && dbGo.every((x) => x.opacity >= 0.5),
    dbGo.length + ' 个，opacity=' + [...new Set(dbGo.map((x) => x.opacity))].join(','))
  const primaryGo = await p.evaluate(() => {
    const e = document.querySelector('.db-primary-go')
    if (!e) return null
    const r = e.getBoundingClientRect()
    const bar = document.querySelector('.db-track')?.getBoundingClientRect()
    return { text: e.innerText.trim(), opacity: Number(getComputedStyle(e).opacity), overlapsTrack: !!bar && !(r.right < bar.left || r.left > bar.right || r.bottom < bar.top || r.top > bar.bottom) }
  })
  check('A1b GMV 主卡也有明细提示且不压进度条', !!primaryGo && primaryGo.opacity >= 0.5 && !primaryGo.overlapsTrack,
    primaryGo ? primaryGo.text + ' opacity=' + primaryGo.opacity : '(缺失)')
  const beforeA = await visibleDrawers(p)
  await p.locator('.db-primary').first().click()
  await sleep(1600)
  const afterA = await visibleDrawers(p)
  check('A2 点大卡打开指标明细抽屉', afterA > beforeA, 'drawer ' + beforeA + '->' + afterA)
  // 实时刷新事件不得冲掉用户刚打开的抽屉（L-064 场景）
  await p.evaluate(() => window.dispatchEvent(new CustomEvent('canteen:data-changed', {
    detail: { operatorId: -999, ts: Date.now() }
  })))
  await sleep(1600)
  check('A3 实时刷新不关闭已打开的明细抽屉', (await visibleDrawers(p)) >= afterA,
    'drawer 仍为 ' + (await visibleDrawers(p)))
  await closeAll(p)

  // ---------- B. 档案/汇总类指标：点明细行必须给提示 ----------
  const minis = await p.$$('.db-mini')
  if (minis.length >= 3) {
    await minis[2].click()
    await sleep(1600)
    const title = await p.evaluate(() => document.querySelector('.el-drawer__title')?.innerText?.trim() || '')
    const rows = await p.locator('.sd-drawer .el-table__row').count()
    if (rows > 0) {
      await p.locator('.sd-drawer .el-table__row').first().click()
      await sleep(900)
      const m = await msgs(p)
      check('B1 档案类明细行点击有文字反馈', /档案|汇总|业务单号|完整列表/.test(m),
        '抽屉=' + title.slice(0, 20) + ' 提示=' + (m.slice(0, 70) || '(无)'))
    } else {
      console.log('SKIP | B1 该指标无明细行 | 抽屉=' + title.slice(0, 20))
    }
  } else {
    console.log('SKIP | B1 未找到 mini 卡')
  }
  await closeAll(p)

  // ---------- C. 有业务单号的指标：明细行 → 单据工作台 ----------
  await p.locator('.db-primary').first().click()
  await sleep(1600)
  const rowsC = await p.locator('.sd-drawer .el-table__row').count()
  if (rowsC > 0) {
    const beforeC = await visibleDrawers(p)
    await p.locator('.sd-drawer .el-table__row').first().click()
    await sleep(1800)
    const afterC = await visibleDrawers(p)
    const m = await msgs(p)
    check('C1 明细行下钻有结果（工作台或说明）', afterC > beforeC || m.length > 0,
      'drawer ' + beforeC + '->' + afterC + ' 提示=' + (m.slice(0, 60) || '(无)'))
  } else {
    console.log('SKIP | C1 大卡指标无明细行')
  }
  await closeAll(p)

  // ---------- D. 订单监管：StatCard 明细入口常显 + 可点 ----------
  await p.goto(BASE + '/admin/orders', { waitUntil: 'domcontentloaded' })
  await sleep(2600)
  const statGo = await p.evaluate(() => [...document.querySelectorAll('.stat-go')].map((e) => ({
    text: e.innerText.trim(), opacity: Number(getComputedStyle(e).opacity)
  })))
  check('D1 统计卡「明细 ›」常显', statGo.length > 0 && statGo.every((x) => x.opacity >= 0.5),
    statGo.length + ' 个，opacity=' + [...new Set(statGo.map((x) => x.opacity))].join(','))
  const beforeD = await visibleDrawers(p)
  await p.locator('.stat-card').first().click()
  await sleep(1600)
  const afterD = await visibleDrawers(p)
  const mD = await msgs(p)
  check('D2 点统计卡有响应', afterD > beforeD || mD.length > 0,
    'drawer ' + beforeD + '->' + afterD + ' 提示=' + (mD.slice(0, 50) || '(无)'))
  await closeAll(p)

  // ---------- E. 结算管理：行内「明细」弹窗正常 ----------
  await p.goto(BASE + '/admin/settlements', { waitUntil: 'domcontentloaded' })
  await sleep(2600)
  const link = p.locator('span.link', { hasText: '明细' }).first()
  if (await link.count()) {
    await link.click()
    await sleep(1800)
    const dlg = await p.evaluate(() => {
      const e = [...document.querySelectorAll('.el-dialog')].filter((x) => x.getClientRects().length)[0]
      return e ? e.innerText.replace(/\s+/g, ' ').slice(0, 120) : ''
    })
    const err = await p.locator('.dlg-error').count()
    check('E1 结算明细弹窗有内容且无错误块', dlg.length > 20 && err === 0, dlg.slice(0, 90))
  } else {
    check('E1 结算明细入口存在', false, '未找到「明细」链接')
  }
  await closeAll(p)

  // ---------- F. 全程无 JS 异常 ----------
  check('F1 全程无 JS 异常', errors.length === 0, errors.slice(0, 3).join(' / ') || '0 error')

  await b.close()
  console.log('\n===== 结果：' + (fails.length ? '失败 ' + fails.length + ' 项 → ' + fails.join(', ') : '全部通过') + ' =====')
  process.exit(fails.length ? 1 : 0)
})().catch((e) => { console.error('脚本异常:', e); process.exit(2) })
