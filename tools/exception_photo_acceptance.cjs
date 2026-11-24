/* ==========================================================================
   异常上报带照片的端到端验收（2026-11-11 第十一轮）
   --------------------------------------------------------------------------
   之前这里是三个「点击只弹演示环境提示」的假格子，本轮接上已有上传接口。
   判据必须闭环：
     1. 质检员选图 → 真的上传到服务端（返回 url，文件落到 uploads/exception）
     2. 提交后预警表 photo_urls 有值
     3. 管理员预警详情能看到「现场照片（N 张）」，且带登录态能取到图（不是 401）
   ========================================================================== */
const path = require('path')
const fs = require('fs')

const ROOT = path.resolve(__dirname, '..')
const BASE = 'http://127.0.0.1:5173'
const API = 'http://127.0.0.1:8080'
const OUT = path.join(ROOT, 'docs', 'reform_2026-11-11', 'shots11')
fs.mkdirSync(OUT, { recursive: true })

const GLOBAL = path.join(process.env.APPDATA, 'npm', 'node_modules')
module.paths.push(GLOBAL)
const { chromium } = require(path.join(GLOBAL, 'playwright'))

// 1x1 PNG，用于真实上传（后端会校验 magic number，不能用伪造文件）
const PNG = Buffer.from(
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==',
  'base64'
)
const TMP_PNG = path.join(OUT, '_upload_test.png')
fs.writeFileSync(TMP_PNG, PNG)

const results = []
const rec = (n, ok, d) => {
  results.push({ n, ok: !!ok, d })
  console.log((ok ? '  PASS ' : '  FAIL ') + n + (d ? ' | ' + String(d).slice(0, 220) : ''))
}
const sleep = ms => new Promise(r => setTimeout(r, ms))

async function apiLogin(username, role) {
  const r = await fetch(API + '/api/auth/login', {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username, password: '123456', expectedRole: role })
  })
  const j = await r.json()
  if (j.code !== 200) throw new Error('登录失败 ' + username)
  return j.data
}

;(async () => {
  const browser = await chromium.launch({ channel: 'msedge' })
  const insp = await apiLogin('inspector01', 'INSPECTOR')
  const admin = await apiLogin('admin', 'ADMIN')

  const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 } })
  await ctx.addInitScript(([t, u, rn, mn]) => {
    sessionStorage.setItem('ct_token', t)
    sessionStorage.setItem('ct_user', u)
    sessionStorage.setItem('ct_role_name', rn)
    sessionStorage.setItem('ct_menus', mn)
  }, [insp.token, JSON.stringify(insp.user), insp.roleName || '', JSON.stringify(insp.menus || [])])
  const page = await ctx.newPage()
  const errs = []
  page.on('pageerror', e => errs.push(e.message.slice(0, 140)))

  await page.goto(BASE + '/inspector/exception', { waitUntil: 'domcontentloaded' })
  await sleep(2200)

  // 关键：不再有"演示环境"的假提示
  const fakeTip = await page.evaluate(() => document.body.innerText.includes('演示环境'))
  rec('上报页不再出现「演示环境」假提示', fakeTip === false, fakeTip ? '仍存在假提示' : 'ok')

  // 选图（input 是动态创建的，用 filechooser 事件接）
  const marker = '第11轮验收' + Date.now().toString().slice(-6)
  const chooserPromise = page.waitForEvent('filechooser', { timeout: 8000 })
  await page.locator('.photo-ph').first().click()
  const chooser = await chooserPromise
  await chooser.setFiles(TMP_PNG)
  await sleep(2600)

  const photoState = await page.evaluate(() => ({
    thumbs: document.querySelectorAll('.photo-ph.has-photo').length,
    hasImg: !!document.querySelector('.photo-img')
  }))
  rec('选图后出现真实缩略图', photoState.hasImg && photoState.thumbs === 1, JSON.stringify(photoState))

  await page.fill('textarea', '冷链温度异常：车厢温度升至 11℃，现场已拍照留证并隔离，等待管理员判定（' + marker + '）')
  await page.locator('.form-actions button:has-text("提交上报")').click()
  await sleep(2500)

  const okMsg = await page.evaluate(() => document.body.innerText.includes('含 1 张现场照片'))
  rec('提交后提示含照片张数', okMsg, okMsg ? 'ok' : '未见「含 1 张现场照片」')

  // 后端核验：最新一条预警必须有 photo_urls，且文件真的存在
  const r = await fetch(API + '/api/admin/warnings?page=1&size=5&keyword=' + encodeURIComponent(marker), {
    headers: { Authorization: 'Bearer ' + admin.token }
  })
  const j = await r.json()
  const recs = j.data?.page?.records || []
  const hit = recs.find(x => String(x.content || '').includes(marker))
  rec('预警落库且带 photo_urls', !!hit && !!hit.photoUrls, hit ? hit.photoUrls : '未找到该预警')

  if (hit && hit.photoUrls) {
    const url = String(hit.photoUrls).split(';')[0]
    const imgRes = await fetch(API.replace('/api', '') + url, {
      headers: { Authorization: 'Bearer ' + admin.token }
    })
    rec('照片可用登录态取回（非 401）', imgRes.status === 200,
      'HTTP ' + imgRes.status + ' · ' + url)
    const anon = await fetch(API.replace('/api', '') + url)
    rec('未登录直连被拒绝（受控下载）', anon.status === 401 || anon.status === 403, 'HTTP ' + anon.status)
  }

  // 管理员侧：详情里能看到照片入口
  const actx = await browser.newContext({ viewport: { width: 1440, height: 900 } })
  await actx.addInitScript(([t, u, rn, mn]) => {
    sessionStorage.setItem('ct_token', t)
    sessionStorage.setItem('ct_user', u)
    sessionStorage.setItem('ct_role_name', rn)
    sessionStorage.setItem('ct_menus', mn)
  }, [admin.token, JSON.stringify(admin.user), admin.roleName || '', JSON.stringify(admin.menus || [])])
  const ap = await actx.newPage()
  await ap.goto(BASE + '/admin/warnings?focus=' + (hit ? hit.warningNo : ''), { waitUntil: 'domcontentloaded' })
  await sleep(3200)
  const seen = await ap.evaluate(() => {
    const t = document.body.innerText || ''
    return {
      photosTitle: /现场照片（\d+ 张）/.test(t),
      photoChip: document.querySelectorAll('.wd-photo').length,
      dialogs: document.querySelectorAll('.el-dialog').length
    }
  })
  rec('管理员详情显示现场照片', seen.photosTitle && seen.photoChip >= 1, JSON.stringify(seen))
  await ap.screenshot({ path: path.join(OUT, 'ADMIN-预警详情-现场照片.png') }).catch(() => {})

  rec('页面无 JS 报错', errs.length === 0, errs[0] || 'ok')

  const pass = results.filter(x => x.ok).length
  console.log(`\n总计 ${pass}/${results.length}`)
  fs.writeFileSync(path.join(OUT, 'exception_photo.json'), JSON.stringify(results, null, 2), 'utf8')
  try { fs.unlinkSync(TMP_PNG) } catch (e) {}
  await browser.close()
  process.exit(pass === results.length ? 0 : 1)
})()
