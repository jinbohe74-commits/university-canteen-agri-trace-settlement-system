/* ==========================================================================
   待办中心（顶栏「待办」）
   --------------------------------------------------------------------------
   用户反馈「顶部栏待办这个功能形同虚设」：原来点一下只是 router.push 到某个列表页，
   徽标数字读的是登录态里不存在的字段（恒 0），质检员没有分支，
   所以既不知道有几件事、也不知道是哪几件。

   现在：数字与清单都来自 /api/common/todo-center（与各业务列表页同源），
   每条待办点下去直接打开单据工作台或对应页面。
   ========================================================================== */
import { reactive } from 'vue'
import { commonApi } from '@/api'

const state = reactive({
  open: false,
  loading: false,
  error: '',
  roleLabel: '',
  total: 0,
  groups: []
})

/** 与 workItemState() / notifState() 一致：函数取实例，禁止 `import { xState as s }`（L-055） */
export function todoCenterState() { return state }

export async function openTodoCenter() {
  state.open = true
  await loadTodoCenter()
}

export function closeTodoCenter() { state.open = false }

export async function loadTodoCenter() {
  state.loading = true
  state.error = ''
  try {
    const res = await commonApi.todoCenter()
    const d = res?.data ?? res
    state.groups = Array.isArray(d?.groups) ? d.groups : []
    state.total = Number(d?.total ?? 0)
    state.roleLabel = d?.roleLabel || ''
  } catch (e) {
    state.error = e?.message || '待办加载失败，请稍后重试'
    state.groups = []
    state.total = 0
  } finally {
    state.loading = false
  }
}
