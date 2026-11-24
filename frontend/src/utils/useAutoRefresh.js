/* ==========================================================================
   页面数据自动刷新 composable
   --------------------------------------------------------------------------
   收到其他角色的实时事件后重载当前页数据，但绝不打断用户正在做的事：
   - 弹窗 / 抽屉打开、输入框聚焦、标签页不可见 → 挂起，条件恢复后补刷
   - 事件防抖 400ms，避免批量操作引发连续请求
   - 只重载数据，不重建组件，因此分页页码与筛选条件都保留
   ========================================================================== */
import { onBeforeUnmount, onMounted } from 'vue'
import { readJson } from '@/utils/session'

// 只看本标签页登录的用户，避免多角色并存时把别人的操作当作"自己做的"而跳过刷新
const currentUserId = () => {
  const u = readJson('ct_user')
  return u ? u.id : null
}

function hasOpenOverlay() {
  return Array.from(document.querySelectorAll('.el-overlay, .el-drawer__container, .cmd-panel'))
    .some(el => getComputedStyle(el).display !== 'none' && getComputedStyle(el).visibility !== 'hidden')
}

function isTyping() {
  const el = document.activeElement
  if (!el) return false
  return /^(INPUT|TEXTAREA|SELECT)$/.test(el.tagName) || el.isContentEditable
}

export function useAutoRefresh(loader, options = {}) {
  const delay = options.delay || 400
  let timer = null
  let pending = false

  const safeRun = () => {
    if (document.hidden || hasOpenOverlay() || isTyping()) {
      pending = true
      return
    }
    pending = false
    // 静默刷新：后台重载数据时抑制整页 loading 遮罩，否则每次别人操作本页都会白闪一下
    const root = document.documentElement
    root.classList.add('is-silent-refresh')
    const done = () => setTimeout(() => root.classList.remove('is-silent-refresh'), 160)
    try {
      Promise.resolve(loader()).then(done, done)
    } catch (e) { done() }
  }

  const onEvent = (e) => {
    const ev = e.detail || {}
    // 自己刚提交的操作，页面内已刷新过，不再重复打断
    if (ev.operatorId && String(ev.operatorId) === String(currentUserId())) return
    clearTimeout(timer)
    timer = setTimeout(safeRun, delay)
  }

  const onVisible = () => { if (!document.hidden && pending) safeRun() }
  const onFocus = () => { if (pending) safeRun() }

  onMounted(() => {
    window.addEventListener('canteen:data-changed', onEvent)
    window.addEventListener('focus', onFocus)
    document.addEventListener('visibilitychange', onVisible)
  })

  onBeforeUnmount(() => {
    clearTimeout(timer)
    window.removeEventListener('canteen:data-changed', onEvent)
    window.removeEventListener('focus', onFocus)
    document.removeEventListener('visibilitychange', onVisible)
  })
}
