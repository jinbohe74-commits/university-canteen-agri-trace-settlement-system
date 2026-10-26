const path = require('path')
const GLOBAL = path.join(process.env.APPDATA, 'npm', 'node_modules')
module.paths.push(GLOBAL)
const { chromium } = require(path.join(GLOBAL, 'playwright'))

const BASE = 'http://127.0.0.1:5173'
const PAGES = [
  { user: 'admin', route: 'admin/dashboard', name: '数据看板' },
  { user: 'admin', route: 'admin/farmers', name: '农户管理' },
  { user: 'admin', route: 'admin/settlements', name: '结算审核' },
  { user: 'farmer24', route: 'farmer/workbench', name: '农户工作台' },
  { user: 'purchaser01', route: 'purchaser/match', name: '智能匹配' },
  { user: 'inspector01', route: 'inspector/tasks', name: '质检任务' }
]

async function login(page, user) {
  await page.goto(BASE + '/login', { waitUntil: 'networkidle' })
  await page.evaluate(() => localStorage.clear())
  await page.goto(BASE + '/login', { waitUntil: 'networkidle' })
  await page.fill('input[placeholder="请输入账号"]', user)
  await page.fill('input[placeholder="请输入密码"]', '123456')
  await page.click('button.submit')
  await page.waitForTimeout(1600)
}

;(async () => {
  const browser = await chromium.launch()
  const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 }, deviceScaleFactor: 2 })
  const page = await ctx.newPage()

  // 登录页指标
  await page.goto(BASE + '/login', { waitUntil: 'networkidle' })
  await page.waitForTimeout(700)
  const loginBox = await page.evaluate(() => {
    const brand = document.querySelector('.brand-side')
    const card = document.querySelector('.login-card')
    return {
      brandWidthPct: brand ? Math.round((brand.getBoundingClientRect().width / window.innerWidth) * 100) : 0,
      cardWidth: card ? Math.round(card.getBoundingClientRect().width) : 0,
      roles: document.querySelectorAll('.role-item').length
    }
  })
  console.log('登录页: 品牌区宽=' + loginBox.brandWidthPct + '% 登录卡宽=' + loginBox.cardWidth + 'px 角色按钮=' + loginBox.roles)

  let cur = ''
  for (const p of PAGES) {
    if (p.user !== cur) {
      await login(page, p.user)
      cur = p.user
    }
    await page.goto(BASE + '/' + p.route, { waitUntil: 'networkidle' })
    await page.waitForTimeout(1200)
    const m = await page.evaluate(() => {
      const rect = (el) => (el ? el.getBoundingClientRect() : null)
      const sidebar = document.querySelector('.sidebar')
      const topbar = document.querySelector('.topbar')
      const cards = [...document.querySelectorAll('.stat-card')].map((c) => {
        const r = c.getBoundingClientRect()
        return { x: Math.round(r.x), y: Math.round(r.y), w: Math.round(r.width), h: Math.round(r.height) }
      })
      const rows = [...document.querySelectorAll('.el-table__row')].slice(0, 3).map((r) => Math.round(r.getBoundingClientRect().height))
      const sidebarBg = sidebar ? getComputedStyle(sidebar).backgroundImage.slice(0, 60) : ''
      const activeMenu = document.querySelector('.menu-item.active')
      const buttons = [...document.querySelectorAll('.el-button--primary')]
      const primaryBg = buttons.length ? getComputedStyle(buttons[0]).backgroundColor : ''
      const overflow = document.documentElement.scrollWidth > window.innerWidth + 1
      // 检查页面上是否有元素横向超出视口
      let offenders = 0
      document.querySelectorAll('.content *').forEach((el) => {
        const r = el.getBoundingClientRect()
        if (r.width > 0 && r.right > window.innerWidth + 2) offenders++
      })
      const bodyText = document.body.innerText
      return {
        sidebarW: sidebar ? Math.round(rect(sidebar).width) : 0,
        topbarH: topbar ? Math.round(rect(topbar).height) : 0,
        cards, rows, sidebarBg, primaryBg, overflow, offenders,
        hasNaN: /NaN|undefined/.test(bodyText)
      }
    })
    const sameRow = m.cards.length >= 4 ? (m.cards[0].y === m.cards[1].y && m.cards[1].y === m.cards[3].y) : false
    console.log(
      '\n' + p.name.padEnd(8) +
      ' 侧栏=' + m.sidebarW + 'px 顶栏=' + m.topbarH + 'px 统计卡=' + m.cards.length +
      ' 同排=' + (sameRow ? '是' : '否') + ' 卡高=' + (m.cards[0] ? m.cards[0].h : 0) +
      '\n          行高=' + JSON.stringify(m.rows) + ' 主色=' + m.primaryBg +
      ' 溢出=' + (m.overflow ? '有(' + m.offenders + ')' : '无') + ' 异常文本=' + m.hasNaN +
      '\n          侧栏渐变=' + (m.sidebarBg || '(无)')
    )
  }
  await browser.close()
})()
