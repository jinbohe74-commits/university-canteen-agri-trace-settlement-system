/* 待办中心 + 入库清单 验收
   断言的是「点下去真的出现东西」，不是接口 200（L-055 教训） */
const path = require('path')
const G = path.join(process.env.APPDATA, 'npm', 'node_modules')
module.paths.push(G)
const { chromium } = require(path.join(G, 'playwright'))

const sleep = (ms) => new Promise((r) => setTimeout(r, ms))
const BASE = 'http://127.0.0.1:5173'
const API = 'http://127.0.0.1:8080'
const OUT = 'docs/reform_2026-11-11/shots8'

const ROLES = [
  { user: 'admin', role: 'ADMIN', home: '/admin/dashboard', expectGroups: ['结算单待审核', '订单逾期未接单', '预警待处置'] },
  { user: 'farmer24', role: 'FARMER', home: '/farmer/workbench', expectGroups: ['待接单', '待发货'] },
  { user: 'purchaser01', role: 'PURCHASER', home: '/purchaser/inbound', expectGroups: ['待验收 / 入库'] },
  { user: 'inspector01', role: 'INSPECTOR', home: '/inspector/tasks', expectGroups: ['待质检', '质检合格待配送'] }
]

let pass = 0, fail = 0
const ok = (cond, msg) => { if (cond) { pass++; console.log('  ✓', msg) } else { fail++; console.log('  ✘', msg) } }

async function login(user, role) {
  const r = await fetch(`${API}/api/auth/login`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username: user, password: '123456', expectedRole: role })
  })
  const d = (await r.json()).data
  return d
}

;(async () => {
  const browser = await chromium.launch({ channel: 'msedge' })

  for (const r of ROLES) {
    console.log(`\n=== ${r.role} 待办中心 ===`)
    const d = await login(r.user, r.role)
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
    await page.goto(`${BASE}${r.home}`, { waitUntil: 'domcontentloaded' })
    await sleep(3200)

    // 徽标：必须与接口 total 一致
    const apiTotal = (await page.evaluate(async (token) => {
      const res = await fetch('http://127.0.0.1:8080/api/common/todo-center', { headers: { Authorization: 'Bearer ' + token } })
      return (await res.json()).data.total
    }, d.token))
    const badge = await page.locator('.lay-act .lay-act-num').first().innerText().catch(() => '0')
    ok(String(apiTotal) === badge.replace('+', ''), `顶栏徽标 ${badge} = 接口 total ${apiTotal}`)

    // 点待办 → 抽屉必须出现且有条目
    await page.locator('button.lay-act', { hasText: '待办' }).first().click()
    await sleep(1200)
    const drawerVisible = await page.locator('.tc-wrap').isVisible().catch(() => false)
    ok(drawerVisible, '点「待办」抽屉可见（不是跳到某个列表页）')
    const groupLabels = await page.locator('.tc-g-label').allInnerTexts()
    for (const g of r.expectGroups) {
      ok(groupLabels.some((x) => x.includes(g)), `分组存在：${g}`)
    }
    const itemCount = await page.locator('.tc-item').count()
    ok(itemCount > 0, `抽屉里列出 ${itemCount} 条可点待办`)
    const badText = await page.locator('.tc-wrap').innerText()
    ok(!/undefined|NaN|\[object Object\]/.test(badText), '抽屉文本无 undefined/NaN')
    await page.screenshot({ path: `${OUT}/${r.role}-1-待办中心.png` })

    // 点第一条 → 单据工作台或对应页面
    const firstBiz = await page.locator('.tc-item').first().evaluate((el) => el.className)
    await page.locator('.tc-item').first().click()
    await sleep(1600)
    const wiVisible = await page.locator('.wi-detail').isVisible().catch(() => false)
    const urlNow = page.url()
    ok(wiVisible || !urlNow.endsWith(r.home), `点第一条后：工作台可见=${wiVisible}，当前地址=${urlNow.replace(BASE, '')}`)
    if (wiVisible) {
      const wiText = await page.locator('.wi-detail').innerText()
      ok(wiText.length > 80, '工作台展示了单据字段/问题/时间线')
      await page.screenshot({ path: `${OUT}/${r.role}-2-待办直达单据.png` })
      await page.keyboard.press('Escape')
      await sleep(500)
    }

    ok(errors.length === 0, `无 JS 异常${errors.length ? '：' + errors[0].slice(0, 80) : ''}`)
    await ctx.close()
  }

  // ---------------- 入库页：待入库清单 ----------------
  console.log('\n=== 采购员 入库确认清单 ===')
  const d = await login('purchaser01', 'PURCHASER')
  const ctx = await browser.newContext({ viewport: { width: 1440, height: 1000 } })
  await ctx.addInitScript(([t, u, rn, mn]) => {
    sessionStorage.setItem('ct_token', t)
    sessionStorage.setItem('ct_user', u)
    sessionStorage.setItem('ct_role_name', rn)
    sessionStorage.setItem('ct_menus', mn)
  }, [d.token, JSON.stringify(d.user), d.roleName || '', JSON.stringify(d.menus || [])])
  const page = await ctx.newPage()
  const errs = []
  page.on('pageerror', (e) => errs.push(e.message))
  await page.goto(`${BASE}/purchaser/inbound`, { waitUntil: 'domcontentloaded' })
  await sleep(3600)

  const rows = await page.locator('.pc-item').count()
  ok(rows >= 2, `待入库清单一次列出 ${rows} 笔（原来只有 1 笔）`)
  const firstNo = await page.locator('.pc-item').first().locator('.pc-l1 b').innerText()
  const shownNo = await page.locator('.wi-title-row, .info-item .mono').first().innerText().catch(() => '')
  ok(shownNo.includes(firstNo) || (await page.locator('.info-grid').innerText()).includes(firstNo),
    `首屏自动载入清单第一笔 ${firstNo}`)
  await page.screenshot({ path: `${OUT}/PURCHASER-3-待入库清单.png` })

  // 点第二笔 → 录入区切换
  if (rows >= 2) {
    const secondNo = await page.locator('.pc-item').nth(1).locator('.pc-l1 b').innerText()
    await page.locator('.pc-item').nth(1).click()
    await sleep(1500)
    const grid = await page.locator('.info-grid').innerText()
    ok(grid.includes(secondNo), `点第二笔后录入区切到 ${secondNo}`)
    const onIdx = await page.locator('.pc-item.on .pc-idx').innerText().catch(() => '')
    ok(onIdx === '2', `清单高亮切到第 ${onIdx} 笔`)
    await page.screenshot({ path: `${OUT}/PURCHASER-4-切换第二笔.png` })
  }

  const posText = await page.locator('.pc-pos').innerText().catch(() => '')
  ok(/第 \d+ \/ \d+ 笔/.test(posText), `录入区标明进度：${posText.trim()}`)
  ok(errs.length === 0, `入库页无 JS 异常${errs.length ? '：' + errs[0].slice(0, 80) : ''}`)

  await ctx.close()
  await browser.close()
  console.log(`\n结果：${pass} 通过 / ${fail} 失败`)
  process.exit(fail ? 1 : 0)
})()
