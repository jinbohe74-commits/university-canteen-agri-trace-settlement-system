/* 报告配图重拍（2026-11-11 第十三轮）
   目的：把实验三/实验四报告里的界面截图换成当前系统（含工作台、比价、配送实时轨迹、账号机制等新界面）。
   统一 1440x900 视口 + deviceScaleFactor=2，全页截图，与旧图分辨率口径一致（2880 宽）。 */
const path = require('path')
const fs = require('fs')
const ROOT = path.resolve(__dirname, '..')
const BASE = 'http://127.0.0.1:5173'
const API = 'http://127.0.0.1:8080'
const OUT = path.join(ROOT, 'docs', 'report_2026-11-11', 'shots')
fs.mkdirSync(OUT, { recursive: true })
const GLOBAL = path.join(process.env.APPDATA, 'npm', 'node_modules')
module.paths.push(GLOBAL)
const { chromium } = require(path.join(GLOBAL, 'playwright'))
const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

async function login(u, role) {
  const r = await fetch(API + '/api/auth/login', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username: u, password: '123456', expectedRole: role })
  })
  const j = await r.json()
  if (!j.data) throw new Error('登录失败 ' + u + ' ' + j.message)
  return j.data
}

async function ctxFor(browser, d) {
  const c = await browser.newContext({
    viewport: { width: 1440, height: 900 },
    deviceScaleFactor: 2,
    locale: 'zh-CN'
  })
  await c.addInitScript(
    ([t, u, rn, mn]) => {
      sessionStorage.setItem('ct_token', t)
      sessionStorage.setItem('ct_user', u)
      sessionStorage.setItem('ct_role_name', rn)
      sessionStorage.setItem('ct_menus', mn)
    },
    [d.token, JSON.stringify(d.user), d.roleName || '', JSON.stringify(d.menus || [])]
  )
  return c
}

const errors = []
function watch(page, tag) {
  page.on('console', (m) => {
    if (m.type() === 'error') errors.push(tag + ': ' + m.text().slice(0, 160))
  })
  page.on('pageerror', (e) => errors.push(tag + ' pageerror: ' + String(e).slice(0, 160)))
}

async function go(page, url, wait = 2600) {
  // 每页先归位到标准视口，否则上一页撑高的视口会让本页 scrollHeight 偏大、截出大片空白
  await page.setViewportSize({ width: 1440, height: 900 })
  await page.goto(BASE + url, { waitUntil: 'domcontentloaded' })
  await sleep(wait)
}

/* 关键：先用「视口 = 内容高度」再截图。
   页面上有几处 position:sticky;bottom:0 的吸底操作条（质检提交、表格滚动提示），
   直接 fullPage 截图会把它们定格在 900px 处、压在正文上，看着像排版错乱。
   把视口拉到内容实际高度后，吸底条落到真正底部，与真实浏览效果一致。 */
async function shot(page, name, full = true) {
  if (full) {
    const h = await page.evaluate(() => document.documentElement.scrollHeight)
    const target = Math.min(Math.max(h, 900), 3200)
    await page.setViewportSize({ width: 1440, height: target })
    await sleep(900)
  }
  const p = path.join(OUT, name + '.png')
  await page.screenshot({ path: p, fullPage: false })
  const box = await page.evaluate(() => ({
    w: document.documentElement.clientWidth,
    h: document.documentElement.scrollHeight
  }))
  console.log('  ✓', name, box.w + 'x' + box.h)
  return p
}

;(async () => {
  const browser = await chromium.launch({ channel: 'msedge' })

  /* ---------- 1. 登录页（未登录，视口截图） ---------- */
  const lc = await browser.newContext({ viewport: { width: 1440, height: 900 }, deviceScaleFactor: 2, locale: 'zh-CN' })
  const lp = await lc.newPage()
  watch(lp, 'login')
  await lp.goto(BASE + '/login', { waitUntil: 'domcontentloaded' })
  await sleep(1500)
  // 选“管理员”角色并填入演示账号，与旧图口径一致
  const adminRole = lp.locator('.role-item', { hasText: '管理员' }).first()
  if (await adminRole.count()) await adminRole.click()
  await lp.fill('input[placeholder*="用户名"], input[placeholder*="账号"]', 'admin')
  await lp.fill('input[type="password"]', '123456')
  await sleep(600)
  await shot(lp, '01-登录页', false)
  await lc.close()

  /* ---------- 2. 管理员 ---------- */
  const admin = await login('admin', 'ADMIN')
  const ac = await ctxFor(browser, admin)
  const ap = await ac.newPage()
  watch(ap, 'admin')

  await go(ap, '/admin/dashboard', 4200)
  await shot(ap, '02-管理员数据看板')

  await go(ap, '/admin/orders', 3000)
  await shot(ap, '05-订单监管')

  await go(ap, '/admin/settlements', 3200)
  await shot(ap, '08-结算审核')

  await go(ap, '/admin/trace', 3200)
  await shot(ap, '09-批次溯源')

  await go(ap, '/admin/users', 2800)
  await shot(ap, '13-用户与权限')

  await ac.close()

  /* ---------- 3. 农户 ---------- */
  const farmer = await login('farmer01', 'FARMER')   // 丰禾农业合作社：待接单 2、本月收入 1.2 万，工作台各项都有内容
  const fc = await ctxFor(browser, farmer)
  const fp = await fc.newPage()
  watch(fp, 'farmer')
  await go(fp, '/farmer/workbench', 3200)
  await shot(fp, '10-农户工作台')
  await go(fp, '/farmer/sources', 2800)
  await shot(fp, '03-农户货源管理')
  await fc.close()

  /* ---------- 4. 采购员 ---------- */
  const pur = await login('purchaser01', 'PURCHASER')
  const pc = await ctxFor(browser, pur)
  const pp = await pc.newPage()
  watch(pp, 'purchaser')
  await go(pp, '/purchaser/demands', 3000)
  await shot(pp, '11-采购需求与比价')
  await go(pp, '/purchaser/match', 3600)
  await shot(pp, '04-智能匹配')
  await go(pp, '/purchaser/inbound', 2800)
  await shot(pp, '07-入库确认')
  await pc.close()

  /* ---------- 5. 质检配送员 ---------- */
  const ins = await login('inspector01', 'INSPECTOR')
  const ic = await ctxFor(browser, ins)
  const ip = await ic.newPage()
  watch(ip, 'inspector')
  await go(ip, '/inspector/tasks', 3000)
  await shot(ip, '12-质检任务工作台')
  await go(ip, '/inspector/quality', 3400)
  {
    const rows = ip.locator('.test-row')
    const n = await rows.count()
    const nums = ['0.02', '0.01', '0.5', '0', '', '6.0', '']
    for (let i = 0; i < n; i++) {
      const row = rows.nth(i)
      // 注意：单选行内部也有 <input type=radio>，不能用 input 存在与否判断输入类型
      const num = row.locator('.tr-input .el-input__inner')
      if (await num.count()) {
        if (nums[i]) await num.first().fill(nums[i])
      } else {
        const radio = row.locator('.el-radio-button__inner').first()
        if (await radio.count()) await radio.click()
      }
      await sleep(120)
    }
    await ip.fill('textarea', '整体品质良好，农残快检与重金属抽检均在标准范围内，冷链到货温度 6.0℃ 符合 2~8℃ 要求，准予生成批次二维码并放行配送。')
    await sleep(900)
    console.log('  质检表单已填', n, '项')
  }
  await shot(ip, '06-质检录入')
  await go(ip, '/inspector/delivery', 4200)
  await shot(ip, '15-配送任务与实时轨迹')
  await ic.close()

  await browser.close()
  console.log(errors.length ? 'JS 错误:\n' + errors.join('\n') : 'JS 错误: 无')
})().catch((e) => {
  console.error('FAILED', e)
  process.exit(1)
})
