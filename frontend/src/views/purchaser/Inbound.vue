<template>
  <div class="page">
    <div class="page-head">
      <div>
        <h2 class="page-title">入库确认（生鲜损耗）</h2>
        <p class="page-sub">扫码核对批次，录入实收数量与质检结果，系统按损耗自动计算结算金额</p>
      </div>
      <div class="page-actions">
        <el-button plain @click="loadRecent"><el-icon><Refresh /></el-icon>刷新记录</el-button>
      </div>
    </div>

    <!-- 扫码 / 查询 -->
    <div class="card card-pad scan-card">
      <div class="scan-row">
        <el-input
          v-model="orderNoQuery"
          size="large"
          clearable
          placeholder="输入批次号或订单号，如 PC20261025F000024157 / DD202610120052"
          @keyup.enter="search"
        >
          <template #prefix><el-icon><Search /></el-icon></template>
        </el-input>
        <el-button type="primary" size="large" @click="search">查询</el-button>
        <el-button size="large" plain @click="scan"><el-icon><Camera /></el-icon>扫描二维码</el-button>
      </div>
      <div class="scan-tip">
        首屏已自动载入本食堂最近一笔「已送达」订单；支持按订单号或批次号精确查询，入库后订单自动流转至待结算。
      </div>
    </div>

    <!-- 入库成功提示 -->
    <div v-if="lastResult" class="card done-banner">
      <el-icon class="db-icon"><CircleCheckFilled /></el-icon>
      <div class="grow">
        <div class="db-title">入库成功，已自动生成结算单 {{ lastResult.settlementNo }}</div>
        <div class="db-sub">
          订单 {{ lastResult.orderNo }} · 实收 {{ fmtNum(lastResult.receiveQuantity) }} {{ lastResult.unit }} · 损耗
          {{ fmtNum(lastResult.lossQuantity) }} {{ lastResult.unit }}（{{ lastResult.lossRate }}%）· 实际结算
          <b>¥{{ fmtMoney(lastResult.actualAmount) }}</b>
        </div>
      </div>
      <span class="tag tag-green">待结算</span>
    </div>

    <div class="inbound-layout" v-loading="loading">
      <!-- 左：订单与录入 -->
      <div class="card">
        <div class="card-head">
          <div class="card-title">订单与批次信息</div>
          <div class="card-extra">
            <StatusTag v-if="order" type="order" :value="order.status" />
            <span v-else class="muted small">等待查询</span>
          </div>
        </div>

        <div class="card-pad">
          <template v-if="order">
            <div class="info-grid">
              <div class="info-item"><span class="k">订单号</span><span class="v mono">{{ order.orderNo }}</span></div>
              <div class="info-item"><span class="k">批次号</span><span class="v mono">{{ batchNo || '—' }}</span></div>
              <div class="info-item"><span class="k">产品</span><span class="v">{{ order.productName }}（{{ order.categoryName }}）</span></div>
              <div class="info-item"><span class="k">供货农户</span><span class="v">{{ order.farmerName }}</span></div>
              <div class="info-item">
                <span class="k">下单数量</span><span class="v">{{ fmtNum(order.quantity) }} {{ order.unit }}</span>
              </div>
              <div class="info-item">
                <span class="k">成交单价</span><span class="v">¥{{ fmtMoney(order.unitPrice) }} / {{ order.unit }}</span>
              </div>
              <div class="info-item">
                <span class="k">期望交付</span><span class="v">{{ fmtDate(order.expectedDelivery) }}</span>
              </div>
              <div class="info-item">
                <span class="k">质检结论</span>
                <span class="v">
                  <StatusTag v-if="qualityResultOfPreview" type="result" :value="qualityResultOfPreview" />
                  <span v-else class="muted">—</span>
                  <span v-if="data.quality" class="muted small"> · {{ data.quality.inspector }}</span>
                </span>
              </div>
            </div>

            <!-- 实收与损耗 -->
            <div class="sub-block">
              <div class="sub-title">实收与损耗录入</div>
              <div class="entry-row">
                <div class="entry-item">
                  <label>实收数量（{{ order.unit }}）</label>
                  <el-input-number
                    v-model="form.receiveQuantity"
                    :min="0"
                    :max="Number(order.quantity)"
                    :step="1"
                    :precision="1"
                    controls-position="right"
                    :disabled="!canInbound"
                    style="width: 100%"
                  />
                  <span class="muted small">下单 {{ fmtNum(order.quantity) }} {{ order.unit }}，支持部分收货</span>
                </div>
                <div class="entry-item">
                  <label>损耗数量（{{ order.unit }}）</label>
                  <div class="readonly-val" :class="{ warn: lossQty > 0 }">{{ fmtNum(lossQty) }} {{ order.unit }}</div>
                  <span class="muted small">下单 − 实收，系统自动核算</span>
                </div>
                <div class="entry-item">
                  <label>损耗率</label>
                  <div class="readonly-val" :class="{ warn: lossRate >= threshold }">
                    {{ lossRate.toFixed(1) }}%
                    <span v-if="lossRate >= threshold" class="tag tag-orange">达阈值</span>
                  </div>
                  <span class="muted small">预警阈值 {{ threshold }}%</span>
                </div>
              </div>

              <div class="entry-grid">
                <div class="field">
                  <label>质检结果</label>
                  <el-select v-model="form.qualityResult" :disabled="!canInbound" style="width: 100%">
                    <el-option label="质检合格" value="合格" />
                    <el-option label="质检不合格" value="不合格" />
                  </el-select>
                </div>
                <div class="field">
                  <label>入库时间</label>
                  <el-date-picker
                    v-model="form.inboundTime"
                    type="datetime"
                    format="YYYY-MM-DD HH:mm"
                    value-format="YYYY-MM-DDTHH:mm:ss"
                    placeholder="选择入库时间"
                    style="width: 100%"
                  />
                </div>
                <div class="field">
                  <label>验收人</label>
                  <el-input :model-value="store.displayName || '—'" readonly />
                </div>
                <div class="field">
                  <label>损耗 / 备注说明</label>
                  <el-input
                    v-model="form.remark"
                    type="textarea"
                    :rows="2"
                    maxlength="200"
                    show-word-limit
                    placeholder="如：运输途中压伤，损耗 20kg，已与农户确认"
                  />
                </div>
              </div>
            </div>
          </template>

          <div v-else class="empty">
            暂无待入库订单，请在上方输入批次号或订单号查询
          </div>
        </div>
      </div>

      <!-- 右：结算预览 -->
      <div class="card">
        <div class="card-head">
          <div class="card-title">结算金额预览</div>
          <div class="card-extra"><span class="tag tag-green">实时计算</span></div>
        </div>

        <div class="card-pad">
          <template v-if="order">
            <div class="settle-box">
              <div class="s-row">
                <span class="s-key">订单金额</span>
                <span class="s-formula">
                  {{ fmtNum(orderQty) }} × {{ fmtMoney(unitPrice) }} =
                  <b class="money">¥{{ fmtMoney(orderAmount) }}</b>
                </span>
              </div>
              <div class="s-row">
                <span class="s-key">损耗数量</span>
                <span class="s-formula">
                  {{ fmtNum(orderQty) }} − {{ fmtNum(receiveQty) }} =
                  <b>{{ fmtNum(lossQty) }} {{ order.unit }}</b>
                </span>
              </div>
              <div class="s-row">
                <span class="s-key">损耗扣款</span>
                <span class="s-formula">
                  {{ fmtNum(lossQty) }} × {{ fmtMoney(unitPrice) }} =
                  <b class="minus">−¥{{ fmtMoney(lossDeduction) }}</b>
                </span>
              </div>
              <div class="s-row">
                <span class="s-key">质检扣款</span>
                <span class="s-formula">
                  不合格按 20% 扣款 =
                  <b class="minus">−¥{{ fmtMoney(qualityDeduction) }}</b>
                </span>
              </div>

              <div class="s-divider"></div>

              <div class="s-total">
                <span class="st-label">实际结算金额</span>
                <span class="st-amount">¥{{ fmtMoney(actualAmount) }}</span>
              </div>
              <div class="s-sub">
                订单金额 ¥{{ fmtMoney(orderAmount) }} − 损耗扣款 ¥{{ fmtMoney(lossDeduction) }} − 质检扣款
                ¥{{ fmtMoney(qualityDeduction) }}
              </div>

              <div v-if="lossRate >= threshold" class="warn-bar">
                <el-icon><Warning /></el-icon>
                <span>损耗率 {{ lossRate }}% 已达阈值，确认后将自动生成结算单并扣减农户信用分 −2</span>
              </div>

              <div class="s-actions">
                <el-button plain @click="reject">拒收</el-button>
                <el-button type="primary" :loading="submitting" :disabled="!canInbound" @click="submit">
                  确认入库
                </el-button>
              </div>
            </div>
          </template>
          <div v-else class="empty">暂无结算预览，查询待入库订单后自动计算</div>
        </div>
      </div>
    </div>

    <!-- 近期入库记录 -->
    <div class="card recent-card">
      <div class="card-head">
        <div class="card-title">近期入库记录</div>
        <div class="card-extra">最近 {{ recent.length }} 笔 · 含损耗扣款与质检结论</div>
      </div>
      <div class="table-wrap">
        <el-table :data="recent" size="default" style="width: 100%">
          <el-table-column label="订单号" min-width="138">
            <template #default="{ row }"><span class="mono">{{ row.orderNo }}</span></template>
          </el-table-column>
          <el-table-column prop="productName" label="产品" width="100" />
          <el-table-column label="下单 / 实收" width="150">
            <template #default="{ row }">
              {{ fmtNum(row.orderQuantity) }} / {{ fmtNum(row.receiveQuantity) }} {{ row.unit }}
            </template>
          </el-table-column>
          <el-table-column label="实付金额" width="120" align="right">
            <template #default="{ row }"><span class="money">¥{{ fmtMoney(row.actualAmount) }}</span></template>
          </el-table-column>
          <el-table-column label="质检结果" width="100">
            <template #default="{ row }">
              <StatusTag v-if="row.qualityResult" type="result" :value="normResult(row.qualityResult)" />
              <span v-else class="muted">—</span>
            </template>
          </el-table-column>
          <el-table-column label="入库时间" min-width="128">
            <template #default="{ row }">{{ fmtDate(row.inboundTime) }}</template>
          </el-table-column>
          <template #empty><div class="empty">暂无入库记录</div></template>
        </el-table>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { purchaserApi } from '@/api'
import { useUserStore } from '@/store/user'
import StatusTag from '@/components/StatusTag.vue'
import { fmtDate, fmtMoney, fmtNum } from '@/utils/format'

const route = useRoute()
const store = useUserStore()

const loading = ref(false)
const submitting = ref(false)
const data = ref(null)
const recent = ref([])
const lastResult = ref(null)
const orderNoQuery = ref(String(route.query.orderNo || ''))

const form = reactive({
  receiveQuantity: null,
  qualityResult: '合格',
  inboundTime: '',
  remark: ''
})

const pad = (n) => String(n).padStart(2, '0')

const nowStr = () => {
  const d = new Date()
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(
    d.getMinutes()
  )}:${pad(d.getSeconds())}`
}

const round2 = (v) => Math.round((Number(v) + Number.EPSILON) * 100) / 100

const order = computed(() => data.value?.order || null)
const batchNo = computed(() => data.value?.batchNo || order.value?.batchNo || '')
const threshold = computed(() => Number(data.value?.lossRateThreshold ?? 5))
const canInbound = computed(() => data.value?.canInbound !== false)

const qualityResultOfPreview = computed(() => {
  const r = data.value?.quality?.result
  return r ? normResult(r) : ''
})

const orderQty = computed(() => Number(order.value?.quantity || 0))
const unitPrice = computed(() => Number(order.value?.unitPrice || 0))
const orderAmount = computed(() => round2(orderQty.value * unitPrice.value))
const receiveQty = computed(() => Number(form.receiveQuantity || 0))
const lossQty = computed(() => Math.max(0, round2(orderQty.value - receiveQty.value)))
const lossRate = computed(() =>
  orderQty.value ? round2((lossQty.value * 100) / orderQty.value) : 0
)
const lossDeduction = computed(() => round2(lossQty.value * unitPrice.value))
const qualityDeduction = computed(() =>
  form.qualityResult === '不合格' ? round2(orderAmount.value * 0.2) : 0
)
const actualAmount = computed(() =>
  Math.max(0, round2(orderAmount.value - lossDeduction.value - qualityDeduction.value))
)

function normResult(v) {
  if (!v) return ''
  return String(v).includes('不合格') ? '不合格' : '合格'
}

const initForm = () => {
  form.receiveQuantity = orderQty.value || null
  form.qualityResult = '合格'
  form.inboundTime = nowStr()
  form.remark = ''
}

const loadPreview = async (no) => {
  loading.value = true
  lastResult.value = null
  try {
    const res = await purchaserApi.inboundPreview(no || undefined)
    data.value = res.data || null
    if (no) orderNoQuery.value = no
    initForm()
  } catch (e) {
    data.value = null
  } finally {
    loading.value = false
  }
}

const loadRecent = async () => {
  try {
    const res = await purchaserApi.inboundRecent(5)
    recent.value = res.data || []
  } catch (e) {
    recent.value = []
  }
}

const search = () => {
  loadPreview(orderNoQuery.value.trim())
}

const scan = () => {
  ElMessage.info('演示环境请使用扫码枪或手动输入批次号 / 订单号后点击查询')
}

const reject = () => {
  ElMessage.warning('拒收需联系质检员现场处理，并登记异常上报单')
}

const submit = async () => {
  if (!order.value) return ElMessage.warning('请先查询待入库订单')
  if (!canInbound.value) return ElMessage.warning('该订单当前状态不可入库')
  const qty = Number(form.receiveQuantity || 0)
  if (qty <= 0) return ElMessage.warning('实收数量必须大于 0')
  if (qty > orderQty.value) return ElMessage.warning('实收数量不能大于下单数量')
  submitting.value = true
  try {
    const res = await purchaserApi.inbound({
      orderId: order.value.id,
      receiveQuantity: qty,
      qualityResult: form.qualityResult,
      remark: form.remark
    })
    const result = res.data || {}
    lastResult.value = {
      orderNo: order.value.orderNo,
      settlementNo: result.settlementNo || '—',
      unit: order.value.unit,
      receiveQuantity: qty,
      lossQuantity: result.lossQuantity ?? lossQty.value,
      lossRate: result.lossRate ?? lossRate.value,
      actualAmount: result.actualAmount ?? actualAmount.value
    }
    ElMessage.success('入库成功，已自动生成结算单 ' + (result.settlementNo || ''))
    data.value = null
    orderNoQuery.value = ''
    await loadRecent()
  } catch (e) {
    /* 错误提示由请求拦截器统一处理 */
  } finally {
    submitting.value = false
  }
}

onMounted(() => {
  loadRecent()
  loadPreview(String(route.query.orderNo || ''))
})
</script>

<style scoped>
.scan-card {
  margin-bottom: 14px;
}

.scan-row {
  display: flex;
  align-items: center;
  gap: 10px;
}

.scan-row .el-input {
  flex: 1;
}

.scan-row .el-button {
  flex-shrink: 0;
}

.scan-tip {
  margin-top: 10px;
  font-size: 11.5px;
  color: var(--c-muted-2);
  line-height: 1.6;
}

.done-banner {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 14px 18px;
  margin-bottom: 14px;
  border-color: #bbe6cb;
  background: linear-gradient(135deg, #f2fdf6, #e8f8ee);
}

.db-icon {
  font-size: 28px;
  color: var(--c-primary);
  flex-shrink: 0;
}

.db-title {
  font-size: 13.5px;
  font-weight: 600;
  color: #14532d;
}

.db-sub {
  margin-top: 4px;
  font-size: 12px;
  color: #3f6d55;
}

.db-sub b {
  color: #14532d;
  font-family: var(--font-num);
  font-size: 13px;
}

.inbound-layout {
  display: grid;
  grid-template-columns: minmax(0, 1.15fr) minmax(0, 1fr);
  gap: 14px;
  align-items: start;
  margin-bottom: 14px;
}

.info-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px 22px;
}

.info-item {
  display: flex;
  gap: 10px;
  font-size: 13px;
  align-items: baseline;
  min-width: 0;
}

.info-item .k {
  color: var(--c-muted);
  flex-shrink: 0;
  width: 62px;
  font-size: 12.5px;
}

.info-item .v {
  color: #1b2a24;
  font-weight: 500;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.sub-block {
  margin-top: 16px;
  padding-top: 14px;
  border-top: 1px dashed var(--c-border);
}

.sub-title {
  font-size: 13px;
  font-weight: 600;
  color: #111827;
  margin-bottom: 12px;
  display: flex;
  align-items: center;
  gap: 8px;
}

.sub-title::before {
  content: '';
  width: 3px;
  height: 12px;
  border-radius: 2px;
  background: linear-gradient(180deg, var(--c-primary-light), var(--c-primary-dark));
}

.entry-row {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12px;
}

.entry-item,
.field {
  display: flex;
  flex-direction: column;
  gap: 6px;
  min-width: 0;
}

.entry-item label,
.field label {
  font-size: 12.5px;
  color: var(--c-muted);
}

.readonly-val {
  height: 32px;
  display: flex;
  align-items: center;
  gap: 7px;
  padding: 0 11px;
  border-radius: 8px;
  background: #f7faf9;
  border: 1px solid var(--c-border);
  font-family: var(--font-num);
  font-size: 13.5px;
  font-weight: 600;
  color: #1b2a24;
}

.readonly-val.warn {
  color: #b45309;
  background: #fdf7ee;
  border-color: #f3e3c8;
}

.entry-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 14px 16px;
  margin-top: 14px;
}

.settle-box {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.s-row {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 14px;
  font-size: 12.5px;
}

.s-key {
  color: var(--c-muted);
  flex-shrink: 0;
}

.s-formula {
  font-family: var(--font-num);
  color: #374151;
  text-align: right;
}

.s-formula b {
  font-weight: 600;
}

.s-formula .minus {
  color: #b45309;
}

.s-divider {
  height: 1px;
  background: var(--c-border);
  margin: 2px 0;
}

.s-total {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 13px 15px;
  border-radius: 11px;
  background: linear-gradient(135deg, #f0fdf4, #e6f8ee);
  border: 1px solid #d6f0e0;
}

.st-label {
  font-size: 12.5px;
  color: #15803d;
  font-weight: 500;
}

.st-amount {
  font-family: var(--font-num);
  font-size: 26px;
  font-weight: 700;
  color: #14532d;
  letter-spacing: -0.6px;
}

.s-sub {
  font-size: 11.5px;
  color: var(--c-muted-2);
  line-height: 1.7;
}

.warn-bar {
  display: flex;
  align-items: flex-start;
  gap: 7px;
  padding: 10px 12px;
  border-radius: 9px;
  background: #fdf2e2;
  border: 1px solid #f3e3c8;
  color: #b45309;
  font-size: 11.5px;
  line-height: 1.65;
}

.warn-bar .el-icon {
  font-size: 14px;
  margin-top: 1px;
  flex-shrink: 0;
}

.s-actions {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-top: 4px;
}

.s-actions .el-button {
  flex: 1;
}

.recent-card {
  margin-bottom: 4px;
}
</style>
