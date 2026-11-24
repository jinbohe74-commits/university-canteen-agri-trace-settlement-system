/* 闭环续：从「我的任务」指定单 330 → 质检提交 → 入库 → 结算 → 评价 */
const path = require('path'); const fs = require('fs')
const ROOT = path.resolve(__dirname, '..'); const BASE = 'http://127.0.0.1:5173'; const API = 'http://127.0.0.1:8080'
const OUT = path.join(ROOT, 'docs', 'roleplay_2026-11-10')
const G = path.join(process.env.APPDATA, 'npm', 'node_modules'); module.paths.push(G)
const { chromium } = require(path.join(G, 'playwright'))
const sleep = ms => new Promise(r => setTimeout(r, ms))
const steps = []
const step = (n, ok, note) => { steps.push({ n, ok, note }); console.log(`${ok ? ' ✔' : ' ✘'} ${n}${note ? ' | ' + String(note).slice(0, 200) : ''}`) }
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
async function toast(p, ms = 3500) {
  try { const t = p.locator('.el-message').first(); await t.waitFor({ state: 'visible', timeout: ms }); return (await t.innerText()).replace(/\s+/g, ' ').trim() } catch { return '' }
}
const ORDER = 'DD202611100330'

;(async () => {
  const b = await chromium.launch({ channel: 'msedge' })
  const q = await open(b, await login('inspector01', 'INSPECTOR'))
  const p = await open(b, await login('purchaser01', 'PURCHASER'))
  const f = await open(b, await login('farmer01', 'FARMER'))

  /* 我的任务 → 指定单 */
  await q.goto(BASE + '/inspector/tasks', { waitUntil: 'domcontentloaded' }); await sleep(2200)
  await shot(q, 'M1-tasks')
  const row = q.locator('.el-table__body tbody tr').filter({ hasText: ORDER }).first()
  const found = await row.count()
  step('我的任务里能看到这单', !!found, found ? '看到 ' + ORDER : '任务列表里没有 ' + ORDER)
  if (found) {
    await row.locator('.link').filter({ hasText: '质检录入' }).first().click()
    await sleep(2200)
    await shot(q, 'M2-qc-target')
    const head = await q.evaluate(() => document.body.innerText.replace(/\s+/g, ' '))
    step('质检页带出的是指定单', head.includes(ORDER), head.match(/订单\s*DD\d+/)?.[0] || '未显示订单号')
    await q.locator('.concl-btn.ok').first().click(); await sleep(500)
    // 首屏能否看到提交按钮
    const btn = q.locator('button:has-text("提交质检")').first()
    const box = await btn.boundingBox().catch(() => null)
    step('提交按钮在首屏内可见', !!(box && box.y < 900), box ? `按钮纵坐标 y=${Math.round(box.y)}（视口 900）` : '找不到提交按钮')
    await btn.scrollIntoViewIfNeeded(); await sleep(400)
    await shot(q, 'M3-qc-bottom')
    await btn.click(); await sleep(1800)
    const bx = q.locator('.el-message-box')
    if (await bx.count()) { await bx.locator('button').filter({ hasText: /确定|确认/ }).last().click(); await sleep(1800) }
    step('提交质检', /成功|合格/.test(await toast(q)), await toast(q))
  }
  await shot(q, 'M4-qc-submitted')

  /* 入库 */
  await p.goto(BASE + '/purchaser/inbound', { waitUntil: 'domcontentloaded' }); await sleep(2200)
  const inp = p.locator('input[placeholder*="批次号或订单号"]').first()
  if (await inp.count()) { await inp.fill(ORDER); await p.locator('button').filter({ hasText: '查询' }).first().click(); await sleep(2000) }
  await shot(p, 'M5-inbound')
  const num = p.locator('.el-input-number input').first()
  if (await num.count()) {
    const v = await num.inputValue()
    await num.fill('130'); await sleep(800)
    const txt = await p.evaluate(() => document.body.innerText.replace(/\s+/g, ' '))
    const loss = txt.match(/损耗率\s*([\d.]+)%/)
    step('改实收→自动算损耗', !!loss, `实收 130（原 ${v}）→ 损耗率 ${loss ? loss[1] : '未算出'}`)
    await shot(p, 'M6-inbound-loss')
    await p.locator('button').filter({ hasText: '确认入库' }).first().click(); await sleep(1500)
    const bx = p.locator('.el-message-box')
    if (await bx.count()) { await bx.locator('button').filter({ hasText: /确定|确认/ }).last().click(); await sleep(2000) }
    step('确认入库', /成功/.test(await toast(p)), await toast(p))
  } else step('入库页出现实收输入', false, '按单号查不到可入库的单')
  await shot(p, 'M7-inbound-done')

  /* 农户结算 */
  await f.goto(BASE + '/farmer/settlement', { waitUntil: 'domcontentloaded' }); await sleep(2200)
  const sb = await f.evaluate(() => document.body.innerText.replace(/\s+/g, ' '))
  step('农户结算出现该单', sb.includes(ORDER), sb.includes(ORDER) ? '找到' : '结算列表没有该单')
  await shot(f, 'M8-settlement')

  /* 评价 */
  await p.goto(BASE + '/purchaser/evaluation', { waitUntil: 'domcontentloaded' }); await sleep(2200)
  const eb = await p.evaluate(() => document.body.innerText.replace(/\s+/g, ' '))
  step('评价页出现该单', eb.includes(ORDER), eb.includes(ORDER) ? '找到' : '评价页没有该单')
  await shot(p, 'M9-eval')

  fs.writeFileSync(path.join(OUT, 'loop3.json'), JSON.stringify({ steps, errors: [...q.__errs, ...p.__errs, ...f.__errs] }, null, 2), 'utf8')
  console.log('\n完成 ' + steps.filter(s => s.ok).length + '/' + steps.length)
  await b.close()
})().catch(e => { console.error(e); process.exit(1) })
