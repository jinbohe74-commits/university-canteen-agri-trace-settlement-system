/* ==========================================================================
   单据工作台（Work Item）全局状态
   --------------------------------------------------------------------------
   第六轮整改的核心契约：通知「去处理」、看板待办、KPI 明细行点击，
   终点不再是「router.push(某个列表页)」，而是统一打开一个抽屉，
   由后端 /api/common/work-item 返回该单据的：
     摘要 fields / 具体问题 issues / 业务时间线 timeline / 可执行动作 actions

   所有入口共享同一份状态，避免每个页面各写一套「跳过去再自己找单」。
   ========================================================================== */
import { reactive } from 'vue'
import { commonApi } from '@/api'

const state = reactive({
  open: false,
  loading: false,
  error: '',
  scope: 'notification',
  role: '',
  title: '',
  items: [],
  activeKey: '',
  total: 0
})

/** 与 realtimeState() / notifState() 保持一致：统一用函数取全局响应式实例（L-049） */
export function workItemState() { return state }

let lastParams = {}

export async function openWorkItem(params = {}) {
  lastParams = { ...params }
  Object.assign(state, {
    open: true,
    loading: true,
    error: '',
    items: [],
    activeKey: '',
    total: 0,
    scope: params.scope || 'notification',
    title: params.title || '单据工作台'
  })
  await fetchWorkItem(params)
}

/** 动作执行后重新拉取，保证「审核通过」后问题清单立刻变成已处理 */
export async function reloadWorkItem() {
  if (!Object.keys(lastParams).length) return
  state.loading = true
  await fetchWorkItem(lastParams)
}

async function fetchWorkItem(params) {
  try {
    const res = await commonApi.workItem(params)
    const d = res?.data ?? res
    const items = Array.isArray(d?.items) ? d.items : []
    state.items = items
    state.total = Number(d?.total ?? items.length)
    state.role = d?.role || ''
    state.activeKey = items.length ? String(items[0].key || '') : ''
    state.error = items.length ? '' : '当前没有与之关联的具体单据'
  } catch (e) {
    state.error = e?.message || '单据信息加载失败，请稍后重试'
    state.items = []
  } finally {
    state.loading = false
  }
}

export function selectWorkItem(key) { state.activeKey = String(key) }
export function closeWorkItem() { state.open = false }
