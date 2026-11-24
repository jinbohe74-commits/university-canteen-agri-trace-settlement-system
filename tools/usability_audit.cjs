/* ==========================================================================
   功能可用性基线审计（2026-11-10）
   --------------------------------------------------------------------------
   判据不是「控件存在」，而是「点了以后真实发生了什么」：
   1. 筛选 → 是否发出带参数的请求 / 数据集是否变化
   2. 导出 → 是否真的触发浏览器下载（download 事件 + 文件名）
   3. 弹窗 → 相对视口的几何中心偏差、页面滚动后是否仍居中、是否溢出视口
   4. 登录选角色 → POST body 是否携带角色、后端是否据此校验
   5. 全局 → 只弹 toast 不产生副作用的可疑按钮清点
   本脚本在修复前后各跑一次：修复前记录缺陷，修复后翻转成门禁。
   ========================================================================== */
const path = require('path')
const fs = require('fs')

const ROOT = path.resolve(__dirname, '..')
const BASE = 'http://127.0.0.1:5173'
const OUT = path.join(ROOT, 'docs', 'usability_2026-11-10')
fs.mkdirSync(OUT, { recursive: true })
const PHASE = process.argv[2] || 'baseline'
const RESULT_FILE = path.join(OUT, PHASE + '.json')

const GLOBAL = path.join(process.env.APPDATA, 'npm', 'node_modules')
module.paths.push(GLOBAL)
const { chromium } = require(path.join(GLOBAL, 'playwright'))

const results = []
const rec = (name, ok, detail) => {
  results.push({ name, ok, ...detail })
  console.log((ok ? '  OK   ' : ' DEFECT') + ' ' + name + ' | ' + JSON.stringify(detail))
  return ok
}
const sleep = ms => new Promise(r => setTimeout(r, ms))

;(async () => {
  const browser = await chromium.launch({ channel: 'msedge' })
  const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 }, acceptDownloads: true })
  const page = await ctx.newPage()

  const requests = []
  const downloads = []
  const consoleErrors = []
  page.on('request', r => {
    if (r.url().includes('/api/')) {
      let body = ''
      try { body = (r.postData() || '').slice(0, 300) } catch (e) { /* ignore */ }
      requests.push({ method: r.method(), url: r.url().replace('http://127.0.0.1:8080', ''), body })
    }
  })
  page.on('download', d => downloads.push(d.suggestedFilename()))
  page.on('console', m => { if (m.type() === 'error') consoleErrors.push(m.text().slice(0, 160)) })
  page.on('pageerror', e => consoleErrors.push('PAGEERROR: ' + e.message.slice(0, 160)))

  const apiCalls = (pathPart, method = 'GET', since = 0) =>
    requests.slice(since).filter(r => r.url.includes(pathPart) && r.method === method)

  /* ================= 1. 登录页：角色选择是否真的参与认证 ================= */
  await page.goto(BASE + '/login', { waitUntil: 'domcontentloaded' })
  await sleep(800)
  const roleButtons = await page.locator('.role-item').count()
  rec('登录页存在 4 个角色按钮', roleButtons === 4, { roleButtons })

  // 选「农户」，但用管理员账号登录：角色若真生效，后端应拒绝或明确提示
  await page.locator('.role-item').nth(1).click()
  const activeRole = await page.locator('.role-item.active span').innerText()
  await page.fill('input[placeholder="请输入账号"]', 'admin')
  await page.fill('input[placeholder="请输入密码"]', '123456')
  const loginBefore = requests.length
  await page.click('button.submit')
  await sleep(2200)
  const loginCalls = apiCalls('/auth/login', 'POST', loginBefore)
  const loginBody = loginCalls.length ? loginCalls[loginCalls.length - 1].body : ''
  rec('选中的角色随登录请求提交给后端',
    /"(role|expectedRole)"\s*:/.test(loginBody),
    { selectedRole: activeRole, loginBody: loginBody.slice(0, 200) })
  rec('角色与账号不符时给出明确拒绝/提示',
    await page.evaluate(() => document.body.innerText.includes('角色') &&
      /不是|不符|请选择|切换/.test(document.body.innerText)),
    { urlAfterLogin: page.url().replace(BASE, '') })

  /* ================= 2. 操作日志：四项筛选是否真实生效 ================= */
  await page.goto(BASE + '/admin/logs', { waitUntil: 'domcontentloaded' })
  await sleep(1800)
  const totalText = () => page.locator('.pager span').first().innerText()
  const rowCount = () => page.locator('.el-table__body tbody tr').count()
  const firstCell = () => page.locator('.el-table__body tbody tr').first().innerText()

  const baseTotal = await totalText()
  const baseFirst = await firstCell()

  // 2.1 功能模块下拉
  const moduleBefore = requests.length
  await page.locator('.filter-bar .el-select').nth(0).click()
  await sleep(350)
  const opts = page.locator('.el-select-dropdown__item:visible')
  const optCount = await opts.count()
  if (optCount > 1) {
    const chosen = (await opts.nth(1).innerText()).trim()
    await opts.nth(1).click()
    await sleep(1400)
    const calls = apiCalls('/admin/logs', 'GET', moduleBefore)
    const withModule = calls.some(r => /[?&]module=/.test(r.url))
    const afterTotal = await totalText()
    rec('功能模块筛选：发出带 module 的请求且结果集变化',
      withModule && afterTotal !== baseTotal,
      { chosen, calls: calls.map(c => c.url), baseTotal, afterTotal })
    rec('功能模块下拉有真实模块选项', optCount > 1, { optCount })
  } else {
    rec('功能模块下拉有真实模块选项', false, { optCount })
  }

  // 2.2 操作结果下拉
  const resultBefore = requests.length
  await page.locator('.filter-bar .el-select').nth(1).click()
  await sleep(300)
  const resultOpts = page.locator('.el-select-dropdown__item:visible')
  if (await resultOpts.count() > 1) {
    await resultOpts.nth(1).click()
    await sleep(1400)
    const calls = apiCalls('/admin/logs', 'GET', resultBefore)
    rec('操作结果筛选：发出带 result 的请求', calls.some(r => /[?&]result=/.test(r.url)),
      { calls: calls.map(c => c.url), total: await totalText() })
  }

  // 2.3 关键词
  const kwBefore = requests.length
  await page.locator('.filter-bar input[placeholder="操作人 / 操作内容"]').fill('REVIEW_UNIQUE_8891')
  await page.locator('.filter-bar button:has-text("查询")').click()
  await sleep(1400)
  const kwCalls = apiCalls('/admin/logs', 'GET', kwBefore)
  rec('关键词筛选：发出带 keyword 的请求且空结果有反馈',
    kwCalls.some(r => /[?&]keyword=/.test(r.url)) && (await rowCount()) === 0,
    { calls: kwCalls.map(c => c.url), rows: await rowCount(), emptyText: await page.locator('.el-table__empty-text').count() })
  await page.locator('.filter-bar button:has-text("重置")').click()
  await sleep(1200)

  // 2.4 注入层是否误导用户（本地 DOM 过滤 vs 服务端筛选）
  const injected = await page.evaluate(() => {
    const bar = document.querySelector('.filter-bar')
    const search = document.querySelector('[data-ui-search]')
    const chips = document.querySelectorAll('.ui-chip').length
    return {
      hasInjectedSearch: !!search,
      injectedInFilterBar: !!(search && bar && bar.contains(search)),
      injectedPlaceholder: search ? search.placeholder : '',
      injectedChips: chips,
      nativeFilterControls: bar ? bar.querySelectorAll('.el-select, .el-date-editor, .el-input').length : 0
    }
  })
  rec('筛选区不混杂「只在当前页过滤」的注入控件',
    !injected.hasInjectedSearch || injected.injectedPlaceholder.includes('服务端'),
    injected)

  /* ================= 3. 导出：必须真的下载文件 ================= */
  const checkDownload = async (url, buttonText, label) => {
    await page.goto(BASE + url, { waitUntil: 'domcontentloaded' })
    await sleep(1500)
    const before = downloads.length
    const btn = page.locator('button:has-text("' + buttonText + '")').first()
    if (await btn.count() === 0) { rec(label + '：按钮存在', false, { url }); return }
    await btn.click()
    await sleep(1300)
    const added = downloads.slice(before)
    rec(label + '：点击后产生真实文件下载', added.length > 0,
      { url, downloads: added, toastOnly: added.length === 0 })
  }
  await checkDownload('/admin/dashboard', '导出报表', '看板导出报表')
  await checkDownload('/admin/farmers', '导出名册', '农户导出名册')
  await checkDownload('/admin/canteens', '导出名册', '食堂导出名册')
  await checkDownload('/admin/products', '下载模板', '农产品导入模板')
  await checkDownload('/admin/logs', '导出日志', '操作日志导出（对照组：应为真下载）')

  /* ================= 4. 弹窗几何：是否居中 / 是否随滚动漂移 ================= */
  const dialogCases = []
  const probeDialog = async (url, clickText, label) => {
    await page.goto(BASE + url, { waitUntil: 'domcontentloaded' })
    await sleep(1500)
    const btn = page.locator('button:has-text("' + clickText + '")').first()
    if (await btn.count() === 0) { dialogCases.push({ label, opened: false }); return }
    await btn.click()
    await sleep(700)
    const geo = await page.evaluate(() => {
      const d = document.querySelector('.el-dialog:not([style*="display: none"])')
      if (!d) return null
      const r = d.getBoundingClientRect()
      return {
        top: Math.round(r.top), bottom: Math.round(r.bottom),
        left: Math.round(r.left), right: Math.round(r.right),
        h: Math.round(r.height), w: Math.round(r.width),
        viewportH: window.innerHeight, viewportW: window.innerWidth,
        centerOffsetY: Math.round((r.top + r.height / 2) - window.innerHeight / 2),
        centerOffsetX: Math.round((r.left + r.width / 2) - window.innerWidth / 2),
        overflows: r.top < 0 || r.bottom > window.innerHeight
      }
    })
    dialogCases.push({ label, opened: !!geo, ...(geo || {}) })
    await page.keyboard.press('Escape')
    await sleep(250)
  }
  await probeDialog('/admin/canteens', '新增', '食堂-新增')
  await probeDialog('/admin/products', '新增', '农产品-新增')
  await probeDialog('/purchaser/demands', '发布需求', '采购需求-发布')

  const opened = dialogCases.filter(d => d.opened)
  rec('编辑/新增弹窗在视口垂直居中（偏差 <= 24px）',
    opened.length >= 2 && opened.every(d => Math.abs(d.centerOffsetY) <= 24),
    { cases: dialogCases })
  rec('弹窗不溢出视口',
    opened.length >= 2 && opened.every(d => !d.overflows),
    { cases: dialogCases })

  // 页面滚动后再开一次，验证「固定在某处、不随视口居中」
  await page.goto(BASE + '/admin/canteens', { waitUntil: 'domcontentloaded' })
  await sleep(1400)
  await page.evaluate(() => window.scrollTo(0, 320))
  await sleep(250)
  const addBtn = page.locator('button:has-text("新增")').first()
  if (await addBtn.count()) {
    await addBtn.click()
    await sleep(700)
    const scrolled = await page.evaluate(() => {
      const d = document.querySelector('.el-dialog')
      if (!d) return null
      const r = d.getBoundingClientRect()
      return {
        scrollY: window.scrollY,
        centerOffsetY: Math.round((r.top + r.height / 2) - window.innerHeight / 2),
        top: Math.round(r.top)
      }
    })
    rec('页面滚动后弹窗仍以视口为中心', !!scrolled && Math.abs(scrolled.centerOffsetY) <= 24, { scrolled })
  }

  /* ================= 5. 消息标已读是否有真实状态变化 ================= */
  // 静态清点：仅弹 toast、无下载/无请求/无状态写入的按钮（由脚本 grep 结果给出）
  const fakeOnly = []
  const viewDir = path.join(ROOT, 'frontend', 'src', 'views')
  const walk = dir => fs.readdirSync(dir, { withFileTypes: true }).flatMap(e =>
    e.isDirectory() ? walk(path.join(dir, e.name)) : (e.name.endsWith('.vue') ? [path.join(dir, e.name)] : []))
  for (const f of walk(viewDir)) {
    const src = fs.readFileSync(f, 'utf8')
    const m = src.match(/ElMessage\.(?:success|info|warning)\(\s*[`'"]?[^)]{0,80}(?:已导出|已生成|已下载|已唤起|已暂存)[^)]*\)/g)
    if (m) {
      const hasRealAction = /downloadCsv|createObjectURL|\.click\(\)|await .*Api\.|URL\.createObjectURL/.test(src)
      if (!hasRealAction) fakeOnly.push({ file: path.relative(ROOT, f).replace(/\\/g, '/'), samples: m.slice(0, 3) })
    }
  }
  rec('不存在「只弹提示、不产生任何副作用」的假按钮', fakeOnly.length === 0,
    { count: fakeOnly.length, files: fakeOnly.map(x => x.file) })

  rec('审计期间 0 控制台错误', consoleErrors.length === 0, { count: consoleErrors.length, samples: consoleErrors.slice(0, 5) })

  const summary = {
    phase: PHASE,
    at: new Date().toISOString(),
    total: results.length,
    defects: results.filter(r => r.ok === false).length,
    passed: results.filter(r => r.ok === true).length,
    downloads: downloads,
    consoleErrors: consoleErrors.length
  }
  fs.writeFileSync(RESULT_FILE, JSON.stringify({ summary, results }, null, 2), 'utf8')
  console.log('\n=== ' + PHASE + ' 汇总 ===\n' + JSON.stringify(summary, null, 2))
  console.log('结果文件：' + RESULT_FILE)
  await browser.close()
})().catch(e => { console.error('审计脚本异常:', e); process.exit(1) })
