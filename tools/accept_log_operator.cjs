/* 验收：操作日志「操作人」显示真实姓名（不再出现未知）
   运行：node tools/accept_log_operator.cjs
   依赖：后端 8080 / 前端 5173 已启动 */
const path = require('path')
const fs = require('fs')
const GLOBAL = path.join(process.env.APPDATA, 'npm', 'node_modules')
module.paths.push(GLOBAL)
const { chromium } = require(path.join(GLOBAL, 'playwright'))

const API = 'http://127.0.0.1:8080/api'
const BASE = 'http://127.0.0.1:5173'
const SHOT = path.join(__dirname, '..', 'docs', 'fix_2026-11-16')
const sleep = (ms) => new Promise((r) => setTimeout(r, ms))
const fails = []
function check(name, ok, detail = '') {
  console.log((ok ? 'PASS' : 'FAIL') + ' | ' + name + ' | ' + detail)
  if (!ok) fails.push(name)
}

;(async () => {
  const login = await fetch(API + '/auth/login', {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username: 'admin', password: '123456', expectedRole: 'ADMIN' })
  })
  const d = (await login.json()).data
  if (!d?.token) { console.log('登录失败'); process.exit(1) }

  const b = await chromium.launch({ channel: 'msedge', headless: true })
  const ctx = await b.newContext({ viewport: { width: 1440, height: 900 }, deviceScaleFactor: 2 })
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

  await p.goto(BASE + '/admin/logs', { waitUntil: 'domcontentloaded' })
  await sleep(3200)

  // 逐行读「操作人」列（第 2 列：主文案 + 角色小字）
  const readRows = () => p.evaluate(() => {
    const rows = [...document.querySelectorAll('.el-table__body .el-table__row')]
    return rows.map((tr) => {
      const cells = tr.querySelectorAll('td')
      const c = cells[1]
      if (!c) return null
      return {
        name: (c.querySelector('.cell-main')?.innerText || '').trim(),
        role: (c.querySelector('.cell-sub')?.innerText || '').trim()
      }
    }).filter(Boolean)
  })

  const rows = await readRows()
  check('L1 列表已渲染', rows.length >= 5, rows.length + ' 行')

  const row0 = rows[0] || {}
  check('L2 首行操作人为真实姓名', row0.name === '王管理', '实际：' + row0.name)
  check('L3 首行角色为中文角色名', row0.role === '系统管理员', '实际：' + row0.role)

  const unknown = rows.filter((r) => !r.name || r.name === '未知' || r.name === '—')
  check('L4 当前页无「未知 / 空」操作人', unknown.length === 0,
    unknown.length ? '仍有 ' + unknown.length + ' 行' : '全部有值')

  const dashRole = rows.filter((r) => r.role === '—' || !r.role)
  check('L5 角色列不再整列为「—」', dashRole.length <= rows.length * 0.2,
    dashRole.length + '/' + rows.length + ' 行为空角色')

  // 全文不应再出现「未知」
  const bodyText = await p.evaluate(() => document.querySelector('.table-wrap')?.innerText || '')
  check('L6 表格文本中不含「未知」', !bodyText.includes('未知'))

  await p.screenshot({ path: path.join(SHOT, 'logs_after.png'), fullPage: false })

  // 按真实姓名搜索：回填后可检索（以前搜「王管理」搜不到登录日志）
  const box = p.locator('.filter-bar input[placeholder*="操作人"]').first()
  await box.fill('王管理')
  await sleep(1600)
  const searched = await readRows()
  check('L7 按真实姓名「王管理」可检索到登录日志',
    searched.length > 0 && searched.every((r) => r.name === '王管理'),
    searched.length + ' 行，首行=' + (searched[0]?.name || '无'))

  // 关键词搜「未知」应为 0 条
  await box.fill('未知')
  await sleep(1600)
  const emptyText = await p.evaluate(() => document.querySelector('.table-wrap .empty')?.innerText.trim() || '')
  const afterRows = await readRows()
  check('L8 关键词「未知」查不到记录', afterRows.length === 0, emptyText || afterRows.length + ' 行')

  await p.screenshot({ path: path.join(SHOT, 'logs_search_after.png'), fullPage: false })

  check('L9 无 JS 报错', errors.length === 0, errors.slice(0, 3).join(' || '))
  await b.close()
  console.log(fails.length ? '\n失败 ' + fails.length + ' 项：' + fails.join(', ') : '\n全部通过（9/9）')
  process.exit(fails.length ? 1 : 0)
})()
