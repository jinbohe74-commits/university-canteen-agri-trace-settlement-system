/* 配送任务排版取证：只发车、不交付，留一张在途单给用户自己试扫码 */
const path = require('path')
const G = path.join(process.env.APPDATA, 'npm', 'node_modules')
module.paths.push(G)
const { chromium } = require(path.join(G, 'playwright'))

const sleep = (ms) => new Promise((r) => setTimeout(r, ms))
const BASE = 'http://127.0.0.1:5173'
const API = 'http://127.0.0.1:8080'
const OUT = 'docs/reform_2026-11-11/shots10'

let pass = 0, fail = 0
const ok = (c, m) => { c ? (pass++, console.log('  ✓', m)) : (fail++, console.log('  ✘', m)) }

const api = (token, p, init = {}) => fetch(`${API}/api${p}`, {
  ...init,
  headers: { 'Content-Type': 'application/json', Authorization: 'Bearer ' + token, ...(init.headers || {}) }
}).then((r) => r.json())

;(async () => {
  const login = await fetch(`${API}/api/auth/login`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username: 'inspector01', password: '123456', expectedRole: 'INSPECTOR' })
  }).then((r) => r.json())
  const d = login.data

  const get = async () => (await api(d.token, '/inspector/deliveries'))?.data || {}
  let data = await get()
  let target = (data.records || []).find((r) => r.status === '配送中')
  if (!target) {
    const cand = (data.records || []).find((r) => r.status === '待发车' && r.batchNo)
    const st = await api(d.token, `/inspector/deliveries/${cand.id}/start`, {
      method: 'POST', body: JSON.stringify({ vehicle: cand.vehicle, driver: cand.driver, temperature: 6 })
    })
    ok(st.code === 200, `发车 ${cand.deliveryNo}：${st.message}`)
    data = await get()
    target = (data.records || []).find((r) => r.id === cand.id)
  }
  ok(target?.status === '配送中', `在途单 ${target.deliveryNo}，批次 ${target.batchNo}（尾号 ${target.batchTail}）`)

  const batchNo = String(target.batchNo)
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

  if (!(await page.locator('.batch-row.mine').innerText().catch(() => '')).includes(batchNo)) {
    await page.locator(`.el-table__row:has-text("${target.deliveryNo}")`).first().click()
    await sleep(1600)
  }

  const cardText = (await page.locator('.batch-row.mine').first().innerText()).replace(/\s+/g, ' ')
  ok(cardText.includes(batchNo), `装载卡显示本单批次：${cardText}`)
  ok((await page.locator('.batch-row').count()) <= 2, '装载卡只列本车批次')
  const h = await page.evaluate(() => {
    const cards = [...document.querySelectorAll('.grid-d > .card')].map((c) => Math.round(c.getBoundingClientRect().height))
    const no = document.querySelector('.br-no')
    return { cards, wraps: no ? no.getBoundingClientRect().height > 30 : null }
  })
  ok(h.cards.length === 3 && Math.max(...h.cards) - Math.min(...h.cards) < 90, `三张卡高度协调：${h.cards.join(' / ')}`)
  ok(h.wraps === false, '批次号不换行')
  await page.screenshot({ path: `${OUT}/INSPECTOR-5-本批装载-发车后.png` })

  await page.locator('button', { hasText: '扫码交付' }).first().click()
  await sleep(900)
  await page.screenshot({ path: `${OUT}/INSPECTOR-6-扫码交付弹窗-发车后.png` })
  await page.locator('.el-dialog button', { hasText: '取消' }).first().click()

  ok(errors.length === 0, `无 JS 报错${errors.length ? '：' + errors.join(' / ') : ''}`)
  await browser.close()
  console.log(`\n结果：${pass} 通过 / ${fail} 失败；留给用户试扫的批次 ${batchNo}（尾号 ${target.batchTail}）`)
  process.exit(fail ? 1 : 0)
})()
