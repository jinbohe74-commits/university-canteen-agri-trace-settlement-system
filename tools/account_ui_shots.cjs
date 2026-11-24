/* ==========================================================================
   账号与密码机制 UI 验收（2026-11-11）
   --------------------------------------------------------------------------
   只认界面上真正发生的事：
     1. 新增用户弹窗选「农户」后，必须出现「绑定农户档案」并可选择
     2. 随机生成按钮必须给出可用的初始密码（8 位以上、含字母数字）
     3. 创建成功后必须弹出「账号凭证」，账号与密码可核对
     4. 用该账号登录，必须弹出不可关闭的「请先修改初始密码」
     5. 改密后必须真正进入工作台（不再被 428 挡住）
   ========================================================================== */
const path = require('path')
const fs = require('fs')

const ROOT = path.resolve(__dirname, '..')
const BASE = 'http://127.0.0.1:5173'
const OUT = path.join(ROOT, 'docs', 'reform_2026-11-11', 'shots11')
fs.mkdirSync(OUT, { recursive: true })

const GLOBAL = path.join(process.env.APPDATA, 'npm', 'node_modules')
module.paths.push(GLOBAL)
const { chromium } = require(path.join(GLOBAL, 'playwright'))

const TAG = Date.now().toString().slice(-5)
const USERNAME = 'ui_farmer_' + TAG
const results = []
const rec = (name, ok, detail) => {
  results.push({ name, ok: !!ok, detail })
  console.log((ok ? '  PASS ' : '  FAIL ') + name + (detail ? ' | ' + String(detail).slice(0, 240) : ''))
}
const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

const shot = async (page, name) => {
  const file = path.join(OUT, name)
  await page.screenshot({ path: file, fullPage: false })
  return file
}

const loginUI = async (page, username, password) => {
  await page.goto(BASE + '/login', { waitUntil: 'domcontentloaded' })
  await page.waitForTimeout(700)
  await page.fill('input[placeholder="请输入账号"]', username)
  await page.fill('input[placeholder="请输入密码"]', password)
  await page.click('button.submit')
  await page.waitForTimeout(2000)
}

;(async () => {
  const browser = await chromium.launch({ channel: 'msedge' })
  const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 } })
  const page = await ctx.newPage()
  const consoleErrors = []
  page.on('pageerror', (e) => consoleErrors.push(e.message.slice(0, 160)))

  /* ------------------------------------------------ 管理员建号 */
  await loginUI(page, 'admin', '123456')
  await page.goto(BASE + '/admin/users', { waitUntil: 'domcontentloaded' })
  await page.waitForTimeout(1600)

  await shot(page, 'ACC-0-用户列表.png')
  await shot(page, 'ACC-0-用户列表.png')
  const orgHeader = await page.locator('.el-table th').allTextContents()
  rec('列表出现「所属单位」列', orgHeader.some((t) => t.includes('所属单位')), orgHeader.join('/'))

  await page.click('button:has-text("新增用户")')
  await page.waitForTimeout(600)

  // 选角色 = 农户
  const dialog = page.locator('.el-dialog:has-text("新增用户")').first()
  await dialog.locator('.el-form-item:has-text("角色") .el-select').first().click()
  await page.waitForTimeout(300)
  await page.locator('.el-select-dropdown__item:has-text("农户/合作社")').last().click()
  await page.waitForTimeout(900)

  const bindVisible = await dialog.locator('.el-form-item:has-text("绑定农户档案")').isVisible()
  const bindHint = bindVisible ? await dialog.locator('.bind-hint').innerText() : ''
  rec('选「农户」后出现绑定档案字段', bindVisible, bindHint.slice(0, 80))
  rec('绑定字段说明了数据范围', /只能看到自己档案/.test(bindHint), bindHint.slice(0, 60))

  // 选择第一个可绑定档案
  await dialog.locator('.el-form-item:has-text("绑定农户档案") .el-select').click()
  await page.waitForTimeout(600)
  // 页面上同时存在角色下拉的残留浮层，必须只在"当前可见"的那个下拉里选
  const bindMenu = page.locator('.el-select-dropdown:visible').last()
  const bindItems = bindMenu.locator('.el-select-dropdown__item')
  const optCount = await bindItems.count()
  rec('绑定下拉有可选档案', optCount > 0, 'options=' + optCount)
  // 选项里同时有名称/编号/类型，取名称用于后面核对列表
  const pickedLabel = (await bindItems.first().locator('.bind-name').innerText()).trim()
  const pickedNo = (await bindItems.first().locator('.bind-no').innerText()).trim()
  await bindItems.first().click()
  await page.waitForTimeout(400)
  await shot(page, 'ACC-1-新增用户-绑定档案.png')

  // 随机生成初始密码
  await dialog.locator('button:has-text("随机生成")').click()
  await page.waitForTimeout(700)
  const pwdValue = await dialog.locator('.pwd-row input').inputValue()
  rec('随机生成初始密码符合强度', pwdValue.length >= 8 && /[A-Za-z]/.test(pwdValue) && /\d/.test(pwdValue), pwdValue)

  await dialog.locator('.el-form-item:has-text("账号") input').first().fill(USERNAME)
  await dialog.locator('.el-form-item:has-text("姓名") input').first().fill('界面验收农户')
  await dialog.locator('.el-form-item:has-text("手机号") input').first().fill('13700000009')
  await dialog.locator('button:has-text("创建账号")').click()
  await page.waitForTimeout(1800)

  const credVisible = await page.locator('.el-dialog:has-text("账号凭证")').isVisible()
  const credText = credVisible ? await page.locator('.cred-card').innerText() : ''
  rec('创建后弹出账号凭证', credVisible, credText.replace(/\n/g, ' | ').slice(0, 120))
  rec('凭证里的账号与初始密码可核对', credText.includes(USERNAME) && credText.includes(pwdValue), '')
  rec('凭证提示首次登录必须改密', /首次登录/.test(await page.locator('.cred-next').innerText()), '')
  await shot(page, 'ACC-2-账号凭证.png')
  await page.locator('.el-dialog:has-text("账号凭证") button:has-text("关闭")').click()
  await page.waitForTimeout(800)

  // 列表里能看到所属单位与待改密（按 id 升序，新账号在末页，必须先搜）
  await page.fill('.filter-bar input[placeholder*="搜索"]', USERNAME)
  await page.click('.filter-bar button:has-text("查询")')
  await page.waitForTimeout(1200)
  const row = page.locator(`.el-table__row:has-text("${USERNAME}")`).first()
  const rowText = await row.innerText()
  rec('列表显示所属单位与待改密标记', /待改密/.test(rowText) && rowText.includes(pickedLabel) && rowText.includes(pickedNo),
    '期望 ' + pickedLabel + '/' + pickedNo + ' ; 实得 ' + rowText.replace(/\n/g, ' | ').slice(0, 120))
  await shot(page, 'ACC-3-用户列表-所属单位.png')

  /* -------------------------------------------------- 新账号首登改密 */
  const ctx2 = await browser.newContext({ viewport: { width: 1440, height: 900 } })
  const p2 = await ctx2.newPage()
  p2.on('pageerror', (e) => consoleErrors.push('p2:' + e.message.slice(0, 160)))
  await loginUI(p2, USERNAME, pwdValue)
  await p2.waitForTimeout(1200)

  const forceTitle = await p2.locator('.el-dialog__title').first().innerText()
  rec('首登弹出强制改密（标题正确）', forceTitle.includes('请先修改初始密码'), forceTitle)
  const closable = await p2.locator('.el-dialog__headerbtn').count()
  rec('强制改密弹窗不可随手关闭', closable === 0, 'close buttons=' + closable)
  await shot(p2, 'ACC-4-首登强制改密.png')

  const pwdDialog = p2.locator('.el-dialog').first()
  const inputs = pwdDialog.locator('input[type="password"]')
  await inputs.nth(0).fill(pwdValue)
  await inputs.nth(1).fill('Farmer2026')
  await inputs.nth(2).fill('Farmer2026')
  await pwdDialog.locator('button:has-text("确认修改")').click()
  await p2.waitForTimeout(2600)

  const afterUrl = p2.url()
  const stillDialog = await p2.locator('.el-dialog__title').first().count()
  rec('改密后进入工作台', /\/farmer\//.test(afterUrl) && stillDialog === 0, afterUrl)
  const bodyText = await p2.locator('body').innerText()
  rec('工作台展示本档案数据（不再 428）', /我的工作台|待处理|订单|货源/.test(bodyText) && !/初始密码/.test(bodyText),
    bodyText.replace(/\n/g, ' ').slice(0, 120))
  await shot(p2, 'ACC-5-改密后农户工作台.png')

  rec('全程无 JS 运行错误', consoleErrors.length === 0, consoleErrors.join(' ; '))

  /* 收尾：把验收账号改成质检员角色，自动解绑农户档案，
     这样脚本可以反复跑，不会把演示库里的空闲档案耗光 */
  try {
    const post = (url, body, token) => fetch(BASE + url, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: 'Bearer ' + token } : {}) },
      body: JSON.stringify(body)
    }).then((r) => r.json())
    const adminLogin = await post('/api/auth/login', { username: 'admin', password: '123456' })
    const token = adminLogin?.data?.token
    const list = await fetch(BASE + '/api/admin/users?keyword=' + USERNAME, {
      headers: { Authorization: 'Bearer ' + token }
    }).then((r) => r.json())
    const u = list?.data?.page?.records?.[0]
    if (u) {
      await post('/api/admin/users', { id: u.id, username: u.username, realName: u.realName, roleType: 'INSPECTOR', status: 1 }, token)
      console.log('  收尾：验收账号已改为质检员，农户档案已释放')
    }
  } catch (e) {
    console.log('  收尾失败（不影响结论）：' + e.message)
  }

  await browser.close()

  const pass = results.filter((x) => x.ok).length
  console.log('\n==============================================')
  console.log(`账号机制 UI 验收：${pass}/${results.length} 通过`)
  console.log('==============================================')
  results.filter((x) => !x.ok).forEach((x) => console.log('  ✗ ' + x.name + ' | ' + x.detail))
  process.exit(pass === results.length ? 0 : 1)
})().catch((e) => {
  console.error('脚本异常:', e.message)
  process.exit(1)
})
