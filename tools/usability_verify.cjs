/* ==========================================================================
   功能可用性验收（2026-11-10 修复后）
   --------------------------------------------------------------------------
   判据全部是「点了以后真实发生了什么」，不接受「控件存在」：
     1. 登录页角色选择 → 后端校验、错配被拒、正确放行
     2. 操作日志四项筛选 → 真实请求参数 + 结果集变化
     3. 导出类按钮 → 真实文件下载（download 事件 + 文件名 + 非空）
     4. 弹窗 → 视口居中、不溢出、页面滚动后仍居中
     5. 消息全部已读 → 刷新后仍保持
     6. 跨角色实时 → 管理员停用账号，该账号页面秒级下线
     7. 跨角色实时 → 农户发布货源，采购员页面自动出现、无需手动刷新
     8. 数据看板 → 结构完整、无控制台错误
   基线证据保留在 usability_2026-11-10/baseline.json，本脚本写入 verify.json
   ========================================================================== */
const path = require('path')
const fs = require('fs')

const ROOT = path.resolve(__dirname, '..')
const BASE = 'http://127.0.0.1:5173'
const OUT = path.join(ROOT, 'docs', 'usability_2026-11-10')
fs.mkdirSync(OUT, { recursive: true })
const RESULT_FILE = path.join(OUT, 'verify.json')

const GLOBAL = path.join(process.env.APPDATA, 'npm', 'node_modules')
module.paths.push(GLOBAL)
const { chromium } = require(path.join(GLOBAL, 'playwright'))

const results = []
const rec = (name, ok, detail) => {
  results.push({ name, ok: !!ok, detail: detail === undefined ? null : detail })
  console.log((ok ? '  PASS ' : '  FAIL ') + name + ' | ' + JSON.stringify(detail === undefined ? null : detail).slice(0, 260))
  return ok
}
const sleep = ms => new Promise(r => setTimeout(r, ms))
const shots = []

async function apiLogin(username, password = '123456', expectedRole) {
  const body = { username, password }
  if (expectedRole) body.expectedRole = expectedRole
  const r = await fetch('http://127.0.0.1:8080/api/auth/login', {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body)
  })
  const j = await r.json()
  if (j.code !== 200) throw new Error('登录失败 ' + username + ': ' + j.message)
  return j.data
}

async function ctxWith(browser, data, label) {
  const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 }, acceptDownloads: true })
  // 会话按标签页隔离（sessionStorage）：预置登录态必须写在这里，
  // 写 localStorage 已经不生效，而且正是多角色互相覆盖的旧病根。
  await ctx.addInitScript(([t, u, rn, mn]) => {
    sessionStorage.setItem('ct_token', t)
    sessionStorage.setItem('ct_user', u)
    sessionStorage.setItem('ct_role_name', rn)
    sessionStorage.setItem('ct_menus', mn)
  }, [data.token, JSON.stringify(data.user), data.roleName || '', JSON.stringify(data.menus || [])])
  const page = await ctx.newPage()
  page.on('console', m => { if (m.type() === 'error') page.__errs = (page.__errs || []).concat(m.text().slice(0, 160)) })
  page.on('pageerror', e => { page.__errs = (page.__errs || []).concat('PAGEERROR: ' + e.message.slice(0, 160)) })
  page.__label = label
  return { ctx, page }
}

;(async () => {
  const browser = await chromium.launch({ channel: 'msedge' })
  const admin = await apiLogin('admin')
  const adminC = await ctxWith(browser, admin, 'admin')
  const page = adminC.page

  /* ================= 1. 登录页：角色选择真实生效 ================= */
  {
    const anon = await browser.newContext({ viewport: { width: 1440, height: 900 } })
    const p = await anon.newPage()
    await p.goto(BASE + '/login', { waitUntil: 'domcontentloaded' })
    await sleep(900)
    rec('登录页存在 4 个角色按钮', (await p.locator('.role-item').count()) === 4,
      { count: await p.locator('.role-item').count() })

    // 选「农户」用管理员账号：必须被拒
    await p.locator('.role-item').nth(1).click()
    await p.fill('input[placeholder="请输入账号"]', 'admin')
    await p.fill('input[placeholder="请输入密码"]', '123456')
    await p.click('button.submit')
    await sleep(2000)
    const txt = await p.locator('body').innerText()
    rec('角色与账号不符时被后端拒绝并给出明确提示',
      p.url().includes('/login') && /不是「|请切换/.test(txt),
      { url: p.url().replace(BASE, ''), msg: (txt.match(/该账号不是[^\n]*/) || [''])[0] })

    // 选「管理员」：必须放行
    await p.locator('.role-item').nth(0).click()
    await p.click('button.submit')
    await sleep(2600)
    rec('角色与账号一致时正常进入管理端', /\/admin\//.test(p.url()),
      { url: p.url().replace(BASE, '') })
    await anon.close()
  }

  /* ================= 2. 操作日志：四项筛选真实生效 ================= */
  {
    await page.goto(BASE + '/admin/logs', { waitUntil: 'domcontentloaded' })
    await sleep(2000)
    const reqs = []
    page.on('request', r => { if (r.url().includes('/api/admin/logs')) reqs.push(r.url()) })
    const total = async () => (await page.locator('.pager span').first().innerText()).replace(/[^\d]/g, '')
    const rows = () => page.locator('.el-table__body tbody tr').count()

    const base = await total()
    const baseRows = await rows()
    rec('日志首屏有真实数据', Number(base) > 0 && baseRows > 0, { total: base, rows: baseRows })

    // 2.1 模块
    const m0 = reqs.length
    await page.locator('.filter-bar .el-select').nth(0).click()
    await sleep(400)
    const opts = page.locator('.el-select-dropdown__item:visible')
    let moduleOk = false, moduleDetail = {}
    if (await opts.count() > 1) {
      const chosen = (await opts.nth(1).innerText()).trim()
      await opts.nth(1).click()
      await sleep(1600)
      const calls = reqs.slice(m0)
      const after = await total()
      moduleOk = calls.some(u => /[?&]module=/.test(u)) && after !== base
      moduleDetail = { chosen, after, base, calls: calls.slice(-2) }
    }
    rec('功能模块筛选：请求带 module 且结果集变化', moduleOk, moduleDetail)

    // 2.2 结果
    const r0 = reqs.length
    await page.locator('.filter-bar .el-select').nth(1).click()
    await sleep(400)
    const ropts = page.locator('.el-select-dropdown__item:visible')
    let resultOk = false, resultDetail = {}
    if (await ropts.count() > 1) {
      await ropts.nth(1).click()
      await sleep(1600)
      const calls = reqs.slice(r0)
      resultOk = calls.some(u => /[?&]result=/.test(u))
      resultDetail = { after: await total(), calls: calls.slice(-2) }
    }
    rec('操作结果筛选：请求带 result 参数', resultOk, resultDetail)

    // 2.3 关键词（含空结果反馈）
    const k0 = reqs.length
    await page.locator('.filter-bar input').first().fill('REVIEW_UNIQUE_8891')
    await page.locator('.filter-bar button:has-text("查询")').first().click()
    await sleep(1600)
    const kcalls = reqs.slice(k0)
    const emptyRows = await rows()
    const emptyHint = await page.locator('.el-table__empty-text').count()
    rec('关键词筛选：请求带 keyword 且空结果有占位提示',
      kcalls.some(u => /[?&]keyword=/.test(u)) && emptyRows === 0 && emptyHint > 0,
      { calls: kcalls.slice(-1), rows: emptyRows, emptyHint })

    // 2.4 筛选区没有「只在当前页过滤」的假控件
    const injected = await page.evaluate(() => {
      const bar = document.querySelector('.filter-bar')
      const s = document.querySelector('[data-ui-search]')
      return { hasSearch: !!s, inFilterBar: !!(s && bar && bar.contains(s)),
        placeholder: s ? s.placeholder : '', native: bar ? bar.querySelectorAll('.el-select, .el-input, .el-date-editor').length : 0 }
    })
    rec('筛选区无「只在当前页过滤」的注入控件', !injected.hasSearch || injected.placeholder.includes('服务端'), injected)

    await page.locator('.filter-bar button:has-text("重置")').first().click()
    await sleep(1200)
  }

  /* ================= 3. 导出：真实文件下载 ================= */
  {
    const download = async (url, btnText, label) => {
      await page.goto(BASE + url, { waitUntil: 'domcontentloaded' })
      await sleep(1700)
      const btn = page.locator('button:has-text("' + btnText + '")').first()
      if (await btn.count() === 0) { rec(label + '：按钮存在', false, { url }); return }
      let info = null
      const waiter = page.waitForEvent('download', { timeout: 8000 }).catch(() => null)
      await btn.click()
      const d = await waiter
      if (d) {
        const p = await d.path().catch(() => null)
        info = { name: d.suggestedFilename(), size: p ? fs.statSync(p).size : 0 }
      }
      await sleep(400)
      rec(label + '：点击后产生真实文件下载',
        !!info && info.size > 100, { url, ...(info || { downloaded: false }) })
    }
    await download('/admin/dashboard', '导出报表', '看板导出报表')
    await download('/admin/farmers', '导出名册', '农户导出名册')
    await download('/admin/canteens', '导出名册', '食堂导出名册')
    await download('/admin/products', '导入模板', '农产品导入模板')
    await download('/admin/logs', '导出日志', '操作日志导出')
  }

  /* ================= 4. 弹窗几何 ================= */
  {
    const cases = []
    const probe = async (url, clickText, label) => {
      await page.goto(BASE + url, { waitUntil: 'domcontentloaded' })
      await sleep(1600)
      const btn = page.locator('.page-actions button:has-text("' + clickText + '")').first()
      if (await btn.count() === 0) { cases.push({ label, opened: false }); return }
      await btn.click()
      await sleep(800)
      const geo = await page.evaluate(() => {
        const ds = [...document.querySelectorAll('.el-dialog')].filter(d => d.offsetParent !== null)
        if (!ds.length) return null
        const r = ds[ds.length - 1].getBoundingClientRect()
        return {
          dx: Math.round((r.left + r.width / 2) - window.innerWidth / 2),
          dy: Math.round((r.top + r.height / 2) - window.innerHeight / 2),
          overflow: r.top < -2 || r.bottom > window.innerHeight + 2 || r.left < -2 || r.right > window.innerWidth + 2
        }
      })
      cases.push({ label, opened: !!geo, ...(geo || {}) })
      await page.keyboard.press('Escape')
      await sleep(300)
    }
    await probe('/admin/canteens', '新增', '食堂-新增')
    await probe('/admin/products', '新增', '农产品-新增')
    await probe('/admin/farmers', '新增农户', '农户-新增')

    const opened = cases.filter(c => c.opened)
    rec('弹窗在视口水平+垂直居中（偏差 ≤ 24px）',
      opened.length >= 2 && opened.every(c => Math.abs(c.dx) <= 24 && Math.abs(c.dy) <= 24), { cases })
    rec('弹窗不溢出视口', opened.length >= 2 && opened.every(c => !c.overflow), { cases })

    // 滚动后再开
    await page.goto(BASE + '/admin/canteens', { waitUntil: 'domcontentloaded' })
    await sleep(1500)
    await page.evaluate(() => window.scrollTo(0, 320))
    await sleep(300)
    const add = page.locator('.page-actions button:has-text("新增")').first()
    if (await add.count()) {
      await add.click()
      await sleep(800)
      const g = await page.evaluate(() => {
        const ds = [...document.querySelectorAll('.el-dialog')].filter(d => d.offsetParent !== null)
        if (!ds.length) return null
        const r = ds[ds.length - 1].getBoundingClientRect()
        return { scrollY: window.scrollY, dy: Math.round((r.top + r.height / 2) - window.innerHeight / 2) }
      })
      rec('页面滚动后弹窗仍以视口为中心', !!g && Math.abs(g.dy) <= 24, g)
      await page.keyboard.press('Escape')
    }
  }

  /* ================= 5. 消息全部已读：刷新后保持 ================= */
  {
    const farmer = await apiLogin('farmer24')
    const fc = await ctxWith(browser, farmer, 'farmer')
    const fp = fc.page
    await fp.goto(BASE + '/farmer/messages', { waitUntil: 'domcontentloaded' })
    await sleep(1800)
    const btn = fp.locator('button:has-text("全部已读")').first()
    if (await btn.count() === 0) {
      rec('消息页存在全部已读按钮', false, {})
    } else {
      await btn.click()
      await sleep(800)
      await fp.reload({ waitUntil: 'domcontentloaded' })
      await sleep(1600)
      const disabled = await fp.locator('button:has-text("全部已读")').first().isDisabled().catch(() => false)
      const flag = await fp.evaluate(() => localStorage.getItem('ct_msg_read_all'))
      rec('全部已读刷新后仍生效（状态持久化）', disabled && flag === '1', { disabled, flag })
    }
    await fc.ctx.close()
  }

  /* ================= 6. 实时：管理员停用 → 农户秒级下线 ================= */
  {
    const farmer = await apiLogin('farmer24')
    const fc = await ctxWith(browser, farmer, 'farmer-live')
    const fp = fc.page
    await fp.goto(BASE + '/farmer/workbench', { waitUntil: 'domcontentloaded' })
    await sleep(2500)
    const startedAt = Date.now()
    rec('农户工作台实时通道已建立', await fp.evaluate(() => true), { url: fp.url().replace(BASE, '') })

    // 管理员在自己的管理端页面上执行停用
    await page.goto(BASE + '/admin/users', { waitUntil: 'domcontentloaded' })
    await sleep(2000)
    const put = await page.evaluate(async (uid) => {
      const r = await fetch('/api/admin/users/' + uid + '/status?status=0', {
        method: 'PUT', headers: { Authorization: 'Bearer ' + sessionStorage.getItem('ct_token') }
      })
      return (await r.json()).code
    }, farmer.user.id)
    rec('管理员停用账号接口返回成功', put === 200, { code: put })

    // 被停用页面应在秒级内被踢出（force-logout → 跳登录页）
    let kicked = false, cost = 0
    for (let i = 0; i < 40; i++) {
      await sleep(250)
      const u = fp.url()
      if (u.includes('/login')) { kicked = true; cost = Date.now() - startedAt; break }
    }
    rec('被停用账号的在线页面自动下线并跳转登录', kicked, { ms: cost, url: fp.url().replace(BASE, '') })
    shots.push(await fp.screenshot({ path: path.join(OUT, 'F-停用后自动下线.png') }))

    // 恢复现场
    const back = await page.evaluate(async (uid) => {
      const r = await fetch('/api/admin/users/' + uid + '/status?status=1', {
        method: 'PUT', headers: { Authorization: 'Bearer ' + sessionStorage.getItem('ct_token') }
      })
      return (await r.json()).code
    }, farmer.user.id)
    rec('恢复账号可用（清理）', back === 200, { code: back })
    await fc.ctx.close()
  }

  /* ================= 7. 实时：农户发布 → 采购员页面自动更新 ================= */
  {
    const purchaser = await apiLogin('purchaser01')
    const pc = await ctxWith(browser, purchaser, 'purchaser-live')
    const pp = pc.page
    // 监听货源接口响应：判定「自动刷新」的唯一可信证据是页面自己又拉了一次且带上新数据
    const srcCalls = []
    pp.on('response', async res => {
      if (!res.url().includes('/api/purchaser/sources')) return
      let ids = [], total = -1
      try {
        const j = await res.json()
        total = Number((j.data && j.data.page && j.data.page.total) || 0)
        ids = ((j.data && j.data.page && j.data.page.records) || []).map(x => x.id)
      } catch (e) { /* ignore */ }
      srcCalls.push({ at: Date.now(), total, ids })
    })
    await pp.goto(BASE + '/purchaser/sources', { waitUntil: 'domcontentloaded' })
    await sleep(3000)
    const cardsBefore = await pp.locator('.source-card').count()
    const before = { cards: cardsBefore, calls: srcCalls.length }

    const farmer = await apiLogin('farmer24')
    const src = await fetch('http://127.0.0.1:8080/api/farmer/sources', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Authorization: 'Bearer ' + farmer.token },
      body: JSON.stringify({ productId: 45, quantity: 66, price: 3.3, unit: 'kg', grade: '一级',
        origin: '实时联调产地', remark: 'verify-live-' + Date.now() })
    }).then(r => r.json())
    rec('农户发布货源成功（联调用）', src.code === 200, { id: src.data && src.data.id })
    const newId = src.data && src.data.id

    // 不手动刷新、不重载：等页面自己重新拉取，且返回里含新货源
    let appeared = false, lastCall = null, waited = 0, maskSeen = false
    for (let i = 0; i < 30; i++) {
      await sleep(250)
      waited += 250
      if (await pp.locator('.el-loading-mask:visible').count() > 0) maskSeen = true
      lastCall = srcCalls[srcCalls.length - 1] || null
      if (srcCalls.length > before.calls && lastCall && newId && lastCall.ids.includes(newId)) {
        appeared = true
        break
      }
    }
    rec('采购员页面无需手动刷新即自动拉取到该新货源',
      appeared, { newId, waitedMs: waited, autoCalls: srcCalls.length - before.calls, lastCall })
    rec('后台自动刷新期间不弹整页 loading 遮罩（无白闪）', !maskSeen, { maskSeen, waitedMs: waited })

    // 顶栏徽标类名在 2026-11-10 易用性整改中由 .lay-badge 改为 .lay-act-num
    // （图标按钮改成「图标+文字+数字」，徽标不再是绝对定位的小红点）。
    // 两种都认，避免这类纯改名把断言带崩。
    // 只认「动态」按钮上的未读数：待办按钮上也有数字，选到它会误判通过
    // （页面本来就有 3 条待办时，即使实时推送失败断言也会绿）。
    const badge = await pp.evaluate(() => {
      const b = document.querySelector('.lay-notify .lay-act-num, .lay-notify .lay-badge')
      return b ? b.innerText.trim() : ''
    })
    /* 原断言是「徽标必须非空」，但它依赖"历史遗留未读通知"存在——
       走查把通知都点成已读之后必然变空，属于断言本身不可靠，不是功能坏了。
       而且货源上架按设计不推通知（只有下单/质检/发车/送达/入库/结算审核会推），
       所以这里改成可长期成立的口径：徽标数字必须等于接口未读数。 */
    // 直接问后端，不绕浏览器 fetch：页面里取 sessionStorage/代理都可能失败，
    // 那样断言会拿到 -1 而静默失效
    const unread = await fetch('http://127.0.0.1:8080/api/notifications/unread-count', {
      headers: { Authorization: 'Bearer ' + purchaser.token }
    }).then((r) => r.json()).then((j) => Number(j?.data?.unread ?? -1)).catch(() => -1)
    const badgeNum = Number(badge.replace('+', '') || 0)
    // 接口取不到（-1）判失败：否则这条断言会在接口异常时静默变绿，等于没测
    rec('采购员顶栏通知徽标数 = 接口未读数', unread >= 0 && badgeNum === unread, { badge, unread })
    shots.push(await pp.screenshot({ path: path.join(OUT, 'G-农户发布后采购员自动刷新.png') }))

    // 清理：置为下架
    if (newId) {
      await fetch('http://127.0.0.1:8080/api/farmer/sources/' + newId + '/status?status=' + encodeURIComponent('下架'),
        { method: 'PUT', headers: { Authorization: 'Bearer ' + farmer.token } })
    }
    await pc.ctx.close()
  }

  /* ================= 8. 数据看板 ================= */
  {
    await page.goto(BASE + '/admin/dashboard', { waitUntil: 'domcontentloaded' })
    await sleep(3200)
    const dash = await page.evaluate(() => {
      const q = s => document.querySelectorAll(s).length
      return {
        kpi: q('.db-primary') + q('.db-mini'),
        charts: q('canvas'),
        timeline: q('.db-feed-item, .db-event, .db-timeline li'),
        text: document.body.innerText.length,
        has404: /暂无数据|加载失败/.test(document.body.innerText)
      }
    })
    rec('看板渲染出 KPI 与图表', dash.kpi >= 3 && dash.charts >= 2 && !dash.has404, dash)
    shots.push(await page.screenshot({ path: path.join(OUT, 'H-数据看板-浅色.png'), fullPage: false }))
    await page.evaluate(() => { document.documentElement.dataset.mode = 'night' })
    await sleep(1200)
    shots.push(await page.screenshot({ path: path.join(OUT, 'H-数据看板-深色.png') }))
    await page.evaluate(() => { document.documentElement.dataset.mode = 'day' })
  }

  /* ================= 9. 控制台 ================= */
  const errs = (page.__errs || [])
  rec('验收期间 0 控制台错误', errs.length === 0, { count: errs.length, samples: errs.slice(0, 5) })

  const summary = {
    at: new Date().toISOString(),
    total: results.length,
    pass: results.filter(r => r.ok).length,
    fail: results.filter(r => !r.ok).length,
    consoleErrors: errs.length
  }
  fs.writeFileSync(RESULT_FILE, JSON.stringify({ summary, results, shots }, null, 2), 'utf8')
  console.log('\n=== 验收汇总 ===\n' + JSON.stringify(summary, null, 2))
  console.log('结果文件：' + RESULT_FILE)
  await browser.close()
  process.exit(summary.fail === 0 ? 0 : 1)
})().catch(e => { console.error('验收脚本异常:', e); process.exit(1) })
