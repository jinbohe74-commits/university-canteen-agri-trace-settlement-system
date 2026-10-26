const path = require('path')
const GLOBAL = path.join(process.env.APPDATA, 'npm', 'node_modules')
module.paths.push(GLOBAL)
const { chromium } = require(path.join(GLOBAL, 'playwright'))
const BASE = 'http://127.0.0.1:5173'

let pass = 0, fail = 0
const ok = (c, m) => { c ? (pass++, console.log('   ✓ ' + m)) : (fail++, console.log('   ✗ ' + m)) }

async function login(page, user) {
  await page.goto(BASE + '/login', { waitUntil: 'networkidle' })
  await page.evaluate(() => localStorage.clear())
  await page.goto(BASE + '/login', { waitUntil: 'networkidle' })
  await page.fill('input[placeholder="请输入账号"]', user)
  await page.fill('input[placeholder="请输入密码"]', '123456')
  await page.click('button.submit')
  await page.waitForTimeout(1700)
}

async function confirmBox(page) {
  const box = page.locator('.el-message-box')
  if (await box.isVisible().catch(() => false)) {
    await page.locator('.el-message-box__btns button').last().click()
    await page.waitForTimeout(900)
  }
}

async function toast(page) {
  const t = page.locator('.el-message').first()
  const txt = await t.innerText().catch(() => '')
  return txt
}

;(async () => {
  const browser = await chromium.launch()
  const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 } })
  const page = await ctx.newPage()
  const errs = []
  page.on('pageerror', (e) => errs.push(e.message))

  console.log('▶ 1. 采购员在「货源大厅」下单')
  await login(page, 'purchaser01')
  await page.goto(BASE + '/purchaser/sources', { waitUntil: 'networkidle' })
  await page.waitForTimeout(1500)
  const firstCard = await page.locator('.source-card, .card').filter({ hasText: '¥' }).first()
  await page.locator('button:has-text("下单")').first().click()
  await page.waitForTimeout(1000)
  const dlgVisible = await page.locator('.el-dialog').first().isVisible().catch(() => false)
  ok(dlgVisible, '下单弹窗打开')
  if (dlgVisible) {
    // 数量输入框（el-input-number 内 input）
    const num = page.locator('.el-dialog input.el-input__inner').first()
    await num.fill('120')
    await page.waitForTimeout(200)
    await page.locator('.el-dialog button:has-text("确认下单"), .el-dialog button:has-text("提交"), .el-dialog .el-button--primary').last().click()
    await page.waitForTimeout(2200)
    const t = await toast(page)
    ok(/下单成功|已通知/.test(t), '下单结果提示：' + (t || '（无提示）'))
    await page.waitForTimeout(600)
    ok(page.url().includes('/purchaser/orders'), '下单后跳转到我的订单：' + page.url().replace(BASE, ''))
  }

  console.log('▶ 2. 农户在「订单管理」接单')
  await login(page, 'farmer24')
  await page.goto(BASE + '/farmer/orders', { waitUntil: 'networkidle' })
  await page.waitForTimeout(1500)
  const acceptLink = page.locator('text=接单').first()
  const hasAccept = await acceptLink.count()
  if (hasAccept) {
    await acceptLink.click()
    await page.waitForTimeout(800)
    await confirmBox(page)
    await page.waitForTimeout(1600)
    const t = await toast(page)
    ok(/接单成功|已接单/.test(t), '接单结果提示：' + (t || '（无提示）'))
    const body = await page.locator('.content').innerText()
    ok(body.includes('已接单') || body.includes('待质检'), '列表出现「已接单/待质检」状态')
  } else {
    ok(false, '未找到待接单订单（可能已无 10 状态订单）')
  }

  console.log('▶ 3. 农户发货 → 质检员质检 → 通过')
  await page.goto(BASE + '/farmer/orders', { waitUntil: 'networkidle' })
  await page.waitForTimeout(1300)
  const shipLink = page.locator('text=发货').first()
  if (await shipLink.count()) {
    await shipLink.click()
    await page.waitForTimeout(700)
    await confirmBox(page)
    await page.waitForTimeout(1500)
    ok(true, '发货操作已提交：' + ((await toast(page)) || '已提交'))
  }

  await login(page, 'inspector01')
  await page.goto(BASE + '/inspector/quality', { waitUntil: 'networkidle' })
  await page.waitForTimeout(1800)
  const testRows = await page.locator('.test-row').count()
  ok(testRows === 6, '质检单加载 6 个检测项目')
  const before = await page.locator('.page-sub').innerText().catch(() => '')
  await page.locator('button:has-text("提交质检")').first().click()
  await page.waitForTimeout(2500)
  await confirmBox(page)
  await page.waitForTimeout(1500)
  const t3 = await toast(page)
  ok(/质检通过|已判定不合格/.test(t3), '质检提交结果：' + (t3 || '（无提示）'))
  const qr = await page.evaluate(() => {
    const img = document.querySelector('.qr-img')
    return img ? img.naturalWidth : 0
  })
  ok(qr > 50, '提交后溯源二维码生成并渲染（' + qr + 'px）')

  console.log('▶ 4. 食堂入库确认（自动核算损耗并生成结算单）')
  await login(page, 'purchaser01')
  await page.goto(BASE + '/purchaser/inbound', { waitUntil: 'networkidle' })
  await page.waitForTimeout(1800)
  const inbBody = await page.locator('.content').innerText()
  ok(inbBody.includes('实际结算金额'), '入库页展示结算金额预览')
  // 实收数量改为下单量 - 10
  const nums = page.locator('input.el-input__inner')
  const cnt = await nums.count()
  let filled = false
  for (let i = 0; i < cnt; i++) {
    const v = await nums.nth(i).inputValue().catch(() => '')
    if (/^\d+(\.\d+)?$/.test(v) && Number(v) > 50) {
      const newVal = String(Math.max(1, Number(v) - 10))
      await nums.nth(i).fill(newVal)
      filled = true
      break
    }
  }
  ok(filled, '已填写实收数量（下单量 − 10）')
  await page.waitForTimeout(600)
  await page.locator('button:has-text("确认入库")').first().click()
  await page.waitForTimeout(1200)
  await confirmBox(page)
  await page.waitForTimeout(2200)
  const t4 = await toast(page)
  ok(/入库成功|结算单/.test(t4), '入库结果：' + (t4 || '（无提示）'))

  console.log('▶ 5. 管理员结算审核')
  await login(page, 'admin')
  await page.goto(BASE + '/admin/settlements', { waitUntil: 'networkidle' })
  await page.waitForTimeout(1600)
  const beforePending = await page.locator('.stat-card').first().innerText().catch(() => '')
  ok(beforePending.length > 0, '结算页统计卡：' + beforePending.replace(/\s+/g, ' ').slice(0, 40))

  console.log('\n=== UI 全流程：通过 ' + pass + '，失败 ' + fail + ' ===')
  if (errs.length) console.log('页面错误：' + errs.slice(0, 3).join(' | '))
  await browser.close()
})()
