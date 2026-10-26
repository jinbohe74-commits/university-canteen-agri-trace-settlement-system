const path = require('path')
const fs = require('fs')
const GLOBAL = path.join(process.env.APPDATA, 'npm', 'node_modules')
module.paths.push(GLOBAL)
const { chromium } = require(path.join(GLOBAL, 'playwright'))

const BASE = 'http://127.0.0.1:5173'
const OUT = path.join(__dirname, '..', 'docs', 'screenshots')
if (!fs.existsSync(OUT)) fs.mkdirSync(OUT, { recursive: true })

const PLAN = [
  ['admin', 'admin/dashboard', '01-管理员-数据看板'],
  ['admin', 'admin/farmers', '02-管理员-农户合作社管理'],
  ['admin', 'admin/canteens', '03-管理员-食堂档案管理'],
  ['admin', 'admin/products', '04-管理员-农产品档案'],
  ['admin', 'admin/users', '05-管理员-用户与权限'],
  ['admin', 'admin/orders', '06-管理员-订单监管'],
  ['admin', 'admin/settlements', '07-管理员-结算审核'],
  ['admin', 'admin/warnings', '08-管理员-食安预警'],
  ['admin', 'admin/trace', '09-管理员-批次溯源'],
  ['admin', 'admin/config', '10-管理员-系统参数配置'],
  ['admin', 'admin/logs', '11-管理员-操作日志'],
  ['farmer24', 'farmer/workbench', '12-农户-我的工作台'],
  ['farmer24', 'farmer/sources', '13-农户-货源管理'],
  ['farmer24', 'farmer/orders', '14-农户-订单管理'],
  ['farmer24', 'farmer/settlement', '15-农户-结算与收入'],
  ['farmer24', 'farmer/credit', '16-农户-信用评价'],
  ['farmer24', 'farmer/messages', '17-农户-消息提醒'],
  ['purchaser01', 'purchaser/demands', '18-采购员-采购需求'],
  ['purchaser01', 'purchaser/sources', '19-采购员-货源大厅'],
  ['purchaser01', 'purchaser/match', '20-采购员-智能匹配'],
  ['purchaser01', 'purchaser/orders', '21-采购员-我的订单'],
  ['purchaser01', 'purchaser/inbound', '22-采购员-入库确认'],
  ['purchaser01', 'purchaser/evaluation', '23-采购员-采购评价'],
  ['inspector01', 'inspector/tasks', '24-质检员-我的任务'],
  ['inspector01', 'inspector/quality', '25-质检员-质检录入'],
  ['inspector01', 'inspector/delivery', '26-质检员-配送任务'],
  ['inspector01', 'inspector/exception', '27-质检员-异常上报']
]

async function login(page, user) {
  await page.goto(BASE + '/login', { waitUntil: 'networkidle' })
  await page.evaluate(() => localStorage.clear())
  await page.goto(BASE + '/login', { waitUntil: 'networkidle' })
  await page.fill('input[placeholder="请输入账号"]', user)
  await page.fill('input[placeholder="请输入密码"]', '123456')
  await page.click('button.submit')
  await page.waitForTimeout(1800)
}

;(async () => {
  const browser = await chromium.launch()
  const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 }, deviceScaleFactor: 1.5 })
  const page = await ctx.newPage()

  await page.goto(BASE + '/login', { waitUntil: 'networkidle' })
  await page.waitForTimeout(900)
  await page.screenshot({ path: path.join(OUT, '00-登录页.png'), fullPage: true })
  console.log('00-登录页.png')

  let cur = ''
  for (const [user, route, name] of PLAN) {
    if (user !== cur) {
      await login(page, user)
      cur = user
    }
    await page.goto(BASE + '/' + route, { waitUntil: 'networkidle' })
    await page.waitForTimeout(1400)
    await page.screenshot({ path: path.join(OUT, name + '.png'), fullPage: true })
    console.log(name + '.png')
  }
  await browser.close()
  console.log('\n截图完成 -> ' + OUT)
})()
