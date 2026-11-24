/* 闭环后半程：接单 → 发货 → 质检 → 入库 → 结算 → 评价（续 loop_run 的真单 330） */
const path = require('path'); const fs = require('fs')
const ROOT = path.resolve(__dirname, '..'); const BASE = 'http://127.0.0.1:5173'; const API = 'http://127.0.0.1:8080'
const OUT = path.join(ROOT, 'docs', 'roleplay_2026-11-10')
const G = path.join(process.env.APPDATA, 'npm', 'node_modules'); module.paths.push(G)
const { chromium } = require(path.join(G, 'playwright'))
const sleep = ms => new Promise(r => setTimeout(r, ms))
const steps = []
const step = (n, ok, note) => { steps.push({ n, ok, note }); console.log(`${ok ? ' ✔' : ' ✘'} ${n}${note ? ' | ' + String(note).slice(0, 180) : ''}`) }
async function login(u, r) {
  const res = await fetch(API + '/api/auth/login', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ username: u, password: '123456', expectedRole: r }) })
  return (await res.json()).data
}
async function open(b, d) {
  const ctx = await b.newContext({ viewport: { width: 1440, height: 900 } })
  await ctx.addInitScript(([t, u, rn, mn]) => { sessionStorage.setItem('ct_token', t); sessionStorage.setItem('ct_user', u); sessionStorage.setItem('ct_role_name', rn); sessionStorage.setItem('ct_menus', mn) }, [d.token, JSON.stringify(d.user), d.roleName || '', JSON.stringify(d.menus || [])])
  const p = await ctx.newPage(); p.__errs = []
  p.on('pageerror', e => p.__errs.push(e.message.slice(0, 150)))
  return p
}
const shot = (p, n) => p.screenshot({ path: path.join(OUT, 'shots3', n + '.png') })
async function toast(p, ms = 3000) {
  try { const t = p.locator('.el-message').first(); await t.waitFor({ state: 'visible', timeout: ms }); return (await t.innerText()).replace(/\s+/g, ' ').trim() } catch { return '' }
}
const ORDER = 'DD202611100330'

;(async () => {
  const b = await chromium.launch({ channel: 'msedge' })
  const f = await open(b, await login('farmer01', 'FARMER'))
  const p = await open(b, await login('purchaser01', 'PURCHASER'))
  const q = await open(b, await login('inspector01', 'INSPECTOR'))

  /* --- 接单 --- */
  await f.goto(BASE + '/farmer/orders', { waitUntil: 'domcontentloaded' }); await sleep(2000)
  await f.locator('.el-tabs__item').filter({ hasText: '待接单' }).first().click(); await sleep(1500)
  try {
    const link = f.locator('.el-table__body tbody tr').first().locator('.link').filter({ hasText: '接单' }).first()
    await link.click(); await sleep(1200)
    const box = f.locator('.el-message-box')
    if (await box.count()) { await box.locator('button').filter({ hasText: /确定|确认|接单/ }).last().click(); await sleep(1200) }
    step('农户接单', /成功/.test(await toast(f)), await toast(f))
  } catch (e) { step('农户接单', false, e.message.slice(0, 100)) }
  await shot(f, 'L1-accepted')

  /* --- 发货 --- */
  await f.goto(BASE + '/farmer/orders', { waitUntil: 'domcontentloaded' }); await sleep(2000)
  await f.locator('.el-tabs__item').filter({ hasText: '已接单' }).first().click(); await sleep(1500)
  try {
    await shot(f, 'L2-ship-tab')
    const link = f.locator('.el-table__body tbody tr').first().locator('.link').filter({ hasText: '发货' }).first()
    await link.click(); await sleep(1300)
    await shot(f, 'L3-ship-dialog')
    const dlg = f.locator('.el-dialog:visible, .el-message-box:visible').first()
    if (await dlg.count()) {
      const txt = (await dlg.innerText()).replace(/\s+/g, ' ')
      step('发货弹窗内容', true, txt.slice(0, 160))
      const inputs = dlg.locator('input')
      const n = await inputs.count()
      for (let i = 0; i < n; i++) {
        const ph = await inputs.nth(i).getAttribute('placeholder') || ''
        if (/批次|物流|单号|车牌|司机/.test(ph)) await inputs.nth(i).fill('B' + Date.now().toString().slice(-6))
      }
      await dlg.locator('button').filter({ hasText: /确定|确认|发货/ }).last().click(); await sleep(1400)
    }
    step('农户发货', /成功/.test(await toast(f)), await toast(f))
  } catch (e) { step('农户发货', false, e.message.slice(0, 100)) }
  await shot(f, 'L4-shipped')

  /* --- 质检 --- */
  await q.goto(BASE + '/inspector/quality', { waitUntil: 'domcontentloaded' }); await sleep(2400)
  try {
    await shot(q, 'L5-qc')
    const body = await q.evaluate(() => document.body.innerText.replace(/\s+/g, ' '))
    const loaded = body.includes('批次质检单')
    step('质检页自动载入待检单', loaded, body.slice(0, 120))
    if (loaded) {
      await q.locator('button').filter({ hasText: '质检合格' }).first().click(); await sleep(1200)
      await shot(q, 'L6-qc-confirm')
      const box = q.locator('.el-message-box')
      if (await box.count()) { await box.locator('button').filter({ hasText: /确定|确认/ }).last().click(); await sleep(1600) }
      step('质检录检合格', /成功/.test(await toast(q)), await toast(q))
    }
  } catch (e) { step('质检录检合格', false, e.message.slice(0, 100)) }
  await shot(q, 'L7-qc-done')

  /* --- 入库 --- */
  await p.goto(BASE + '/purchaser/inbound', { waitUntil: 'domcontentloaded' }); await sleep(2200)
  try {
    const inp = p.locator('input[placeholder*="批次号或订单号"]').first()
    if (await inp.count()) { await inp.fill(ORDER); await p.locator('button').filter({ hasText: '查询' }).first().click(); await sleep(1800) }
    await shot(p, 'L8-inbound-order')
    const body = await p.evaluate(() => document.body.innerText.replace(/\s+/g, ' '))
    step('入库页能按单号带出这单', body.includes(ORDER), body.slice(0, 140))
    const num = p.locator('.el-input-number input').first()
    if (await num.count()) {
      const v = await num.inputValue()
      await num.fill('130'); await sleep(600)   // 少收 7kg，模拟损耗
      const after = await p.evaluate(() => document.body.innerText.replace(/\s+/g, ' '))
      const loss = after.match(/损耗率\s*([\d.]+)%/)
      step('改实收数量后自动算损耗', !!loss, '实收 130 / 原 ' + v + ' → 损耗率 ' + (loss ? loss[1] : '没算出来'))
      await shot(p, 'L9-inbound-loss')
    } else step('入库页有实收输入框', false, '没有数量输入')
    await p.locator('button').filter({ hasText: '确认入库' }).first().click(); await sleep(1300)
    const box = p.locator('.el-message-box')
    if (await box.count()) { await box.locator('button').filter({ hasText: /确定|确认/ }).last().click(); await sleep(1600) }
    step('采购入库确认', /成功/.test(await toast(p)), await toast(p))
  } catch (e) { step('采购入库确认', false, e.message.slice(0, 100)) }
  await shot(p, 'L10-inbound-done')

  /* --- 农户结算 --- */
  await f.goto(BASE + '/farmer/settlement', { waitUntil: 'domcontentloaded' }); await sleep(2200)
  const sb = await f.evaluate(() => document.body.innerText.replace(/\s+/g, ' '))
  step('农户结算出现这单', sb.includes(ORDER), sb.includes(ORDER) ? '找到 ' + ORDER : '结算里没有这单')
  await shot(f, 'L11-settlement')

  /* --- 评价 --- */
  await p.goto(BASE + '/purchaser/evaluation', { waitUntil: 'domcontentloaded' }); await sleep(2200)
  const eb = await p.evaluate(() => document.body.innerText.replace(/\s+/g, ' '))
  step('评价页出现这单', eb.includes(ORDER), eb.includes(ORDER) ? '找到' : '没找到')
  await shot(p, 'L12-eval')

  fs.writeFileSync(path.join(OUT, 'loop2.json'), JSON.stringify({ order: ORDER, steps, errors: [...f.__errs, ...p.__errs, ...q.__errs] }, null, 2), 'utf8')
  console.log('\n完成 ' + steps.filter(s => s.ok).length + '/' + steps.length)
  await b.close()
})().catch(e => { console.error(e); process.exit(1) })
