/**
 * 易用性量化探针：把"感觉难用"变成可数指标
 * 指标：首屏主操作是否可见 / 表格列数 / 横向溢出 / 页面高度倍数 / 小字号占比 /
 *      筛选条占首屏比 / 英文状态码 / 空状态引导 / 每页条数 / 可点按钮数
 */
const { chromium } = require('playwright')
const fs = require('fs')

const BASE = 'http://127.0.0.1:5173'
const API = 'http://127.0.0.1:8080'

const ACCOUNTS = {
  ADMIN: 'admin',
  FARMER: 'farmer24',
  PURCHASER: 'purchaser01',
  INSPECTOR: 'inspector01'
}

const ROUTES = {
  ADMIN: ['/admin/dashboard', '/admin/farmers', '/admin/canteens', '/admin/products', '/admin/users',
    '/admin/orders', '/admin/settlements', '/admin/warnings', '/admin/trace', '/admin/config', '/admin/logs'],
  FARMER: ['/farmer/workbench', '/farmer/sources', '/farmer/orders', '/farmer/settlement', '/farmer/credit', '/farmer/messages'],
  PURCHASER: ['/purchaser/demands', '/purchaser/sources', '/purchaser/match', '/purchaser/orders', '/purchaser/inbound', '/purchaser/evaluation', '/purchaser/trace'],
  INSPECTOR: ['/inspector/tasks', '/inspector/quality', '/inspector/delivery', '/inspector/exception', '/inspector/trace']
}

async function apiLogin(username) {
  const r = await fetch(API + '/api/auth/login', {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username, password: '123456' })
  })
  const j = await r.json()
  if (j.code !== 200) throw new Error('登录失败 ' + username + ': ' + j.message)
  return j.data
}

async function makeCtx(browser, data) {
  const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 } })
  await ctx.addInitScript(([t, u, rn, mn]) => {
    sessionStorage.setItem('ct_token', t)
    sessionStorage.setItem('ct_user', u)
    sessionStorage.setItem('ct_role_name', rn)
    sessionStorage.setItem('ct_menus', mn)
  }, [data.token, JSON.stringify(data.user), data.roleName || '', JSON.stringify(data.menus || [])])
  return ctx
}

// 页面内采集
function collect() {
  const vh = window.innerHeight
  const vw = window.innerWidth
  const vis = (el) => {
    const r = el.getBoundingClientRect()
    const cs = getComputedStyle(el)
    return r.width > 0 && r.height > 0 && cs.visibility !== 'hidden' && cs.display !== 'none' && r.bottom > 0
  }
  const inFirstScreen = (el) => {
    const r = el.getBoundingClientRect()
    return r.top >= 0 && r.bottom <= vh + 4
  }
  const txt = (el) => (el.innerText || el.textContent || '').trim().replace(/\s+/g, ' ')

  // 主操作按钮（Element Plus primary / danger 的实心按钮）
  const btns = Array.from(document.querySelectorAll('.el-button')).filter(vis)
  const primaries = btns.filter(b => {
    const c = b.className || ''
    return /el-button--primary|el-button--success|el-button--warning|el-button--danger/.test(c) && !b.disabled
  }).map(b => ({ text: txt(b).slice(0, 20), inFirst: inFirstScreen(b), top: Math.round(b.getBoundingClientRect().top) }))

  // 表格
  const tables = Array.from(document.querySelectorAll('.el-table')).filter(vis).map(t => {
    const headCells = Array.from(t.querySelectorAll('.el-table__header th')).filter(h => txt(h)).length
    const wrap = t.querySelector('.el-scrollbar__wrap, .el-table__body-wrapper')
    const overflowX = wrap ? wrap.scrollWidth > wrap.clientWidth + 4 : false
    const rows = t.querySelectorAll('.el-table__body tr').length
    return { cols: headCells, overflowX, rows }
  })

  // 字号分布
  const all = Array.from(document.querySelectorAll('body *')).filter(e => {
    if (!vis(e)) return false
    return Array.from(e.childNodes).some(n => n.nodeType === 3 && n.textContent.trim().length > 0)
  })
  let tiny = 0
  const tinySamples = []
  all.forEach(e => {
    const fs = parseFloat(getComputedStyle(e).fontSize)
    if (fs < 12) { tiny++; if (tinySamples.length < 4) tinySamples.push(txt(e).slice(0, 14) + '@' + fs) }
  })

  // 筛选条高度占首屏比
  const filterBar = document.querySelector('.toolbar, .filter-bar, .page-toolbar, .search-bar')
  const filterH = filterBar && vis(filterBar) ? Math.round(filterBar.getBoundingClientRect().height) : 0

  // 英文状态码 / 大写枚举
  const bodyText = document.body.innerText || ''
  const enumHits = (bodyText.match(/\b(PENDING|APPROVED|REJECTED|ACTIVE|DISABLED|WAIT\w*|NORMAL|FINISHED|PAID)\b/g) || [])
  const enumUniq = Array.from(new Set(enumHits))

  // 空状态
  const emptyText = txt(document.querySelector('.el-table__empty-text, .empty-state, .el-empty') || { innerText: '' })
  const emptyEl = document.querySelector('.el-table__empty-text, .el-empty')

  // 分页
  const pager = document.querySelector('.el-pagination')
  const pageSize = pager ? (txt(pager).match(/条\/页|(\d+)\s*条/) || [])[0] : null

  // 一屏内可见文字量（信息密度）
  const firstScreenText = Array.from(document.querySelectorAll('body *'))
    .filter(e => vis(e) && inFirstScreen(e) && /^(P|SPAN|H1|H2|H3|DIV|TD|TH|LABEL)$/.test(e.tagName))
    .filter(e => Array.from(e.childNodes).some(n => n.nodeType === 3 && n.textContent.trim()))
    .length

  return {
    h1: txt(document.querySelector('h1, .page-title, .page-header__title') || { innerText: '' }).slice(0, 40),
    pageHeight: document.documentElement.scrollHeight,
    screens: +(document.documentElement.scrollHeight / vh).toFixed(1),
    btns: btns.length,
    primaries,
    primaryInFirst: primaries.some(p => p.inFirst),
    tables,
    maxCols: tables.reduce((m, t) => Math.max(m, t.cols), 0),
    overflowX: tables.some(t => t.overflowX),
    tiny, tinySamples,
    filterH, filterRatio: +(filterH / vh * 100).toFixed(1),
    enumUniq,
    hasEmpty: !!emptyEl,
    emptyText: emptyText.slice(0, 60),
    pageSize,
    rowsOnPage: tables.reduce((m, t) => Math.max(m, t.rows), 0),
    firstScreenNodes: firstScreenText,
    hasSearch: !!document.querySelector('input[placeholder*="搜索"], input[placeholder*="输入"], .el-input__inner'),
    bodyChars: bodyText.replace(/\s/g, '').length
  }
}

;(async () => {
  const browser = await chromium.launch({ channel: 'msedge' })
  const out = { pages: [], summary: {} }
  fs.mkdirSync('docs/ux_2026-11-10/shots', { recursive: true })

  for (const role of Object.keys(ROUTES)) {
    const data = await apiLogin(ACCOUNTS[role])
    const ctx = await makeCtx(browser, data)
    const page = await ctx.newPage()
    for (const route of ROUTES[role]) {
      const errs = []
      page.on('console', m => { if (m.type() === 'error') errs.push(m.text().slice(0, 100)) })
      await page.goto(BASE + route, { waitUntil: 'domcontentloaded' })
      await page.waitForTimeout(1500)
      let m = {}
      try { m = await page.evaluate(collect) } catch (e) { m = { error: e.message } }
      const name = role + '_' + route.split('/').pop()
      await page.screenshot({ path: `docs/ux_2026-11-10/shots/${name}.png` })
      out.pages.push({ role, route, ...m, consoleErrors: errs.length })
      console.log(
        (role + ' ' + route).padEnd(30),
        '屏' + String(m.screens || 0).padEnd(5),
        '列' + String(m.maxCols || 0).padEnd(3),
        (m.overflowX ? '横溢!' : '  ').padEnd(4),
        '小字' + String(m.tiny || 0).padEnd(4),
        '首屏主操' + (m.primaryInFirst ? '有' : '无').padEnd(3),
        '筛选%' + String(m.filterRatio || 0).padEnd(5),
        '空态' + (m.hasEmpty ? '有' : '无').padEnd(3),
        '枚举' + (m.enumUniq || []).join(',').slice(0, 30)
      )
      page.removeAllListeners('console')
    }
    await ctx.close()
  }

  fs.writeFileSync('docs/ux_2026-11-10/ux_probe.json', JSON.stringify(out, null, 2))
  await browser.close()
  const P = out.pages
  console.log('\n===== 汇总 =====')
  console.log('页面数', P.length,
    '| 无首屏主操作', P.filter(p => !p.primaryInFirst).length,
    '| 横向溢出', P.filter(p => p.overflowX).length,
    '| 列数>8', P.filter(p => p.maxCols > 8).length,
    '| 超过3屏', P.filter(p => p.screens > 3).length,
    '| 小字>20处', P.filter(p => p.tiny > 20).length,
    '| 出现英文枚举', P.filter(p => (p.enumUniq || []).length).length,
    '| 控制台报错', P.filter(p => p.consoleErrors).length)
})()
