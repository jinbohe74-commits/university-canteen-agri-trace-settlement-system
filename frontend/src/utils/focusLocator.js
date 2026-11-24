/* ==========================================================================
   页面级单据定位（Biz Focus）
   --------------------------------------------------------------------------
   单据工作台里的「去 XXX 页」动作会带 ?focus=<单号>。目标列表页用这一套
   统一处理：自动把该单号填进筛选、重新拉数据、并直接打开这一行的详情/处理弹窗。

   不逐页各写一套，是为了避免又变成「有的页能定位、有的页不能」的补丁式实现。
   ========================================================================== */
import { onMounted, watch } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'

const FOCUS_KEYS = [
  'orderNo', 'settlementNo', 'warningNo', 'batchNo', 'deliveryNo',
  'poNo', 'demandNo', 'farmerNo', 'target', 'content'
]

/**
 * 生成一个标准 focus handler。
 * @param {Object}  o
 * @param {Object}  o.query  页面筛选对象（reactive），需含 keyword
 * @param {Function}o.load   重新拉列表的异步函数
 * @param {Function}o.rows   返回当前行数组的函数
 * @param {Function}o.open   打开某一行详情/处理视图的函数
 */
export function makeListFocus({ query, load, rows, open, beforeLoad }) {
  return async (no) => {
    if (!no) return
    if (typeof beforeLoad === 'function') {
      beforeLoad(no)               // 页面用独立 ref 而非 reactive query 时走这里
    } else if (query) {
      query.keyword = no
      query.page = 1
      /* 状态/类型等旧筛选会把目标单挡掉，定位时一律清空 */
      for (const k of ['status', 'type', 'level', 'days', 'period', 'canteenId']) {
        if (k in query) query[k] = k === 'period' ? null : ''
      }
    }
    await load()
    const arr = (typeof rows === 'function' ? rows() : rows) || []
    const hit = arr.find(r => FOCUS_KEYS.some(k => r[k] && String(r[k]).includes(no)))
    if (hit && typeof open === 'function') {
      await open(hit)
      ElMessage.info('已自动定位到单据 ' + no)
    } else {
      ElMessage.warning('已按 ' + no + ' 筛选，但当前页未找到匹配行，请检查筛选条件')
    }
  }
}

/** 在页面 setup 里调用一次即可；同时兼容 ?orderNo= 这类已有参数 */
export function useFocusLocator(handler) {
  const route = useRoute()
  const apply = (v) => { if (v) handler(String(v)) }
  onMounted(() => apply(route.query.focus || route.query.orderNo))
  watch(() => route.query.focus, v => apply(v))
}
