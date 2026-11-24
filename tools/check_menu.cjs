/* ==========================================================================
   侧栏菜单完整性检查（2026-11-10 追加）
   --------------------------------------------------------------------------
   起因：管理员侧栏看不到「数据看板」。
   根因：sys_role_menu 只授权了子菜单 10，漏配父菜单 1（概览），
        而菜单树只从 parentId==0 的已授权节点起建，整组被静默丢弃。
   本脚本从两个层面守住这条链路：
     1. 接口层：四个角色登录后菜单树必须包含该角色应有的全部菜单项、且无空分组
     2. 界面层：管理员登录后侧栏必须真的渲染出「数据看板」，且可以点进去
   ========================================================================== */
const path = require('path')
const fs = require('fs')

const ROOT = path.resolve(__dirname, '..')
const BASE = 'http://127.0.0.1:5173'
const API = 'http://127.0.0.1:8080'
const OUT = path.join(ROOT, 'docs', 'usability_2026-11-10')
fs.mkdirSync(OUT, { recursive: true })

const GLOBAL = path.join(process.env.APPDATA, 'npm', 'node_modules')
module.paths.push(GLOBAL)
const { chromium } = require(path.join(GLOBAL, 'playwright'))

const results = []
const rec = (name, ok, detail) => {
  results.push({ name, ok: !!ok, detail: detail === undefined ? null : detail })
  console.log((ok ? '  PASS ' : '  FAIL ') + name + ' | ' + JSON.stringify(detail === undefined ? null : detail).slice(0, 300))
  return ok
}
const sleep = ms => new Promise(r => setTimeout(r, ms))

// 各角色「必须有」的菜单项（与 db/02_data.sql 的授权一致）
const EXPECT = {
  admin: ['数据看板', '农户/合作社管理', '食堂档案管理', '农产品档案', '用户与权限',
    '订单监管', '结算审核', '食安预警', '批次溯源', '系统参数配置', '操作日志'],
  farmer24: ['我的工作台', '货源管理', '订单管理', '结算与收入', '信用评价', '消息提醒'],
  purchaser01: ['采购需求', '货源大厅', '智能匹配', '我的订单', '入库确认', '采购评价', '批次溯源'],
  inspector01: ['我的任务', '质检录入', '配送任务', '异常上报', '批次溯源'],
}

async function apiLogin(username) {
  const r = await fetch(API + '/api/auth/login', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username, password: '123456' }),
  })
  return r.json()
}

async function main() {
  // ---------- 1. 接口层 ----------
  for (const [user, expect] of Object.entries(EXPECT)) {
    const res = await apiLogin(user)
    if (!res || res.code !== 200) { rec(`${user} 登录`, false, res && res.message); continue }
    const menus = res.data.menus || []
    const flat = menus.flatMap(g => (g.children || []).map(c => c.name))
    const missing = expect.filter(n => !flat.includes(n))
    const emptyGroups = menus.filter(g => !g.children || g.children.length === 0).map(g => g.name)
    rec(`${user} 菜单项齐全（${expect.length} 项）`, missing.length === 0, { groups: menus.map(g => g.name), missing })
    rec(`${user} 无空分组`, emptyGroups.length === 0, { emptyGroups })
  }

  // ---------- 2. 界面层：管理员侧栏 ----------
  const browser = await chromium.launch({ channel: 'msedge' })
  const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 } })
  const page = await ctx.newPage()
  const errors = []
  page.on('console', m => { if (m.type() === 'error') errors.push(m.text()) })
  page.on('pageerror', e => errors.push(String(e)))

  await page.goto(BASE + '/login', { waitUntil: 'domcontentloaded' })
  await sleep(600)
  await page.fill('input[placeholder*="账号"]', 'admin')
  await page.fill('input[type="password"]', '123456')
  await page.click('.submit')
  await page.waitForURL(/\/admin\//, { timeout: 15000 })
  await sleep(1200)
  // 首次登录会自动弹出「怎么用」引导（真实功能，不能为了测试关掉），脚本里先关掉再走查
  if (await page.locator('.gd-x').count()) await page.locator('.gd-x').first().click()
  await page.keyboard.press('Escape')
  await sleep(500)

  const labels = await page.$$eval('.lay-nav-label', els => els.map(e => e.textContent.trim()))
  const items = await page.$$eval('.lay-nav-item', els => els.map(e => e.textContent.replace(/\d+$/, '').trim()))
  rec('侧栏渲染出「数据看板」', items.includes('数据看板'), { labels, items })
  rec('侧栏「概览」分组可见', labels.includes('概览'), { labels })

  // 点进去，确认路由与页面标题真的对得上
  const link = page.locator('.lay-nav-item', { hasText: '数据看板' }).first()
  const href = await link.getAttribute('href')
  rec('数据看板链接指向 /admin/dashboard', href && href.includes('/admin/dashboard'), { href })
  await link.click()
  await sleep(1500)
  const url = page.url()
  const title = await page.textContent('.page-title').catch(() => '')
  rec('点击后进入数据看板页且内容已渲染', url.includes('/admin/dashboard') && !!title, { url, title: (title || '').trim() })

  await page.screenshot({ path: path.join(OUT, 'I-侧栏数据看板.png'), fullPage: false })
  rec('全程 0 控制台错误', errors.length === 0, { count: errors.length, samples: errors.slice(0, 3) })

  await browser.close()

  const summary = {
    at: new Date().toISOString(),
    total: results.length,
    pass: results.filter(r => r.ok).length,
    fail: results.filter(r => !r.ok).length,
  }
  fs.writeFileSync(path.join(OUT, 'menu_check.json'), JSON.stringify({ summary, results }, null, 2), 'utf8')
  console.log('\n== 汇总 ==', JSON.stringify(summary))
  process.exit(summary.fail ? 1 : 0)
}

main().catch(e => { console.error('脚本异常：', e); process.exit(2) })
