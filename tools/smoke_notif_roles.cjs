/* 多角色冒烟：通知面板在 0 未读 / 全未读 / 混合 三种数据形态下都要正常
   运行：node tools/smoke_notif_roles.cjs */
const path = require('path')
const GLOBAL = path.join(process.env.APPDATA, 'npm', 'node_modules')
module.paths.push(GLOBAL)
const { chromium } = require(path.join(GLOBAL, 'playwright'))

const API = 'http://127.0.0.1:8080/api'
const BASE = 'http://127.0.0.1:5173'
const sleep = (ms) => new Promise((r) => setTimeout(r, ms))
const fails = []
const check = (n, ok, d = '') => { console.log((ok ? 'PASS' : 'FAIL') + ' | ' + n + ' | ' + d); if (!ok) fails.push(n) }

const ROLES = [
  { user: 'admin', pwd: '123456', role: 'ADMIN', home: '/admin/dashboard', label: '管理员' },
  { user: 'farmer24', pwd: '123456', role: 'FARMER', home: '/farmer/orders', label: '农户' },
  { user: 'inspector01', pwd: '123456', role: 'INSPECTOR', home: '/inspector/tasks', label: '质检配送员' }
]

;(async () => {
  const b = await chromium.launch({ channel: 'msedge', headless: true })
  for (const r of ROLES) {
    const lg = await fetch(API + '/auth/login', {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ username: r.user, password: r.pwd, expectedRole: r.role })
    })
    const d = (await lg.json()).data
    if (!d?.token) { check(r.label + ' 登录', false); continue }

    const ctx = await b.newContext({ viewport: { width: 1440, height: 900 } })
    await ctx.addInitScript(([t, u, rn, mn]) => {
      sessionStorage.setItem('ct_token', t)
      sessionStorage.setItem('ct_user', u)
      sessionStorage.setItem('ct_role_name', rn)
      sessionStorage.setItem('ct_menus', mn)
    }, [d.token, JSON.stringify(d.user), d.roleName || '', JSON.stringify(d.menus || [])])
    const p = await ctx.newPage()
    const errs = []
    p.on('pageerror', (e) => errs.push(String(e).slice(0, 120)))
    await p.goto(BASE + r.home, { waitUntil: 'domcontentloaded' })
    await sleep(2600)
    await p.locator('.lay-notify .lay-act').click()
    await sleep(900)

    const base = await p.evaluate(() => ({
      tabs: [...document.querySelectorAll('.lnp-tabs button')].map((e) => e.innerText.replace(/\s+/g, ' ').trim()),
      foot: document.querySelector('.lnp-foot')?.innerText.trim() || '',
      unreadItems: [...document.querySelectorAll('.lnp-item')].filter((e) => e.classList.contains('is-unread')).length,
      delBtns: document.querySelectorAll('.lnp-item .lnp-del').length,
      empty: document.querySelector('.lnp-empty')?.innerText.trim() || ''
    }))
    check(r.label + ' 分组存在', base.tabs.length >= 3, base.tabs.join(' | '))
    check(r.label + ' 未读项无删除按钮', base.unreadItems === 0 || base.delBtns === 0,
      '未读 ' + base.unreadItems + ' 条 / 删除按钮 ' + base.delBtns + ' 个')

    // 切到已读：若有内容，点第一条必须弹详情
    await p.locator('.lnp-tabs button').nth(1).click()
    await sleep(800)
    const readCount = await p.locator('.lnp-item').count()
    if (readCount > 0) {
      await p.locator('.lnp-item').first().click()
      await sleep(1000)
      const ok = await p.locator('.el-dialog .ntf').count()
      const t = await p.evaluate(() => document.querySelector('.ntf-title')?.innerText.trim() || '')
      check(r.label + ' 已读条目可弹详情', ok > 0 && !!t, t || '未弹出')
      await p.keyboard.press('Escape')
      await sleep(600)
    } else {
      const readEmpty = await p.evaluate(() => document.querySelector('.lnp-empty')?.innerText.trim() || '')
      check(r.label + ' 已读空态有说明', /已读/.test(readEmpty), readEmpty.replace(/\n/g, ' '))
    }
    check(r.label + ' 无 JS 报错', errs.length === 0, errs.slice(0, 2).join(' || '))
    await ctx.close()
  }
  await b.close()
  console.log(fails.length ? '\n失败：' + fails.join(', ') : '\n多角色冒烟全部通过')
  process.exit(fails.length ? 1 : 0)
})()
