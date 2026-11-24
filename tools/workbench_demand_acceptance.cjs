/* 第十轮验收：配送任务工作台重排 + 采购需求列表与待办深链
   断言"看到的就是该看到的"：默认只列待处理、历史单要标明、待办点进来要定位到具体需求 */
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
const num = (s) => Number(String(s).replace(/[^\d]/g, ''))

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

async function openPage(browser, d, url) {
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
  await page.goto(url, { waitUntil: 'domcontentloaded' })
  await sleep(3600)
  return { page, errors }
}

;(async () => {
  const browser = await chromium.launch({ channel: 'msedge' })

  /* ==================== 一、质检配送员：配送任务 ==================== */
  console.log('\n=== 配送任务工作台 ===')
  const insp = await login('inspector01', 'INSPECTOR')
  const raw = await api(insp.token, '/inspector/deliveries')
  const d0 = raw.data || {}
  const counts = d0.counts || {}
  const rows = d0.records || []
  const activeCount = rows.filter((r) => r.status === '待发车' || r.status === '配送中').length
  const doneCount = rows.filter((r) => r.status === '已送达').length

  ok(counts.waiting + counts.shipping === activeCount, `接口在途/待发车计数与明细一致（${counts.waiting}+${counts.shipping}=${activeCount}）`)
  ok(counts.delivered === doneCount, `已交付计数与明细一致（${counts.delivered}）`)
  if (activeCount === 0) {
    ok(d0.current === null, '没有待办单时 current 为 null（不再拿历史单冒充）')
  } else {
    ok(['待发车', '配送中'].includes(d0.current?.status), `current 只可能是待发车/配送中（${d0.current?.status}）`)
  }
  // 历史单的节点必须补成已完成，不能再画成 0/4
  const doneRow = rows.find((r) => r.status === '已送达')
  const doneNodes = (doneRow?.traces || []).filter((t) => t.done).length
  ok(doneRow && doneNodes === (doneRow.traces || []).length,
    `已送达单的运输节点全部标记完成（${doneNodes}/${(doneRow?.traces || []).length}）`)

  const dv = await openPage(browser, insp, `${BASE}/inspector/delivery`)
  const pv = dv.page
  await sleep(800)
  const chipNums = await pv.locator('.tb-cell .tb-n').allInnerTexts()
  ok(num(chipNums[0]) === counts.waiting && num(chipNums[1]) === counts.shipping && num(chipNums[2]) === counts.delivered,
    `状态条数字与接口一致：待发车 ${chipNums[0]} / 在途 ${chipNums[1]} / 已交付 ${chipNums[2]}`)

  // 默认只列待处理
  const tableRows = await pv.locator('.el-table__row').count()
  ok(tableRows === activeCount, `默认页签只列待处理 ${tableRows} 行（不再把 ${doneCount} 条历史单平铺进来）`)
  const tableTxt = await pv.locator('.el-table').first().innerText()
  ok(!tableTxt.includes('已送达') || activeCount === 0, '待处理页签里不混入已交付行')
  const hasFakeBtn = await pv.locator('button', { hasText: '配送单' }).count()
  ok(hasFakeBtn === 0, '去掉了点了只弹提示的「+配送单」假按钮')
  await pv.screenshot({ path: `${OUT}/INSPECTOR-1-待处理工作台.png` })

  // 已交付页签
  await pv.locator('.tb-cell', { hasText: '已交付待入库' }).click()
  await sleep(1200)
  const doneRows = await pv.locator('.el-table__row').count()
  ok(doneRows === Math.min(doneCount, 10) || doneRows === doneCount, `已交付页签列出 ${doneRows} 行（接口 ${doneCount}）`)
  const histNote = await pv.locator('.history-note').innerText().catch(() => '')
  ok(histNote.includes('历史单'), `点已交付单会明示这是历史单：${histNote.replace(/\s+/g, ' ').slice(0, 40)}`)
  const traceExtra = await pv.locator('.dm-extra').innerText().catch(() => '')
  ok(traceExtra.includes('定格'), `已交付单的轨迹标注定格（${traceExtra.replace(/\s+/g, ' ')}）`)
  await pv.screenshot({ path: `${OUT}/INSPECTOR-2-已交付留档.png` })
  await pv.context().close()

  // 深链定位：/inspector/delivery?focus=订单号
  const focusRow = rows.find((r) => r.status === '待发车') || rows.find((r) => r.status === '配送中') || rows[0]
  const dv2 = await openPage(browser, insp, `${BASE}/inspector/delivery?focus=${encodeURIComponent(focusRow.orderNo)}`)
  const pv2 = dv2.page
  await sleep(900)
  const cardTitle = await pv2.locator('.card-title').first().innerText()
  ok(cardTitle.includes(focusRow.deliveryNo), `待办带 focus 进来直接定位到该单：${cardTitle}`)
  await pv2.screenshot({ path: `${OUT}/INSPECTOR-3-深链定位.png` })
  await pv2.context().close()

  /* ==================== 二、采购员：需求列表 + 比价定位 ==================== */
  console.log('\n=== 采购需求列表 ===')
  const pur = await login('purchaser01', 'PURCHASER')
  const todo = await api(pur.token, '/common/todo-center')
  const demandGroup = (todo.data?.groups || []).find((g) => g.key === 'DEMAND')
  const demItem = demandGroup?.items?.[0]
  ok(!!demItem, `待办里有需求条目：${demItem?.title || '无'}`)
  ok(String(demItem?.path || '').includes('focus='), `需求待办的 path 带 focus 定位：${demItem?.path}`)
  ok(String(demItem?.path || '').includes(demItem?.demandNo || ''), 'focus 值就是需求编号本身')

  const pd = await api(pur.token, '/purchaser/demands?page=1&size=10&status=处理中')
  const pendingTotal = Number(pd.data?.page?.total || 0)
  ok(pendingTotal > 0, `待比价需求 ${pendingTotal} 条`)

  const dm = await openPage(browser, pur, `${BASE}/purchaser/demands`)
  const pm = dm.page
  const activeTab = await pm.locator('.el-tabs__item.is-active').innerText()
  ok(activeTab.includes('待比价'), `默认页签是「待比价」（${activeTab}）`)
  const listCount = await pm.locator('.el-table__row').count()
  ok(listCount === Math.min(pendingTotal, 10), `默认只展示待比价 ${listCount} 行（接口 ${pendingTotal}）`)
  const openDrawerBtn = await pm.locator('button', { hasText: '发布需求' }).count()
  ok(openDrawerBtn === 1, '发布表单收进抽屉，只在页头留一个入口')
  const formInPage = await pm.locator('.demand-form').isVisible().catch(() => false)
  ok(!formInPage, '发布表单不再常驻右侧抢版面')
  const heads = (await pm.locator('.el-table__header th').allInnerTexts()).map((s) => s.trim()).filter(Boolean)
  ok(heads.includes('期望单价') && heads.includes('交货截止'), `列头完整不截断：${heads.join(' / ')}`)
  const bodyTxt = await pm.locator('.el-table__body').innerText()
  ok(!/undefined|NaN|\[object/.test(bodyTxt), '列表无 undefined/NaN')
  await pm.screenshot({ path: `${OUT}/PURCHASER-1-待比价列表.png` })
  await pm.context().close()

  // 深链：?focus=需求编号 → 定位条 + 高亮 + 自动打开比价
  const target = demItem.demandNo
  const dm2 = await openPage(browser, pur, `${BASE}/purchaser/demands?focus=${encodeURIComponent(target)}`)
  const pm2 = dm2.page
  await sleep(1200)
  const focusBar = await pm2.locator('.focus-bar').innerText().catch(() => '')
  ok(focusBar.includes(target), `定位条写明正在看哪条：${focusBar.replace(/\s+/g, ' ').slice(0, 46)}`)
  const hl = await pm2.locator('.el-table__row.row-focus').count()
  ok(hl >= 1, '目标行高亮（不是只把页面打开）')
  const dlgVisible = await pm2.locator('.el-dialog', { hasText: '需求详情' }).isVisible().catch(() => false)
  ok(dlgVisible, '自动打开该需求的比价面板')
  const dlgTxt = await pm2.locator('.el-dialog', { hasText: '需求详情' }).innerText()
  ok(dlgTxt.includes(target), `比价面板标题区显示该需求编号：${target}`)
  await pm2.screenshot({ path: `${OUT}/PURCHASER-2-从待办定位到比价.png` })
  await pm2.context().close()

  // 用户的原始动作：顶栏「待办」→ 需求条目「去比价」→ 必须落到那一条
  const dm3 = await openPage(browser, pur, `${BASE}/purchaser/inbound`)
  const pm3 = dm3.page
  await pm3.locator('button.lay-act', { hasText: '待办' }).first().click()
  await sleep(1200)
  const demandItem = pm3.locator('.tc-item', { hasText: '需求' }).first()
  const itemTxt = await demandItem.innerText().catch(() => '')
  ok(itemTxt.includes(target), `待办条目上写明需求编号：${itemTxt.replace(/\s+/g, ' ').slice(0, 40)}`)
  await demandItem.click()
  await sleep(2200)
  const urlNow = pm3.url()
  ok(urlNow.includes('focus=' + target), `点「去比价」落到带定位参数的地址：${urlNow.split('5173')[1]}`)
  const bar3 = await pm3.locator('.focus-bar').innerText().catch(() => '')
  ok(bar3.includes(target), '落页后能看到定位条（不是空白列表）')
  const dlg3 = await pm3.locator('.el-dialog', { hasText: '需求详情' }).isVisible().catch(() => false)
  ok(dlg3, '落页后比价面板直接打开，不用再找一次')
  await pm3.screenshot({ path: `${OUT}/PURCHASER-3-待办点击后落地.png` })
  await pm3.context().close()

  ok(dv.errors.length === 0 && dm.errors.length === 0, `页面无 JS 报错${[...dv.errors, ...dm.errors].join(' / ')}`)
  await browser.close()
  console.log(`\n结果：${pass} 通过 / ${fail} 失败`)
  process.exit(fail ? 1 : 0)
})()
