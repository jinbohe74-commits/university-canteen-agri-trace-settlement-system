/* ==========================================================================
   全站体检（2026-11-11 第十一轮）
   --------------------------------------------------------------------------
   用户连续多轮反馈的问题其实是同一批病，这里把它们变成可量化的检查：
     1. JS 报错 / 接口 4xx-5xx          → 页面自身坏了
     2. 表格横向溢出却没有滚动条        → "有些表格不能向右看"
     3. 文字被 ellipsis 截断            → "列挤到看不全"
     4. 首屏大面积空白 / 只有"暂无"     → "方框只能看"
     5. 可点击样式但点击无任何变化      → "点了没反应"
     6. KPI 数字是否全 0                → "数字都是假的"
   输出 report.json + 控制台清单，供逐条修复。
   ========================================================================== */
const path = require('path')
const fs = require('fs')

const ROOT = path.resolve(__dirname, '..')
const BASE = 'http://127.0.0.1:5173'
const OUT = path.join(ROOT, 'docs', 'audit_2026-11-11')
fs.mkdirSync(OUT, { recursive: true })

const GLOBAL = path.join(process.env.APPDATA, 'npm', 'node_modules')
module.paths.push(GLOBAL)
const { chromium } = require(path.join(GLOBAL, 'playwright'))

const PAGES = {
  ADMIN: ['admin/dashboard', 'admin/farmers', 'admin/canteens', 'admin/products', 'admin/users',
    'admin/orders', 'admin/settlements', 'admin/warnings', 'admin/trace', 'admin/config', 'admin/logs'],
  FARMER: ['farmer/workbench', 'farmer/sources', 'farmer/demands', 'farmer/orders',
    'farmer/settlement', 'farmer/credit', 'farmer/messages'],
  PURCHASER: ['purchaser/demands', 'purchaser/sources', 'purchaser/match', 'purchaser/orders',
    'purchaser/inbound', 'purchaser/stock', 'purchaser/evaluation', 'purchaser/trace'],
  INSPECTOR: ['inspector/tasks', 'inspector/quality', 'inspector/delivery', 'inspector/exception', 'inspector/trace']
}
const ACCOUNTS = { ADMIN: 'admin', FARMER: 'farmer01', PURCHASER: 'purchaser01', INSPECTOR: 'inspector01' }

const sleep = ms => new Promise(r => setTimeout(r, ms))

async function apiLogin(username, expectedRole) {
  const r = await fetch('http://127.0.0.1:8080/api/auth/login', {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username, password: '123456', expectedRole })
  })
  const j = await r.json()
  if (j.code !== 200) throw new Error('登录失败 ' + username + ': ' + j.message)
  return j.data
}

/** 页面内取证的函数：一次 evaluate 把要量的东西全拿回来 */
const PROBE = () => {
  const vis = (el) => {
    const r = el.getBoundingClientRect()
    const s = getComputedStyle(el)
    return r.width > 0 && r.height > 0 && s.visibility !== 'hidden' && s.display !== 'none'
  }

  // 1. 表格：溢出但滚不动
  const tables = []
  document.querySelectorAll('.el-table').forEach((t, i) => {
    if (!vis(t)) return
    const wrap = t.querySelector('.el-scrollbar__wrap') || t.querySelector('.el-table__body-wrapper')
    let overflowX = 0, canScroll = false
    if (wrap) {
      overflowX = wrap.scrollWidth - wrap.clientWidth
      const s = getComputedStyle(wrap)
      canScroll = /auto|scroll/.test(s.overflowX) || !!t.querySelector('.el-table__body-wrapper .el-scrollbar__bar.is-horizontal')
    }
    // 固定列右侧的"操作"列是否被盖住：量最后一列右边界是否超出表格可视右边界
    const lastTh = t.querySelector('.el-table__header-wrapper th:last-child')
    let lastColClipped = false
    if (lastTh) {
      const tr = lastTh.getBoundingClientRect(), br = t.getBoundingClientRect()
      lastColClipped = tr.right > br.right + 2
    }
    tables.push({ i, overflowX, canScroll, lastColClipped })
  })

  // 2. 文字截断（只看可见、有文本、被 overflow 裁掉的）
  const clipped = []
  const walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT)
  const seenEl = new Set()
  let n
  while ((n = walker.nextNode())) {
    const txt = (n.nodeValue || '').trim()
    if (txt.length < 6) continue
    const el = n.parentElement
    if (!el || seenEl.has(el) || !vis(el)) continue
    seenEl.add(el)
    const s = getComputedStyle(el)
    if (!/hidden|clip/.test(s.overflow + s.overflowX)) continue
    if (el.scrollWidth > el.clientWidth + 4 && el.clientWidth > 0) {
      clipped.push({ text: txt.slice(0, 26), w: el.clientWidth, need: el.scrollWidth, tag: el.tagName.toLowerCase() })
    }
  }

  // 3. 可点击样式但可能无反应（只统计，不点击）
  const fakeClick = []
  document.querySelectorAll('*').forEach(el => {
    const s = getComputedStyle(el)
    if (s.cursor !== 'pointer') return
    if (!vis(el)) return
    if (el.closest('button, a, .el-button, .el-tabs__item, .el-pagination, input, select')) return
    if (el.__vueParentComponent) return // 有组件绑定的先跳过
    const txt = (el.innerText || '').trim().slice(0, 20)
    if (!txt) return
    fakeClick.push(txt)
  })

  // 4. KPI 数字
  const kpis = []
  document.querySelectorAll('.stat-card, .kpi-card, .stat-item').forEach(c => {
    if (!vis(c)) return
    const t = (c.innerText || '').replace(/\s+/g, ' ').trim()
    if (t) kpis.push(t.slice(0, 40))
  })

  // 5. 首屏空白：主内容区高度中，空白占比
  const main = document.querySelector('.app-main, .page-body, main')
  const bodyText = (document.body.innerText || '').replace(/\s+/g, '')

  return {
    tables, clipped: clipped.slice(0, 12), clippedTotal: clipped.length,
    fakeClick: [...new Set(fakeClick)].slice(0, 10),
    kpis, bodyLen: bodyText.length,
    emptyOnly: bodyText.length < 120
  }
}

;(async () => {
  const browser = await chromium.launch({ channel: 'msedge' })
  const report = { generatedAt: new Date().toISOString(), pages: [] }

  for (const role of Object.keys(PAGES)) {
    const data = await apiLogin(ACCOUNTS[role], role)
    const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 } })
    await ctx.addInitScript(([t, u, rn, mn]) => {
      sessionStorage.setItem('ct_token', t)
      sessionStorage.setItem('ct_user', u)
      sessionStorage.setItem('ct_role_name', rn)
      sessionStorage.setItem('ct_menus', mn)
    }, [data.token, JSON.stringify(data.user), data.roleName || '', JSON.stringify(data.menus || [])])

    for (const p of PAGES[role]) {
      const page = await ctx.newPage()
      const errs = [], bad = []
      page.on('console', m => { if (m.type() === 'error') errs.push(m.text().slice(0, 200)) })
      page.on('pageerror', e => errs.push('PAGEERROR: ' + e.message.slice(0, 200)))
      page.on('response', r => {
        const u = r.url()
        if (r.status() >= 400 && u.includes('/api/')) bad.push(`${r.status()} ${u.split('/api/')[1]}`)
      })
      let probe = null
      try {
        await page.goto(`${BASE}/${p}`, { waitUntil: 'domcontentloaded' })
        await sleep(2400)
        // 关掉可能存在的引导/公告弹窗，避免遮挡统计
        await page.keyboard.press('Escape').catch(() => {})
        await sleep(300)
        probe = await page.evaluate(PROBE)
      } catch (e) {
        errs.push('NAV: ' + e.message.slice(0, 120))
      }
      const row = { role, path: p, errs: [...new Set(errs)], badApi: [...new Set(bad)], ...(probe || {}) }
      report.pages.push(row)

      const flags = []
      if (row.errs.length) flags.push(`ERR×${row.errs.length}`)
      if (row.badApi?.length) flags.push(`API×${row.badApi.length}`)
      const badTables = (row.tables || []).filter(t => t.overflowX > 8 && !t.canScroll)
      if (badTables.length) flags.push(`表格溢出不可滚×${badTables.length}`)
      const clipCol = (row.tables || []).filter(t => t.lastColClipped)
      if (clipCol.length) flags.push(`末列被裁×${clipCol.length}`)
      if ((row.clippedTotal || 0) > 0) flags.push(`截断×${row.clippedTotal}`)
      if (row.emptyOnly) flags.push('页面几乎空白')
      if (row.kpis?.length && row.kpis.every(k => /[^\d]0[^\d]?$/.test(k) || /\s0\s/.test(k))) flags.push('KPI全0')

      console.log(`${flags.length ? '⚠️ ' : '✅'} ${role.padEnd(9)} ${p.padEnd(22)} ${flags.join(' ') || 'ok'}`)
      if (row.errs.length) console.log('     err:', row.errs.slice(0, 2).join(' | '))
      if (row.badApi?.length) console.log('     api:', row.badApi.slice(0, 3).join(' | '))
      if ((row.clippedTotal || 0) > 0) console.log('     clip:', (row.clipped || []).slice(0, 3).map(c => `${c.text}(${c.w}/${c.need})`).join(' ; '))
      if (row.fakeClick?.length) console.log('     pointer无绑定:', row.fakeClick.slice(0, 4).join(' / '))

      await page.close()
    }
    await ctx.close()
  }

  fs.writeFileSync(path.join(OUT, 'report.json'), JSON.stringify(report, null, 2), 'utf8')
  console.log('\n报告写入', path.join(OUT, 'report.json'))
  await browser.close()
})()
