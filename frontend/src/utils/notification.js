/* ==========================================================================
   定向通知中心
   --------------------------------------------------------------------------
   顶栏「通知」只读 /api/notifications —— 每条都带明确的 recipient_id，后端 SQL
   就按当前用户过滤，前端拿不到别人的消息。点开面板 + 15 秒轮询保证及时性。
   分组（未读 / 已读）、单条删除、一键清空已读都在这里收口：
     · 未读是「还没处理的线索」，前端不给删除入口，后端 DELETE 也带 is_read=1，
       两层都挡住，避免只改前端被直接打接口绕过。
   SSE 仍由 utils/realtime.js 负责账号停用强制下线与数据变更刷新。
   ========================================================================== */
import { reactive } from 'vue'
import { notificationApi } from '@/api'

const state = reactive({
  items: [],
  unread: 0,
  read: 0,
  /** 'unread' | 'read' —— 面板当前分组 */
  tab: 'unread',
  loading: false
})

/** 与 realtimeState() 保持一致：统一用函数取同一个全局响应式实例 */
export function notifState() { return state }

let timer = null
let started = false

function applyCounts(d) {
  if (!d) return
  if (Number.isFinite(Number(d.unread))) state.unread = Number(d.unread)
  if (Number.isFinite(Number(d.read))) state.read = Number(d.read)
}

export async function refreshNotifications() {
  try {
    // 取 100 条：面板要同时支撑「未读 / 已读」两个分组，太少会让已读分组看起来是空的
    const res = await notificationApi.recent(100)
    const d = res?.data || {}
    state.items = Array.isArray(d.items) ? d.items : []
    state.unread = Number(d.unread || 0)
    state.read = Number(d.read || 0)
  } catch {
    // 通知是旁路，拉取失败不影响页面使用
  }
}

/** 单条已读：后端带 recipient_id 条件，不能替别人标记 */
export async function readNotification(id) {
  try {
    const res = await notificationApi.markRead(id)
    applyCounts(res?.data)
  } catch {
    // 忽略，跳转不受影响
  }
  state.items = state.items.map(x => x.id === id ? { ...x, isRead: 1 } : x)
}

export async function readAllNotifications() {
  try {
    const res = await notificationApi.markAllRead()
    applyCounts(res?.data)
    state.unread = 0
    state.items = state.items.map(x => ({ ...x, isRead: 1 }))
  } catch {
    // 忽略
  }
}

/**
 * 删除单条。未读通知不允许删除（前端先拦一道，后端 DELETE 也带 is_read = 1）。
 * 返回 true 表示删除成功。
 */
export async function deleteNotification(id) {
  const item = state.items.find(x => x.id === id)
  if (item && !Number(item.isRead)) return false
  const res = await notificationApi.remove(id)
  applyCounts(res?.data)
  state.items = state.items.filter(x => x.id !== id)
  return true
}

/** 一键删除全部已读；未读不受影响 */
export async function deleteReadNotifications() {
  const res = await notificationApi.removeRead()
  applyCounts(res?.data)
  state.items = state.items.filter(x => !Number(x.isRead))
  return Number(res?.data?.deleted || 0)
}

export function setNotifTab(tab) {
  state.tab = tab === 'read' ? 'read' : 'unread'
}

export function startNotifications() {
  if (started) return
  started = true
  refreshNotifications()
  timer = window.setInterval(refreshNotifications, 15000)
  // 业务数据变更后顺带刷新（SSE / 本地写操作都会派发这个事件）
  window.addEventListener('canteen:data-changed', refreshNotifications)
}

export function stopNotifications() {
  if (timer) window.clearInterval(timer)
  timer = null
  started = false
  window.removeEventListener('canteen:data-changed', refreshNotifications)
}
