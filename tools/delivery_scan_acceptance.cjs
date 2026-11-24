/* 配送任务「扫码交付」+「本批装载」验收（可重复跑：没有在途单就先把待发车的发出去）
   根因回归：批次号提示是 "04****" 掩码、装载清单列的是全场在途批次（含别的车），
   操作员抄了别车尾号 → 明明输对也判错。
   断言要求：页面必须能看到本单批次与尾号；尾号能通过；整串能通过；别车尾号必须被拒。 */
const path = require('path')
const G = path.join(process.env.APPDATA, 'npm', 'node_modules')
module.paths.push(G)
const { chromium } = require(path.join(G, 'playwright'))

const sleep = (ms) => new Promise((r) => setTimeout(r, ms))
const BASE = 'http://127.0.0.1:5173'
const API = 'http://127.0.0.1:8080'
const OUT = 'docs/reform_2026-11-11/shots9'

let pass = 0, fail = 0
const ok = (cond, msg) => { if (cond) { pass++; console.log('  ✓', msg) } else { fail++; console.log('  ✘', msg) } }

async function login(user, role) {
  const r = await fetch(`${API}/api/auth/login`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username: user, password: '123456', expectedRole: role })
  })
  return (await r.json()).data
}
const api = (token, p, init = {}) => fetch(`${API}/api${p}`, {
  ...init,
  headers: { 'Content-Type': 'application/json', Authorization: 'Bearer ' + token, ...(init.headers || {}) }
}).then((r) => r.json())

;(async () => {
  const d = await login('inspector01', 'INSPECTOR')
  ok(!!d?.token, '质检员登录成功')
  const get = async () => (await api(d.token, '/inspector/deliveries'))?.data || {}

  /* ---------- 准备：拿到一张在途配送单 ---------- */
  let data = await get()
  let target = (data.records || []).find((r) => r.status === '配送中')
  if (!target) {
    const cand = (data.records || []).find((r) => r.status === '待发车' && r.batchNo)
    ok(!!cand, `无在途单，改用待发车单做准备：${cand?.deliveryNo || '无'}`)
    if (!cand) { console.log('无可用于验收的配送单'); process.exit(1) }
    const st = await api(d.token, `/inspector/deliveries/${cand.id}/start`, {
      method: 'POST', body: JSON.stringify({ vehicle: cand.vehicle, driver: cand.driver, temperature: 6 })
    })
    ok(st.code === 200, `发车成功（起始温度 6℃）：${st.message}`)
    data = await get()
    target = (data.records || []).find((r) => r.id === cand.id)
  }
  ok(target?.status === '配送中', `在途配送单 ${target?.deliveryNo}（${target?.status}）`)

  /* ---------- 接口层 ---------- */
  const batchNo = String(target?.batchNo || '')
  const tail6 = batchNo.slice(-6)
  ok(batchNo.length >= 6, `配送单带整串批次号：${batchNo}`)
  ok(target?.batchTail === tail6, `batchTail 等于末 6 位（${target?.batchTail}）`)
  ok(!/^\w{2}\*+$/.test(String(target?.batchTailHint || '')), '不再下发 "04****" 这类掩码提示')

  const otherBatch = (data.records || [])
    .filter((r) => r.batchNo && r.id !== target.id)
    .map((r) => String(r.batchNo))
    .find((b) => b.slice(-6) !== tail6)
  const otherTail = otherBatch ? otherBatch.slice(-6) : ''

  if (otherTail) {
    const bad = await api(d.token, `/inspector/deliveries/${target.id}/arrive`, {
      method: 'POST', body: JSON.stringify({ batchCode: otherTail })
    })
    ok(bad.code !== 200, `别的车尾号 ${otherTail} 被拒绝`)
    ok(String(bad.message || '').includes(batchNo), `拒绝提示里给出本单完整批次号：${bad.message}`)
  } else {
    ok(false, '未能找到其他批次的尾号用于反例')
  }
  const empty = await api(d.token, `/inspector/deliveries/${target.id}/arrive`, {
    method: 'POST', body: JSON.stringify({ batchCode: '' })
  })
  ok(empty.code !== 200, '空输入被拒绝')

  /* ---------- 页面层 ---------- */
  const browser = await chromium.launch({ channel: 'msedge' })
  const ctx = await browser.newContext({ viewport: { width: 1440, height: 940 } })
  await ctx.addInitScript(([t, u, rn, mn]) => {
    sessionStorage.setItem('ct_token', t)
    sessionStorage.setItem('ct_user', u)
    sessionStorage.setItem('ct_role_name', rn)
    sessionStorage.setItem('ct_menus', mn)
  }, [d.token, JSON.stringify(d.user), d.roleName || '', JSON.stringify(d.menus || [])])
  const page = await ctx.newPage()
  const errors = []
  page.on('pageerror', (e) => errors.push(e.message))
  await page.goto(`${BASE}/inspector/delivery`, { waitUntil: 'domcontentloaded' })
  await sleep(3600)

  // 切到目标配送单（表格点行）
  const rowSel = `.el-table__row:has-text("${target.deliveryNo}")`
  if (!(await page.locator('.batch-row.mine').innerText().catch(() => '')).includes(batchNo)) {
    await page.locator(rowSel).first().click()
    await sleep(1500)
  }

  // 本批装载：只能是本车的批次，且要显示尾号
  const rows = await page.locator('.batch-row').count()
  ok(rows >= 1 && rows <= 2, `本批装载只列本车批次（${rows} 个，不再是全场 15 个）`)
  const mineText = await page.locator('.batch-row.mine').first().innerText().catch(() => '')
  ok(mineText.includes(batchNo), `本单批次在装载卡里可见：${mineText.replace(/\s+/g, ' ')}`)
  ok(mineText.includes(tail6), `装载卡里直接给出尾号 ${tail6}`)
  const overflow = await page.evaluate(() => {
    const el = document.querySelector('.br-no')
    if (!el) return { found: false }
    return { found: true, wrapped: el.getBoundingClientRect().height > 30 }
  })
  ok(overflow.found && !overflow.wrapped, `批次号单行显示、超长省略（${JSON.stringify(overflow)}）`)
  await page.screenshot({ path: `${OUT}/INSPECTOR-1-本批装载.png` })

  // 打开扫码交付弹窗：必须能看到本单批次与尾号
  await page.locator('button', { hasText: '扫码交付' }).first().click()
  await sleep(900)
  const dlg = page.locator('.el-dialog', { hasText: '扫码交付' }).first()
  const dlgText = (await dlg.innerText()).replace(/\s+/g, ' ')
  ok(dlgText.includes(batchNo), `弹窗里写明本单批次 ${batchNo}`)
  ok(dlgText.includes(tail6), `弹窗里写明尾号 ${tail6}`)
  ok(!/应为：\s*\w{2}\*+/.test(dlgText), '弹窗不再出现 "应为：04****" 的星号提示')
  await page.screenshot({ path: `${OUT}/INSPECTOR-2-扫码交付弹窗.png` })

  // 输错：提示里带本单完整批次号
  await dlg.locator('input').first().fill('000000')
  await dlg.locator('button', { hasText: '核对并交付' }).click()
  await sleep(1500)
  const errText = await page.locator('.scan-error').innerText().catch(() => '')
  ok(errText.includes(batchNo), `输错时代码提示本单批次：${errText}`)
  await page.screenshot({ path: `${OUT}/INSPECTOR-3-输错提示.png` })

  // 输对整串（模拟扫枪）：必须通过并关闭弹窗
  await dlg.locator('input').first().fill(batchNo)
  await dlg.locator('button', { hasText: '核对并交付' }).click()
  await sleep(2600)
  const stillOpen = await page.locator('.el-dialog', { hasText: '扫码交付' }).first().isVisible().catch(() => false)
  ok(!stillOpen, '输入正确批次号后弹窗关闭（不再误判）')
  const after = (await get()).records || []
  const afterRow = after.find((r) => r.id === target.id)
  ok(afterRow?.status === '已送达', `该配送单已置为已送达（${afterRow?.status}）`)
  ok(afterRow?.orderStatus === 60, '关联订单进入「待入库」（status=60）')
  await page.screenshot({ path: `${OUT}/INSPECTOR-4-交付后.png` })

  ok(errors.length === 0, `页面无 JS 报错${errors.length ? '：' + errors.join(' / ') : ''}`)

  await browser.close()
  console.log(`\n结果：${pass} 通过 / ${fail} 失败`)
  process.exit(fail ? 1 : 0)
})()
