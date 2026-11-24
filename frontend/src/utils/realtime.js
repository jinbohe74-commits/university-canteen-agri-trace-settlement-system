/* ==========================================================================
   跨角色实时数据刷新（2026-11-10 建，2026-11-11 与业务通知分离）
   --------------------------------------------------------------------------
   SSE 负责「立刻」，轮询负责「不丢」：事件表是唯一事实源，两者共用同一个游标。
   - 任何写操作由后端 AOP 落一条 CACHE_INVALIDATE 静默信号并广播给全部在线连接；
     它只触发 canteen:data-changed 让页面重新拉数据，不进通知中心、不加未读数。
   - 用户能看见的「发给我的通知」由 utils/notification.js 读 sys_notification，
     按 recipient_id 定向过滤，与这里的广播信号完全无关。
   - 账号被停用：定向 ACCOUNT_DISABLED 事件 → 立即清登录态并跳转登录页。
   - SSE 断开自动降级轮询并定时重连。
   ========================================================================== */

import { reactive } from 'vue'
import { readSession, readJson } from '@/utils/session'

const state = reactive({
  connected: false,
  mode: 'off',          // off | sse | polling
  lastId: 0,
  items: [],            // 通知中心最近事件
  unread: 0
})

let es = null
let pollTimer = null
let retryTimer = null
let stopping = false
let started = false

// 会话按标签页隔离：SSE 必须用「本标签页」的身份，不能用别的标签页登录的角色
const token = () => readSession('ct_token')
const currentUser = () => readJson('ct_user')
const authHeaders = () => ({
  'Content-Type': 'application/json',
  Authorization: 'Bearer ' + token()
})

export function realtimeState() { return state }
export function markRead() { state.unread = 0 }
export function markAllRead() { state.unread = 0 }

function emit(ev) {
  window.dispatchEvent(new CustomEvent('canteen:data-changed', { detail: ev }))
}

function pushItem(ev) {
  state.items.unshift(ev)
  if (state.items.length > 30) state.items.length = 30
  state.unread = Math.min(state.unread + 1, 99)
}

async function handleEvent(ev) {
  if (!ev || !ev.id || ev.id <= state.lastId) return
  state.lastId = ev.id

  // 账号被停用：定向事件只发给本人，立即下线（不等下一次请求 403）
  const me = currentUser()
  if (ev.eventType === 'ACCOUNT_DISABLED' && ev.targetUserId && me
      && String(ev.targetUserId) === String(me.id)) {
    stopRealtime()
    window.dispatchEvent(new CustomEvent('canteen:force-logout', {
      detail: { message: ev.content || '账号已被停用，当前登录已失效' }
    }))
    return
  }

  // 缓存失效信号是技术事件，不能在界面上变成一条「动态」或未读红点
  if (ev.eventType !== 'CACHE_INVALIDATE') pushItem(ev)
  emit(ev)
}

async function poll() {
  if (stopping || !token()) return
  try {
    const res = await fetch('/api/common/events?since=' + state.lastId + '&limit=50',
      { headers: authHeaders() })
    const body = await res.json()
    if (body && body.code === 200 && Array.isArray(body.data)) {
      for (const ev of body.data) await handleEvent(ev)
    } else if (res.status === 401) {
      stopRealtime()
    }
  } catch (e) { /* 网络抖动由下一轮补 */ }
}

async function loadRecent() {
  try {
    const res = await fetch('/api/common/events/recent?limit=20', { headers: authHeaders() })
    const body = await res.json()
    if (body && body.code === 200 && Array.isArray(body.data)) {
      state.items = body.data.filter(x => x.eventType !== 'CACHE_INVALIDATE').slice(0, 30)
      if (state.items.length) state.lastId = Math.max(state.lastId, ...state.items.map(x => x.id || 0))
    }
  } catch (e) { /* ignore */ }
}

async function connect() {
  if (stopping || !token()) return
  try {
    const res = await fetch('/api/common/events/ticket', {
      method: 'POST', headers: authHeaders()
    })
    const body = await res.json()
    if (!body || body.code !== 200 || !body.data || !body.data.ticket) throw new Error('ticket 获取失败')
    state.lastId = Math.max(state.lastId, Number(body.data.cursor || 0))

    es = new EventSource('/api/common/events/stream?ticket=' + encodeURIComponent(body.data.ticket))
    es.addEventListener('ready', () => { state.connected = true; state.mode = 'sse' })
    es.addEventListener('event', (e) => {
      try { handleEvent(JSON.parse(e.data)) } catch (err) { /* ignore */ }
    })
    es.onerror = () => {
      state.connected = false
      state.mode = 'polling'
      if (es) { es.close(); es = null }
      scheduleReconnect()
    }
  } catch (e) {
    state.connected = false
    state.mode = 'polling'
    scheduleReconnect()
  }
}

function scheduleReconnect() {
  if (stopping || retryTimer) return
  retryTimer = setTimeout(() => { retryTimer = null; connect() }, 3000)
}

export async function startRealtime() {
  if (started || !token()) return
  started = true
  stopping = false
  await loadRecent()
  await connect()
  // 始终保留低频轮询：SSE 被代理缓冲、浏览器休眠断线时用它补漏
  clearInterval(pollTimer)
  pollTimer = setInterval(poll, 4000)
}

export function stopRealtime() {
  stopping = true
  started = false
  state.connected = false
  state.mode = 'off'
  if (es) { es.close(); es = null }
  clearInterval(pollTimer); pollTimer = null
  clearTimeout(retryTimer); retryTimer = null
}
