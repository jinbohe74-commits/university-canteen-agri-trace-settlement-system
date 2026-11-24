/* ==========================================================================
   角色扮演式可用性走查（2026-11-10）
   --------------------------------------------------------------------------
   出发点：前几轮都在量「页面指标」（字号、溢出、控件数），那只能证明页面规整，
   证明不了「活能不能干完」。本脚本换成第一人称：以 4 个真实角色的身份，
   把当天真实要干的活干一遍，记录：
     · 要点几下才办成
     · 有没有卡住 / 找不到入口 / 不知道下一步
     · 操作后有没有明确反馈
     · 空状态下系统有没有告诉我该怎么办
   输出 docs/roleplay_2026-11-10/roleplay.json + shots/
   ========================================================================== */
const path = require('path')
const fs = require('fs')

const ROOT = path.resolve(__dirname, '..')
const BASE = 'http://127.0.0.1:5173'
const API = 'http://127.0.0.1:8080'
const OUT = path.join(ROOT, 'docs', 'roleplay_2026-11-10')
fs.mkdirSync(path.join(OUT, 'shots'), { recursive: true })

const GLOBAL = path.join(process.env.APPDATA, 'npm', 'node_modules')
module.paths.push(GLOBAL)
const { chromium } = require(path.join(GLOBAL, 'playwright'))

const sleep = ms => new Promise(r => setTimeout(r, ms))
const log = []
const notes = []   // 第一人称主观感受，最后汇总

function say(role, kind, text, extra) {
  notes.push({ role, kind, text, extra: extra === undefined ? null : extra })
  console.log(`  [${role}][${kind}] ${text}`)
}

async function apiLogin(username, password = '123456', expectedRole) {
  const body = { username, password }
  if (expectedRole) body.expectedRole = expectedRole
  const r = await fetch(API + '/api/auth/login', {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body)
  })
  const j = await r.json()
  if (j.code !== 200) throw new Error('登录失败 ' + username + ': ' + j.message)
  return j.data
}

async function openAs(browser, data, label) {
  const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 }, acceptDownloads: true })
  await ctx.addInitScript(([t, u, rn, mn]) => {
    sessionStorage.setItem('ct_token', t)
    sessionStorage.setItem('ct_user', u)
    sessionStorage.setItem('ct_role_name', rn)
    sessionStorage.setItem('ct_menus', mn)
  }, [data.token, JSON.stringify(data.user), data.roleName || '', JSON.stringify(data.menus || [])])
  const page = await ctx.newPage()
  page.__errs = []
  page.on('console', m => { if (m.type() === 'error') page.__errs.push(m.text().slice(0, 200)) })
  page.on('pageerror', e => page.__errs.push('PAGEERROR: ' + e.message.slice(0, 200)))
  page.__label = label
  return { ctx, page }
}

/** 走真实入口：从侧栏点进去。找不到就记录下来——这就是「入口不可发现」 */
async function nav(page, menuText, route) {
  const t0 = Date.now()
  const item = page.locator(`.el-menu-item:has-text("${menuText}"), .el-sub-menu__title:has-text("${menuText}")`).first()
  if (await item.count()) {
    await item.click({ timeout: 3000 }).catch(() => {})
    await sleep(600)
    // 二级菜单
    const sub = page.locator(`.el-menu-item:has-text("${menuText}")`).first()
    if (await sub.count() && !(await page.url()).includes(route)) {
      await sub.click({ timeout: 3000 }).catch(() => {})
      await sleep(700)
    }
  }
  if (!(await page.url()).includes(route)) {
    await page.goto(BASE + route, { waitUntil: 'domcontentloaded' })
    await sleep(900)
  }
  return Date.now() - t0
}

async function shot(page, name) {
  await page.screenshot({ path: path.join(OUT, 'shots', name + '.png') })
}

/** 页面在说什么：标题 + 主操作 + 空状态 + 表格行数 */
async function snapshot(page) {
  return await page.evaluate(() => {
    const txt = el => (el ? (el.innerText || '').replace(/\s+/g, ' ').trim() : '')
    const btns = [...document.querySelectorAll('.el-button, button')]
      .filter(b => b.offsetParent && (b.innerText || '').trim())
      .map(b => b.innerText.replace(/\s+/g, ' ').trim())
    const rows = document.querySelectorAll('.el-table__body tbody tr').length
    const empty = txt(document.querySelector('.el-table__empty-text, .el-empty'))
    const cards = [...document.querySelectorAll('.el-card, .stat-card, .kpi')].length
    return { title: txt(document.querySelector('h1,h2,.page-title,.card-title')), btns: [...new Set(btns)], rows, empty, cards }
  })
}

/** 记录「我想干这件事，但系统没让我干成」 */
async function tryAct(page, label, fn) {
  const t0 = Date.now()
  try {
    const r = await fn()
    const ms = Date.now() - t0
    log.push({ label, ok: true, ms, detail: r === undefined ? null : r })
    console.log(`  OK   ${label} (${ms}ms)`)
    return { ok: true, r }
  } catch (e) {
    const ms = Date.now() - t0
    log.push({ label, ok: false, ms, detail: String(e.message).slice(0, 300) })
    console.log(`  FAIL ${label} (${ms}ms) ${String(e.message).slice(0, 200)}`)
    return { ok: false, e }
  }
}

const toastText = async page => {
  const t = page.locator('.el-message, .el-notification').first()
  if (await t.count()) return (await t.innerText().catch(() => '')).replace(/\s+/g, ' ').trim()
  return ''
}

;(async () => {
  const browser = await chromium.launch({ channel: 'msedge' })

  /* ==================================================================
     角色 1：王管理（管理员）— 周一 8:50，食堂中午要开饭
     我的活：先看有没有要处理的，然后把卡住的审批清掉
     ================================================================== */
  {
    const d = await apiLogin('admin')
    const { page } = await openAs(browser, d, 'admin')
    const R = '管理员'

    // 1. 打开系统第一眼，我能不能知道「现在有几件事等我」
    await page.goto(BASE + '/admin/dashboard', { waitUntil: 'domcontentloaded' })
    await sleep(1600)
    await shot(page, 'A1-admin-dashboard')
    const s1 = await snapshot(page)
    say(R, '开场', `看板首屏：${s1.cards} 个卡片，按钮 ${JSON.stringify(s1.btns)}`)

    const dashText = await page.evaluate(() => document.body.innerText.replace(/\s+/g, ' '))
    const hasTodo = /待审|待办|待处理|待审核/.test(dashText)
    say(R, hasTodo ? 'ok' : 'friction', hasTodo ? '看板能看到「待处理」字样' : '看板没有一句话告诉我今天有几件事要办')
    await shot(page, 'A2-admin-dashboard-full')
    // 首屏是否露出主操作：能不能一键跳去处理
    const dashBtns = s1.btns
    say(R, dashBtns.some(b => /处理|审核|查看/.test(b)) ? 'ok' : 'friction',
      `看板上能直接点去处理吗：${dashBtns.filter(b => /处理|审核|查看/.test(b)).slice(0, 5).join(' / ') || '没有'}`)

    // 2. 农户审核：新农户要上线卖菜，我得批
    await nav(page, '农户', '/admin/farmers')
    await sleep(1200)
    await shot(page, 'B1-admin-farmers')
    const s2 = await snapshot(page)
    say(R, '观察', `农户列表 ${s2.rows} 行，按钮 ${JSON.stringify(s2.btns.slice(0, 10))}`)
    // 待审核在哪
    const pendingEntry = await page.locator('text=/待审/').count()
    say(R, pendingEntry ? 'ok' : 'friction', `页面上「待审核」相关入口出现 ${pendingEntry} 处`)

    const approve = await tryAct(page, '农户：找到待审核并批准', async () => {
      const btn = page.locator('button:has-text("待审核")').first()
      if (await btn.count()) { await btn.click(); await sleep(900) }
      const row = page.locator('.el-table__body tbody tr').first()
      const rowText = (await row.innerText().catch(() => '')).replace(/\s+/g, ' ')
      const acts = await row.locator('button, .el-link').allInnerTexts().catch(() => [])
      return { rowText: rowText.slice(0, 120), acts: acts.map(a => a.trim()).filter(Boolean) }
    })
    if (approve.ok) say(R, '观察', `待审核行：${JSON.stringify(approve.r)}`)
    await shot(page, 'B2-admin-farmers-pending')

    // 3. 订单监管：有没有超时/异常的单
    await nav(page, '订单监管', '/admin/orders')
    await sleep(1300)
    await shot(page, 'C1-admin-orders')
    const s3 = await snapshot(page)
    const orderText = await page.evaluate(() => document.body.innerText.replace(/\s+/g, ' '))
    const hasAbnormal = /超时|异常|预警|催/.test(orderText)
    say(R, hasAbnormal ? 'ok' : 'friction', `订单页 ${s3.rows} 行；异常/超时提示：${hasAbnormal ? '有' : '没有——我只能自己一行行看'}`)

    // 4. 结算审核：钱的事，我得核对金额
    await nav(page, '结算审核', '/admin/settlements')
    await sleep(1300)
    await shot(page, 'D1-admin-settlements')
    const s4 = await snapshot(page)
    say(R, '观察', `结算页 ${s4.rows} 行，按钮 ${JSON.stringify(s4.btns.slice(0, 8))}`)
    // 明细能不能看到「怎么算出来的」
    const detailBtn = page.locator('button:has-text("明细"), .el-link:has-text("明细"), button:has-text("详情")').first()
    if (await detailBtn.count()) {
      await detailBtn.click().catch(() => {})
      await sleep(1100)
      await shot(page, 'D2-admin-settlement-detail')
      const dt = await page.evaluate(() => document.body.innerText.replace(/\s+/g, ' '))
      say(R, /单价|斤|数量|扣|佣金|手续费/.test(dt) ? 'ok' : 'friction',
        '结算明细里能看到计价构成吗：' + (/单价|斤|数量|扣|佣金|手续费/.test(dt) ? '能' : '看不到，只有一个总额'))
      await page.keyboard.press('Escape'); await sleep(400)
    } else {
      say(R, 'friction', '结算列表没有「明细/详情」入口，我审钱只能看一个总额')
    }

    // 5. 食安预警：出事了要处理
    await nav(page, '预警', '/admin/warnings')
    await sleep(1300)
    await shot(page, 'E1-admin-warnings')
    const s5 = await snapshot(page)
    say(R, '观察', `预警页 ${s5.rows} 行，按钮 ${JSON.stringify(s5.btns.slice(0, 8))}`)
    const wText = await page.evaluate(() => document.body.innerText.replace(/\s+/g, ' '))
    say(R, /建议|处置|整改|下架/.test(wText) ? 'ok' : 'friction',
      '预警里有没有告诉我「该怎么处置」：' + (/建议|处置|整改|下架/.test(wText) ? '有' : '只有一条告警，没有处置指引'))

    // 6. 操作日志：查「谁动了王丰收的信用分」——审计场景，必须快
    await nav(page, '操作日志', '/admin/logs')
    await sleep(1300)
    await shot(page, 'F1-admin-logs')
    const s6 = await snapshot(page)
    say(R, '观察', `日志页 ${s6.rows} 行，控件 ${JSON.stringify(s6.btns.slice(0, 10))}`)
    const audit = await tryAct(page, '日志：按「信用」关键字查改动', async () => {
      const input = page.locator('input[placeholder*="关键"], input[placeholder*="搜索"], input[placeholder*="内容"]').first()
      if (!(await input.count())) throw new Error('没有可用的关键词搜索框')
      await input.fill('信用')
      await input.press('Enter')
      await sleep(1200)
      const rows = await page.locator('.el-table__body tbody tr').count()
      const first = (await page.locator('.el-table__body tbody tr').first().innerText().catch(() => '')).replace(/\s+/g, ' ')
      return { rows, first: first.slice(0, 140) }
    })
    if (audit.ok) say(R, audit.r.rows > 0 ? 'ok' : 'friction', `搜「信用」得到 ${audit.r.rows} 条：${audit.r.first}`)
    await shot(page, 'F2-admin-logs-search')
    // 能不能按人查？日志里有没有「操作对象」
    const logTable = await page.evaluate(() => {
      const th = [...document.querySelectorAll('.el-table__header th')].map(x => x.innerText.trim())
      return th
    })
    say(R, logTable.some(h => /对象|目标|影响/.test(h)) ? 'ok' : 'friction', `日志表头：${JSON.stringify(logTable)}`)

    // 7. 导出：领导要一份订单表
    await nav(page, '订单监管', '/admin/orders')
    await sleep(1200)
    const exp = await tryAct(page, '订单：导出 Excel', async () => {
      const dl = page.waitForEvent('download', { timeout: 8000 }).catch(() => null)
      const b = page.locator('button:has-text("导出")').first()
      if (!(await b.count())) throw new Error('没有导出按钮')
      await b.click()
      const d = await dl
      if (!d) throw new Error('点了导出，没有下载发生')
      return { file: d.suggestedFilename() }
    })
    if (exp.ok) say(R, 'ok', `导出成功：${exp.r.file}`)
    else say(R, 'friction', '导出失败：' + exp.e.message.slice(0, 120))

    if (page.__errs.length) say(R, 'bug', '控制台报错 ' + page.__errs.length + ' 条：' + page.__errs.slice(0, 2).join(' | '))
  }

  /* ==================================================================
     角色 2：王丰收（农户）— 早上 7:30，刚摘完菜，要上架
     我的活：把今天摘的菜挂上去 → 看有没有单 → 看挣了多少
     ================================================================== */
  {
    const d = await apiLogin('farmer01', '123456', 'FARMER')
    const { page } = await openAs(browser, d, 'farmer')
    const R = '农户'

    await page.goto(BASE + '/farmer/workbench', { waitUntil: 'domcontentloaded' })
    await sleep(1600)
    await shot(page, 'G1-farmer-workbench')
    const s1 = await snapshot(page)
    say(R, '开场', `工作台：${s1.cards} 个卡片，按钮 ${JSON.stringify(s1.btns.slice(0, 8))}`)
    const wb = await page.evaluate(() => document.body.innerText.replace(/\s+/g, ' '))
    // 农户最关心的两件事：有没有新单、这个月挣多少
    say(R, /本月|收入|赚|元/.test(wb) ? 'ok' : 'friction', '工作台能看到收入：' + (/本月|收入|赚|元/.test(wb) ? '有' : '没有'))
    say(R, /新订单|待接单|待发货|待处理/.test(wb) ? 'ok' : 'friction', '工作台能看到「有几单等我处理」：' + (/新订单|待接单|待发货|待处理/.test(wb) ? '有' : '没有'))
    // 我要上架，第一步在哪点？
    const addSource = page.locator('button:has-text("发布"), button:has-text("上架"), button:has-text("新增货源")').first()
    say(R, await addSource.count() ? 'ok' : 'friction',
      '工作台上能直接「发布货源」吗：' + (await addSource.count() ? '能' : '不能，得自己去货源管理页找'))

    await nav(page, '货源', '/farmer/sources')
    await sleep(1300)
    await shot(page, 'H1-farmer-sources')
    const s2 = await snapshot(page)
    say(R, '观察', `货源页 ${s2.rows} 行，按钮 ${JSON.stringify(s2.btns.slice(0, 8))}`)

    // 发布一条货源：菜名、数量、价格、上市时间
    const pub = await tryAct(page, '农户：发布一条货源（大白菜 500 斤 2.2 元）', async () => {
      const b = page.locator('button:has-text("发布"), button:has-text("新增")').first()
      if (!(await b.count())) throw new Error('页面上找不到发布入口')
      await b.click()
      await sleep(1200)
      const labels = await page.evaluate(() => {
        const dlg = document.querySelector('.el-dialog, .el-drawer')
        if (!dlg) return null
        const items = [...dlg.querySelectorAll('.el-form-item')].map(fi => ({
          label: (fi.querySelector('.el-form-item__label')?.innerText || '').trim(),
          required: !!fi.querySelector('.is-required'),
          hint: (fi.querySelector('.el-form-item__label')?.innerText || '').includes('*')
        }))
        return { title: (dlg.querySelector('.el-dialog__title, .el-drawer__title')?.innerText || '').trim(), items }
      })
      if (!labels) throw new Error('点了发布，没有弹窗出现')
      return labels
    })
    if (pub.ok) {
      say(R, '观察', `发布弹窗「${pub.r.title}」字段：` + pub.r.items.map(i => i.label + (i.required ? '*' : '')).join(' / '))
      await shot(page, 'H2-farmer-publish-dialog')
      // 必填项都填了吗？先直接提交看它拦不拦、提示清不清楚
      const submitEmpty = await tryAct(page, '农户：什么都不填直接提交（试探校验提示）', async () => {
        await page.locator('.el-dialog button:has-text("确定"), .el-dialog button:has-text("保存"), .el-dialog button:has-text("提交")').first().click()
        await sleep(900)
        const errs = await page.locator('.el-form-item__error').allInnerTexts().catch(() => [])
        return errs.map(e => e.trim()).filter(Boolean)
      })
      if (submitEmpty.ok) say(R, submitEmpty.r.length ? 'ok' : 'friction',
        `空表单提交被拦：提示 ${JSON.stringify(submitEmpty.r)}`)
      await shot(page, 'H3-farmer-publish-validate')
      await page.keyboard.press('Escape'); await sleep(500)
    }

    // 看订单：有没有新单要接
    await nav(page, '订单', '/farmer/orders')
    await sleep(1300)
    await shot(page, 'I1-farmer-orders')
    const s3 = await snapshot(page)
    say(R, '观察', `订单页 ${s3.rows} 行，按钮 ${JSON.stringify(s3.btns.slice(0, 10))}`)
    const orderTabs = await page.evaluate(() => [...document.querySelectorAll('.el-tabs__item, .el-radio-button__inner')].map(x => x.innerText.trim()))
    say(R, '观察', `状态入口：${JSON.stringify(orderTabs)}`)

    // 收入
    await nav(page, '结算', '/farmer/settlement')
    await sleep(1400)
    await shot(page, 'J1-farmer-settlement')
    const inc = await tryAct(page, '农户：看本月收入与到账情况', async () => {
      const t = await page.evaluate(() => document.body.innerText.replace(/\s+/g, ' '))
      const nums = t.match(/[\d,]+\.\d{2}/g) || []
      return { nums: nums.slice(0, 6), hasPending: /待结算|已结算|已到账/.test(t) }
    })
    if (inc.ok) say(R, inc.r.nums.length ? 'ok' : 'friction', `收入数字：${JSON.stringify(inc.r.nums)}，分状态：${inc.r.hasPending}`)

    // 信用分为什么掉了
    await nav(page, '信用', '/farmer/credit')
    await sleep(1400)
    await shot(page, 'K1-farmer-credit')
    const cr = await page.evaluate(() => document.body.innerText.replace(/\s+/g, ' '))
    say(R, /原因|扣|加分|说明/.test(cr) ? 'ok' : 'friction', '信用分能不能看懂「为什么是这个分」：' + (/原因|扣|加分|说明/.test(cr) ? '有说明' : '只有一个数字'))

    if (page.__errs.length) say(R, 'bug', '控制台报错：' + page.__errs.slice(0, 2).join(' | '))
  }

  /* ==================================================================
     角色 3：李建国（采购员）— 早上 8:00，要备明天食堂的菜
     我的活：找货 → 下单 → 等收货 → 入库 → 评价
     ================================================================== */
  {
    const d = await apiLogin('purchaser01', '123456', 'PURCHASER')
    const { page } = await openAs(browser, d, 'purchaser')
    const R = '采购员'

    await page.goto(BASE + '/purchaser/sources', { waitUntil: 'domcontentloaded' })
    await sleep(1600)
    await shot(page, 'L1-purchaser-sources')
    const s1 = await snapshot(page)
    say(R, '开场', `货源大厅 ${s1.rows} 行，按钮 ${JSON.stringify(s1.btns.slice(0, 10))}`)
    // 我要买 500 斤大白菜，能不能直接搜
    const find = await tryAct(page, '采购员：搜「白菜」找货', async () => {
      const input = page.locator('input[placeholder*="搜"], input[placeholder*="品种"], input[placeholder*="名称"]').first()
      if (!(await input.count())) throw new Error('货源大厅没有搜索框')
      await input.fill('白菜'); await input.press('Enter'); await sleep(1200)
      const rows = await page.locator('.el-table__body tbody tr').count()
      const first = (await page.locator('.el-table__body tbody tr').first().innerText().catch(() => '')).replace(/\s+/g, ' ')
      return { rows, first: first.slice(0, 160) }
    })
    if (find.ok) say(R, find.r.rows ? 'ok' : 'friction', `搜白菜 → ${find.r.rows} 条：${find.r.first}`)
    await shot(page, 'L2-purchaser-search')

    // 下单：点了「采购」以后呢？
    const buy = await tryAct(page, '采购员：对一条货源下单', async () => {
      const b = page.locator('button:has-text("采购"), button:has-text("下单"), button:has-text("立即")').first()
      if (!(await b.count())) throw new Error('货源行里没有采购/下单按钮')
      await b.click(); await sleep(1200)
      const dlg = await page.locator('.el-dialog, .el-drawer').first()
      if (!(await dlg.count())) throw new Error('点了采购，没有下单表单出现')
      const items = await page.evaluate(() => {
        const d = document.querySelector('.el-dialog, .el-drawer')
        return [...d.querySelectorAll('.el-form-item')].map(fi => (fi.querySelector('.el-form-item__label')?.innerText || '').trim())
      })
      return { fields: items }
    })
    if (buy.ok) {
      say(R, '观察', `下单表单字段：${JSON.stringify(buy.r.fields)}`)
      await shot(page, 'L3-purchaser-order-dialog')
      // 金额是我最关心的：填了数量后，总价会不会自己算出来
      const calc = await tryAct(page, '采购员：填数量看总价自动算', async () => {
        const num = page.locator('.el-dialog input[type="number"], .el-dialog .el-input-number input').first()
        if (!(await num.count())) throw new Error('下单表单里没有数量输入框')
        await num.fill('500'); await num.press('Tab'); await sleep(900)
        const body = await page.locator('.el-dialog').innerText()
        const money = body.match(/[¥￥]\s?[\d,]+(\.\d+)?/g) || []
        return { money: money.slice(0, 4) }
      })
      if (calc.ok) say(R, calc.r.money.length ? 'ok' : 'friction', `总价显示：${JSON.stringify(calc.r.money)}`)
      await shot(page, 'L4-purchaser-order-calc')
      await page.keyboard.press('Escape'); await sleep(500)
    }

    // 入库确认：货到了要点收
    await nav(page, '入库', '/purchaser/inbound')
    await sleep(1400)
    await shot(page, 'M1-purchaser-inbound')
    const s2 = await snapshot(page)
    say(R, '观察', `入库页 ${s2.rows} 行，按钮 ${JSON.stringify(s2.btns.slice(0, 8))}`)
    // 我要核对「实际收到多少」，能不能改数量
    const recv = await tryAct(page, '采购员：入库时改实收数量（短斤少两）', async () => {
      const b = page.locator('button:has-text("入库"), button:has-text("确认收货"), button:has-text("签收")').first()
      if (!(await b.count())) throw new Error('没有入库/收货按钮')
      await b.click(); await sleep(1100)
      const dlg = await page.locator('.el-dialog, .el-drawer').first()
      const has = await dlg.count()
      const fields = has ? await page.evaluate(() => {
        const d = document.querySelector('.el-dialog, .el-drawer')
        return [...d.querySelectorAll('.el-form-item')].map(fi => (fi.querySelector('.el-form-item__label')?.innerText || '').trim())
      }) : []
      return { dialog: !!has, fields }
    })
    if (recv.ok) say(R, recv.r.dialog ? 'ok' : 'friction', `入库确认弹窗：${recv.r.dialog ? JSON.stringify(recv.r.fields) : '没有弹窗，点一下就入库了——短斤少两我没法登记'}`)
    await shot(page, 'M2-purchaser-receive')
    await page.keyboard.press('Escape').catch(() => {}); await sleep(400)

    // 智能匹配：这个功能到底给不给我有用的结论
    await nav(page, '匹配', '/purchaser/match')
    await sleep(1500)
    await shot(page, 'N1-purchaser-match')
    const mt = await page.evaluate(() => document.body.innerText.replace(/\s+/g, ' '))
    say(R, /推荐|匹配度|最优|建议/.test(mt) ? 'ok' : 'friction',
      '智能匹配页在说什么：' + mt.slice(0, 180))

    if (page.__errs.length) say(R, 'bug', '控制台报错：' + page.__errs.slice(0, 2).join(' | '))
  }

  /* ==================================================================
     角色 4：赵质检（质检员）— 早上 9:00，菜到了要抽检
     我的活：看今天几单 → 录质检结果 → 异常上报
     ================================================================== */
  {
    const d = await apiLogin('inspector01', '123456', 'INSPECTOR')
    const { page } = await openAs(browser, d, 'inspector')
    const R = '质检员'

    await page.goto(BASE + '/inspector/tasks', { waitUntil: 'domcontentloaded' })
    await sleep(1600)
    await shot(page, 'O1-inspector-tasks')
    const s1 = await snapshot(page)
    say(R, '开场', `我的任务 ${s1.rows} 行，按钮 ${JSON.stringify(s1.btns.slice(0, 8))}`)
    const tk = await page.evaluate(() => document.body.innerText.replace(/\s+/g, ' '))
    say(R, /今天|今日|截止|紧急/.test(tk) ? 'ok' : 'friction', '任务里能看出「哪个最急」吗：' + (/今天|今日|截止|紧急/.test(tk) ? '有' : '没有优先级/时限'))

    // 录质检
    await nav(page, '质检', '/inspector/quality')
    await sleep(1400)
    await shot(page, 'P1-inspector-quality')
    const s2 = await snapshot(page)
    say(R, '观察', `质检录入 ${s2.rows} 行，按钮 ${JSON.stringify(s2.btns.slice(0, 8))}`)
    const qc = await tryAct(page, '质检员：录一条抽检结果', async () => {
      const b = page.locator('button:has-text("录入"), button:has-text("质检"), button:has-text("新增")').first()
      if (!(await b.count())) throw new Error('没有录入入口')
      await b.click(); await sleep(1200)
      const dlg = await page.locator('.el-dialog, .el-drawer').first()
      if (!(await dlg.count())) throw new Error('点了录入没有表单')
      return await page.evaluate(() => {
        const d = document.querySelector('.el-dialog, .el-drawer')
        const items = [...d.querySelectorAll('.el-form-item')].map(fi => ({
          label: (fi.querySelector('.el-form-item__label')?.innerText || '').trim(),
          type: (fi.querySelector('input,textarea,.el-select,.el-radio')?.className || '').slice(0, 40)
        }))
        return items
      })
    })
    if (qc.ok) {
      say(R, '观察', `质检表单：${JSON.stringify(qc.r.map(i => i.label))}`)
      await shot(page, 'P2-inspector-quality-form')
      // 合格/不合格怎么填？有没有「农药残留」这类专业指标
      const prof = qc.r.some(i => /农残|残留|指标|标准|判定/.test(i.label))
      say(R, prof ? 'ok' : 'friction', '质检表单是否有专业判定项（农残/标准/判定）：' + (prof ? '有' : '没有，只有通用字段'))
      await page.keyboard.press('Escape'); await sleep(400)
    }

    // 异常上报
    await nav(page, '异常', '/inspector/exception')
    await sleep(1300)
    await shot(page, 'Q1-inspector-exception')
    const ex = await snapshot(page)
    say(R, '观察', `异常上报 ${ex.rows} 行，按钮 ${JSON.stringify(ex.btns.slice(0, 8))}`)
    const exText = await page.evaluate(() => document.body.innerText.replace(/\s+/g, ' '))
    say(R, /上报|记录|提交/.test(exText) ? 'ok' : 'friction', '异常上报页可用性：' + exText.slice(0, 160))

    if (page.__errs.length) say(R, 'bug', '控制台报错：' + page.__errs.slice(0, 2).join(' | '))
  }

  await browser.close()
  fs.writeFileSync(path.join(OUT, 'roleplay.json'), JSON.stringify({ log, notes }, null, 2), 'utf8')
  const fails = log.filter(l => !l.ok)
  console.log('\n===== 汇总 =====')
  console.log('步骤 ' + log.length + '，失败/受阻 ' + fails.length)
  fails.forEach(f => console.log('  · ' + f.label + ' → ' + (f.detail || '').slice(0, 160)))
})().catch(e => { console.error('脚本异常', e); process.exit(1) })
