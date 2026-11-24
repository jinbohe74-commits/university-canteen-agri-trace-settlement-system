import { reactive } from 'vue'
import { commonApi } from '@/api'

/**
 * 「怎么用」引导状态（第七轮）
 *
 * 为什么要有这个模块：用户反馈"我有点使用不明白"。走查 31 页后发现每页都只讲自己，
 * 没有任何地方回答"我在整条链的哪一步、今天先做哪件、做完谁接手"。
 * 这里把角色主线（/common/my-flow，数字全部来自真实业务表）缓存下来，供：
 *   1) GuideDrawer 展示五步主线；
 *   2) 顶栏「流程 x/5」定位当前位置；
 *   3) 首次登录自动弹出（每个角色只弹一次，避免每进一次都挡人）。
 */

const state = reactive({
  open: false,
  loading: false,
  error: '',
  data: null,
  loadedAt: 0
})

const TTL = 60 * 1000
const seenKey = (role) => 'ct_guide_seen_' + (role || 'UNKNOWN')

export function guideState() {
  return state
}

export async function loadGuide(force = false) {
  if (!force && state.data && Date.now() - state.loadedAt < TTL) return state.data
  state.loading = true
  state.error = ''
  try {
    const res = await commonApi.myFlow()
    state.data = res.data || null
    state.loadedAt = Date.now()
  } catch (e) {
    state.error = e?.message || '流程数据加载失败，请稍后重试'
  } finally {
    state.loading = false
  }
  return state.data
}

export async function openGuide() {
  state.open = true
  await loadGuide()
  markSeen()
}

export function closeGuide() {
  state.open = false
  markSeen()
}

export function markSeen() {
  const role = state.data?.role
  if (role) localStorage.setItem(seenKey(role), '1')
}

export const hasSeenGuide = (role) => localStorage.getItem(seenKey(role)) === '1'

/**
 * 首次登录自动打开：每个角色一次。
 * 只对「从登录页登录进来的会话」生效（sessionStorage.ct_guide_fresh），
 * 否则验收脚本 / 直接带 token 的调试环境会被抽屉挡住点击——
 * 上一版就是在这里把 41 条自动化断言挡成了超时。
 */
export async function maybeAutoOpenGuide(role) {
  const fresh = sessionStorage.getItem('ct_guide_fresh') === '1'
  sessionStorage.removeItem('ct_guide_fresh')
  if (!role || !fresh || hasSeenGuide(role)) return false
  const data = await loadGuide()
  if (!data) return false
  state.open = true
  markSeen()
  return true
}

/**
 * 当前路由对应流程第几步。
 * 同一个页面可能承担两步（如农户订单页同时是「接单」和「发货」），
 * 这时返回区间 { from, to }，顶栏显示「流程 3–4/5 步」，不假装精确。
 */
export function stepAt(path) {
  const steps = state.data?.steps || []
  const hits = steps.filter((s) => samePath(s.path, path))
  // 不在主线上（如农户工作台、消息页）：返回一个"总览"入口，首屏也要有锚点
  if (!hits.length) return steps.length ? { step: null, total: steps.length, title: '', count: 0 } : null
  return {
    from: Math.min(...hits.map((h) => h.no)),
    to: Math.max(...hits.map((h) => h.no)),
    total: steps.length,
    title: hits[0].title,
    count: hits.reduce((n, h) => n + Number(h.count || 0), 0),
    step: hits[0]
  }
}

const samePath = (a, b) => {
  if (!a || !b) return false
  const clean = (s) => String(s).split('?')[0].replace(/\/$/, '')
  return clean(a) === clean(b)
}

/** 引导里点的每一步都要真的能落到对应页面；把 query 一起带上，落到正确的标签页 */
export function stepTarget(step) {
  return step?.path || null
}
