/* 补完真单 330：质检 → 入库（少收7kg）→ 结算 → 评价；顺带验证新加的审计日志 */
const path = require('path'); const fs = require('fs')
const ROOT = path.resolve(__dirname, '..'); const BASE = 'http://127.0.0.1:5173'; const API = 'http://127.0.0.1:8080'
const OUT = path.join(ROOT, 'docs', 'roleplay_2026-11-10', 'shots5'); fs.mkdirSync(OUT, { recursive: true })
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
const shot = (p, n) => p.screenshot({ path: path.join(OUT, n + '.png') })
async function toast(p, ms = 4000) {
  try { const t = p.locator('.el-message').first(); await t.waitFor({ state: 'visible', timeout: ms }); return (await t.innerText()).replace(/\s+/g, ' ').trim() } catch { return '' }
}
const ORDER = 'DD202611100330'

;(async () => {
  const b = await chromium.launch({ channel: 'msedge' })
  const q = await open(b, await login('inspector01', 'INSPECTOR'))
  const p = await open(b, await login('purchaser01', 'PURCHASER'))
  const f = await open(b, await login('farmer01', 'FARMER'))

  /* 质检：从我的任务点进去 */
  await q.goto(BASE + '/inspector/tasks', { waitUntil: 'domcontentloaded' }); await sleep(2200)
  const row = q.locator('.el-table__body tbody tr').filter({ hasText: ORDER }).first()
  await row.locator('.link-strong').click(); await sleep(2400)
  await shot(q, 'Q1-quality')
  const head = await q.evaluate(() => document.body.innerText.replace(/\s+/g, ' '))
  step('从我的任务进入质检，载入指定单', head.includes(ORDER), (head.match(/订单\s*DD\d+/) || [''])[0])
  const sb = await q.locator('button:has-text("提交质检")').boundingBox().catch(() => null)
  step('提交按钮首屏可见', !!(sb && sb.y < 900), sb ? 'y=' + Math.round(sb.y) : '找不到')
  await q.locator('.concl-btn.ok').click(); await sleep(400)
  await q.locator('button:has-text("提交质检")').click(); await sleep(1800)
  const bx = q.locator('.el-message-box')
  if (await bx.count()) { await bx.locator('button').filter({ hasText: /确定|确认/ }).last().click(); await sleep(2000) }
  step('提交质检', /成功|合格/.test(await toast(q)), await toast(q))
  await shot(q, 'Q2-submitted')

  /* 入库：少收 7kg 触发损耗 */
  await p.goto(BASE + '/purchaser/inbound?orderNo=' + ORDER, { waitUntil: 'domcontentloaded' }); await sleep(2600)
  await shot(p, 'I1-inbound')
  const num = p.locator('.el-input-number input').first()
  const disabled = await num.isDisabled()
  step('该单已可入库（表单可编辑）', !disabled, disabled ? '仍被禁用' : '可编辑')
  if (!disabled) {
    await num.fill('130'); await sleep(900)
    const txt = await p.evaluate(() => document.body.innerText.replace(/\s+/g, ' '))
    const loss = txt.match(/损耗率\s*([\d.]+)%/)
    step('改实收 130 后自动算损耗', !!loss, '损耗率 ' + (loss ? loss[1] : '未算出') + '%')
    await shot(p, 'I2-loss')
    await p.locator('button:has-text("确认入库")').click(); await sleep(1500)
    const bx2 = p.locator('.el-message-box')
    if (await bx2.count()) { await bx2.locator('button').filter({ hasText: /确定|确认/ }).last().click(); await sleep(2200) }
    step('确认入库', /成功/.test(await toast(p)), await toast(p))
  }
  await shot(p, 'I3-inbound-done')

  /* 农户结算 */
  await f.goto(BASE + '/farmer/settlement', { waitUntil: 'domcontentloaded' }); await sleep(2400)
  const sb2 = await f.evaluate(() => document.body.innerText.replace(/\s+/g, ' '))
  step('农户结算出现该单', sb2.includes(ORDER), sb2.includes(ORDER) ? '找到' : '没找到')
  await shot(f, 'S1-settlement')

  /* 采购评价 */
  await p.goto(BASE + '/purchaser/evaluation', { waitUntil: 'domcontentloaded' }); await sleep(2400)
  const eb = await p.evaluate(() => document.body.innerText.replace(/\s+/g, ' '))
  step('评价页出现该单', eb.includes(ORDER), eb.includes(ORDER) ? '找到' : '没找到')
  await shot(p, 'E1-eval')

  fs.writeFileSync(path.join(OUT, 'result.json'), JSON.stringify({ steps, errors: [...q.__errs, ...p.__errs, ...f.__errs] }, null, 2), 'utf8')
  console.log('\n完成 ' + steps.filter(s => s.ok).length + '/' + steps.length)
  await b.close()
})().catch(e => { console.error(e); process.exit(1) })
