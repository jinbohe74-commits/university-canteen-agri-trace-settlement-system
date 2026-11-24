/* ==========================================================================
   第五轮整改验收（2026-11-11）
   1) 统计方框点击后必须打开真实明细
   2) 顶栏通知只发给相关责任人
   3) 数据看板导出必须是 8 个工作表的 .xlsx
   4) 配送中/已入库/待结算/已结算必须有可执行动作
   ========================================================================== */
const path = require('path')
const fs = require('fs')
const BASE = 'http://127.0.0.1:5173'
const API = 'http://127.0.0.1:8080'
const OUT = path.join(__dirname, '..', 'docs', 'reform_2026-11-11', 'shots')
fs.mkdirSync(OUT, { recursive: true })
const G = path.join(process.env.APPDATA, 'npm', 'node_modules')
module.paths.push(G)
const { chromium } = require(path.join(G, 'playwright'))
const XLSX = require(path.join(__dirname, '..', 'frontend', 'node_modules', 'xlsx'))
const sleep = (ms) => new Promise(r => setTimeout(r, ms))

let pass = 0, fail = 0
const step = (name, ok, detail = '') => {
  if (ok) pass++; else fail++
  console.log(` ${ok ? '✔' : '✘'} ${name}${detail ? ' | ' + detail : ''}`)
}

async function login(u, r) {
  const res = await fetch(API + '/api/auth/login', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username: u, password: '123456', expectedRole: r })
  })
  return (await res.json()).data
}

async function open(browser, d, route, opts = {}) {
  const c = await browser.newContext({ viewport: { width: 1440, height: 940 }, acceptDownloads: true })
  await c.addInitScript(([t, u, rn, mn]) => {
    sessionStorage.setItem('ct_token', t)
    sessionStorage.setItem('ct_user', u)
    sessionStorage.setItem('ct_role_name', rn)
    sessionStorage.setItem('ct_menus', mn)
  }, [d.token, JSON.stringify(d.user), d.roleName || '', JSON.stringify(d.menus || [])])
  const p = await c.newPage()
  p.__e = []
  p.on('pageerror', e => p.__e.push(e.message.slice(0, 200)))
  await p.goto(BASE + route, { waitUntil: 'domcontentloaded' })
  await sleep(opts.wait || 3200)
  return p
}

;(async () => {
  const browser = await chromium.launch({ channel: 'msedge' })
  const errors = []

  /* ---------------- 1. KPI 卡片钻取 ---------------- */
  console.log('\n[1] 统计方框点击 → 真实明细')
  {
    // 用户截图里的四张方框来自农户货源页；数据看板主指标带是自绘卡片，两者都要能点
    const targets = [
      { u: 'farmer01', r: 'FARMER', route: '/farmer/sources', sel: '.stat-card', tag: '农户货源四卡' },
      { u: 'admin', r: 'ADMIN', route: '/admin/dashboard', sel: '.db-drill', tag: '看板主指标带' }
    ]
    for (const t of targets) {
      const d = await login(t.u, t.r)
      const p = await open(browser, d, t.route)
      const cards = p.locator(t.sel)
      const n = await cards.count()
      step(`${t.tag}渲染出可点方框`, n >= 4, `${n} 张`)

      let opened = 0, withRows = 0
      for (let i = 0; i < Math.min(4, n); i++) {
        await cards.nth(i).click()
        await sleep(1300)
        if (await p.locator('.sd-head').isVisible().catch(() => false)) {
          opened++
          const rows = await p.locator('.sd-table .el-table__body tbody tr').count()
          if (rows > 0) withRows++
          const title = await p.locator('.el-drawer__title').last().innerText().catch(() => '')
          const src = await p.locator('.sd-source').innerText().catch(() => '')
          console.log(`   · ${title} / ${rows} 行 / ${src}`)
          if (i === 0) await p.screenshot({ path: path.join(OUT, `1-KPI明细-${t.u}.png`) })
        }
        await p.locator('.sd-foot .el-button').first().click().catch(() => {})
        await sleep(450)
      }
      step(`${t.tag}点击后都能打开明细抽屉`, opened === Math.min(4, n), `${opened}/${Math.min(4, n)}`)
      step(`${t.tag}明细来自真实接口`, withRows >= 3, `${withRows} 张有明细行`)
      errors.push(...p.__e)
      await p.context().close()
    }
  }

  /* ---------------- 2. 定向通知 ---------------- */
  console.log('\n[2] 顶栏通知 → 只发给相关责任人')
  {
    const d = await login('admin', 'ADMIN')
    const p = await open(browser, d, '/admin/dashboard')
    await p.locator('.lay-notify .lay-act').click()
    await sleep(900)
    const items = p.locator('.lnp-item')
    const cnt = await items.count()
    const panelTitle = await p.locator('.lay-notify-panel b').first().innerText().catch(() => '')
    step('管理员看到发给自己的通知', cnt > 0 && panelTitle.includes('发给我的'), `${cnt} 条 · ${panelTitle}`)
    const firstText = cnt ? (await items.first().innerText()).replace(/\s+/g, ' ') : ''
    console.log('   · 首条：' + firstText.slice(0, 130))
    step('通知带业务单号/发送人/跳转入口', /DD\d+|JS\d+/.test(firstText) && firstText.length > 20, firstText.slice(0, 60))
    await p.screenshot({ path: path.join(OUT, '2-定向通知面板.png') })

    /* 第六轮改了契约：点通知不再「跳转到列表页让用户自己找单」，而是原地打开
       单据工作台，直接展示这张单的具体问题 + 时间线 + 可执行动作。 */
    const before = p.url()
    const firstBiz = cnt ? (await items.first().locator('.lnp-from .mono').innerText().catch(() => '')).trim() : ''
    if (cnt) { await items.first().click(); await sleep(1800) }
    const wiTitle = await p.locator('.wi-title-row h3').innerText().catch(() => '')
    step('点击通知原地打开该单工作台（不再只跳列表）',
      !!wiTitle && p.url() === before,
      `${before.split('/').pop()} → ${wiTitle || '未打开'}`)
    step('工作台定位到通知对应的单号',
      !firstBiz || wiTitle.includes(firstBiz), `${firstBiz} → ${wiTitle}`)
    errors.push(...p.__e)
    await p.context().close()

    // 未参与订单的账号不应收到任何消息
    const d2 = await login('purchaser02', 'PURCHASER')
    const p2 = await open(browser, d2, '/purchaser/orders')
    await p2.locator('.lay-notify .lay-act').click()
    await sleep(800)
    const empty = await p2.locator('.lnp-empty').isVisible().catch(() => false)
    const c2 = await p2.locator('.lnp-item').count()
    step('非相关账号收不到别人的业务动态', empty && c2 === 0, `purchaser02 收到 ${c2} 条`)
    errors.push(...p2.__e)
    await p2.context().close()
  }

  /* ---------------- 3. 多工作表 Excel ---------------- */
  console.log('\n[3] 数据看板导出 → 8 个工作表')
  {
    const d = await login('admin', 'ADMIN')
    const p = await open(browser, d, '/admin/dashboard')
    const [dl] = await Promise.all([
      p.waitForEvent('download', { timeout: 20000 }),
      p.getByRole('button', { name: /导出报表/ }).click()
    ])
    const file = path.join(OUT, '平台运营报表.xlsx')
    await dl.saveAs(file)
    const wb = XLSX.readFile(file)
    const names = wb.SheetNames
    step('导出文件是 .xlsx', /\.xlsx$/i.test(dl.suggestedFilename()), dl.suggestedFilename())
    step('每段报表一个工作表', names.length === 8, `${names.length} 个：${names.join(' / ')}`)
    const sizes = names.map(n => XLSX.utils.sheet_to_json(wb.Sheets[n], { header: 1 }).length)
    step('每个工作表都有表头和数据', sizes.every(s => s >= 2), sizes.join(','))
    console.log('   · 文件大小 ' + fs.statSync(file).size + ' bytes，各表行数 ' + sizes.join('/'))
    errors.push(...p.__e)
    await p.context().close()
  }

  /* ---------------- 4. 四状态可执行动作 ---------------- */
  console.log('\n[4] 配送中 / 已入库 / 待结算 / 已结算 → 阶段动作')
  {
    const d = await login('admin', 'ADMIN')
    const token = d.token
    const wanted = [
      [50, '配送中', '查看实时轨迹'],
      [70, '已入库', '查看验收单'],
      [80, '待结算', '查看结算单'],
      [90, '已结算', '查看结算明细']
    ]
    for (const [status, label, expectBtn] of wanted) {
      const q = await (await fetch(`${API}/api/admin/orders?status=${status}&pageNum=1&pageSize=1`,
        { headers: { Authorization: 'Bearer ' + token } })).json()
      const row = q.data?.page?.records?.[0]
      if (!row) { step(`${label} 找到样本订单`, false, '接口无数据'); continue }
      step(`${label} 找到样本订单`, true, row.orderNo)

      const p = await open(browser, d, '/admin/orders')
      await p.locator('.filter-bar input[placeholder="订单号 / 产品 / 农户"]').fill(row.orderNo)
      await p.keyboard.press('Enter')
      await sleep(1600)
      const link = p.locator('.el-table__body .mono.link', { hasText: row.orderNo }).first()
      await link.click()
      await sleep(1800)
      const osa = p.locator('.osa')
      const visible = await osa.isVisible().catch(() => false)
      const stageText = visible ? (await osa.locator('.osa-stage').innerText()).trim() : ''
      const btns = visible ? await osa.locator('.osa-actions .el-button').allInnerTexts() : []
      step(`${label} 抽屉出现阶段动作区`, visible && stageText.includes(label), stageText)
      step(`${label} 有「${expectBtn}」等可执行动作`, btns.some(b => b.includes(expectBtn)), btns.join(' / '))
      await p.screenshot({ path: path.join(OUT, `4-阶段动作-${status}-${label}.png`) })

      if (status === 50) {
        const trackBtn = osa.getByRole('button', { name: /查看实时轨迹/ })
        if (await trackBtn.count()) {
          await trackBtn.click()
          await sleep(1800)
          const map = await p.locator('.el-dialog:visible svg').count()
          const hud = await p.locator('.el-dialog:visible').innerText().catch(() => '')
          step('配送中可打开实时轨迹', map > 0 && /已行驶|里程|ETA|已抵达|温度/.test(hud), `SVG ${map} 个`)
          await p.screenshot({ path: path.join(OUT, '4-配送中实时轨迹.png') })
        }
      }
      errors.push(...p.__e)
      await p.context().close()
    }
  }

  const uniq = [...new Set(errors)]
  step('全程无前端运行时异常', uniq.length === 0, uniq.slice(0, 4).join(' | ') || 'console errors: []')
  console.log(`\n验收 ${pass}/${pass + fail}`)
  await browser.close()
  process.exit(fail ? 1 : 0)
})().catch(e => { console.error('FATAL', e); process.exit(1) })
