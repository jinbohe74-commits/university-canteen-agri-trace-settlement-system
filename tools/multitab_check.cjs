/* ==========================================================================
   多角色并行登录验收（2026-11-10 追加）
   --------------------------------------------------------------------------
   起因：用户同时登录好几个角色，出现「无权访问该资源」，随后数据全空。
   根因：会话存 localStorage，全标签页共享 → 后登录的 token 覆盖先登录的，
         A 标签拿着 B 的角色去请求 → 403；刷新时路由守卫还会把这个身份
         当成本标签的身份，把用户重定向到另一个角色的首页 → "数据全没了"。
   本脚本用两个真实标签页（同一 context，localStorage 共享但 sessionStorage
   各自独立）复现这个使用方式，断言互不干扰。
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

const results = []
const rec = (name, ok, detail) => {
  results.push({ name, ok: !!ok, detail: detail === undefined ? null : detail })
  console.log((ok ? '  PASS ' : '  FAIL ') + name + ' | ' + JSON.stringify(detail === undefined ? null : detail).slice(0, 300))
}
const sleep = ms => new Promise(r => setTimeout(r, ms))

async function instrument(page) {
  const st = { errors: [], e403: 0, notifies: [] }
  page.on('console', m => { if (m.type() === 'error') st.errors.push(m.text()) })
  page.on('pageerror', e => st.errors.push(String(e)))
  page.on('response', r => { if (r.status() === 403) st.e403++ })
  return st
}

async function login(page, username, expectPath) {
  await page.goto(BASE + '/login', { waitUntil: 'domcontentloaded' })
  await sleep(500)
  await page.fill('input[placeholder*="账号"]', username)
  await page.fill('input[type="password"]', '123456')
  await page.click('.submit')
  await page.waitForURL(new RegExp(expectPath), { timeout: 15000 })
  await sleep(1400)
  // 首次登录会自动弹出「怎么用」引导（真实功能，不能为了测试关掉），脚本里先关掉再走查
  if (await page.locator('.gd-x').count()) await page.locator('.gd-x').first().click()
  await page.keyboard.press('Escape')
  await sleep(500)
}

const sessionInfo = (page) => page.evaluate(() => ({
  token: sessionStorage.getItem('ct_token') || '',
  role: (() => { try { return JSON.parse(sessionStorage.getItem('ct_user') || 'null')?.role } catch (e) { return null } })(),
  lsToken: localStorage.getItem('ct_token'),
  lsUser: localStorage.getItem('ct_user')
}))

// JWT 前 24 位是固定头，拿来比较永远相等；这里取尾部用于辨认
const tail = (t) => (t || '').slice(-12)

const toastText = (page) => page.$$eval('.el-message', els => els.map(e => e.textContent.trim()))

async function main() {
  const browser = await chromium.launch({ channel: 'msedge' })
  const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 } })

  // ---- 标签 A：农户 ----
  const A = await ctx.newPage()
  const sA = await instrument(A)
  await login(A, 'farmer24', '/farmer/workbench')
  const infoA1 = await sessionInfo(A)
  const showA = { role: infoA1.role, tail: tail(infoA1.token), lsToken: infoA1.lsToken, lsUser: infoA1.lsUser }
  rec('A 标签登录农户成功', infoA1.role === 'FARMER', showA)
  rec('A 标签会话不写 localStorage（避免全标签共享）', infoA1.lsToken === null && infoA1.lsUser === null, showA)

  // ---- 标签 B：管理员（关键：这一步以前会把 A 的身份冲掉） ----
  const B = await ctx.newPage()
  const sB = await instrument(B)
  await login(B, 'admin', '/admin/dashboard')
  const infoB1 = await sessionInfo(B)
  rec('B 标签登录管理员成功', infoB1.role === 'ADMIN', { role: infoB1.role, tail: tail(infoB1.token) })
  rec('两个标签页持有各自的令牌', !!infoA1.token && !!infoB1.token && infoA1.token !== infoB1.token,
    { A: tail(infoA1.token), B: tail(infoB1.token), sameLength: infoA1.token.length === infoB1.token.length })

  // ---- 回到 A：切换页面 + 刷新，仍必须是农户，且不出现 403 ----
  await A.bringToFront()
  await A.click('.lay-nav-item:has-text("货源管理")')
  await sleep(1800)
  const aUrl = A.url()
  const aRows = await A.$$eval('.el-table__body tr', els => els.length).catch(() => 0)
  rec('A 标签切换页面后仍在农户端', aUrl.includes('/farmer/'), { url: aUrl })
  rec('A 标签页面仍有数据', aRows > 0, { rows: aRows })
  rec('A 标签未出现 403', sA.e403 === 0, { e403: sA.e403 })
  const aToast = await toastText(A)
  rec('A 标签未出现「无权访问」提示', !aToast.some(t => t.includes('无权') || t.includes('权限')), { toast: aToast })

  // 刷新是原缺陷最狠的路径：旧逻辑会把 A 重定向到管理员的首页
  await A.reload({ waitUntil: 'domcontentloaded' })
  await sleep(2200)
  const aReloadUrl = A.url()
  const infoA2 = await sessionInfo(A)
  rec('A 标签刷新后仍是农户身份', infoA2.role === 'FARMER', { url: aReloadUrl, role: infoA2.role, tail: tail(infoA2.token) })
  rec('A 标签刷新后没有被踢到管理员端', aReloadUrl.includes('/farmer/'), { url: aReloadUrl })

  // ---- B 也必须完好 ----
  await B.bringToFront()
  await B.reload({ waitUntil: 'domcontentloaded' })
  await sleep(2200)
  const bUrl = B.url()
  const infoB2 = await sessionInfo(B)
  rec('B 标签刷新后仍是管理员身份', infoB2.role === 'ADMIN', { url: bUrl, role: infoB2.role, tail: tail(infoB2.token) })
  rec('B 标签未出现 403', sB.e403 === 0, { e403: sB.e403 })

  // ---- 退出 B 不影响 A ----
  await B.evaluate(() => { sessionStorage.clear() }) // 等价于"该标签退出"，不应影响另一个标签
  await sleep(800)
  const infoA3 = await sessionInfo(A)
  rec('另一标签退出登录不影响 A 的身份', infoA3.role === 'FARMER', { role: infoA3.role, tail: tail(infoA3.token) })

  // ---- 第三个标签：质检员，三身份并存 ----
  const C = await ctx.newPage()
  const sC = await instrument(C)
  await login(C, 'inspector01', '/inspector/tasks')
  const infoC = await sessionInfo(C)
  rec('C 标签登录质检员成功', infoC.role === 'INSPECTOR', { role: infoC.role, tail: tail(infoC.token) })
  await C.click('.lay-nav-item:has-text("质检录入")')
  await sleep(1600)
  rec('C 标签切换页面正常且无 403', C.url().includes('/inspector/') && sC.e403 === 0, { url: C.url(), e403: sC.e403 })

  await A.bringToFront(); await sleep(400); await A.screenshot({ path: path.join(OUT, 'J-多标签-农户仍在.png') })
  await B.bringToFront(); await sleep(400); await B.screenshot({ path: path.join(OUT, 'J-多标签-管理员.png') })

  const consoleErrs = [...sA.errors, ...sB.errors, ...sC.errors]
  rec('三个标签页全程 0 控制台错误', consoleErrs.length === 0, { count: consoleErrs.length, samples: consoleErrs.slice(0, 3) })

  await browser.close()

  const summary = {
    at: new Date().toISOString(),
    total: results.length,
    pass: results.filter(r => r.ok).length,
    fail: results.filter(r => !r.ok).length
  }
  fs.writeFileSync(path.join(OUT, 'multitab_check.json'), JSON.stringify({ summary, results }, null, 2), 'utf8')
  console.log('\n== 汇总 ==', JSON.stringify(summary))
  process.exit(summary.fail ? 1 : 0)
}

main().catch(e => { console.error('脚本异常：', e); process.exit(2) })
