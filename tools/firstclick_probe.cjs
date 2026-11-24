/* ==========================================================================
   第一反应探针（2026-11-10）
   一个真人打开系统，最先点的是：右上角待办、动态、Ctrl+K 搜索、侧栏每个菜单。
   这些地方没反应/没内容，就会形成「说不出来的难用」。本脚本逐个点，
   记录「点了以后发生了什么」和「空的时候系统说了什么」。
   ========================================================================== */
const path = require('path')
const fs = require('fs')
const ROOT = path.resolve(__dirname, '..')
const BASE = 'http://127.0.0.1:5173'
const API = 'http://127.0.0.1:8080'
const OUT = path.join(ROOT, 'docs', 'roleplay_2026-11-10')
fs.mkdirSync(path.join(OUT, 'shots2'), { recursive: true })

const GLOBAL = path.join(process.env.APPDATA, 'npm', 'node_modules')
module.paths.push(GLOBAL)
const { chromium } = require(path.join(GLOBAL, 'playwright'))
const sleep = ms => new Promise(r => setTimeout(r, ms))
const out = []
const rec = (k, v) => { out.push({ k, v }); console.log('  ' + k + ' → ' + JSON.stringify(v).slice(0, 300)) }

async function apiLogin(u, p = '123456', r) {
  const res = await fetch(API + '/api/auth/login', {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username: u, password: p, expectedRole: r })
  })
  const j = await res.json()
  return j.data
}

;(async () => {
  const browser = await chromium.launch({ channel: 'msedge' })
  const d = await apiLogin('admin')
  const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 } })
  await ctx.addInitScript(([t, u, rn, mn]) => {
    sessionStorage.setItem('ct_token', t); sessionStorage.setItem('ct_user', u)
    sessionStorage.setItem('ct_role_name', rn); sessionStorage.setItem('ct_menus', mn)
  }, [d.token, JSON.stringify(d.user), d.roleName || '', JSON.stringify(d.menus || [])])
  const page = await ctx.newPage()
  await page.goto(BASE + '/admin/dashboard', { waitUntil: 'domcontentloaded' })
  await sleep(1800)

  /* 1. 右上角「待办 4」点下去 */
  const todo = page.locator('.lay-act').filter({ hasText: '待办' }).first()
  if (await todo.count()) {
    await todo.click(); await sleep(900)
    await page.screenshot({ path: path.join(OUT, 'shots2', 'T1-todo-pop.png') })
    const panel = await page.evaluate(() => {
      const p = document.querySelector('.el-popover:not([style*="display: none"]), .el-dropdown__popper, .el-drawer')
      if (!p) return null
      return (p.innerText || '').replace(/\s+/g, ' ').slice(0, 400)
    })
    rec('点「待办4」', panel || '什么都没有弹出')
    await page.keyboard.press('Escape'); await sleep(400)
  } else rec('点「待办4」', '找不到待办入口')

  /* 2. 右上角「动态」 */
  const dyn = page.locator('.lay-act').filter({ hasText: '动态' }).first()
  if (await dyn.count()) {
    await dyn.click(); await sleep(900)
    await page.screenshot({ path: path.join(OUT, 'shots2', 'T2-dyn-pop.png') })
    const panel = await page.evaluate(() => {
      const p = document.querySelector('.el-popover:not([style*="display: none"]), .el-drawer')
      return p ? (p.innerText || '').replace(/\s+/g, ' ').slice(0, 400) : null
    })
    rec('点「动态」', panel || '什么都没有弹出')
    await page.keyboard.press('Escape'); await sleep(400)
  }

  /* 3. Ctrl+K 全局搜索：搜「王丰收」 */
  await page.keyboard.press('Control+k'); await sleep(700)
  await page.screenshot({ path: path.join(OUT, 'shots2', 'T3-search-open.png') })
  const searchOpen = await page.evaluate(() => {
    const el = document.querySelector('.gs-mask, .global-search, .el-dialog__wrapper:not([style*="display: none"])')
    return el ? (el.innerText || '').replace(/\s+/g, ' ').slice(0, 200) : null
  })
  rec('Ctrl+K 打开', searchOpen || '没有反应')
  if (searchOpen) {
    await page.keyboard.type('王丰收', { delay: 60 }); await sleep(1200)
    await page.screenshot({ path: path.join(OUT, 'shots2', 'T4-search-result.png') })
    const res = await page.evaluate(() => {
      const el = document.querySelector('.gs-mask, .global-search, .el-dialog__wrapper:not([style*="display: none"])')
      return el ? (el.innerText || '').replace(/\s+/g, ' ').slice(0, 500) : null
    })
    rec('搜「王丰收」', res || '无结果区域')
    await page.keyboard.press('Escape'); await sleep(400)
  }

  /* 4. 侧栏每个叶子菜单逐个点，记录：有没有内容、空状态说了什么 */
  const menuItems = await page.locator('.el-menu-item').all()
  const pages = []
  for (const mi of menuItems) {
    const label = (await mi.innerText().catch(() => '')).replace(/\s+/g, ' ').trim()
    if (!label) continue
    await mi.click().catch(() => {})
    await sleep(1000)
    const info = await page.evaluate(() => {
      const t = (s) => { const e = document.querySelector(s); return e ? (e.innerText || '').replace(/\s+/g, ' ').trim() : '' }
      const rows = document.querySelectorAll('.el-table__body tbody tr').length
      const cards = document.querySelectorAll('.el-card, .kpi, [class*="kpi"], [class*="stat"]').length
      const empty = t('.el-table__empty-text') || t('.empty') || t('.el-empty')
      const btns = [...document.querySelectorAll('.page-actions button, .el-button--primary')]
        .filter(b => b.offsetParent).map(b => b.innerText.replace(/\s+/g, ' ').trim()).filter(Boolean)
      // 有没有「下一步」提示
      const guide = /请先|暂无|去.{0,6}(创建|新增|发布|发起)|点击.{0,8}(新建|添加)/.test(document.body.innerText)
      return { title: t('.page-title'), rows, empty: empty.slice(0, 80), btns: [...new Set(btns)].slice(0, 4), guide }
    })
    pages.push({ label, ...info })
    console.log(`  · ${label} | 行${info.rows} 空态「${info.empty.slice(0, 30)}」 引导:${info.guide}`)
  }
  fs.writeFileSync(path.join(OUT, 'menu_pages.json'), JSON.stringify(pages, null, 2), 'utf8')
  await page.screenshot({ path: path.join(OUT, 'shots2', 'T5-last-menu.png') })

  await browser.close()
  fs.writeFileSync(path.join(OUT, 'firstclick.json'), JSON.stringify(out, null, 2), 'utf8')
  console.log('\n---- 空状态/引导统计 ----')
  const blank = pages.filter(p => p.rows === 0)
  console.log('零数据页面 ' + blank.length + '/' + pages.length)
  blank.forEach(b => console.log(`  · ${b.label} 空态:「${b.empty}」 有引导:${b.guide} 按钮:${JSON.stringify(b.btns)}`))
})().catch(e => { console.error(e); process.exit(1) })
