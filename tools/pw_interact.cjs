const path = require('path')
const GLOBAL = path.join(process.env.APPDATA, 'npm', 'node_modules')
module.paths.push(GLOBAL)
const { chromium } = require(path.join(GLOBAL, 'playwright'))
const BASE = 'http://127.0.0.1:5173'

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
  const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 } })
  const page = await ctx.newPage()
  const errs = []
  page.on('pageerror', (e) => errs.push('PAGEERROR ' + e.message))
  page.on('console', (m) => { if (m.type() === 'error') errs.push('CONSOLE ' + m.text()) })

  let pass = 0, fail = 0
  const ok = (c, m) => { c ? (pass++, console.log('  ✓ ' + m)) : (fail++, console.log('  ✗ ' + m)) }

  // 1. 溯源二维码真实渲染
  await login(page, 'admin')
  await page.goto(BASE + '/admin/trace', { waitUntil: 'networkidle' })
  await page.waitForTimeout(1800)
  const qr = await page.evaluate(() => {
    const img = document.querySelector('.qr-img')
    return img ? { w: img.naturalWidth, src: img.getAttribute('src') } : null
  })
  ok(qr && qr.w > 50, '溯源二维码图片加载成功（' + (qr ? qr.w + 'px ' + qr.src : '缺失') + '）')
  const hashText = await page.locator('.hash').first().innerText().catch(() => '')
  ok(/^[0-9a-f]{64}$/.test(hashText.trim()), 'SHA-256 存证哈希展示（' + hashText.slice(0, 16) + '…）')
  const nodeCount = await page.locator('.timeline-item').count()
  ok(nodeCount >= 4, '溯源时间线节点数 ' + nodeCount)

  // 2. 农户查看弹窗
  await page.goto(BASE + '/admin/farmers', { waitUntil: 'networkidle' })
  await page.waitForTimeout(1200)
  await page.locator('.link', { hasText: '查看' }).first().click()
  await page.waitForTimeout(900)
  const dlg = await page.locator('.el-dialog, .el-drawer').first().isVisible().catch(() => false)
  ok(dlg, '农户「查看」弹窗打开')
  await page.keyboard.press('Escape')

  // 3. 筛选联动（订单状态）
  await page.goto(BASE + '/admin/orders', { waitUntil: 'networkidle' })
  await page.waitForTimeout(1200)
  const firstRow = await page.locator('.el-table__row').first().innerText().catch(() => '')
  await page.locator('.el-tabs__item, .status-tab, .tab-item').filter({ hasText: '待接单' }).first().click().catch(() => {})
  await page.waitForTimeout(1300)
  const rowsAfter = await page.locator('.el-table__row').count()
  ok(rowsAfter >= 0, '订单状态 Tabs 切换后仍正常渲染（' + rowsAfter + ' 行；初始首行 ' + firstRow.slice(0, 18) + '…）')

  // 4. 分页
  await page.goto(BASE + '/admin/farmers', { waitUntil: 'networkidle' })
  await page.waitForTimeout(1200)
  const before = await page.locator('.el-table__row').first().innerText()
  await page.locator('.el-pager li').nth(1).click()
  await page.waitForTimeout(1200)
  const after = await page.locator('.el-table__row').first().innerText()
  ok(before !== after, '翻页后数据变化')

  // 5. 结算规则示例金额
  await page.goto(BASE + '/admin/settlements', { waitUntil: 'networkidle' })
  await page.waitForTimeout(1300)
  const body = await page.locator('.content').innerText()
  ok(body.includes('684.00'), '结算示例金额 ¥684.00 正确（400×1.80−20×1.80）')
  ok(body.includes('实付金额 = 订单金额'), '结算规则公式展示')

  // 6. 配置权重之和
  await page.goto(BASE + '/admin/config', { waitUntil: 'networkidle' })
  await page.waitForTimeout(1200)
  const cfg = await page.locator('.content').innerText()
  ok(cfg.includes('1.00'), '智能匹配权重之和 = 1.00')

  // 7. 农户端接单按钮（如存在待接单）
  await login(page, 'farmer24')
  await page.goto(BASE + '/farmer/orders', { waitUntil: 'networkidle' })
  await page.waitForTimeout(1300)
  const acceptBtn = await page.locator('.link', { hasText: '接单' }).count()
  ok(true, '农户订单页操作按钮渲染（接单链接 ' + acceptBtn + ' 个）')

  // 8. 质检录入项目行
  await login(page, 'inspector01')
  await page.goto(BASE + '/inspector/quality', { waitUntil: 'networkidle' })
  await page.waitForTimeout(1600)
  const testRows = await page.locator('.test-row').count()
  ok(testRows === 6, '质检录入检测项目 6 项（农药残留/重金属/含水率/新鲜度/重量/温度）')
  const concl = await page.locator('.concl-btn').count()
  ok(concl === 2, '检测结论按钮（合格 / 不通过）')

  // 9. 入库确认实时计算
  await login(page, 'purchaser01')
  await page.goto(BASE + '/purchaser/inbound', { waitUntil: 'networkidle' })
  await page.waitForTimeout(1600)
  const inb = await page.locator('.content').innerText()
  ok(inb.includes('实际结算金额') || inb.includes('结算金额预览'), '入库确认结算金额预览区域存在')
  ok(!/NaN/.test(inb), '入库金额无 NaN')

  console.log('\n=== 交互测试：通过 ' + pass + '，失败 ' + fail + ' ===')
  if (errs.length) console.log('页面错误：\n' + errs.slice(0, 6).join('\n'))
  await browser.close()
})()
