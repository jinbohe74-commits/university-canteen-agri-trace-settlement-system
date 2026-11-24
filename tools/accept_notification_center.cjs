/* 验收：顶栏通知中心（未读/已读分组 + 单条删除 + 删除已读 + 详情弹窗）
   运行：node tools/accept_notification_center.cjs
   依赖：后端 8080 / 前端 5173 已启动；用 purchaser01（未读较多）验证 */
const path = require('path')
const GLOBAL = path.join(process.env.APPDATA, 'npm', 'node_modules')
module.paths.push(GLOBAL)
const { chromium } = require(path.join(GLOBAL, 'playwright'))

const API = 'http://127.0.0.1:8080/api'
const BASE = 'http://127.0.0.1:5173'
const SHOT = path.join(__dirname, '..', 'docs', 'fix_2026-11-16')
const sleep = (ms) => new Promise((r) => setTimeout(r, ms))
const fails = []
function check(name, ok, detail = '') {
  console.log((ok ? 'PASS' : 'FAIL') + ' | ' + name + ' | ' + detail)
  if (!ok) fails.push(name)
}

;(async () => {
  const login = await fetch(API + '/auth/login', {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username: 'purchaser01', password: '123456', expectedRole: 'PURCHASER' })
  })
  const d = (await login.json()).data
  if (!d?.token) { console.log('登录失败'); process.exit(1) }

  const b = await chromium.launch({ channel: 'msedge', headless: true })
  const ctx = await b.newContext({ viewport: { width: 1440, height: 900 }, deviceScaleFactor: 2 })
  await ctx.addInitScript(([t, u, rn, mn]) => {
    sessionStorage.setItem('ct_token', t)
    sessionStorage.setItem('ct_user', u)
    sessionStorage.setItem('ct_role_name', rn)
    sessionStorage.setItem('ct_menus', mn)
  }, [d.token, JSON.stringify(d.user), d.roleName || '', JSON.stringify(d.menus || [])])
  const p = await ctx.newPage()
  const errors = []
  p.on('pageerror', (e) => errors.push('PAGEERROR ' + String(e).slice(0, 180)))
  p.on('console', (m) => { if (m.type() === 'error') errors.push('CONSOLE ' + m.text().slice(0, 180)) })

  await p.goto(BASE + '/purchaser/orders', { waitUntil: 'domcontentloaded' })
  await sleep(2500)

  const openPanel = async () => {
    if (!(await p.locator('.lay-notify-panel').count())) {
      await p.locator('.lay-notify .lay-act').click()
      await sleep(900)
    }
  }
  const tabTexts = () => p.evaluate(() =>
    [...document.querySelectorAll('.lnp-tabs button')].map((e) => e.innerText.trim()))
  const items = () => p.evaluate(() =>
    [...document.querySelectorAll('.lnp-item')].map((e) => ({
      unread: e.classList.contains('is-unread'),
      hasDel: !!e.querySelector('.lnp-del'),
      text: (e.querySelector('.lnp-content')?.innerText || '').slice(0, 30)
    })))

  // ---------- A. 分组结构 ----------
  await openPanel()
  const tabs = await tabTexts()
  check('A1 有「未读 / 已读」两个分组', tabs.length >= 2 && /未读/.test(tabs[0]) && /已读/.test(tabs[1]), tabs.join(' | '))
  check('A2 有「删除已读」入口', tabs.some((t) => /删除已读/.test(t)), tabs.join(' | '))

  const foot = await p.evaluate(() => document.querySelector('.lnp-foot')?.innerText.trim() || '')
  check('A3 底部说明「未读需阅读后才能删除」', /未读.*阅读.*删除/.test(foot), foot)

  const unreadList = await items()
  check('A4 未读分组有内容', unreadList.length > 0, unreadList.length + ' 条')
  check('A5 未读项没有删除按钮', unreadList.every((x) => !x.hasDel),
    unreadList.filter((x) => x.hasDel).length + ' 条有删除按钮')
  await p.screenshot({ path: path.join(SHOT, 'notif_unread.png') })

  // ---------- B. 点击弹详情 ----------
  const first = unreadList[0]
  await p.locator('.lnp-item').first().click()
  await sleep(1200)
  const dlg = p.locator('.el-dialog:visible').first()
  check('B1 点击通知弹出详情界面', await dlg.count() > 0,
    await dlg.count() ? await dlg.locator('.el-dialog__title, .ntf-title').first().innerText().catch(() => '') : '未弹出')

  const detail = await p.evaluate(() => {
    const d = document.querySelector('.el-dialog .ntf')
    if (!d) return null
    return {
      title: d.querySelector('.ntf-title')?.innerText.trim() || '',
      body: d.querySelector('.ntf-body')?.innerText.trim() || '',
      rows: [...d.querySelectorAll('.ntf-row')].map((r) => r.innerText.replace(/\s+/g, ' ').trim()),
      state: document.querySelector('.ntf-state')?.innerText.trim() || ''
    }
  })
  check('B2 详情有标题', !!detail?.title, detail?.title || '空')
  check('B3 详情有完整正文', (detail?.body || '').length > 10, (detail?.body || '').slice(0, 40))
  check('B4 详情有发送人 / 单据等要素', (detail?.rows || []).length >= 4, (detail?.rows || []).join(' | '))
  check('B5 打开后状态为已读', detail?.state === '已读', detail?.state || '空')
  await p.screenshot({ path: path.join(SHOT, 'notif_detail.png') })

  // B6 关闭弹窗
  await p.locator('.el-dialog:visible .el-dialog__footer button').first().click()
  await sleep(800)
  check('B6 关闭按钮可关闭详情', (await p.locator('.el-dialog:visible').count()) === 0)

  // ---------- C. 已读分组 + 单条删除 ----------
  await openPanel()
  await p.locator('.lnp-tabs button').nth(1).click()
  await sleep(900)
  const readList = await items()
  check('C1 已读分组有内容（刚才点开的那条）', readList.length > 0, readList.length + ' 条')
  check('C2 已读项有删除按钮', readList.every((x) => x.hasDel),
    readList.filter((x) => !x.hasDel).length + ' 条缺删除按钮')
  await p.screenshot({ path: path.join(SHOT, 'notif_read.png') })

  const beforeDel = readList.length
  await p.locator('.lnp-item .lnp-del').first().click()
  await sleep(1200)
  const afterDel = await items()
  const msg1 = await p.evaluate(() => [...document.querySelectorAll('.el-message')].map((e) => e.innerText.trim()).join(' | '))
  check('C3 单条删除生效', afterDel.length === beforeDel - 1, beforeDel + ' → ' + afterDel.length + '，提示：' + msg1)

  // ---------- D. 一键删除已读（二次确认） ----------
  // 先造一条已读：C3 已经把已读删空了，这里点开一条未读再关掉
  await p.locator('.lnp-tabs button').nth(0).click()
  await sleep(600)
  await p.locator('.lnp-item').first().click()
  await sleep(1000)
  await p.locator('.el-dialog:visible .el-dialog__footer button').first().click()
  await sleep(700)
  await openPanel()
  await p.locator('.lnp-tabs button').nth(1).click()
  await sleep(800)
  const prepareRead = await items()
  check('D0 已读分组可用于批量删除', prepareRead.length > 0, prepareRead.length + ' 条')

  await p.locator('.lnp-tabs .lnp-clear').click()
  await sleep(900)
  const boxVisible = await p.locator('.el-message-box:visible').count()
  check('D1 删除已读前有二次确认', boxVisible > 0, boxVisible ? '已弹确认框' : '直接删了')
  await p.screenshot({ path: path.join(SHOT, 'notif_confirm.png') })
  if (boxVisible) {
    await p.locator('.el-message-box:visible .el-button--primary').click()
    await sleep(1500)
  }
  const afterClear = await items()
  const empty = await p.evaluate(() => document.querySelector('.lnp-empty')?.innerText.trim() || '')
  check('D2 已读被清空', afterClear.length === 0, afterClear.length + ' 条，空态：' + empty.replace(/\n/g, ' '))

  // ---------- E. 未读未受影响 ----------
  await p.locator('.lnp-tabs button').nth(0).click()
  await sleep(700)
  const unreadAfter = await items()
  check('E1 未读未被批量删除影响', unreadAfter.length > 0, unreadAfter.length + ' 条')
  await p.screenshot({ path: path.join(SHOT, 'notif_after_all.png') })

  check('E2 无 JS 报错', errors.length === 0, errors.slice(0, 3).join(' || '))
  await b.close()
  console.log(fails.length ? '\n失败 ' + fails.length + ' 项：' + fails.join(', ') : '\n全部通过（15/15）')
  process.exit(fails.length ? 1 : 0)
})()
