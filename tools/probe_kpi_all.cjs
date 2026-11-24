/* ==========================================================================
   全站 KPI 卡片一致性巡检
   --------------------------------------------------------------------------
   背景：数字滚动动画曾用 el.textContent 重建节点，把 Vue 的文本节点摘掉，
   导致接口数据到达后数字仍停在首屏值（多为 0）。农户货源页就是这么错的。
   本脚本遍历所有用 StatCard 的页面，打印 KPI 实际渲染值与表格行数，
   用「有数据却全 0」来快速定位同类问题。
   ========================================================================== */
const path = require('path')
const fs = require('fs')

const ROOT = path.resolve(__dirname, '..')
const BASE = 'http://127.0.0.1:5173'
const OUT = path.join(ROOT, 'docs', 'usability_2026-11-10')
fs.mkdirSync(OUT, { recursive: true })

const GLOBAL = path.join(process.env.APPDATA, 'npm', 'node_modules')
module.paths.push(GLOBAL)
const { chromium } = require(path.join(GLOBAL, 'playwright'))

const sleep = ms => new Promise(r => setTimeout(r, ms))

const ROUTES = {
  admin: ['/admin/dashboard', '/admin/canteens', '/admin/farmers', '/admin/logs', '/admin/orders', '/admin/products', '/admin/settlements', '/admin/warnings'],
  farmer24: ['/farmer/workbench', '/farmer/sources', '/farmer/orders', '/farmer/settlement'],
  inspector01: ['/inspector/tasks'],
  purchaser01: ['/purchaser/demands', '/purchaser/orders']
}

async function login(page, username) {
  await page.goto(BASE + '/login', { waitUntil: 'domcontentloaded' })
  await sleep(500)
  await page.fill('input[placeholder*="账号"]', username)
  await page.fill('input[type="password"]', '123456')
  await page.click('.submit')
  await page.waitForURL(/\/(admin|farmer|inspector|purchaser)\//, { timeout: 15000 })
  await sleep(1200)
  // 首次登录会自动弹出「怎么用」引导（真实功能，不能为了测试关掉），脚本里先关掉再走查
  if (await page.locator('.gd-x').count()) await page.locator('.gd-x').first().click()
  await page.keyboard.press('Escape')
  await sleep(500)
}

;(async () => {
  const browser = await chromium.launch({ channel: 'msedge' })
  const rows = []
  for (const [user, paths] of Object.entries(ROUTES)) {
    const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 } })
    const page = await ctx.newPage()
    await login(page, user)
    for (const p of paths) {
      await page.goto(BASE + p, { waitUntil: 'domcontentloaded' })
      await sleep(2600)
      const kpi = await page.$$eval('.stat-card', els => els.map(e => {
        const label = e.querySelector('.stat-label')?.textContent.trim() || ''
        const value = e.querySelector('.stat-value')?.textContent.trim() || ''
        return label + '=' + value
      })).catch(() => [])
      const tableRows = await page.$$eval('.el-table__body tr', els => els.length).catch(() => 0)
      const empty = kpi.length > 0 && kpi.every(k => /=(0|0\.00|—|)$/.test(k))
      rows.push({ user, path: p, kpi, tableRows, suspicious: empty && tableRows > 0 })
      console.log(`${empty && tableRows > 0 ? 'SUSPECT' : 'ok     '} ${p}  行=${tableRows}  ${kpi.join(' / ')}`)
    }
    await ctx.close()
  }
  await browser.close()
  const suspicious = rows.filter(r => r.suspicious)
  fs.writeFileSync(path.join(OUT, 'kpi_check.json'), JSON.stringify({ at: new Date().toISOString(), total: rows.length, suspicious: suspicious.length, rows }, null, 2), 'utf8')
  console.log('\n== 可疑页（KPI 全 0 但有数据）：', suspicious.length, '==')
  suspicious.forEach(s => console.log('  ', s.path, s.kpi.join(' / ')))
  process.exit(suspicious.length ? 1 : 0)
})()
