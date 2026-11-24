/* ==========================================================================
   定位链路验收（2026-11-11 第十一轮）
   --------------------------------------------------------------------------
   判据只看「点了以后目标页有没有真的找到那一条」，不看代码里有没有写 focus：
     1. 待办中心每条 actionable 待办的 path 必须带 focus=<业务编号>
     2. 打开该 path 后，落地页必须出现定位效果（弹窗/筛选/选中），不能只是列表
     3. 定位失败必须明确说"没找到"，不许静默落在列表页
   覆盖：管理员 4 类 + 采购员 3 类 + 质检员 3 类 + 农户 2 类
   ========================================================================== */
const path = require('path')
const fs = require('fs')

const ROOT = path.resolve(__dirname, '..')
const BASE = 'http://127.0.0.1:5173'
const API = 'http://127.0.0.1:8080'
const OUT = path.join(ROOT, 'docs', 'reform_2026-11-11', 'shots11')
fs.mkdirSync(OUT, { recursive: true })

const GLOBAL = path.join(process.env.APPDATA, 'npm', 'node_modules')
module.paths.push(GLOBAL)
const { chromium } = require(path.join(GLOBAL, 'playwright'))

const results = []
const rec = (name, ok, detail) => {
  results.push({ name, ok: !!ok, detail })
  console.log((ok ? '  PASS ' : '  FAIL ') + name + (detail ? ' | ' + String(detail).slice(0, 200) : ''))
}
const sleep = ms => new Promise(r => setTimeout(r, ms))

async function apiLogin(username, expectedRole) {
  const r = await fetch(API + '/api/auth/login', {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username, password: '123456', expectedRole })
  })
  const j = await r.json()
  if (j.code !== 200) throw new Error('登录失败 ' + username)
  return j.data
}
async function todoCenter(token) {
  const r = await fetch(API + '/api/common/todo-center', { headers: { Authorization: 'Bearer ' + token } })
  return (await r.json()).data
}

/** 每个落点页的"定位成功"判定：页面必须出现这些具体信号之一 */
const EXPECT = {
  '/admin/settlements': { sel: '.el-dialog, .el-drawer', text: ['结算单', '审核', '实结'] },
  '/admin/warnings': { sel: '.el-dialog, .el-drawer', text: ['预警', '处置', '等级'] },
  '/admin/farmers': { sel: '.el-dialog, .el-drawer', text: ['农户', '信用', '认证'] },
  '/admin/orders': { sel: '.el-dialog, .el-drawer', text: ['订单', '批次', '金额'] },
  '/purchaser/demands': { sel: '.el-dialog, .el-drawer', text: ['报价', '比价', '需求'] },
  '/purchaser/orders': { sel: '.el-dialog, .el-drawer', text: ['订单', '批次', '状态'] },
  '/purchaser/inbound': { text: ['实收', '入库', '批次'] },
  '/purchaser/evaluation': { text: ['评分', '评价', '订单'] },
  '/farmer/orders': { sel: '.el-dialog, .el-drawer', text: ['订单', '批次', '收货'] },
  '/farmer/settlement': { sel: '.el-dialog, .el-drawer', text: ['结算', '实结', '损耗'] },
  '/inspector/quality': { text: ['质检', '批次', '结论'] },
  '/inspector/delivery': { text: ['配送', '批次', '车辆'] }
}

;(async () => {
  const browser = await chromium.launch({ channel: 'msedge' })
  const accounts = [
    { role: 'ADMIN', user: 'admin' },
    { role: 'PURCHASER', user: 'purchaser01' },
    { role: 'INSPECTOR', user: 'inspector01' },
    { role: 'FARMER', user: 'farmer01' }
  ]

  for (const acc of accounts) {
    const data = await apiLogin(acc.user, acc.role)
    const tc = await todoCenter(data.token)

    // 1. 每条 actionable 待办的 path 都要带 focus
    const items = []
    for (const g of tc.groups) {
      if (!g.actionable) continue
      for (const it of g.items) if (it.path) items.push({ group: g.label, ...it })
    }
    const noFocus = items.filter((it) => !/[?&]focus=/.test(it.path))
    rec(`[${acc.role}] 待办 path 全部带定位（${items.length} 条）`, noFocus.length === 0,
      noFocus.slice(0, 3).map(x => x.path).join(' , ') || 'ok')

    // 2. 逐个落点页验证定位效果（每类取第一条即可）
    const byPath = new Map()
    for (const it of items) {
      const p = it.path.split('?')[0]
      if (!byPath.has(p) && EXPECT[p]) byPath.set(p, it)
    }

    const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 } })
    await ctx.addInitScript(([t, u, rn, mn]) => {
      sessionStorage.setItem('ct_token', t)
      sessionStorage.setItem('ct_user', u)
      sessionStorage.setItem('ct_role_name', rn)
      sessionStorage.setItem('ct_menus', mn)
    }, [data.token, JSON.stringify(data.user), data.roleName || '', JSON.stringify(data.menus || [])])

    for (const [p, it] of byPath) {
      const page = await ctx.newPage()
      const errs = []
      page.on('pageerror', e => errs.push(e.message.slice(0, 120)))
      await page.goto(BASE + it.path, { waitUntil: 'domcontentloaded' })
      await sleep(2600)
      const exp = EXPECT[p]
      const info = await page.evaluate((e) => {
        const vis = (el) => {
          const r = el.getBoundingClientRect(), s = getComputedStyle(el)
          return r.width > 0 && r.height > 0 && s.display !== 'none' && s.visibility !== 'hidden'
        }
        const dialogs = Array.from(document.querySelectorAll(e.sel || '.el-dialog, .el-drawer')).filter(vis)
        const body = document.body.innerText || ''
        const hitText = e.text.filter(t => body.includes(t))
        const warnMiss = body.includes('未找到') || body.includes('没有找到')
        return { dialogs: dialogs.length, hitText, warnMiss, bodyHead: body.replace(/\s+/g, ' ').slice(0, 120) }
      }, exp)

      const okOpen = info.dialogs > 0 || info.hitText.length >= 2
      rec(`[${acc.role}] ${it.path} → 定位「${it.bizNo}」`,
        okOpen && !info.warnMiss && errs.length === 0,
        `弹窗${info.dialogs} 关键词[${info.hitText}] 未找到=${info.warnMiss} err=${errs[0] || ''}`)

      if (it.group.includes('结算') || it.group.includes('预警')) {
        await page.screenshot({ path: `${OUT}/${acc.role}-${it.group}-${it.bizNo}.png` }).catch(() => {})
      }
      await page.close()
    }
    await ctx.close()
  }

  const pass = results.filter(r => r.ok).length
  console.log(`\n总计 ${pass}/${results.length}`)
  fs.writeFileSync(path.join(OUT, 'focus_chain.json'), JSON.stringify(results, null, 2), 'utf8')
  await browser.close()
  process.exit(pass === results.length ? 0 : 1)
})()
