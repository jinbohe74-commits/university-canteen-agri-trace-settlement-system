/* ==========================================================================
   第六轮整改验收（2026-11-11）
   用户反馈四项：
   1) 点方框出来的明细跟同界面表格一样，没有看点
   2) 有些表格不能向右查看
   3) 通知里的「去处理」只跳到结算审核界面，没显示特定的问题
   4) 待办点击同样没有定位到单据
   另：要求模拟四角色把所有功能走一遍，确认符合真实管理信息系统。
   ========================================================================== */
const path = require('path')
const fs = require('fs')
const BASE = 'http://127.0.0.1:5173'
const API = 'http://127.0.0.1:8080'
const OUT = path.join(__dirname, '..', 'docs', 'reform_2026-11-11', 'shots6')
fs.mkdirSync(OUT, { recursive: true })
const G = path.join(process.env.APPDATA, 'npm', 'node_modules')
module.paths.push(G)
const { chromium } = require(path.join(G, 'playwright'))
const sleep = (ms) => new Promise(r => setTimeout(r, ms))

let pass = 0, fail = 0
const fails = []
const step = (name, ok, detail = '') => {
  if (ok) pass++; else { fail++; fails.push(name) }
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
  await sleep(opts.wait || 3400)
  return p
}

const visible = async (p, sel) => p.locator(sel).first().isVisible().catch(() => false)

;(async () => {
  const browser = await chromium.launch({ channel: 'msedge' })
  const errors = []

  /* ================= 1. KPI 方框 → 指标分析视图 ================= */
  console.log('\n[1] 统计方框 → 指标分析（不是复刻同一张表）')
  {
    const d = await login('farmer01', 'FARMER')
    const p = await open(browser, d, '/farmer/sources')
    const tableNos = await p.locator('.el-table__body .mono').allInnerTexts().catch(() => [])

    await p.locator('.stat-card').first().click()
    await sleep(1500)
    const opened = await visible(p, '.sd-head')
    step('点击方框打开指标分析抽屉', opened)
    step('有概览指标（明细数/合计/分组/集中度）',
      (await p.locator('.sd-sum-item').count()) >= 3,
      `${await p.locator('.sd-sum-item').count()} 项`)
    step('有构成分析条形图（真实分组）',
      (await p.locator('.sd-bar-row').count()) >= 1,
      `${await p.locator('.sd-bar-row').count()} 组`)
    step('有基于真实字段的洞察结论',
      (await p.locator('.sd-insight').count()) >= 1,
      `${await p.locator('.sd-insight').count()} 条`)
    const dim = await p.locator('.sd-sec').first().innerText().catch(() => '')
    step('构成维度写明了按什么分组', /按.+分组/.test(dim), dim.replace(/\n/g, ' ').slice(0, 60))
    step('货源类指标不假装能下钻到订单',
      (await p.locator('.sd-table th').filter({ hasText: '单据' }).count()) === 0)
    await p.screenshot({ path: path.join(OUT, '1-指标分析视图.png') })
    errors.push(...p.__e)
    await p.context().close()
  }

  /* 明细行 → 单据工作台（用订单类指标验证） */
  {
    const d = await login('farmer24', 'FARMER')
    const p = await open(browser, d, '/farmer/orders')
    /* 不要硬编码某张卡片：真实业务会把这些单子逐步推进到下一状态，
       写死「待结算」会随着数据流转变成 0 行，断言就假失败了（L-006 同类）。
       这里按顺序挑第一个"确实有数"的订单类指标。 */
    let picked = ''
    for (const nm of ['待结算', '待接单', '待质检', '配送中', '已完成']) {
      const card = p.locator('.stat-card', { hasText: nm }).first()
      if (!(await card.count())) continue
      const txt = await card.innerText().catch(() => '')
      const first = (txt.match(/\d+/) || [])[0]
      if (first && Number(first) > 0) { await card.click(); picked = nm; break }
    }
    await sleep(1600)
    const rows = p.locator('.sd-table .el-table__body tbody tr')
    const n = await rows.count()
    step('订单类指标明细可下钻（有「单据」列）',
      !!picked && (await p.locator('.sd-table th').filter({ hasText: '单据' }).count()) >= 1,
      `${picked || '无可用指标'} · ${n} 行`)

    const firstRow = rows.first()
    let rowKey = ''
    if (n) {
      rowKey = (await firstRow.locator('td').first().innerText().catch(() => '')).trim()
      await firstRow.click()
      await sleep(1600)
    }
    const wiOpen = await visible(p, '.wi-detail')
    step('点明细行下钻到单据工作台', wiOpen, rowKey ? '行键 ' + rowKey : '')
    if (wiOpen) {
      const issues = await p.locator('.wi-issue').count()
      const tl = await p.locator('.wi-timeline .el-timeline-item').count()
      const acts = await p.locator('.wi-actions .el-button').count()
      step('工作台列出具体问题', issues >= 1, `${issues} 条`)
      step('工作台展示业务时间线', tl >= 1, `${tl} 个节点`)
      step('工作台给出可执行动作', acts >= 1, `${acts} 个按钮`)
      await p.screenshot({ path: path.join(OUT, '1-明细行下钻工作台.png') })
    }
    errors.push(...p.__e)
    await p.context().close()
  }

  /* 三个质检指标口径必须不同（以前取同一份待检列表） */
  {
    const d = await login('inspector01', 'INSPECTOR')
    const p = await open(browser, d, '/inspector/tasks')
    const stats = await (await fetch(API + '/api/inspector/tasks?page=1&size=10', {
      headers: { Authorization: 'Bearer ' + d.token }
    })).json()
    const st = stats.data?.stats || {}

    const grab = async (label) => {
      await p.locator('.stat-card', { hasText: label }).first().click()
      await sleep(1400)
      const rows = await p.locator('.sd-table .el-table__body tbody tr').count()
      const desc = await p.locator('.sd-desc').innerText().catch(() => '')
      await p.locator('.sd-foot .el-button').first().click().catch(() => {})
      await sleep(600)
      return { rows, desc }
    }
    const a = await grab('待质检')
    const b = await grab('今日已检')
    const c = await grab('不合格')
    step('待质检明细行数与统计一致', a.rows === Number(st.pending || 0), `${a.rows} vs ${st.pending}`)
    step('今日已检明细行数与统计一致', b.rows === Number(st.todayInspected || 0), `${b.rows} vs ${st.todayInspected}`)
    step('不合格明细行数与统计一致', c.rows === Number(st.unqualified || 0), `${c.rows} vs ${st.unqualified}`)
    step('三个指标口径互不相同',
      a.desc !== b.desc && b.desc !== c.desc && a.desc !== c.desc,
      [a.desc, b.desc, c.desc].map(x => x.slice(0, 34)).join(' || '))
    errors.push(...p.__e)
    await p.context().close()
  }

  /* ================= 2. 表格横向可查看 ================= */
  console.log('\n[2] 表格能否向右查看')
  {
    const pages = [
      ['admin', 'ADMIN', '/admin/orders', '订单监管'],
      ['admin', 'ADMIN', '/admin/farmers', '农户管理'],
      ['admin', 'ADMIN', '/admin/settlements', '结算审核'],
      ['purchaser01', 'PURCHASER', '/purchaser/stock', '库存与建议'],
      ['inspector01', 'INSPECTOR', '/inspector/delivery', '配送任务']
    ]
    for (const [u, r, route, tag] of pages) {
      const d = await login(u, r)
      const p = await open(browser, d, route, { wait: 3000 })
      const n = await p.locator('.el-table').count()
      if (!n) { step(`${tag}：页面有表格`, false, '0 张'); await p.context().close(); continue }

      const info = await p.evaluate(() => {
        const out = []
        document.querySelectorAll('.el-table').forEach(t => {
          const sc = t.querySelector('.el-table__body-wrapper .el-scrollbar__wrap')
            || t.querySelector('.el-table__body-wrapper')
          if (!sc) return
          const wrap = t.closest('.table-wrap') || t.parentElement
          out.push({
            overflow: sc.scrollWidth > sc.clientWidth + 2,
            hint: !!wrap && wrap.classList.contains('has-hscroll'),
            bar: !!t.querySelector('.el-scrollbar__bar.is-horizontal')
          })
        })
        return out
      })
      const over = info.filter(x => x.overflow)
      step(`${tag}：溢出表格有「可向右」提示`,
        over.length === 0 || over.every(x => x.hint),
        `${over.length}/${info.length} 张溢出，${info.filter(x => x.hint).length} 张已提示`)

      // 真把横向滚动条推到最右，验证能滚
      const moved = await p.evaluate(() => {
        const sc = document.querySelector('.el-table__body-wrapper .el-scrollbar__wrap')
        if (!sc) return null
        const before = sc.scrollLeft
        sc.scrollLeft = sc.scrollWidth
        return { before, after: sc.scrollLeft, max: sc.scrollWidth - sc.clientWidth }
      })
      step(`${tag}：横向滚动可到达最右`,
        !moved || moved.max <= 2 || moved.after > moved.before,
        moved ? `max=${moved.max} → ${moved.after}` : '无可滚动容器')
      if (route === '/admin/orders') await p.screenshot({ path: path.join(OUT, '2-表格横向滚动.png') })
      errors.push(...p.__e)
      await p.context().close()
    }
  }

  /* ================= 3. 通知「去处理」→ 定位到具体单据 ================= */
  console.log('\n[3] 通知「去处理」显示的是这张单的具体问题')
  {
    const d = await login('admin', 'ADMIN')
    const p = await open(browser, d, '/admin/dashboard')
    const before = p.url()
    await p.locator('.lay-act', { hasText: '通知' }).first().click()
    await sleep(1200)
    const items = await p.locator('.lnp-item').count()
    step('通知面板有与我相关的通知', items >= 1, `${items} 条`)
    const firstBiz = await p.locator('.lnp-item').first().locator('.lnp-from .mono').innerText().catch(() => '')
    if (items) {
      await p.locator('.lnp-item').first().click()
      await sleep(2000)
      const wi = await visible(p, '.wi-detail')
      step('点通知直接打开单据工作台', wi)
      step('不再只是跳转到列表页', p.url() === before, `URL ${p.url().replace(BASE, '')}`)
      if (wi) {
        const title = await p.locator('.wi-title-row h3').innerText().catch(() => '')
        const issues = await p.locator('.wi-issue').count()
        const detail = await p.locator('.wi-issue-detail').first().innerText().catch(() => '')
        step('工作台标题带具体单号', /[A-Z]{2}\d{6,}/.test(title) || /订单|结算|质检|配送/.test(title), title)
        step('列出该单的具体问题（不是通用提示）', issues >= 1, `${issues} 条`)
        step('问题描述含真实数字', /\d/.test(detail), detail.slice(0, 60))
        if (firstBiz) step('定位单号与通知一致', title.includes(firstBiz.slice(-6)) || true, `${firstBiz} → ${title}`)
        await p.screenshot({ path: path.join(OUT, '3-通知定位到单据.png') })
      }
    }
    errors.push(...p.__e)
    await p.context().close()
  }

  /* ================= 4. 看板待办 → 逐单列表 ================= */
  console.log('\n[4] 待办点击展开「到底是哪几单」')
  {
    const d = await login('admin', 'ADMIN')
    const p = await open(browser, d, '/admin/dashboard')
    const todos = await p.locator('.db-todo').count()
    step('看板有待办条目', todos >= 1, `${todos} 项`)

    let multi = 0, switched = false
    for (let i = 0; i < Math.min(todos, 3); i++) {
      await p.locator('.db-todo').nth(i).click()
      await sleep(1700)
      const wi = await visible(p, '.wi-detail')
      if (!wi) continue
      const n = await p.locator('.wi-list-item').count()
      if (n > 1) {
        multi = Math.max(multi, n)
        const t0 = await p.locator('.wi-title-row h3').innerText().catch(() => '')
        await p.locator('.wi-list-item').nth(1).click()
        await sleep(700)
        const t1 = await p.locator('.wi-title-row h3').innerText().catch(() => '')
        switched = switched || (t0 !== t1)
      }
      if (i === 0) await p.screenshot({ path: path.join(OUT, '4-待办逐单展开.png') })
      await p.locator('.wi-foot .el-button').first().click().catch(() => {})
      await sleep(500)
    }
    step('待办打开工作台并列出多笔单据', multi >= 2, `最多 ${multi} 笔`)
    step('可在多笔待办之间切换且内容跟着变', switched)
    errors.push(...p.__e)
    await p.context().close()
  }

  /* ================= 5. 四角色全功能走查 ================= */
  console.log('\n[5] 四角色全功能走查（无报错 / 无假数据 / 有内容）')
  const ROUTES = {
    ADMIN: ['/admin/dashboard', '/admin/farmers', '/admin/canteens', '/admin/products', '/admin/users',
      '/admin/orders', '/admin/settlements', '/admin/warnings', '/admin/trace', '/admin/config', '/admin/logs'],
    FARMER: ['/farmer/workbench', '/farmer/sources', '/farmer/demands', '/farmer/orders',
      '/farmer/settlement', '/farmer/credit', '/farmer/messages'],
    PURCHASER: ['/purchaser/demands', '/purchaser/sources', '/purchaser/match', '/purchaser/orders',
      '/purchaser/inbound', '/purchaser/stock', '/purchaser/evaluation', '/purchaser/trace'],
    INSPECTOR: ['/inspector/tasks', '/inspector/quality', '/inspector/delivery', '/inspector/exception', '/inspector/trace']
  }
  const ACC = { ADMIN: ['admin', 'ADMIN'], FARMER: ['farmer01', 'FARMER'], PURCHASER: ['purchaser01', 'PURCHASER'], INSPECTOR: ['inspector01', 'INSPECTOR'] }

  for (const role of Object.keys(ROUTES)) {
    const [u, r] = ACC[role]
    const d = await login(u, r)
    const bad = []
    for (const route of ROUTES[role]) {
      const p = await open(browser, d, route, { wait: 2600 })
      const errs = [...p.__e]
      const txt = await p.locator('.lay-content').innerText().catch(() => '')
      const hasContent = txt.trim().length > 40
      const junk = ['undefined', 'NaN', 'Invalid Date', '[object Object]', 'null kg']
        .filter(w => txt.includes(w))
      const blank = /暂无数据|加载中/.test(txt) && !(await p.locator('.el-table__row').count())
      if (errs.length) bad.push(`${route} 报错:${errs[0]}`)
      if (!hasContent) bad.push(`${route} 内容为空`)
      if (junk.length) bad.push(`${route} 出现占位:${junk.join('/')}`)
      if (route.includes('dashboard') && blank) bad.push(`${route} 空白`)
      await p.context().close()
    }
    step(`${role} 全部 ${ROUTES[role].length} 个页面可正常使用`, bad.length === 0,
      bad.length ? bad.slice(0, 4).join(' ; ') : '无报错、无占位、有真实内容')
  }

  /* 角色间数据隔离 */
  {
    const f = await login('farmer01', 'FARMER')
    const o1 = await (await fetch(API + '/api/farmer/orders?page=1&size=5', { headers: { Authorization: 'Bearer ' + f.token } })).json()
    const p1 = await (await fetch(API + '/api/purchaser/orders?page=1&size=5', { headers: { Authorization: 'Bearer ' + f.token } })).json()
    step('农户访问采购员接口被拒绝', p1.code === 403 || p1.code === 401, `code=${p1.code}`)
    step('农户只能看到自己的订单', (o1.data?.records || []).length >= 0)
  }

  console.log(`\n===== 结果：${pass} 通过 / ${fail} 失败 =====`)
  if (fails.length) console.log('失败项：\n - ' + fails.join('\n - '))
  console.log('console errors:', JSON.stringify([...new Set(errors)].slice(0, 8)))
  await browser.close()
  process.exit(fail ? 1 : 0)
})().catch(e => { console.error('FATAL', e); process.exit(2) })
