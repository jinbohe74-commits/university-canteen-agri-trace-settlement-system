/* ==========================================================================
   第一反应探针 v2（2026-11-10）
   修正 v1 的选择器错误：布局用的是 .lay-nav-item / .lay-act，
   不是 el-menu-item；Ctrl+K 要真按 Control+KeyK。
   记录：点了以后去了哪 / 弹了什么 / 空页面说了什么。
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
const rec = (k, v) => console.log('  ' + k + ' → ' + JSON.stringify(v).slice(0, 320))
const out = {}
const pages = []

async function apiLogin(u, p = '123456', r) {
  const res = await fetch(API + '/api/auth/login', {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username: u, password: p, expectedRole: r })
  })
  return (await res.json()).data
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
  const errs = []
  page.on('pageerror', e => errs.push(e.message.slice(0, 150)))
  await page.goto(BASE + '/admin/dashboard', { waitUntil: 'domcontentloaded' })
  await sleep(1800)

  /* 1. 待办 */
  await page.locator('.lay-act').filter({ hasText: '待办' }).first().click()
  await sleep(1200)
  out.todo = { url: page.url().replace(BASE, ''), title: await page.locator('.page-title').first().innerText().catch(() => '') }
  rec('点「待办」', out.todo)

  /* 2. 动态（先在任意页点，看会不会弹出面板） */
  await page.locator('.lay-act').filter({ hasText: '动态' }).first().click()
  await sleep(900)
  out.dyn = await page.evaluate(() => {
    const p = document.querySelector('.rt-panel, .notify-panel, .el-popover, .el-drawer')
    return p ? { cls: p.className.slice(0, 60), text: (p.innerText || '').replace(/\s+/g, ' ').slice(0, 300) } : null
  })
  rec('点「动态」', out.dyn || '没有面板')
  await page.screenshot({ path: path.join(OUT, 'shots2', 'T2-dyn-pop.png') })
  await page.mouse.click(700, 500); await sleep(400)

  /* 3. Ctrl+K 全局搜索 */
  await page.keyboard.down('Control'); await page.keyboard.press('KeyK'); await page.keyboard.up('Control')
  await sleep(800)
  out.cmdOpen = await page.evaluate(() => {
    const e = document.querySelector('.cp-mask, .cmd-palette, .gs-mask')
    return e ? (e.innerText || '').replace(/\s+/g, ' ').slice(0, 120) : null
  })
  rec('Ctrl+K', out.cmdOpen || '没有反应')
  await page.screenshot({ path: path.join(OUT, 'shots2', 'T3-cmdk.png') })
  if (out.cmdOpen) {
    await page.keyboard.type('王丰收', { delay: 80 }); await sleep(1200)
    out.cmdResult = await page.evaluate(() => {
      const e = document.querySelector('.cp-mask, .cmd-palette')
      return e ? (e.innerText || '').replace(/\s+/g, ' ').slice(0, 400) : null
    })
    rec('搜「王丰收」', out.cmdResult)
    await page.screenshot({ path: path.join(OUT, 'shots2', 'T4-cmdk-result.png') })
    await page.keyboard.press('Escape'); await sleep(400)
  }

  /* 4. 侧栏逐页 */
  const items = await page.locator('.lay-nav-item').all()
  rec('侧栏项数', items.length)
  for (const it of items) {
    const label = (await it.innerText().catch(() => '')).replace(/\s+/g, ' ').trim()
    if (!label) continue
    await it.click({ force: true }).catch(() => {})
    await sleep(1100)
    const info = await page.evaluate(() => {
      const t = (s) => { const e = document.querySelector(s); return e ? (e.innerText || '').replace(/\s+/g, ' ').trim() : '' }
      const body = document.body.innerText.replace(/\s+/g, ' ')
      return {
        title: t('.page-title'),
        rows: document.querySelectorAll('.el-table__body tbody tr').length,
        cards: document.querySelectorAll('.card, .el-card').length,
        empty: t('.el-table__empty-text') || t('.empty'),
        guide: /请先|暂无|等待.{0,6}(发货|审核)|去.{0,8}(创建|新增|发布|发起)|点击.{0,8}(新建|添加)/.test(body),
        actions: [...new Set([...document.querySelectorAll('.page-actions button')].filter(b => b.offsetParent).map(b => b.innerText.replace(/\s+/g, ' ').trim()))].slice(0, 5)
      }
    })
    pages.push({ label, ...info })
    console.log(`  · ${label} | 行${info.rows} 空态「${info.empty.slice(0, 40)}」引导:${info.guide} 操作:${JSON.stringify(info.actions)}`)
  }
  fs.writeFileSync(path.join(OUT, 'menu_pages.json'), JSON.stringify(pages, null, 2), 'utf8')
  out.errors = errs
  fs.writeFileSync(path.join(OUT, 'firstclick.json'), JSON.stringify(out, null, 2), 'utf8')
  await browser.close()

  console.log('\n---- 零数据页 ----')
  const blank = pages.filter(p => p.rows === 0)
  blank.forEach(b => console.log(`  · ${b.label} 空态「${b.empty}」 有引导:${b.guide}`))
  console.log(`零数据 ${blank.length}/${pages.length}；其中无引导 ${blank.filter(b => !b.guide).length}`)
})().catch(e => { console.error(e); process.exit(1) })
