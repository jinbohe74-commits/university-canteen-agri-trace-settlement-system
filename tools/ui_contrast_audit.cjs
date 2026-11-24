/* 深色模式低对比文本批量审计（L-006：量化成可自动检测条件，一次抓全）
   遍历 night 模式下各页面的叶子文本元素，算出实际前景/背景对比度，列出 < 3.0 的实例。 */
const path = require('path')
const fs = require('fs')
const GLOBAL = path.join(process.env.APPDATA, 'npm', 'node_modules')
module.paths.push(GLOBAL)
const { chromium } = require(path.join(GLOBAL, 'playwright'))
const BASE = 'http://127.0.0.1:5173'
const ROOT = path.resolve(__dirname, '..')
const OUT = path.join(ROOT, 'docs', 'design_2026-11-10', 'ui_unify_acceptance')

const AUDIT = () => {
  const parse = s => {
    s = (s || '').trim()
    if (s.startsWith('#')) {
      let h = s.slice(1); if (h.length === 3) h = h.split('').map(c => c + c).join('')
      return [parseInt(h.slice(0,2),16), parseInt(h.slice(2,4),16), parseInt(h.slice(4,6),16), 1]
    }
    const m = s.match(/-?[\d.]+/g)
    if (!m) return [0,0,0,0]
    return [Number(m[0]), Number(m[1]), Number(m[2]), m[3] === undefined ? 1 : Number(m[3])]
  }
  const lum = rgb => {
    const a = rgb.slice(0,3).map(v => { const c=v/255; return c<=0.03928?c/12.92:Math.pow((c+0.055)/1.055,2.4) })
    return 0.2126*a[0]+0.7152*a[1]+0.0722*a[2]
  }
  const contrast = (fg, bg) => { const l1=lum(fg),l2=lum(bg); return (Math.max(l1,l2)+0.05)/(Math.min(l1,l2)+0.05) }
  const bgOf = el => {
    let node = el
    while (node && node !== document.documentElement) {
      const c = parse(getComputedStyle(node).backgroundColor)
      if (c[3] > 0.95) return c.slice(0,3)
      node = node.parentElement
    }
    return [11, 20, 28]
  }
  const seen = new Set(), out = []
  document.querySelectorAll('.lay-content *').forEach(el => {
    if (el.children.length) return
    const txt = (el.textContent || '').trim()
    if (!txt || txt.length > 50) return
    const st = getComputedStyle(el)
    if (st.display === 'none' || st.visibility === 'hidden' || parseFloat(st.opacity) < 0.3) return
    const r = el.getBoundingClientRect()
    if (r.width < 2 || r.height < 2 || r.top > window.innerHeight) return
    const fg = parse(st.color), bg = bgOf(el)
    const ratio = contrast(fg, bg)
    if (ratio >= 3.0) return
    let cls = ''
    try { cls = typeof el.className === 'string' ? el.className : String(el.className.baseVal || '') } catch(e) { cls = '' }
    const key = (el.tagName + '|' + cls + '|' + st.color).slice(0, 160)
    if (seen.has(key)) return
    seen.add(key)
    out.push({ tag: el.tagName, cls: cls.slice(0,80), color: st.color, bg: 'rgb(' + bg.map(Math.round).join(',') + ')', ratio: Number(ratio.toFixed(2)), sample: txt.slice(0, 26) })
  })
  return out.sort((a,b) => a.ratio - b.ratio).slice(0, 40)
}

/* night 模式「白块」审计：找出仍使用浅色硬编码背景且面积可见的容器。
   白块本身不低对比（深字白底对比度反而高），所以单靠文字审计抓不到，
   但它会让深色页看起来像贴了几块别的系统。 */
const BRIGHT = () => {
  const parse = s => {
    s = (s || '').trim()
    if (s.startsWith('#')) {
      let h = s.slice(1); if (h.length === 3) h = h.split('').map(c => c + c).join('')
      return [parseInt(h.slice(0,2),16), parseInt(h.slice(2,4),16), parseInt(h.slice(4,6),16), 1]
    }
    const m = s.match(/-?[\d.]+/g)
    if (!m) return [0,0,0,0]
    return [Number(m[0]), Number(m[1]), Number(m[2]), m[3] === undefined ? 1 : Number(m[3])]
  }
  const lum = rgb => {
    const a = rgb.slice(0,3).map(v => { const c=v/255; return c<=0.03928?c/12.92:Math.pow((c+0.055)/1.055,2.4) })
    return 0.2126*a[0]+0.7152*a[1]+0.0722*a[2]
  }
  const seen = new Set(), out = []
  document.querySelectorAll('.lay-content *').forEach(el => {
    if (el.tagName === 'IMG' || el.tagName === 'CANVAS') return   // 二维码必须保持白底
    const st = getComputedStyle(el)
    const c = parse(st.backgroundColor)
    const grad = st.backgroundImage || 'none'
    // 浅色渐变（background-color 透明）也是白块：结算页 .formula/.eg-box 就躲在这里
    const lightGradient = grad !== 'none' && grad.includes('linear-gradient') && c[3] < 0.5
    const solidLight = c[3] >= 0.95 && lum(c) >= 0.62
    if (!solidLight && !lightGradient) return
    const r = el.getBoundingClientRect()
    if (r.width < 50 || r.height < 18 || r.top > window.innerHeight || r.bottom < 0) return
    let cls = ''
    try { cls = typeof el.className === 'string' ? el.className : String(el.className.baseVal || '') } catch(e) { cls = '' }
    if (cls.includes('qr-box')) return   // 二维码容器必须保持白底才能扫码
    const key = (el.tagName + '|' + cls + '|' + st.backgroundColor).slice(0, 150)
    if (seen.has(key)) return
    seen.add(key)
    out.push({ tag: el.tagName, cls: cls.slice(0, 70), bg: st.backgroundColor,
      size: Math.round(r.width) + 'x' + Math.round(r.height),
      sample: (el.textContent || '').trim().slice(0, 22) })
  })
  return out.sort((a, b) => {
    const A = a.size.split('x').reduce((x, y) => x * y, 1)
    const B = b.size.split('x').reduce((x, y) => x * y, 1)
    return B - A
  }).slice(0, 25)
}

;(async () => {
  const browser = await chromium.launch({ channel: 'msedge' })
  const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 } })
  const page = await ctx.newPage()
  await page.goto(BASE + '/login', { waitUntil: 'domcontentloaded' })
  await page.waitForTimeout(800)
  await page.fill('input[placeholder="请输入账号"]', 'admin')
  await page.fill('input[placeholder="请输入密码"]', '123456')
  await page.click('button.submit'); await page.waitForTimeout(2200)
  // 切到 night 并持久化
  await page.evaluate(() => localStorage.setItem('ct_ui_mode', 'night'))
  await page.reload({ waitUntil: 'domcontentloaded' }); await page.waitForTimeout(1600)

  const pages = [
    '/admin/orders', '/admin/dashboard', '/admin/trace', '/admin/settlements',
    '/admin/users', '/admin/farmers', '/admin/canteens', '/admin/products',
    '/admin/warnings', '/admin/config', '/admin/logs'
  ]
  const report = {}, blocks = {}
  for (const p of pages) {
    await page.goto(BASE + p, { waitUntil: 'domcontentloaded' })
    await page.waitForTimeout(1700)
    report[p] = await page.evaluate(AUDIT)
    blocks[p] = await page.evaluate(BRIGHT)
    console.log('\n### ' + p + ' | 低对比文本 ' + report[p].length + ' 类 | 白块 ' + blocks[p].length + ' 类')
    report[p].forEach(x => console.log('  文本 ' + x.ratio + ' | ' + x.tag + '.' + x.cls + ' | ' + x.color + ' on ' + x.bg + ' | ' + x.sample))
    blocks[p].forEach(x => console.log('  白块 ' + x.tag + '.' + x.cls + ' | ' + x.bg + ' | ' + x.size + ' | ' + x.sample))
  }
  fs.mkdirSync(OUT, { recursive: true })
  fs.writeFileSync(path.join(OUT, 'night_contrast_audit.json'),
    JSON.stringify({ lowContrast: report, brightBlocks: blocks }, null, 2), 'utf-8')
  console.log('\n写出: docs/design_2026-11-10/ui_unify_acceptance/night_contrast_audit.json')
  await browser.close()
})().catch(e => { console.error('FATAL', e); process.exit(1) })
