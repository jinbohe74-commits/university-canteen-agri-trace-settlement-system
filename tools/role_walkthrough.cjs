/* ==========================================================================
   四角色真实使用走查（2026-11-11 第七轮）
   目的：用户反馈"我有点使用不明白"。先以真实角色登录，逐页记录
        ① 这页自己说清目的是吗（page-sub）
        ② 首屏有没有主操作
        ③ 空状态有没有指路
        ④ 有没有告诉我"做完这一步谁接手"
   产出 JSON + 每个角色首页截图，作为改造前基线。
   ========================================================================== */
const path = require('path')
const fs = require('fs')
const BASE = 'http://127.0.0.1:5173'
const API = 'http://127.0.0.1:8080'
const OUT = path.join(__dirname, '..', 'docs', 'reform_2026-11-11', 'walk_before')
fs.mkdirSync(OUT, { recursive: true })
const G = path.join(process.env.APPDATA, 'npm', 'node_modules')
module.paths.push(G)
const { chromium } = require(path.join(G, 'playwright'))
const sleep = (ms) => new Promise(r => setTimeout(r, ms))

const ROLES = [
  { key: 'ADMIN', user: 'admin', name: '王管理', home: '/admin/dashboard', label: '管理员' },
  { key: 'FARMER', user: 'farmer24', name: '张大山', home: '/farmer/workbench', label: '农户' },
  { key: 'PURCHASER', user: 'purchaser01', name: '李建国', home: '/purchaser/demands', label: '采购员' },
  { key: 'INSPECTOR', user: 'inspector01', name: '赵质检', home: '/inspector/tasks', label: '质检配送员' }
]

async function login(u, role) {
  const res = await fetch(API + '/api/auth/login', {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username: u, password: '123456', expectedRole: role })
  })
  const j = await res.json()
  if (!j.data) throw new Error('登录失败: ' + JSON.stringify(j))
  return j.data
}

async function open(browser, d, route) {
  const c = await browser.newContext({ viewport: { width: 1440, height: 940 } })
  await c.addInitScript(([t, u, rn, mn]) => {
    sessionStorage.setItem('ct_token', t)
    sessionStorage.setItem('ct_user', u)
    sessionStorage.setItem('ct_role_name', rn)
    sessionStorage.setItem('ct_menus', mn)
  }, [d.token, JSON.stringify(d.user), d.roleName || '', JSON.stringify(d.menus || [])])
  const p = await c.newPage()
  p.__e = []
  p.on('pageerror', e => p.__e.push(e.message.slice(0, 160)))
  await p.goto(BASE + route, { waitUntil: 'domcontentloaded' })
  await sleep(3000)
  return p
}

/* 页面自述情况：是否讲清了"这页干什么 / 我该点哪 / 做完给谁" */
async function audit(p) {
  return p.evaluate(() => {
    const q = (s) => document.querySelector(s)
    const txt = (s) => (q(s)?.innerText || '').trim().replace(/\s+/g, ' ')
    const btns = [...document.querySelectorAll('.page-actions button, .page-head button')]
      .map(b => (b.innerText || '').trim()).filter(Boolean)
    const primary = btns.filter(b => !/刷新|导出|重置/.test(b))
    const rows = document.querySelectorAll('.el-table__body tbody tr').length
    const emptyText = txt('.el-table__empty-text') || txt('.empty')
    const body = (document.body.innerText || '')
    return {
      title: txt('.page-title'),
      sub: txt('.page-sub'),
      actions: btns, primary,
      rows, emptyText: emptyText.slice(0, 60),
      // 页面上是否出现"上游/下一步/接手"这类流程语义
      flowHint: /下一步|上游|接手|完成后|步骤/.test(body),
      hasGuide: !!document.querySelector('.lay-flow-chip, .guide-drawer')
    }
  })
}

;(async () => {
  const browser = await chromium.launch({ channel: 'msedge' })
  const report = {}
  for (const r of ROLES) {
    const d = await login(r.user, r.key)
    const menus = (d.menus || []).flatMap(g => (g.children || []).map(c => ({ name: c.name, path: c.path })))
    const home = await open(browser, d, r.home)
    await home.screenshot({ path: path.join(OUT, `${r.key}-0-登录后首屏.png`), fullPage: false })
    const auditHome = await audit(home)
    const navCount = await home.evaluate(() => document.querySelectorAll('.lay-nav-item').length)
    await home.context().close()

    const pages = []
    for (const m of menus) {
      if (!m.path) continue
      const p = await open(browser, d, m.path)
      const a = await audit(p)
      a.path = m.path; a.menu = m.name; a.errors = p.__e.slice(0, 2)
      pages.push(a)
      await p.context().close()
    }
    report[r.key] = { label: r.label, user: r.user, navCount, home: auditHome, pages }
    const bad = pages.filter(p => !p.sub || p.primary.length === 0)
    console.log(`${r.label}(${r.user}) 菜单 ${navCount} 项 / 页面 ${pages.length} 页 / 无副标题或无主操作 ${bad.length} 页`)
  }
  await browser.close()
  fs.writeFileSync(path.join(OUT, 'report.json'), JSON.stringify(report, null, 2), 'utf8')

  // 生成可读摘要
  const lines = ['# 改造前：四角色走查基线', '']
  for (const [k, v] of Object.entries(report)) {
    lines.push(`## ${v.label}（${v.user}） · 菜单 ${v.navCount} 项`, '')
    lines.push('| 页面 | 路径 | 这页说明 | 首屏主操作 | 行数 | 空状态 | 流程提示 |')
    lines.push('|---|---|---|---|---|---|---|')
    for (const p of v.pages) {
      lines.push(`| ${p.menu} | ${p.path} | ${p.sub ? (p.sub.length > 34 ? p.sub.slice(0, 34) + '…' : p.sub) : '**无**'} | ${p.primary.length ? p.primary.slice(0, 3).join(' / ') : '**无**'} | ${p.rows} | ${p.emptyText || '—'} | ${p.flowHint ? '有' : '**无**'} |`)
    }
    lines.push('')
  }
  fs.writeFileSync(path.join(OUT, 'walkthrough.md'), lines.join('\n'), 'utf8')
  console.log('\n报告已写入 docs/reform_2026-11-11/walk_before/')
})()
