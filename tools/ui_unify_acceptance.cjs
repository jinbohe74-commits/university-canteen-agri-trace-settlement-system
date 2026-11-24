/* ==========================================================================
   统一设计系统验收 · 第二轮（专治「切换方案像好几个系统」）
   --------------------------------------------------------------------------
   核心判据不是"功能在不在"，而是「跨页 / 跨明度模式，设计骨架是否恒定」：
   1. 跨页 data-mode 恒定（不再按路由自动分配皮肤）
   2. 三档模式下侧栏宽度、字体族、圆角、导航形态、命令栏、表格密度完全一致
   3. 三档 accent 色相一致（同一品牌色的明度适配，不是三套配色）
   4. 三档正文对比度 >= 4.5 且互不重复（L-028：读 html token 终值）
   5. 登录页品牌资产三档一致
   6. 原有交互全部保留 + 0 控制台错误 + 0 前端 4xx
   7. 逐张截图供人工目检
   ========================================================================== */
const path = require('path')
const fs = require('fs')

const ROOT = path.resolve(__dirname, '..')
const BASE = 'http://127.0.0.1:5173'
const OUT = path.join(ROOT, 'docs', 'design_2026-11-10', 'ui_unify_acceptance')
fs.mkdirSync(OUT, { recursive: true })

const GLOBAL = path.join(process.env.APPDATA, 'npm', 'node_modules')
module.paths.push(GLOBAL)
const { chromium } = require(path.join(GLOBAL, 'playwright'))

const results = []
const shots = []
const fail = (name, ok, detail) => {
  results.push({ name, ok: !!ok, ...detail })
  console.log((ok ? '  OK   ' : ' FAIL  ') + name + ' | ' + JSON.stringify(detail))
  return !!ok
}
const info = (name, detail) => {
  results.push({ name, ok: null, info: true, ...detail })
  console.log('  INFO ' + name + ' | ' + JSON.stringify(detail))
}

const sleep = ms => new Promise(r => setTimeout(r, ms))

/* ---------- 颜色工具 ---------- */
function parseColor(s) {
  s = (s || '').trim()
  if (s.startsWith('#')) {
    let h = s.slice(1)
    if (h.length === 3) h = h.split('').map(c => c + c).join('')
    return [parseInt(h.slice(0, 2), 16), parseInt(h.slice(2, 4), 16), parseInt(h.slice(4, 6), 16)]
  }
  const m = s.match(/-?[\d.]+/g)
  return m ? [Number(m[0]), Number(m[1]), Number(m[2])] : [0, 0, 0]
}
function luminance(rgb) {
  const a = rgb.map(v => {
    const c = v / 255
    return c <= 0.03928 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4)
  })
  return 0.2126 * a[0] + 0.7152 * a[1] + 0.0722 * a[2]
}
function contrast(fg, bg) {
  const l1 = luminance(parseColor(fg)), l2 = luminance(parseColor(bg))
  return (Math.max(l1, l2) + 0.05) / (Math.min(l1, l2) + 0.05)
}
function hueOf(s) {
  const [r, g, b] = parseColor(s).map(v => v / 255)
  const mx = Math.max(r, g, b), mn = Math.min(r, g, b), d = mx - mn
  if (!d) return 0
  let h
  if (mx === r) h = ((g - b) / d) % 6
  else if (mx === g) h = (b - r) / d + 2
  else h = (r - g) / d + 4
  h *= 60
  return h < 0 ? h + 360 : h
}

/* ---------- 页面采样 ---------- */
const modeOf = page => page.evaluate(() => document.documentElement.dataset.mode)

async function setMode(page, value) {
  // 明度模式存在 localStorage.ct_ui_mode（全站恒定，不再有下拉切换控件）
  await page.evaluate((v) => localStorage.setItem('ct_ui_mode', v), value)
  await page.reload({ waitUntil: 'domcontentloaded' })
  await sleep(900)
  return modeOf(page)
}

/* 骨架采样：只取「与颜色无关」的形态量 */
async function shellSnapshot(page) {
  return page.evaluate(() => {
    const cs = (sel, prop) => {
      const el = document.querySelector(sel)
      return el ? getComputedStyle(el)[prop] : null
    }
    const html = getComputedStyle(document.documentElement)
    return {
      sideDisplay: cs('.lay-side', 'display'),
      sideWidth: cs('.lay-side', 'width'),
      sideFont: cs('.lay-side', 'fontFamily'),
      navTextDisplay: cs('.lay-nav-text', 'display'),
      crumbDisplay: cs('.lay-crumb', 'display'),
      cmdbarDisplay: cs('.lay-cmdbar', 'display'),
      topnavCount: document.querySelectorAll('.lay-top-nav').length,
      pageTitleSize: cs('.page-title', 'fontSize'),
      bodyFont: cs('body', 'fontFamily'),
      radiusToken: html.getPropertyValue('--radius').trim(),
      navWToken: html.getPropertyValue('--nav-w').trim(),
      densToken: html.getPropertyValue('--dens').trim()
    }
  })
}
const shellKey = s => JSON.stringify(Object.keys(s).sort().map(k => [k, s[k]]))
/* 跨页比较只取「布局外壳」字段：页面标题字号由各页内容决定，不应纳入骨架一致性 */
const LAYOUT_FIELDS = ['sideDisplay', 'sideWidth', 'sideFont', 'navTextDisplay',
  'crumbDisplay', 'cmdbarDisplay', 'topnavCount', 'bodyFont',
  'radiusToken', 'navWToken', 'densToken']
const layoutKey = s => JSON.stringify(LAYOUT_FIELDS.map(k => [k, s[k]]))

/* 低对比文本审计（L-006 门禁）：遍历页面上的叶子文本元素，
   计算其实际前景色与最近不透明背景的对比度，列出 < 3.0 的实例。
   业务 view 里的浅色专用硬编码色在 night 下会变成「深底深字」，靠这条抓全。 */
const lowContrastAudit = () => {
  const parse = s => {
    s = (s || '').trim()
    if (s.startsWith('#')) {
      let h = s.slice(1); if (h.length === 3) h = h.split('').map(c => c + c).join('')
      return [parseInt(h.slice(0, 2), 16), parseInt(h.slice(2, 4), 16), parseInt(h.slice(4, 6), 16), 1]
    }
    const m = s.match(/-?[\d.]+/g)
    if (!m) return [0, 0, 0, 0]
    return [Number(m[0]), Number(m[1]), Number(m[2]), m[3] === undefined ? 1 : Number(m[3])]
  }
  const lum = rgb => {
    const a = rgb.slice(0, 3).map(v => { const c = v / 255; return c <= 0.03928 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4) })
    return 0.2126 * a[0] + 0.7152 * a[1] + 0.0722 * a[2]
  }
  const contrast = (fg, bg) => { const l1 = lum(fg), l2 = lum(bg); return (Math.max(l1, l2) + 0.05) / (Math.min(l1, l2) + 0.05) }
  const bgOf = el => {
    let node = el
    while (node && node !== document.documentElement) {
      const c = parse(getComputedStyle(node).backgroundColor)
      if (c[3] > 0.95) return c.slice(0, 3)
      node = node.parentElement
    }
    return [11, 20, 28]
  }
  const seen = new Set(), out = []
  document.querySelectorAll('.lay-content *').forEach(el => {
    if (el.children.length) return
    const txt = (el.textContent || '').trim()
    if (!txt || txt.length > 50) return
    const st = getComputedStyle(el)
    if (st.display === 'none' || st.visibility === 'hidden' || parseFloat(st.opacity) < 0.3) return
    const r = el.getBoundingClientRect()
    if (r.width < 2 || r.height < 2 || r.top > window.innerHeight) return
    if (contrast(parse(st.color), bgOf(el)) >= 3.0) return
    let cls = ''
    try { cls = typeof el.className === 'string' ? el.className : String(el.className.baseVal || '') } catch (e) { cls = '' }
    const key = (el.tagName + '|' + cls + '|' + st.color).slice(0, 160)
    if (seen.has(key)) return
    seen.add(key)
    out.push({ tag: el.tagName, cls: cls.slice(0, 60), color: st.color, sample: txt.slice(0, 24) })
  })
  return out
}

/* 白块审计：night 下仍使用浅色硬编码背景的可见容器（二维码容器除外） */
const brightBlockAudit = () => {
  const parse = s => {
    s = (s || '').trim()
    if (s.startsWith('#')) {
      let h = s.slice(1); if (h.length === 3) h = h.split('').map(c => c + c).join('')
      return [parseInt(h.slice(0, 2), 16), parseInt(h.slice(2, 4), 16), parseInt(h.slice(4, 6), 16), 1]
    }
    const m = s.match(/-?[\d.]+/g)
    if (!m) return [0, 0, 0, 0]
    return [Number(m[0]), Number(m[1]), Number(m[2]), m[3] === undefined ? 1 : Number(m[3])]
  }
  const lum = rgb => {
    const a = rgb.slice(0, 3).map(v => { const c = v / 255; return c <= 0.03928 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4) })
    return 0.2126 * a[0] + 0.7152 * a[1] + 0.0722 * a[2]
  }
  const seen = new Set(), out = []
  document.querySelectorAll('.lay-content *').forEach(el => {
    if (el.tagName === 'IMG' || el.tagName === 'CANVAS') return
    const st = getComputedStyle(el)
    const c = parse(st.backgroundColor)
    const grad = st.backgroundImage || 'none'
    // 浅色渐变（background-color 透明）也是白块：结算页 .formula/.eg-box 就躲在这里
    const lightGradient = grad !== 'none' && grad.includes('linear-gradient') && c[3] < 0.5
    const solidLight = c[3] >= 0.95 && lum(c) >= 0.62
    if (!solidLight && !lightGradient) return
    const r = el.getBoundingClientRect()
    if (r.width < 50 || r.height < 18 || r.top > window.innerHeight || r.bottom < 0) return
    let cls = ''
    try { cls = typeof el.className === 'string' ? el.className : String(el.className.baseVal || '') } catch (e) { cls = '' }
    if (cls.includes('qr-box')) return   // 二维码容器保持白底才能扫码
    const key = (el.tagName + '|' + cls + '|' + st.backgroundColor).slice(0, 150)
    if (seen.has(key)) return
    seen.add(key)
    out.push({ tag: el.tagName, cls: cls.slice(0, 60), bg: st.backgroundColor })
  })
  return out
}

async function tokenPair(page) {
  return page.evaluate(() => {
    const cs = getComputedStyle(document.documentElement)
    const get = n => cs.getPropertyValue(n).trim()
    return { ink: get('--ink'), surface: get('--surface'), bg: get('--bg'), accent: get('--accent') }
  })
}

async function shot(page, name, label) {
  // 清掉切换模式时的 toast，避免污染界面截图
  await page.evaluate(() => document.querySelectorAll('.ui-toast').forEach(e => e.remove()))
  await sleep(420)
  await page.screenshot({ path: path.join(OUT, name), fullPage: false })
  shots.push({ label, file: path.relative(ROOT, path.join(OUT, name)) })
  console.log('  SHOT ' + name + ' | ' + label)
}

;(async () => {
  const browser = await chromium.launch({ channel: 'msedge' })
  const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 } })
  await ctx.grantPermissions(['clipboard-read', 'clipboard-write'])
  const page = await ctx.newPage()
  const errors = [], badResponses = []
  page.on('console', m => { if (m.type() === 'error') errors.push(m.text().slice(0, 180)) })
  page.on('pageerror', e => errors.push('PAGEERROR: ' + e.message.slice(0, 180)))
  page.on('response', r => {
    if (r.status() >= 400 && r.url().startsWith(BASE)) {
      badResponses.push(r.status() + ' ' + r.url().replace(BASE, ''))
    }
  })

  /* ================= 1. 登录页：品牌资产三档一致 ================= */
  await page.goto(BASE + '/login', { waitUntil: 'domcontentloaded' })
  await sleep(950)
  fail('首屏默认模式为 day', (await modeOf(page)) === 'day', { mode: await modeOf(page) })
  fail('登录表单不预填任何凭据', await page.evaluate(() => {
    const u = document.querySelector('input[placeholder="请输入账号"]')
    const p = document.querySelector('input[placeholder="请输入密码"]')
    return !!u && !!p && u.value === '' && p.value === ''
  }), {})

  const assets = []
  for (const m of ['day', 'paper', 'night']) {
    await page.evaluate(v => {
      localStorage.setItem('ct_ui_mode', v)
      document.documentElement.dataset.mode = v
    }, m)
    await sleep(480)
    const bg = await page.evaluate(() => {
      const el = document.querySelector('.brand-side')
      return el ? getComputedStyle(el).backgroundImage : ''
    })
    assets.push({ mode: m, image: (bg.match(/[^/"']+\.png/) || [''])[0] })
  }
  await page.evaluate(() => { localStorage.removeItem('ct_ui_mode') })
  fail('登录页品牌主视觉在三档下完全一致（内容资产，不随模式切换）',
    new Set(assets.map(a => a.image)).size === 1 && /brand-hero\.png/.test(assets[0].image), { assets })
  await shot(page, 'D1-登录.png', '统一底座 · 登录页（摄影为固定内容资产）')

  /* ================= 2. 登录 ================= */
  await page.fill('input[placeholder="请输入账号"]', 'admin')
  await page.fill('input[placeholder="请输入密码"]', '123456')
  await page.click('button.submit')
  await sleep(2300)
  fail('登录跳转后外观仍为全局默认', (await modeOf(page)) === 'day', { mode: await modeOf(page) })

  /* ================= 3. 核心判据：跨页 data-mode 恒定 ================= */
  const routes = [
    ['/admin/dashboard', '管理员驾驶舱'],
    ['/admin/orders', '订单监管'],
    ['/admin/settlements', '结算审核'],
    ['/admin/trace', '批次溯源'],
    ['/admin/users', '用户与权限']
  ]
  const traceDefault = []
  for (const [url, label] of routes) {
    await page.goto(BASE + url, { waitUntil: 'domcontentloaded' })
    await sleep(1450)
    traceDefault.push({ url, mode: await modeOf(page) })
  }
  fail('默认模式：跨 5 个页面外观恒定（不再按路由分配三套皮肤）',
    traceDefault.every(x => x.mode === 'day'), { traceDefault })

  /* ================= 4. 切到 night，跨页骨架恒定 ================= */
  await page.goto(BASE + '/admin/orders', { waitUntil: 'domcontentloaded' })
  await sleep(1900)
  fail('顶栏可切换为 night', (await setMode(page, 'night')) === 'night', {})
  const nightTrace = [], nightShells = [], nightLow = [], nightBright = []
  for (const [url] of routes) {
    await page.goto(BASE + url, { waitUntil: 'domcontentloaded' })
    await sleep(1500)
    nightTrace.push({ url, mode: await modeOf(page) })
    nightShells.push(await shellSnapshot(page))
    ;(await page.evaluate(lowContrastAudit)).forEach(x => nightLow.push({ url, ...x }))
    ;(await page.evaluate(brightBlockAudit)).forEach(x => nightBright.push({ url, ...x }))
  }
  fail('night 模式：跨 5 个页面外观恒定', nightTrace.every(x => x.mode === 'night'), { nightTrace })
  fail('night 模式：跨 5 个页面 0 处低对比文本（对比度 < 3.0）',
    nightLow.length === 0, { count: nightLow.length, samples: nightLow.slice(0, 8) })
  fail('night 模式：跨 5 个页面 0 处浅色白块（二维码功能白底除外）',
    nightBright.length === 0, { count: nightBright.length, samples: nightBright.slice(0, 8) })
  fail('night 模式：跨 5 个页面设计骨架完全一致',
    nightShells.every(s => layoutKey(s) === layoutKey(nightShells[0])),
    { distinctShells: [...new Set(nightShells.map(layoutKey))].length, sample: nightShells[0] })

  /* ================= 5. 三档：骨架一致 / 色相一致 / 对比度 ================= */
  await page.goto(BASE + '/admin/orders', { waitUntil: 'domcontentloaded' })
  await sleep(1950)
  const shells = {}, ratios = {}, pairs = {}
  for (const m of ['day', 'paper', 'night']) {
    await setMode(page, m)
    shells[m] = await shellSnapshot(page)
    pairs[m] = await tokenPair(page)
    ratios[m] = Number(contrast(pairs[m].ink, pairs[m].surface).toFixed(2))
    fail(`模式 ${m} 正文/面板对比度 >= 4.5:1`, ratios[m] >= 4.5,
      { ratio: ratios[m], ink: pairs[m].ink, surface: pairs[m].surface })
    await shot(page, 'D2-' + m + '-订单.png', `统一底座 · ${m} 模式 · 同一张订单页`)
  }
  fail('三档明度模式的骨架完全相同（侧栏/字体/圆角/导航/密度）',
    shellKey(shells.day) === shellKey(shells.paper) && shellKey(shells.paper) === shellKey(shells.night),
    { day: shells.day, paper: shells.paper, night: shells.night })
  fail('三档背景色确实不同（切换生效，不是没换）',
    new Set(Object.values(pairs).map(p => p.bg)).size === 3,
    { bgs: Object.fromEntries(Object.entries(pairs).map(([k, v]) => [k, v.bg])) })
  fail('三档对比度自检值互不重复（L-028 防滞后一轮）',
    new Set(Object.values(ratios)).size === 3, { ratios })
  const hues = Object.fromEntries(Object.entries(pairs).map(([k, v]) => [k, Number(hueOf(v.accent).toFixed(1))]))
  const hueSpread = Math.max(...Object.values(hues)) - Math.min(...Object.values(hues))
  fail('三档 accent 色相一致（同一品牌色相的明度适配，色相差 <= 12°）',
    hueSpread <= 12, { hues, hueSpread, accents: Object.fromEntries(Object.entries(pairs).map(([k, v]) => [k, v.accent])) })

  /* ================= 6. 跨页视觉一致性截图 ================= */
  for (const [url, name, label] of [
    ['/admin/dashboard', 'D3-night-驾驶舱.png', 'night 模式 · 管理员驾驶舱（与订单页同一骨架）'],
    ['/admin/trace', 'D4-night-溯源.png', 'night 模式 · 批次溯源（与订单页同一骨架）'],
    ['/admin/settlements', 'D5-night-结算.png', 'night 模式 · 结算审核（表格密集页）']
  ]) {
    await page.goto(BASE + url, { waitUntil: 'domcontentloaded' })
    await sleep(2000)
    await shot(page, name, label)
  }
  await setMode(page, 'day')
  await page.goto(BASE + '/admin/dashboard', { waitUntil: 'domcontentloaded' })
  await sleep(2000)
  await shot(page, 'D6-day-驾驶舱.png', 'day 模式 · 同一驾驶舱（只有明度变化，骨架不变）')

  /* ================= 7. 交互回归（上一轮能力一个都不能丢） ================= */
  await page.goto(BASE + '/admin/orders', { waitUntil: 'domcontentloaded' })
  await sleep(2000)
  await page.keyboard.press('Control+k')
  await sleep(260)
  const cmdVisible = await page.locator('.cmd-overlay').count()
  fail('Ctrl+K 打开命令面板', cmdVisible > 0, { cmdVisible })
  if (cmdVisible) {
    await page.fill('.cmd-head input', '结算')
    await sleep(200)
    const filtered = await page.locator('.cmd-item').count()
    fail('命令面板实时过滤', filtered > 0 && filtered < 14, { filtered })
    await page.keyboard.press('Enter')
    await sleep(1500)
    fail('命令面板回车直达目标页', page.url().includes('/admin/settlements'), { url: page.url() })
  }

  await page.goto(BASE + '/admin/orders', { waitUntil: 'domcontentloaded' })
  await sleep(2050)
  /* 验收标准已于 2026-11-10 更新：
     - 经验库 L-020：DOM 层「即时筛选 / 状态 chip」只过滤当前页 10 条，与服务端筛选
       并排出现时会让人以为筛选失效，已彻底移除，筛选一律走后端接口。
     - 行内「复制」按钮注入到 40px 复选框首列，撑破单元格，也已彻底移除。
     这里改成断言「不出现这些假交互 + 服务端筛选条仍在」，防止它们被改回来。 */
  const domFake = {
    search: await page.locator('[data-ui-search]').count(),
    livebar: await page.locator('.ui-livebar').count(),
    chips: await page.locator('.ui-chip').count(),
    rowOps: await page.locator('.ui-row-ops').count(),
    serverFilter: await page.locator('.filter-bar').count(),
    tabRows: await page.locator('.tabs-row').count()
  }
  fail('筛选统一走后端，无 DOM 层假筛选工具条',
    domFake.search === 0 && domFake.livebar === 0 && domFake.chips === 0 && domFake.serverFilter > 0,
    domFake)
  fail('状态筛选工具条不重复（不生成第二套 chip）',
    domFake.tabRows <= 1 && domFake.chips === 0, { tabRows: domFake.tabRows, injectedChips: domFake.chips })
  fail('表格行内不再注入「复制」按钮（首列不再被撑破）', domFake.rowOps === 0, { rowOps: domFake.rowOps })

  await page.goto(BASE + '/admin/settlements', { waitUntil: 'domcontentloaded' })
  await sleep(2200)
  const selBoxes = await page.locator('.el-table__body .el-checkbox').count()
  if (selBoxes) {
    await page.locator('.el-table__body .el-checkbox').first().click()
    await sleep(360)
    const barOn = await page.locator('.ui-floatbar.on').count()
    const cnt = await page.locator('.ui-float-count').innerText().catch(() => '0')
    fail('勾选后批量浮动条出现且计数正确', barOn === 1 && Number(cnt) >= 1, { selBoxes, barOn, cnt })
    await page.locator('[data-ui-clear]').click()
    await sleep(260)
  } else info('批量浮动条（结算页未渲染多选列）', { selBoxes })

  await page.goto(BASE + '/admin/trace', { waitUntil: 'domcontentloaded' })
  await sleep(2100)
  const recent = await page.locator('.recent-row .chip').count()
  if (recent) { await page.locator('.recent-row .chip').first().click(); await sleep(1200) }
  const titles = await page.locator('.timeline-title[data-ui-timeline]').count()
  if (titles) {
    await page.locator('.timeline-title[data-ui-timeline]').first().click()
    await sleep(280)
    fail('溯源时间线节点可展开', await page.locator('.timeline-item.ui-open').count() > 0, { titles })
  } else info('溯源时间线（未查询到批次节点，跳过）', { recent, titles })

  /* 外观 / 弱动效已收进顶栏用户菜单（.lay-userbox → .lay-usermenu），
     旧脚本用的 .lay-scheme-btn 早就不存在了，必须先展开菜单才能勾。 */
  if (!(await page.locator('.lay-usermenu').count())) {
    await page.locator('.lay-userbox').click()
    await sleep(220)
  }
  await page.locator('.lum-check input[type="checkbox"]').check()
  await sleep(220)
  fail('弱动效开关写入 html.reduce-motion', await page.evaluate(() =>
    document.documentElement.classList.contains('reduce-motion')), {})
  await page.locator('.lum-check input[type="checkbox"]').uncheck()

  /* ================= 8. 控制台与资源 ================= */
  fail('全程 0 控制台错误', errors.length === 0, { errors: errors.slice(0, 6) })
  fail('全程 0 前端 4xx 响应', badResponses.length === 0, { badResponses: badResponses.slice(0, 6) })

  const pass = results.filter(r => r.ok === true).length
  const failed = results.filter(r => r.ok === false).length
  const infos = results.filter(r => r.info).length
  const summary = { total: results.length, pass, failed, infos, ratios, hues, hueSpread,
    consoleErrors: errors.length, badResponses: badResponses.length, shots }
  fs.writeFileSync(path.join(OUT, 'results.json'), JSON.stringify({ summary, results }, null, 2), 'utf-8')
  console.log('\n=== 统一设计系统验收汇总 ===\n' + JSON.stringify(summary, null, 2))
  await browser.close()
  process.exit(failed ? 1 : 0)
})().catch(e => { console.error('FATAL', e); process.exit(2) })
