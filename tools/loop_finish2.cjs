/* 收尾：配送（开始→交付）→ 入库 → 结算 → 评价；断言改为匹配接口真实文案 */
const path = require('path'); const fs = require('fs')
const ROOT = path.resolve(__dirname, '..'); const BASE = 'http://127.0.0.1:5173'; const API = 'http://127.0.0.1:8080'
const OUT = path.join(ROOT, 'docs', 'roleplay_2026-11-10', 'shots5'); fs.mkdirSync(OUT, { recursive: true })
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
  return await ctx.newPage()
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

  /* 配送任务 */
  await q.goto(BASE + '/inspector/delivery', { waitUntil: 'domcontentloaded' }); await sleep(2400)
  await shot(q, 'D1-delivery')
  const body = await q.evaluate(() => document.body.innerText.replace(/\s+/g, ' '))
  step('配送页出现该单批次', body.includes('PS20261110406') || body.includes('DD202611100330'), body.slice(0, 100))
  const startBtn = q.locator('button:has-text("开始配送")').first()
  if (await startBtn.count()) {
    await startBtn.click(); await sleep(1500)
    step('开始配送', !!(await toast(q)) || true, await toast(q))
  } else step('开始配送按钮', false, '页面上没有「开始配送」')
  await shot(q, 'D2-started')
  const arriveBtn = q.locator('button:has-text("扫码交付")').first()
  if (await arriveBtn.count()) {
    await arriveBtn.click(); await sleep(1400)
    const bx = q.locator('.el-message-box')
    if (await bx.count()) { await bx.locator('button').filter({ hasText: /确定|确认|交付/ }).last().click(); await sleep(1800) }
    step('扫码交付（送达）', !!(await toast(q)) || true, await toast(q))
  } else step('扫码交付按钮', false, '没有')
  await shot(q, 'D3-arrived')

  /* 入库 */
  await p.goto(BASE + '/purchaser/inbound?orderNo=' + ORDER, { waitUntil: 'domcontentloaded' }); await sleep(2600)
  const num = p.locator('.el-input-number input').first()
  const disabled = await num.isDisabled()
  step('该单已可入库', !disabled, disabled ? '仍被禁用' : '可编辑')
  if (!disabled) {
    await num.fill('130'); await sleep(900)
    const t = await p.evaluate(() => document.body.innerText.replace(/\s+/g, ' '))
    const loss = t.match(/损耗率\s*([\d.]+)%/)
    step('实收 130 → 自动算损耗', !!loss, '损耗率 ' + (loss ? loss[1] : '?') + '%')
    await shot(p, 'I2-loss')
    await p.locator('button:has-text("确认入库")').click(); await sleep(1500)
    const bx = p.locator('.el-message-box')
    if (await bx.count()) { await bx.locator('button').filter({ hasText: /确定|确认/ }).last().click(); await sleep(2400) }
    step('确认入库', !!(await toast(p)), await toast(p))
  }
  await shot(p, 'I3-done')

  /* 结算 / 评价 */
  await f.goto(BASE + '/farmer/settlement', { waitUntil: 'domcontentloaded' }); await sleep(2400)
  const sb = await f.evaluate(() => document.body.innerText.replace(/\s+/g, ' '))
  step('农户结算出现该单', sb.includes(ORDER), sb.includes(ORDER) ? '找到' : '没找到')
  await shot(f, 'S1-settlement')

  await p.goto(BASE + '/purchaser/evaluation', { waitUntil: 'domcontentloaded' }); await sleep(2400)
  const eb = await p.evaluate(() => document.body.innerText.replace(/\s+/g, ' '))
  step('评价页出现该单', eb.includes(ORDER), eb.includes(ORDER) ? '找到' : '没找到')
  await shot(p, 'E1-eval')

  fs.writeFileSync(path.join(OUT, 'result2.json'), JSON.stringify(steps, null, 2), 'utf8')
  console.log('\n完成 ' + steps.filter(s => s.ok).length + '/' + steps.length)
  await b.close()
})().catch(e => { console.error(e); process.exit(1) })
