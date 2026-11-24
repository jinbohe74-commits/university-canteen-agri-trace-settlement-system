<template>
  <div class="osa">
    <div class="osa-head">
      <div>
        <span class="osa-stage">当前阶段 · {{ stage.label }}</span>
        <span class="osa-next">下一步：{{ stage.next }}</span>
      </div>
      <span class="osa-hint">按当前状态可执行</span>
    </div>
    <div class="osa-actions">
      <el-button v-for="a in stage.actions" :key="a.key" size="small"
                 :type="a.type || 'default'" @click="run(a.key)">
        {{ a.label }}
      </el-button>
      <span v-if="!stage.actions.length" class="osa-none">该阶段无需你操作</span>
    </div>

    <!-- 业务单据详情：字段由接口返回对象自适应渲染，不写死猜测 -->
    <el-dialog v-model="detailOpen" :title="detailTitle" width="560px" append-to-body>
      <el-descriptions v-if="detailRows.length" :column="2" border size="small">
        <el-descriptions-item v-for="r in detailRows" :key="r.label" :label="r.label">
          {{ r.value }}
        </el-descriptions-item>
      </el-descriptions>
      <div v-else class="osa-empty">该单据暂无数据</div>
      <template #footer><el-button @click="detailOpen = false">关闭</el-button></template>
    </el-dialog>

    <!-- 实时轨迹：四角色共用 /common/orders/{id}/track -->
    <el-dialog v-model="trackOpen" :title="`实时轨迹 · ${order?.orderNo || ''}`"
               width="760px" append-to-body @closed="stopTrackPoll">
      <div v-if="trackLoading" class="osa-empty">正在读取轨迹…</div>
      <template v-else-if="track">
        <DeliveryMap :track="track" />
        <div class="osa-temps">
          <span class="osa-temp-title">冷链温度流水</span>
          <el-tag v-for="(t, i) in track.tempSeries || []" :key="i" size="small"
                  :type="t.alarm ? 'danger' : 'info'" effect="plain">
            {{ t.source === 'depart' ? '发车' : '上报' }} {{ t.temperature }}℃
          </el-tag>
          <span v-if="!(track.tempSeries || []).length" class="osa-empty">暂无温度上报记录</span>
        </div>
      </template>
      <div v-else class="osa-empty">暂无轨迹数据</div>
      <template #footer>
        <el-button @click="loadTrack">刷新位置</el-button>
        <el-button @click="trackOpen = false">关闭</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import DeliveryMap from './DeliveryMap.vue'
import { commonApi } from '@/api'
import { exportWorkbook } from '@/utils/exportWorkbook'
import { stamped } from '@/utils/export'
import { fmtDate, fmtQty } from '@/utils/format'

const props = defineProps({
  order: { type: Object, default: null },
  /** 部分接口返回的 order 里没有 id，用抽屉传入的订单 id 兜底 */
  orderId: { type: [Number, String], default: '' },
  delivery: { type: Object, default: null },
  inbound: { type: Object, default: null },
  settlement: { type: Object, default: null },
  evaluation: { type: Object, default: null },
  role: { type: String, default: '' }
})

const router = useRouter()
const detailOpen = ref(false)
const detailTitle = ref('')
const detailRows = ref([])
const trackOpen = ref(false)
const trackLoading = ref(false)
const track = ref(null)
let trackTimer = null

const status = computed(() => Number(props.order?.status || 0))

/** 字段名 → 中文，只展示真实返回的字段 */
const LABELS = {
  orderNo: '订单号', deliveryNo: '配送单号', settlementNo: '结算单号', batchNo: '批次号',
  status: '状态', vehicle: '车牌', driver: '司机', driverPhone: '司机电话',
  temperature: '车厢温度(℃)', mileage: '里程(km)', origin: '起点', destination: '终点',
  startTime: '发车时间', arriveTime: '到达时间',
  orderQuantity: '下单量(kg)', receiveQuantity: '实收(kg)', lossQuantity: '损耗(kg)',
  lossRate: '损耗率(%)', qualityResult: '质检结论', operatorName: '验收人',
  inboundTime: '入库时间', remark: '备注',
  totalAmount: '应结金额(元)', lossDeduction: '损耗扣款(元)', actualAmount: '实结金额(元)',
  settleTime: '结算时间', createTime: '创建时间',
  score: '评分', comment: '评价内容', evaluator: '评价人'
}
const rowsOf = (obj, keys) => {
  if (!obj) return []
  const list = (keys || Object.keys(obj)).filter(k =>
    obj[k] !== null && obj[k] !== undefined && obj[k] !== '' && typeof obj[k] !== 'object')
  return list.map(k => ({ label: LABELS[k] || k, value: String(obj[k]) }))
}

/** 状态 → 可执行动作。按角色裁剪，避免把别角色的入口塞给用户。 */
const STAGE = {
  10: { label: '待接单', next: '农户确认接单', actions: [
    { key: 'goOrders', label: '去接单', type: 'primary', roles: ['FARMER'] },
    { key: 'detailOrder', label: '查看订单信息' } ] },
  20: { label: '已接单', next: '农户发货', actions: [
    { key: 'goOrders', label: '去发货', type: 'primary', roles: ['FARMER'] },
    { key: 'detailOrder', label: '查看订单信息' } ] },
  30: { label: '待质检', next: '录入检测数据并自动判定', actions: [
    { key: 'goQuality', label: '去质检录入', type: 'primary', roles: ['INSPECTOR'] },
    { key: 'detailOrder', label: '查看订单信息' } ] },
  40: { label: '质检通过', next: '发车检查', actions: [
    { key: 'goDelivery', label: '去发车检查', type: 'primary', roles: ['INSPECTOR'] },
    { key: 'detailOrder', label: '查看订单信息' } ] },
  41: { label: '质检不合格', next: '复检或整批驳回', actions: [
    { key: 'goWarnings', label: '查看食安预警', type: 'primary', roles: ['ADMIN'] },
    { key: 'goQuality', label: '去复检', roles: ['INSPECTOR'] },
    { key: 'detailOrder', label: '查看订单信息' } ] },
  50: { label: '配送中', next: '扫码交付到食堂', actions: [
    { key: 'track', label: '查看实时轨迹', type: 'primary' },
    { key: 'detailDelivery', label: '配送单详情' },
    { key: 'exportDelivery', label: '导出配送记录' },
    { key: 'goDelivery', label: '去扫码交付', roles: ['INSPECTOR'] } ] },
  60: { label: '已送达', next: '食堂实秤验收入库', actions: [
    { key: 'goInbound', label: '去验收入库', type: 'primary', roles: ['PURCHASER'] },
    { key: 'detailDelivery', label: '配送单详情' } ] },
  70: { label: '已入库', next: '生成结算单并审核', actions: [
    { key: 'detailReceipt', label: '查看验收单', type: 'primary' },
    { key: 'exportReceipt', label: '导出验收单' },
    { key: 'goStock', label: '查看在库库存', roles: ['PURCHASER', 'ADMIN'] },
    { key: 'goAudit', label: '去结算审核', roles: ['ADMIN'] } ] },
  80: { label: '待结算', next: '管理员审核结算单', actions: [
    { key: 'detailSettlement', label: '查看结算单', type: 'primary' },
    { key: 'exportSettlement', label: '导出结算明细' },
    { key: 'goAudit', label: '去审核结算', roles: ['ADMIN'] },
    { key: 'goSettlement', label: '查看我的对账单', roles: ['FARMER'] } ] },
  90: { label: '已结算', next: '采购方评价并归档', actions: [
    { key: 'detailSettlement', label: '查看结算明细', type: 'primary' },
    { key: 'exportVoucher', label: '下载结算凭证' },
    { key: 'goEvaluation', label: '去评价本次供货', roles: ['PURCHASER'] },
    { key: 'goSettlement', label: '查看我的对账单', roles: ['FARMER'] } ] },
  100: { label: '已完成', next: '归档，可查看评价', actions: [
    { key: 'detailEvaluation', label: '查看供货评价', type: 'primary' },
    { key: 'exportVoucher', label: '下载结算凭证' } ] },
  99: { label: '已取消', next: '流程终止', actions: [
    { key: 'detailOrder', label: '查看订单信息' } ] }
}
const stage = computed(() => {
  const s = STAGE[status.value] || { label: '未知', next: '—', actions: [] }
  return {
    ...s,
    actions: s.actions.filter(a => !a.roles || a.roles.includes(props.role))
  }
})

const openDetail = (title, rows) => {
  detailTitle.value = title
  detailRows.value = rows
  detailOpen.value = true
}

const go = (path) => router.push(path)

const loadTrack = async () => {
  const oid = props.order?.id || props.orderId
  if (!oid) return
  trackLoading.value = true
  try {
    const res = await commonApi.orderTrack(oid)
    track.value = res?.data || null
  } catch (e) {
    ElMessage.error(e?.message || '轨迹读取失败')
  } finally {
    trackLoading.value = false
  }
}
const stopTrackPoll = () => {
  if (trackTimer) { clearInterval(trackTimer); trackTimer = null }
}
const openTrack = async () => {
  trackOpen.value = true
  await loadTrack()
  stopTrackPoll()
  trackTimer = setInterval(loadTrack, 5000)
}
onBeforeUnmount(stopTrackPoll)

const run = async (key) => {
  const o = props.order || {}
  switch (key) {
    case 'detailOrder':
      openDetail('订单信息', rowsOf(o, ['orderNo', 'status', 'createTime', 'productName',
        'quantity', 'totalAmount', 'farmerName', 'canteenName']))
      break
    case 'detailDelivery':
      openDetail('配送单详情', rowsOf(props.delivery))
      break
    case 'detailReceipt':
      openDetail(`验收单 · ${o.orderNo || ''}`, rowsOf(props.inbound))
      break
    case 'detailSettlement':
      openDetail(`结算单 · ${o.orderNo || ''}`, rowsOf(props.settlement))
      break
    case 'detailEvaluation':
      openDetail('供货评价', rowsOf(props.evaluation))
      break
    case 'track': await openTrack(); break
    case 'exportDelivery': {
      const d = props.delivery || {}
      if (!d.deliveryNo) { ElMessage.warning('暂无配送记录可导出'); break }
      if (!track.value) await loadTrack()
      const n = exportWorkbook(stamped('配送记录_' + (o.orderNo || ''), 'xlsx'), [
        { title: '配送单', note: '数据来源：订单详情 / 配送与入库',
          header: ['字段', '内容'],
          rows: rowsOf(d).map(r => [r.label, r.value]) },
        { title: '冷链温度流水',
          note: '发车温度 + 途中人工上报，按时间顺序',
          header: ['序号', '环节', '温度(℃)', '是否告警'],
          rows: (track.value?.tempSeries || []).map((t, i) =>
            [i + 1, t.source === 'depart' ? '发车' : '途中上报', t.temperature, t.alarm ? '是' : '否']) }
      ])
      ElMessage.success(`配送记录已导出（2 个工作表 · ${n} 行）`)
      break
    }
    case 'exportReceipt': {
      const r = props.inbound || {}
      if (!r.receiveQuantity && !r.orderQuantity) { ElMessage.warning('暂无验收单可导出'); break }
      const n = exportWorkbook(stamped('验收单_' + (o.orderNo || ''), 'xlsx'), [
        { title: '验收单', note: '数据来源：食堂实秤入库记录',
          header: ['字段', '内容'],
          rows: [
            ['订单号', o.orderNo || '—'], ['产品', o.productName || '—'],
            ['下单量(kg)', fmtQty(r.orderQuantity)], ['实收量(kg)', fmtQty(r.receiveQuantity)],
            ['损耗量(kg)', fmtQty(r.lossQuantity)], ['损耗率(%)', r.lossRate ?? '—'],
            ['质检结论', r.qualityResult || '以质检报告为准'],
            ['验收人', r.operatorName || '—'], ['入库时间', fmtDate(r.inboundTime)],
            ['损耗说明', r.remark || '—']
          ] }
      ])
      ElMessage.success(`验收单已导出（${n} 行）`)
      break
    }
    case 'exportSettlement':
    case 'exportVoucher': {
      const s = props.settlement || {}
      if (!s.settlementNo) { ElMessage.warning('暂无结算单可导出'); break }
      const r = props.inbound || {}
      const n = exportWorkbook(stamped('结算凭证_' + (s.settlementNo || o.orderNo || ''), 'xlsx'), [
        { title: '结算单', note: '数据来源：结算审核记录',
          header: ['字段', '内容'],
          rows: [
            ['订单号', o.orderNo || '—'], ['结算单号', s.settlementNo || '—'],
            ['应结金额(元)', s.totalAmount ?? '—'], ['损耗扣款(元)', s.lossDeduction ?? '—'],
            ['实结金额(元)', s.actualAmount ?? '—'], ['状态', s.status || '—'],
            ['审核人', s.auditor || '—'], ['结算时间', s.settleTime || '—']
          ] },
        { title: '扣款依据', note: '损耗扣款来自食堂实秤验收记录',
          header: ['项目', '数值', '说明'],
          rows: [
            ['下单量(kg)', fmtQty(r.orderQuantity), '订单约定数量'],
            ['实收量(kg)', fmtQty(r.receiveQuantity), '食堂实际过秤'],
            ['损耗量(kg)', fmtQty(r.lossQuantity), r.remark || '无损耗说明'],
            ['损耗率(%)', r.lossRate ?? '—', '损耗量 / 下单量']
          ] }
      ])
      ElMessage.success(`结算凭证已导出（2 个工作表 · ${n} 行）`)
      break
    }
    case 'goOrders': go(props.role === 'FARMER' ? '/farmer/orders' : '/purchaser/orders'); break
    case 'goQuality': go('/inspector/tasks'); break
    case 'goDelivery': go('/inspector/delivery'); break
    case 'goInbound': go('/purchaser/inbound'); break
    case 'goStock': go('/purchaser/stock'); break
    case 'goAudit': go('/admin/settlements'); break
    case 'goSettlement': go('/farmer/settlement'); break
    case 'goEvaluation': go('/purchaser/evaluation'); break
    case 'goWarnings': go('/admin/warnings'); break
  }
}
</script>

<style scoped>
.osa {
  margin: 0 0 16px;
  padding: 12px 14px;
  border: 1px solid var(--line);
  border-left: 3px solid var(--accent);
  border-radius: 8px;
  background: var(--surface-2);
}
.osa-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 10px; }
.osa-stage { font-size: var(--fs-base); font-weight: 700; color: var(--ink); }
.osa-next { display: block; margin-top: 3px; font-size: var(--fs-xs); color: var(--ink-2); }
.osa-hint { font-size: var(--fs-2xs); color: var(--muted); white-space: nowrap; }
.osa-actions { display: flex; flex-wrap: wrap; gap: 8px; margin-top: 11px; }
.osa-none, .osa-empty { font-size: var(--fs-sm); color: var(--muted); padding: 12px 0; }
.osa-temps { display: flex; align-items: center; flex-wrap: wrap; gap: 8px; margin-top: 12px; }
.osa-temp-title { font-size: var(--fs-sm); font-weight: 600; color: var(--ink-2); margin-right: 4px; }
</style>
