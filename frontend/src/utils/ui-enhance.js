/* ==========================================================================
   全局交互增强层（2026-11-10 重构）
   --------------------------------------------------------------------------
   设计原则（经验库 L-020）：业务 DOM 与数据契约一个字不改，所有交互以
   「叠加」方式注入；Vue 重新渲染页面时注入物随之销毁，下一轮自动重建。
   事件一律走 document 级委托，只绑定一次，不产生重复监听。
   当前覆盖：批量浮动条 / 数字滚动 / 时间线展开 / toast。
   ⚠️ 已移除、不要再改回来：
     · 即时筛选 / 状态 chip：DOM 层只过滤当前页 10 条，会让人以为服务端筛选失效；
     · 表头 DOM 排序：同上，排序应走后端；
     · 行内「复制」按钮：注入到 40px 复选框首列会撑破单元格（本轮用户反馈）。
   ========================================================================== */

let toastEl = null
let floatEl = null
let installed = false
/* 筛选一律交给后端接口，交互增益只保留批量条 / 数字滚动 / 时间线 / toast。 */
let filterState = { table: null }
let lastSubmitAt = new Map()

/* ---------------- toast ---------------- */
export function showToast(text) {
  if (!toastEl) {
    toastEl = document.createElement('div')
    toastEl.className = 'ui-toast'
    document.body.appendChild(toastEl)
  }
  toastEl.textContent = text
  toastEl.style.animation = 'none'
  void toastEl.offsetWidth
  toastEl.style.animation = ''
  clearTimeout(toastEl._timer)
  toastEl._timer = setTimeout(() => toastEl.remove(), 2200)
}

/* ---------------- 批量浮动条 ---------------- */
function ensureFloatBar() {
  if (floatEl && floatEl.isConnected) return floatEl
  floatEl = document.createElement('div')
  floatEl.className = 'ui-floatbar'
  floatEl.innerHTML =
    '<span>已选择 <b class="ui-float-count">0</b> 项</span>' +
    '<button type="button" data-ui-clear>取消选择</button>' +
    '<button type="button" data-ui-copy>复制所选单号</button>'
  document.body.appendChild(floatEl)
  return floatEl
}

function syncFloatBar() {
  const table = filterState.table
  const checked = table
    ? Array.from(table.querySelectorAll('.el-table__body tbody input[type="checkbox"]:checked'))
    : []
  const bar = ensureFloatBar()
  bar.querySelector('.ui-float-count').textContent = String(checked.length)
  bar.classList.toggle('on', checked.length > 0)
}

/* ---------------- 行内操作 ----------------
   ⚠️ 已移除「往每行首列塞一个复制按钮」的注入。
   原实现挂在 td:first-child 上，而首列通常是 40px 宽的多选框列，塞进去后
   即使 opacity:0 也照样占宽（inline-flex + margin-left + border-left），
   结果是首列内容被顶到第二行、表格看起来像错位（2026-11-10 用户截图反馈）。
   批量复制的需求由底部浮动条承担，不必污染每一行。 */

/** 取一行的业务单号：优先 .mono（单号列的通用类），退化为第一个有内容的单元格 */
function rowNo(tr) {
  const mono = tr.querySelector('.mono')
  if (mono && mono.innerText.trim()) return mono.innerText.trim().split(/\s+/)[0].slice(0, 64)
  const cells = Array.from(tr.querySelectorAll('td .cell'))
  for (const c of cells) {
    const t = (c.innerText || '').trim()
    if (t && /[A-Za-z0-9]/.test(t)) return t.split(/\s+/)[0].slice(0, 64)
  }
  return ''
}

async function copyText(text) {
  try {
    await navigator.clipboard.writeText(text)
  } catch (e) {
    const ta = document.createElement('textarea')
    ta.value = text
    ta.style.position = 'fixed'
    ta.style.opacity = '0'
    document.body.appendChild(ta)
    ta.select()
    try { document.execCommand('copy') } catch (_) { /* ignore */ }
    ta.remove()
  }
  showToast('已复制「' + text + '」')
}

/* ---------------- 数字滚动 ----------------
   只改「文本节点的值」，绝不重建 DOM。
   早期实现用的是 el.textContent = ...，那会把 Vue 管理的文本节点连同 <small> 后缀
   一起从 DOM 里摘掉；等接口数据到达时，Vue 更新的是那个已经游离的节点，
   页面上的数字就永远停在首屏值（大多数是 0）——农户货源页 KPI 全 0 就是这么来的。
   这里改为写 nodeValue，结构不动，Vue 后续数据更新照常生效。 */
function animateNumbers(root) {
  if (document.documentElement.classList.contains('reduce-motion')) return
  Array.from(root.querySelectorAll('.stat-value')).forEach((el) => {
    if (el.dataset.uiCounted) return
    el.dataset.uiCounted = '1'
    const textNode = Array.from(el.childNodes).find((n) => n.nodeType === 3 && n.nodeValue.trim())
    if (!textNode) return
    const raw = textNode.nodeValue
    const m = raw.match(/(-?[\d,]+(?:\.\d+)?)/)
    if (!m) return
    const target = Number(m[0].replace(/,/g, ''))
    if (!isFinite(target) || target === 0) return
    const start = performance.now()
    const dur = 720
    const prefix = raw.slice(0, m.index)
    const suffix = raw.slice(m.index + m[0].length)
    const decimals = (m[0].split('.')[1] || '').length
    const tick = (now) => {
      // 节点若已被 Vue 卸载/替换，立即停手，不要往游离节点上写
      if (!textNode.isConnected) return
      const p = Math.min(1, (now - start) / dur)
      const eased = 1 - Math.pow(1 - p, 3)
      const val = decimals ? (target * eased).toFixed(decimals) : String(Math.round(target * eased))
      textNode.nodeValue = prefix + val + suffix
      if (p < 1) requestAnimationFrame(tick)
    }
    requestAnimationFrame(tick)
  })
}

/* ---------------- 溯源时间线展开 ---------------- */
function bindTimeline(root) {
  Array.from(root.querySelectorAll('.timeline-item')).forEach((item) => {
    if (item.querySelector('.ui-timeline-extra')) return
    const title = item.querySelector('.timeline-title')
    const desc = item.querySelector('.timeline-desc')
    const time = item.querySelector('.timeline-time')
    if (!title) return
    const extra = document.createElement('div')
    extra.className = 'ui-timeline-extra'
    extra.textContent =
      '节点时间：' + ((time && time.innerText) || '—') + ' · 详情：' +
      ((desc && desc.innerText) || '该环节已记录并写入批次存证，点击其他节点可切换查看。')
    item.appendChild(extra)
    title.style.cursor = 'pointer'
    title.setAttribute('data-ui-timeline', '1')
  })
}

/* ---------------- 主入口：每次路由切换后调用 ---------------- */
export function enhancePage(root) {
  if (!root) return
  const page = root.querySelector ? root : document.querySelector('.lay-content')
  const scope = page && page.querySelector ? page : document.querySelector('.lay-content')
  if (!scope) return

  // 表格由异步接口渲染，会被多轮补齐；这里只挂真实增益交互，不碰筛选职责
  // 切换路由后旧表格已卸载：先清空引用，避免浮动条统计到上一页的行
  if (filterState.table && !filterState.table.isConnected) filterState.table = null
  const table = scope.querySelector('.el-table')
  if (table) {
    filterState.table = table
  } else {
    filterState.table = null
  }
  syncFloatBar()

  animateNumbers(scope)
  bindTimeline(scope)
}

/* ---------------- 全局事件委托（只装一次） ---------------- */
export function installEnhancements() {
  if (installed) return
  installed = true
  ensureFloatBar()

  document.addEventListener('click', (e) => {
    const t = e.target

    // 表头排序（DOM 层，不触碰 Vue 数据）
    const th = t.closest && t.closest('.el-table th.el-table__cell')
    if (th && th.closest('.el-table') === filterState.table) {
      const cellIndex = Array.from(th.parentElement.children).indexOf(th)
      const body = filterState.table.querySelector('.el-table__body tbody')
      if (body) {
        const rows = Array.from(body.querySelectorAll('tr'))
        const dir = th.dataset.uiSort === 'asc' ? 'desc' : 'asc'
        filterState.table.querySelectorAll('th.el-table__cell').forEach((x) => delete x.dataset.uiSort)
        th.dataset.uiSort = dir
        rows.sort((a, b) => {
          const va = (a.children[cellIndex]?.innerText || '').trim()
          const vb = (b.children[cellIndex]?.innerText || '').trim()
          const na = parseFloat(va.replace(/[,¥%\s]/g, ''))
          const nb = parseFloat(vb.replace(/[,¥%\s]/g, ''))
          const cmp = !isNaN(na) && !isNaN(nb) ? na - nb : va.localeCompare(vb, 'zh-CN')
          return dir === 'asc' ? cmp : -cmp
        })
        rows.forEach((r) => body.appendChild(r))
        showToast('已按「' + (th.innerText || '').trim() + '」' + (dir === 'asc' ? '升序' : '降序') + '排列')
      }
      return
    }

    // 浮动条操作
    if (t.closest && t.closest('[data-ui-copy]')) {
      const table = filterState.table
      const nos = table
        ? Array.from(table.querySelectorAll('.el-table__body tbody tr'))
            .filter((tr) => tr.querySelector('input[type="checkbox"]:checked'))
            .map(rowNo).filter(Boolean)
        : []
      if (nos.length) copyText(nos.join(', '))
      return
    }
    if (t.closest && t.closest('[data-ui-clear]')) {
      const table = filterState.table
      if (table) {
        table.querySelectorAll('input[type="checkbox"]:checked').forEach((box) => box.click())
        showToast('已取消选择')
      }
      return
    }

    // 时间线节点展开
    const tl = t.closest && t.closest('[data-ui-timeline]')
    if (tl) {
      const item = tl.closest('.timeline-item')
      item.parentElement.querySelectorAll('.timeline-item.ui-open').forEach((x) => {
        if (x !== item) x.classList.remove('ui-open')
      })
      item.classList.toggle('ui-open')
      return
    }

    // 防重复提交：同一主按钮 700ms 内二次点击直接拦截
    const btn = t.closest && t.closest('.el-button--primary')
    if (btn && !btn.closest('.cmd-panel') && !btn.closest('.ui-floatbar')) {
      const now = Date.now()
      const prev = lastSubmitAt.get(btn) || 0
      if (now - prev < 700) {
        e.stopPropagation()
        e.preventDefault()
        showToast('操作已提交，请勿重复点击')
        return
      }
      lastSubmitAt.set(btn, now)
    }
  }, true)

  // 勾选变化 → 批量浮动条
  document.addEventListener('change', (e) => {
    if (e.target && e.target.type === 'checkbox' && e.target.closest('.el-table')) {
      setTimeout(syncFloatBar, 0)
    }
  }, true)

  // 键盘：Esc 收起批量浮动条
  document.addEventListener('keydown', (e) => {
    if (e.key === 'Escape') {
      const bar = document.querySelector('.ui-floatbar.on')
      if (bar) bar.classList.remove('on')
    }
  })
}
