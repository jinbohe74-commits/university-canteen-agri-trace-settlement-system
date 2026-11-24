/* ==========================================================================
   前端重构验收（2026-11-10）
   --------------------------------------------------------------------------
   真实浏览器（系统 Edge）验证：三套方案自动分配 + 手动锁定、对比度终值、
   15 项交互补强、控制台错误、6 张关键场景截图。
   对比度严格按经验库 L-028：读 html 上的设计 token 终值，不读后代插值色；
   切换方案后等待 ≥ 过渡时长 + 余量（本项目 280ms → 500ms）再截图。
   ========================================================================== */
const path = require('path')
const fs = require('fs')
const GLOBAL = path.join(process.env.APPDATA, 'npm', 'node_modules')
module.paths.push(GLOBAL)
const { chromium } = require(path.join(GLOBAL, 'playwright'))

const BASE = 'http://127.0.0.1:5173'
const ROOT = path.resolve(__dirname, '..')
const OUT = path.join(ROOT, 'docs', 'design_2026-11-10', 'ui_acceptance')
fs.mkdirSync(OUT, { recursive: true })

const results = []
const shots = []
const fail = (name, ok, detail) => {
  results.push({ name, ok, ...detail })
  console.log((ok ? '  OK   ' : ' FAIL  ') + name + ' | ' + JSON.stringify(detail))
  return ok
}
const info = (name, detail) => {
  results.push({ name, ok: null, info: true, ...detail })
  console.log('  INFO ' + name + ' | ' + JSON.stringify(detail))
}

/* ---------- 颜色工具：支持 #RGB / #RRGGBB / rgb() / rgba() ---------- */
function parseColor(s) {
  s = (s || '').trim()
  if (s.startsWith('#')) {
    let h = s.slice(1)
    if (h.length === 3) h = h.split('').map((c) => c + c).join('')
    return [parseInt(h.slice(0, 2), 16), parseInt(h.slice(2, 4), 16), parseInt(h.slice(4, 6), 16)]
  }
  const m = s.match(/(-?[\d.]+)/g)
  return m ? [Number(m[0]), Number(m[1]), Number(m[2])] : [0, 0, 0]
}
function luminance(rgb) {
  const a = rgb.map((v) => {
    const c = v / 255
    return c <= 0.03928 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4)
  })
  return 0.2126 * a[0] + 0.7152 * a[1] + 0.0722 * a[2]
}
function contrast(fg, bg) {
  const l1 = luminance(parseColor(fg)), l2 = luminance(parseColor(bg))
  return (Math.max(l1, l2) + 0.05) / (Math.min(l1, l2) + 0.05)
}

async function schemeOf(page) {
  return page.evaluate(() => document.documentElement.dataset.scheme)
}
async function setScheme(page, value) {
  await page.selectOption('select.lay-scheme-btn', value)
  await page.waitForTimeout(500)
}
async function shot(page, name, label) {
  const file = path.join(OUT, name)
  await page.screenshot({ path: file, fullPage: false })
  shots.push({ label, file: path.relative(ROOT, file) })
}

;(async () => {
  const browser = await chromium.launch({ channel: 'msedge' })
  const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 } })
  await ctx.grantPermissions(['clipboard-read', 'clipboard-write'])
  const page = await ctx.newPage()
  const errors = []
  const badResponses = []
  page.on('console', (m) => { if (m.type() === 'error') errors.push(m.text().slice(0, 180)) })
  page.on('pageerror', (e) => errors.push('PAGEERROR: ' + e.message.slice(0, 180)))
  page.on('response', (r) => {
    if (r.status() >= 400 && new URL(r.url()).hostname === '127.0.0.1') {
      badResponses.push(r.status() + ' ' + r.url().replace(BASE, ''))
    }
  })

  /* ---------------- 1. 登录页 A ---------------- */
  await page.goto(BASE + '/login', { waitUntil: 'domcontentloaded' })
  await page.waitForTimeout(900)
  fail('登录页自动分配 A 田埂', (await schemeOf(page)) === 'a', { scheme: await schemeOf(page) })
  fail('登录页摄影主视觉已加载', await page.evaluate(() => {
    const el = document.querySelector('.brand-side')
    return !!el && getComputedStyle(el).backgroundImage.includes('A-organic')
  }), {})
  fail('登录表单不预填任何凭据', await page.evaluate(() => {
    const u = document.querySelector('input[placeholder="请输入账号"]')
    const p = document.querySelector('input[placeholder="请输入密码"]')
    return !!u && !!p && u.value === '' && p.value === ''
  }), {})
  await shot(page, 'A-登录.png', 'A 田埂 · 登录页（摄影主视觉 + 衬线 + 零渐变）')

  /* ---------------- 2. 登录 ---------------- */
  await page.fill('input[placeholder="请输入账号"]', 'admin')
  await page.fill('input[placeholder="请输入密码"]', '123456')
  await page.click('button.submit')
  await page.waitForTimeout(2200)

  /* ---------------- 3. 自动分配 C / B / A ---------------- */
  const schemeCases = [
    ['/admin/dashboard', 'c', '管理员驾驶舱 → C 夜航'],
    ['/admin/orders', 'b', '表格密集页 → B 枢纽'],
    ['/admin/trace', 'a', '溯源查询页 → A 田埂'],
    ['/farmer/workbench', 'b', '农户工作台（越权应被挡，仅记录）']
  ]
  for (const [route, expected, label] of schemeCases) {
    await page.goto(BASE + route, { waitUntil: 'domcontentloaded' })
    await page.waitForTimeout(1900)
    const got = await schemeOf(page)
    if (route === '/farmer/workbench') info('跨角色路由访问被鉴权拦截', { url: page.url(), scheme: got })
    else fail('自动分配：' + label, got === expected, { expected, got })
  }

  /* ---------------- 4. 三套方案对比度（读 html token 终值） ---------------- */
  await page.goto(BASE + '/admin/orders', { waitUntil: 'domcontentloaded' })
  await page.waitForTimeout(1800)
  const ratios = {}
  for (const s of ['a', 'b', 'c']) {
    await setScheme(page, s)
    const pair = await page.evaluate(() => {
      const cs = getComputedStyle(document.documentElement)
      return {
        ink: cs.getPropertyValue('--ink').trim(),
        surface: cs.getPropertyValue('--surface').trim()
      }
    })
    ratios[s] = Number(contrast(pair.ink, pair.surface).toFixed(2))
    fail(`方案 ${s.toUpperCase()} 正文/面板对比度 ≥ 4.5:1`, ratios[s] >= 4.5,
      { ratio: ratios[s], ink: pair.ink, surface: pair.surface })
  }
  const uniq = new Set(Object.values(ratios))
  fail('三套方案对比度自检值互不重复（防滞后一轮）', uniq.size === 3, { ratios })
  await setScheme(page, 'auto')

  /* ---------------- 5. 交互：命令面板 ---------------- */
  await page.keyboard.press('Control+k')
  await page.waitForTimeout(250)
  const cmdVisible = await page.locator('.cmd-overlay').count()
  fail('⌘K / Ctrl+K 打开命令面板', cmdVisible > 0, { cmdVisible })
  if (cmdVisible) {
    await page.fill('.cmd-head input', '结算')
    await page.waitForTimeout(180)
    const filtered = await page.locator('.cmd-item').count()
    fail('命令面板输入实时过滤', filtered > 0 && filtered < 12, { filtered })
    await page.keyboard.press('Enter')
    await page.waitForTimeout(1400)
    fail('命令面板回车直达目标页', page.url().includes('/admin/settlements'), { url: page.url() })
  }

  /* ---------------- 6. 交互：表格增强（在订单页做） ---------------- */
  await page.goto(BASE + '/admin/orders', { waitUntil: 'domcontentloaded' })
  await page.waitForTimeout(2000)
  const hasSearch = await page.locator('[data-ui-search]').count()
  const livebarRows = await page.locator('.ui-livebar').count()
  fail('表格页注入即时筛选（并入原筛选条，不新增重复工具条）',
    hasSearch > 0 && (await page.locator('.filter-bar [data-ui-search]').count()) > 0,
    { hasSearch, standaloneLivebars: livebarRows })

  if (hasSearch) {
    const visibleRows = () => page.evaluate(() =>
      Array.from(document.querySelectorAll('.el-table__body tbody tr'))
        .filter((tr) => tr.style.display !== 'none').length)
    const before = await visibleRows()
    await page.fill('[data-ui-search]', '不存在的批次ZZZZ')
    await page.waitForTimeout(200)
    const zero = await visibleRows()
    await page.fill('[data-ui-search]', '')
    await page.waitForTimeout(200)
    const restored = await visibleRows()
    fail('即时搜索：输入 → 行数归零，清空 → 恢复',
      before > 0 && zero === 0 && restored === before, { before, zero, restored })

    const uiChips = await page.locator('.ui-chip').count()
    const nativeChips = await page.locator('.tabs-row .tab-btn, .chips .chip, .tab-btn').count()
    const dupChips = await page.locator('.ui-chip').count()
    fail('页面原有状态 chips 时不再生成第二套', nativeChips > 0 ? dupChips === 0 : true,
      { nativeChips, injectedChips: dupChips })
    if (uiChips > 1 || nativeChips > 1) {
      const sel = uiChips > 1 ? '.ui-chip' : '.tabs-row .tab-btn, .chips .chip, .tab-btn'
      const beforeChip = await visibleRows()
      await page.locator(sel).nth(1).click()
      await page.waitForTimeout(600)
      const activeSel = uiChips > 1
        ? '.ui-chip.on'
        : '.tabs-row .tab-btn.on, .chips .chip.on, .tab-btn.on'
      const onCount = await page.locator(activeSel).count()
      const afterChip = await visibleRows()
      fail('状态 chip 筛选生效', onCount >= 1 || afterChip !== beforeChip,
        { sel, onCount, beforeChip, afterChip })
      await page.locator(sel).first().click()
      await page.waitForTimeout(400)
    } else info('状态 chip（该页无状态列，跳过）', { uiChips, nativeChips })

    const th = page.locator('.el-table th.el-table__cell').nth(1)
    if (await th.count()) {
      await th.click()
      await page.waitForTimeout(250)
      const sorted = await th.getAttribute('data-ui-sort')
      fail('表头点击排序（DOM 层）', sorted === 'asc' || sorted === 'desc', { sorted })
    }

    const rowOps = await page.locator('.ui-row-ops').count()
    fail('行 hover 快捷操作已注入', rowOps > 0, { rowOps })
    if (rowOps) {
      await page.locator('.ui-row-ops button').first().click({ force: true })
      await page.waitForTimeout(250)
      fail('复制单号后出现 toast', await page.locator('.ui-toast').count() > 0, {})
    }

    const boxes = await page.locator('.el-table__body input[type="checkbox"]').count()
    if (boxes) {
      await page.locator('.el-table__body input[type="checkbox"]').first().check({ force: true })
      await page.waitForTimeout(300)
      const on = await page.locator('.ui-floatbar.on').count()
      const cnt = await page.locator('.ui-float-count').innerText().catch(() => '0')
      fail('勾选后批量浮动条出现且计数正确', on === 1 && Number(cnt) >= 1, { on, cnt })
      await page.locator('[data-ui-clear]').click()
    } else info('批量浮动条（该页无多选列，跳过）', { boxes })
  }
  await shot(page, 'B-订单.png', 'B 枢纽 · 订单监管（70px 窄栏 + 即时筛选 + 高密度表格）')

  /* ---------------- 6b. 批量浮动条（在有多选列的结算审核页测） ---------------- */
  await page.goto(BASE + '/admin/settlements', { waitUntil: 'domcontentloaded' })
  await page.waitForTimeout(2200)
  const selBoxes = await page.locator('.el-table__body input[type="checkbox"]').count()
  if (selBoxes) {
    // Element Plus 的原生 input 是 sr-only，直接 check 会判定在视口外；点可见的 label
    await page.locator('.el-table__body .el-checkbox').first().click()
    await page.waitForTimeout(350)
    const barOn = await page.locator('.ui-floatbar.on').count()
    const cnt = await page.locator('.ui-float-count').innerText().catch(() => '0')
    fail('勾选后批量浮动条出现且计数正确', barOn === 1 && Number(cnt) >= 1,
      { selBoxes, barOn, cnt })
    await page.locator('[data-ui-clear]').click()
    await page.waitForTimeout(250)
  } else info('批量浮动条（结算页未渲染多选列）', { selBoxes })
  await page.goto(BASE + '/admin/orders', { waitUntil: 'domcontentloaded' })
  await page.waitForTimeout(1900)

  /* ---------------- 7. 外观锁定截图 ---------------- */
  await setScheme(page, 'a')
  await shot(page, 'A-表格锁定.png', '手动锁定 A 田埂 · 表格页换肤一致性')
  await setScheme(page, 'c')
  await shot(page, 'C-表格锁定.png', '手动锁定 C 夜航 · 表格页换肤一致性')
  await setScheme(page, 'auto')

  /* ---------------- 8. C 驾驶舱 ---------------- */
  await page.goto(BASE + '/admin/dashboard', { waitUntil: 'domcontentloaded' })
  await page.waitForTimeout(2200)
  fail('驾驶舱自动分配 C', (await schemeOf(page)) === 'c', { scheme: await schemeOf(page) })
  fail('C 方案隐藏侧栏并显示横向导航', await page.evaluate(() => {
    const side = document.querySelector('.lay-side')
    const nav = document.querySelector('.lay-top-nav')
    return !!side && !!nav && getComputedStyle(side).display === 'none' && getComputedStyle(nav).display !== 'none'
  }), {})
  fail('统计数字完成滚动且非空', await page.evaluate(() =>
    Array.from(document.querySelectorAll('.stat-value')).every((e) => /\d/.test(e.textContent))), {})
  fail('深色下 ECharts 已渲染', await page.locator('canvas').count() > 0, {})
  await shot(page, 'C-驾驶舱.png', 'C 夜航 · 管理员驾驶舱（横向导航 + 明度分层 + 数据高亮）')

  /* ---------------- 9. A 溯源时间线 ---------------- */
  await page.goto(BASE + '/admin/trace', { waitUntil: 'domcontentloaded' })
  await page.waitForTimeout(2000)
  fail('溯源页自动分配 A', (await schemeOf(page)) === 'a', { scheme: await schemeOf(page) })
  const recent = await page.locator('.recent-row .chip').count()
  if (recent) {
    await page.locator('.recent-row .chip').first().click()
    await page.waitForTimeout(1200)
  }
  const titles = await page.locator('.timeline-title[data-ui-timeline]').count()
  if (titles) {
    await page.locator('.timeline-title[data-ui-timeline]').first().click()
    await page.waitForTimeout(250)
    fail('溯源时间线节点可展开', await page.locator('.timeline-item.ui-open').count() > 0, { titles })
  } else info('溯源时间线（未查询到批次节点，跳过）', { recent, titles })
  await shot(page, 'A-溯源.png', 'A 田埂 · 批次溯源（纸质纪实 + 可展开时间线）')

  /* ---------------- 10. 弱动效 / 控制台 ---------------- */
  await page.locator('.lay-scheme-btn input[type="checkbox"]').check()
  await page.waitForTimeout(200)
  fail('弱动效开关写入 html.reduce-motion', await page.evaluate(() =>
    document.documentElement.classList.contains('reduce-motion')), {})
  await page.locator('.lay-scheme-btn input[type="checkbox"]').uncheck()

  const pass = results.filter((r) => r.ok === true).length
  const failed = results.filter((r) => r.ok === false).length
  const infos = results.filter((r) => r.info).length
  fail('全程 0 控制台错误', errors.length === 0, { errors: errors.slice(0, 5) })
  fail('全程 0 本地 4xx 响应', badResponses.length === 0, { badResponses: badResponses.slice(0, 5) })

  const summary = { total: results.length, pass, failed, infos, ratios,
    consoleErrors: errors.length, badResponses: badResponses.length, shots }
  fs.writeFileSync(path.join(OUT, 'ui_acceptance_results.json'),
    JSON.stringify({ summary, results }, null, 2), 'utf-8')
  console.log('\n=== UI 重构验收汇总 ===\n' + JSON.stringify(summary, null, 2))
  await browser.close()
  process.exit(failed ? 1 : 0)
})().catch((e) => { console.error('FATAL', e); process.exit(2) })
