/* ==========================================================================
   闭环跑单（2026-11-10）
   以真实用户身份，把一单生意从发布货源跑到评价：
     农户发布货源 → 采购员下单 → 农户接单发货 → 质检录检 → 采购入库 → 结算 → 评价
   每一步只做「一个真人会做的操作」，找不到按钮就记下来——那就是卡点。
   ========================================================================== */
const path = require('path')
const fs = require('fs')
const ROOT = path.resolve(__dirname, '..')
const BASE = 'http://127.0.0.1:5173'
const API = 'http://127.0.0.1:8080'
const OUT = path.join(ROOT, 'docs', 'roleplay_2026-11-10')
fs.mkdirSync(path.join(OUT, 'shots3'), { recursive: true })

const GLOBAL = path.join(process.env.APPDATA, 'npm', 'node_modules')
module.paths.push(GLOBAL)
const { chromium } = require(path.join(GLOBAL, 'playwright'))
const sleep = ms => new Promise(r => setTimeout(r, ms))

const steps = []
const step = (name, ok, note) => {
  steps.push({ name, ok, note })
  console.log(`${ok ? ' ✔' : ' ✘'} ${name}${note ? ' | ' + String(note).slice(0, 200) : ''}`)
}

async function apiLogin(u, p = '123456', r) {
  const res = await fetch(API + '/api/auth/login', {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username: u, password: p, expectedRole: r })
  })
  const j = await res.json()
  if (j.code !== 200) throw new Error(u + ' 登录失败:' + j.message)
  return j.data
}
async function open(browser, d) {
  const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 } })
  await ctx.addInitScript(([t, u, rn, mn]) => {
    sessionStorage.setItem('ct_token', t); sessionStorage.setItem('ct_user', u)
    sessionStorage.setItem('ct_role_name', rn); sessionStorage.setItem('ct_menus', mn)
  }, [d.token, JSON.stringify(d.user), d.roleName || '', JSON.stringify(d.menus || [])])
  const page = await ctx.newPage()
  page.__errs = []
  page.on('pageerror', e => page.__errs.push(e.message.slice(0, 150)))
  return page
}
const shot = (p, n) => p.screenshot({ path: path.join(OUT, 'shots3', n + '.png') })
async function toast(page, ms = 2500) {
  const t = page.locator('.el-message').first()
  try {
    await t.waitFor({ state: 'visible', timeout: ms })
    const s = (await t.innerText()).replace(/\s+/g, ' ').trim()
    return s
  } catch { return '' }
}

;(async () => {
  const browser = await chromium.launch({ channel: 'msedge' })
  const farmer = await open(browser, await apiLogin('farmer01', '123456', 'FARMER'))
  const buyer = await open(browser, await apiLogin('purchaser01', '123456', 'PURCHASER'))
  const qc = await open(browser, await apiLogin('inspector01', '123456', 'INSPECTOR'))

  const MARK_QTY = 137
  let orderNo = ''

  /* ---------- 1. 农户发布货源 ---------- */
  await farmer.goto(BASE + '/farmer/sources', { waitUntil: 'domcontentloaded' })
  await sleep(1700)
  try {
    // 产品下拉
    await farmer.locator('.src-form .el-select').first().click()
    await sleep(500)
    await farmer.keyboard.type('大白菜', { delay: 40 })
    await sleep(800)
    const opt = farmer.locator('.el-select-dropdown__item:visible').first()
    await opt.click()
    await sleep(400)
    // 数量 / 价格
    const nums = farmer.locator('.src-form .el-input-number input')
    await nums.nth(0).fill(String(MARK_QTY))
    await nums.nth(1).fill('2.11')
    await farmer.locator('.src-form button:has-text("发布货源"), .src-form button:has-text("保存")').first().click()
    const t = await toast(farmer)
    step('农户发布货源', /成功/.test(t), t || '没有提示')
  } catch (e) { step('农户发布货源', false, e.message.slice(0, 120)) }
  await shot(farmer, 'S1-farmer-publish')

  /* ---------- 2. 采购员找到这单货并下单 ---------- */
  await buyer.goto(BASE + '/purchaser/sources', { waitUntil: 'domcontentloaded' })
  await sleep(1700)
  try {
    // 用页面自己的搜索框（占位符含"搜索产品名"）
    await buyer.locator('input[placeholder*="产品名"]').first().fill('大白菜')
    await buyer.locator('button:has-text("查询")').first().click()
    await sleep(1600)
    await shot(buyer, 'S2-buyer-search')
    // 找 137kg 那张卡（我刚发的）
    const card = buyer.locator('.src-card, .hall-card, .card').filter({ hasText: String(MARK_QTY) }).first()
    const has = await card.count()
    if (!has) throw new Error('货源大厅里找不到刚发布的 137kg 货源')
    await card.locator('button:has-text("下单")').click()
    await sleep(1000)
    await buyer.locator('.el-dialog .el-input-number input').first().fill(String(MARK_QTY))
    await shot(buyer, 'S3-buyer-order')
    await buyer.locator('.el-dialog button:has-text("确认"), .el-dialog button:has-text("提交"), .el-dialog button:has-text("下单")').last().click()
    const t = await toast(buyer)
    step('采购员下单', /成功/.test(t), t || '没有提示')
  } catch (e) { step('采购员下单', false, e.message.slice(0, 120)) }
  await shot(buyer, 'S4-buyer-order-done')

  /* ---------- 3. 农户接单 + 发货 ---------- */
  await farmer.goto(BASE + '/farmer/orders', { waitUntil: 'domcontentloaded' })
  await sleep(1700)
  try {
    await farmer.locator('.el-tabs__item:has-text("待接单") button, [class*="tab"]:has-text("待接单")').first().click().catch(() => {})
    await sleep(1200)
    const row = farmer.locator('.el-table__body tbody tr').first()
    orderNo = (await row.locator('td').first().innerText().catch(() => '')).trim()
    await row.locator('text=接单').first().click()
    await sleep(900)
    // 可能有二次确认
    const confirm = farmer.locator('.el-message-box button:has-text("确定"), .el-message-box button:has-text("确认")').first()
    if (await confirm.count()) { await confirm.click(); await sleep(900) }
    const t = await toast(farmer)
    step('农户接单', /成功/.test(t), (t || '没有提示') + ' 单号 ' + orderNo)
  } catch (e) { step('农户接单', false, e.message.slice(0, 120)) }
  await shot(farmer, 'S5-farmer-accept')

  await sleep(800)
  try {
    // 接单后刷新到「已接单」
    await farmer.reload({ waitUntil: 'domcontentloaded' }); await sleep(1600)
    await farmer.locator('.el-tabs__item:has-text("已接单"), [class*="tab"]:has-text("已接单")').first().click().catch(() => {})
    await sleep(1200)
    const row = farmer.locator('.el-table__body tbody tr').first()
    await row.locator('text=发货').first().click()
    await sleep(1100)
    await shot(farmer, 'S6-farmer-ship-form')
    // 发货表单：可能填物流单号/批次
    const dlg = farmer.locator('.el-dialog:visible, .el-message-box:visible').first()
    if (await dlg.count()) {
      const inputs = dlg.locator('input')
      const n = await inputs.count()
      for (let i = 0; i < n; i++) {
        const ph = await inputs.nth(i).getAttribute('placeholder')
        if (ph && /批次|物流|单号|司机/.test(ph)) await inputs.nth(i).fill('B' + Date.now().toString().slice(-8))
      }
      await dlg.locator('button:has-text("确定"), button:has-text("确认"), button:has-text("发货")').last().click()
      await sleep(1000)
    }
    const t = await toast(farmer)
    step('农户发货', /成功/.test(t), t || '没有提示')
  } catch (e) { step('农户发货', false, e.message.slice(0, 120)) }
  await shot(farmer, 'S7-farmer-shipped')

  /* ---------- 4. 质检员录检 ---------- */
  await qc.goto(BASE + '/inspector/quality', { waitUntil: 'domcontentloaded' })
  await sleep(2000)
  try {
    await shot(qc, 'S8-qc-page')
    const hasForm = await qc.locator('text=批次质检单').count()
    if (!hasForm) throw new Error('质检页没有载入待检批次（显示暂无待质检订单）')
    // 全部达标 → 合格
    const passBtn = qc.locator('button:has-text("质检合格")').first()
    await passBtn.click()
    await sleep(1000)
    const confirm = qc.locator('.el-message-box button:has-text("确定"), .el-message-box button:has-text("确认")').first()
    if (await confirm.count()) { await confirm.click(); await sleep(1200) }
    const t = await toast(qc)
    step('质检录检合格', /成功/.test(t), t || '没有提示')
  } catch (e) { step('质检录检合格', false, e.message.slice(0, 120)) }
  await shot(qc, 'S9-qc-done')

  /* ---------- 5. 采购员入库确认 ---------- */
  await buyer.goto(BASE + '/purchaser/inbound', { waitUntil: 'domcontentloaded' })
  await sleep(1800)
  try {
    // 搜索这单
    await buyer.locator('input[placeholder*="批次号或订单号"]').first().fill(orderNo)
    await buyer.locator('button:has-text("查询")').first().click()
    await sleep(1600)
    await shot(buyer, 'S10-buyer-inbound')
    const qty = buyer.locator('.el-input-number input').first()
    const cur = await qty.inputValue()
    step('入库页自动带出实收数量', !!cur, '实收=' + cur)
    await buyer.locator('button:has-text("确认入库")').first().click()
    await sleep(1000)
    const confirm = buyer.locator('.el-message-box button:has-text("确定"), .el-message-box button:has-text("确认")').first()
    if (await confirm.count()) { await confirm.click(); await sleep(1300) }
    const t = await toast(buyer)
    step('采购入库确认', /成功/.test(t), t || '没有提示')
  } catch (e) { step('采购入库确认', false, e.message.slice(0, 120)) }
  await shot(buyer, 'S11-buyer-inbound-done')

  /* ---------- 6. 农户看结算 ---------- */
  await farmer.goto(BASE + '/farmer/settlement', { waitUntil: 'domcontentloaded' })
  await sleep(1800)
  try {
    const body = await farmer.evaluate(() => document.body.innerText.replace(/\s+/g, ' '))
    const hit = body.includes(orderNo) || body.includes(String(MARK_QTY))
    step('农户能看到这单结算', hit, hit ? '在结算列表里找到了' : '结算列表里找不到这单')
  } catch (e) { step('农户能看到这单结算', false, e.message.slice(0, 100)) }
  await shot(farmer, 'S12-farmer-settlement')

  /* ---------- 7. 采购员评价 ---------- */
  await buyer.goto(BASE + '/purchaser/evaluation', { waitUntil: 'domcontentloaded' })
  await sleep(1800)
  try {
    const body = await buyer.evaluate(() => document.body.innerText.replace(/\s+/g, ' '))
    const has = body.includes(orderNo)
    step('评价页能找到这单', has, has ? '找到了' : '评价页看不到刚入库的单')
  } catch (e) { step('评价页能找到这单', false, e.message.slice(0, 100)) }
  await shot(buyer, 'S13-buyer-eval')

  const errs = [...farmer.__errs, ...buyer.__errs, ...qc.__errs]
  fs.writeFileSync(path.join(OUT, 'loop.json'), JSON.stringify({ orderNo, qty: MARK_QTY, steps, errors: errs }, null, 2), 'utf8')
  console.log('\n单号 ' + orderNo)
  console.log('完成 ' + steps.filter(s => s.ok).length + '/' + steps.length)
  if (errs.length) console.log('页面异常 ' + errs.length + '：' + errs.slice(0, 3).join(' | '))
  await browser.close()
})().catch(e => { console.error('脚本异常', e); process.exit(1) })
