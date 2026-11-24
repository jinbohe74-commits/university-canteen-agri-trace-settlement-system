const path = require('path')
const fs = require('fs')
const BASE = 'http://127.0.0.1:5173'
const API = 'http://127.0.0.1:8080'
const OUT = path.join(__dirname, '..', 'docs', 'reform_2026-11-10', 'shots')
fs.mkdirSync(OUT, { recursive: true })
const G = path.join(process.env.APPDATA, 'npm', 'node_modules')
module.paths.push(G)
const { chromium } = require(path.join(G, 'playwright'))
const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

async function login(u, r) {
  const res = await fetch(API + '/api/auth/login', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username: u, password: '123456', expectedRole: r })
  })
  return (await res.json()).data
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
  p.on('pageerror', (e) => p.__e.push(e.message.slice(0, 160)))
  await p.goto(BASE + route, { waitUntil: 'domcontentloaded' })
  await sleep(3000)
  return p
}

;(async () => {
  const browser = await chromium.launch({ channel: 'msedge' })
  const errors = []

  /* ---------------- 1. 数据看板：行情可自选 ---------------- */
  {
    const d = await login('admin', 'ADMIN')
    const p = await open(browser, d, '/admin/dashboard')
    await sleep(1800)
    await p.screenshot({ path: path.join(OUT, '1-看板-行情默认.png') })

    // 展开商品多选，证明可选品项远超两个
    await p.locator('.pf-prod').click()
    await sleep(900)
    await p.screenshot({ path: path.join(OUT, '2-看板-行情可选品项.png') })
    const optCount = await p.locator('.el-select-dropdown__item:visible').count()
    console.log('可选品项数', optCount)
    // 分组标题必须在下拉还开着的时候统计，Escape 之后就取不到了
    const groups = await p.locator('.el-select-group__title:visible').allInnerTexts()
    console.log('品类分组', groups.join('/'))
    await p.keyboard.press('Escape')
    await sleep(500)

    // 分组多选：再选 3 个品项做对比
    await p.locator('.pf-prod').click()
    await sleep(800)
    const items = p.locator('.el-select-dropdown__item:visible')
    const n = Math.min(3, await items.count())
    for (let i = 0; i < n; i++) {
      await items.nth(i).click()
      await sleep(320)
    }
    await p.keyboard.press('Escape')
    await sleep(1800)
    await p.screenshot({ path: path.join(OUT, '3-看板-自选品类对比.png') })
    const legend = await p.locator('.db-inline-legend').innerText()
    console.log('图例', legend.replace(/\s+/g, ' '))
    const series = await p.evaluate(() => {
      const t = document.querySelector('.pf-source')
      return t ? t.innerText : ''
    })
    console.log('口径', series)
    errors.push(...p.__e)
  }

  /* ---------------- 2. 质检录入：按品类检测项 + 系统判定 ---------------- */
  {
    const d = await login('inspector01', 'INSPECTOR')
    const p = await open(browser, d, '/inspector/quality')
    await sleep(1500)
    await p.screenshot({ path: path.join(OUT, '4-质检-分品类检测项.png') })
    const head = await p.locator('.sub-note').first().innerText().catch(() => '')
    console.log('品类标准', head.replace(/\s+/g, ' '))
    const rowCount = await p.locator('.test-row').count()
    const verdict = await p.locator('.verdict-box').innerText().catch(() => '')
    console.log('检测项', rowCount, '判定', verdict.replace(/\s+/g, ' ').slice(0, 80))

    // 填一项超限值，判定应立刻变红并说明哪项超了
    const firstNum = p.locator('.test-row').filter({ has: p.locator('input') }).first()
    if (await firstNum.count()) {
      const input = firstNum.locator('input').first()
      await input.fill('9.9')
      await sleep(900)
      await p.screenshot({ path: path.join(OUT, '5-质检-超限自动判不合格.png') })
      const v2 = await p.locator('.verdict-box').innerText().catch(() => '')
      console.log('超限后判定', v2.replace(/\s+/g, ' ').slice(0, 100))
      await input.fill('')
      await sleep(400)
    }
    errors.push(...p.__e)
  }

  /* ---------------- 3. 配送：实时地图 ---------------- */
  {
    const d = await login('inspector01', 'INSPECTOR')
    const p = await open(browser, d, '/inspector/delivery')
    await sleep(2200)
    // 选一条配送中的单
    const rows = p.locator('.el-table__body tbody tr')
    const cnt = await rows.count()
    let picked = false
    for (let i = 0; i < cnt; i++) {
      const t = await rows.nth(i).innerText()
      if (t.includes('配送中')) {
        await rows.nth(i).click()
        picked = true
        break
      }
    }
    console.log('选中配送中单行', picked)
    await sleep(2200)
    await p.locator('.dm-card').scrollIntoViewIfNeeded().catch(() => {})
    await sleep(600)
    await p.screenshot({ path: path.join(OUT, '6-配送-实时地图.png') })
    const hud = await p.locator('.dmap-hud').innerText().catch(() => '')
    console.log('地图指标', hud.replace(/\s+/g, ' '))

    // 发车检查弹窗（切一条待发车的）
    await p.keyboard.press('Escape')
    errors.push(...p.__e)
  }

  /* ---------------- 4. 预警页：确认没有「复制」 ---------------- */
  {
    const d = await login('admin', 'ADMIN')
    const p = await open(browser, d, '/admin/warnings')
    await sleep(1800)
    await p.screenshot({ path: path.join(OUT, '7-预警-无行内复制.png') })
    const copyCount = await p.locator('text=复制').count()
    const bodyText = await p.locator('.lay-content').innerText().catch(() => '')
    console.log('页面出现「复制」次数', copyCount, '含复制字样', bodyText.includes('复制'))
    errors.push(...p.__e)
  }

  console.log('console errors:', errors.slice(0, 5))
  await browser.close()
})().catch((e) => { console.error(e); process.exit(1) })
