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
        下方会列出本食堂全部「已送达」待验收订单，按到货先后排；也可以扫批次号 / 输单号直接定位某一单。入库后订单自动流转至待结算。
      </div>
    </div>

    <!-- 待入库清单：原来这一页只会自动带出一笔，处理完就清空，采购员得自己记住下一笔单号再手输，
         等于把"今天还有哪些货要收"交给人的记忆。这里把本食堂全部已送达订单一次列出来。 -->
    <div class="card pending-card">
      <div class="card-head">
        <div class="card-title">
          待入库订单
          <span class="pc-count">{{ pending.total }}</span>
        </div>
        <div class="card-extra">
          <span v-if="pending.total > 0">按到货先后排序，先到的先验（生鲜不能压着）</span>
          <span v-else>当前没有已送达待验收的订单</span>
        </div>
      </div>

      <div v-if="pending.items.length" class="pc-list">
        <button
          v-for="(p, i) in pending.items"
          :key="p.orderId"
          type="button"
          class="pc-item"
          :class="{ on: order && order.id === p.orderId }"
          @click="pick(p)"
        >
          <span class="pc-idx">{{ i + 1 }}</span>
          <span class="grow pc-main">
            <span class="pc-l1">
              <b class="mono">{{ p.orderNo }}</b>
              <span class="pc-prod">{{ p.productName }}</span>
              <span v-if="p.qualityAbnormal" class="tag tag-red">质检不合格</span>
              <span v-if="p.coldAbnormal" class="tag tag-orange">冷链超温</span>
            </span>
            <span class="pc-l2">
              {{ p.farmerName }} · {{ fmtNum(p.quantity) }} {{ p.unit }} · ¥{{ fmtMoney(p.totalAmount) }}
              <span class="muted">· 到货 {{ fmtDate(p.arriveTime) || '—' }}</span>
            </span>
          </span>
          <span class="pc-side">
            <span class="pc-temp" :class="{ bad: p.coldAbnormal }">
              {{ p.temperature == null ? '无温度记录' : p.temperature + '℃' }}
            </span>
            <span class="pc-go">{{ order && order.id === p.orderId ? '正在录入' : '去录入 ›' }}</span>
          </span>
        </button>
      </div>
      <div v-else class="pc-empty">
        没有待入库的订单。货到之后（配送员点击"已送达"）会自动出现在这里，也会推到顶栏「待办」。
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
          <div class="card-title">
            订单与批次信息
            <span v-if="order && currentIndex >= 0 && pending.total > 0" class="pc-pos">
              第 {{ currentIndex + 1 }} / {{ pending.total }} 笔
            </span>
          </div>
          <div class="card-extra">
            <StatusTag v-if="order" type="order" :value="order.status" />
            <span v-else class="muted small">等待查询</span>
            <el-button v-if="nextPending" size="small" plain @click="pick(nextPending)">
              先跳过，看下一笔
            </el-button>
          </div>
        </div>

        <div class="card-pad">
          <!-- 不能入库时把原因说清楚：整屏表单被禁用却不说为什么，用户只会以为系统坏了 -->
          <div v-if="order && !canInbound" class="block-bar">
            <el-icon><WarningFilled /></el-icon>
            <div class="bb-text">
              该单当前为
              <StatusTag type="order" :value="order.status" />
              ，还没走到「已送达」，因此暂不能录入实收与确认入库。
              <span v-if="order.status === 30 || order.status === 50" class="muted">
                需要质检员完成批次质检并放行配送后才能收货。
              </span>
            </div>
            <el-button size="small" plain @click="goOrders">查看我的订单</el-button>
          </div>

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
                    class="pc-entry"
                    :min="0"
                    :max="Number(order.quantity)"
                    :step="1"
                    :precision="1"
                    controls-position="right"
                    :disabled="!canInbound"
                    placeholder="实秤后录入"
                    style="width: 100%"
                  />
                  <span class="muted small">
                    下单 {{ fmtNum(order.quantity) }} {{ order.unit }}；请按实秤录入，不再默认填下单量
                  </span>
                </div>
                <div class="entry-item">
                  <label>损耗数量（{{ order.unit }}）</label>
                  <div class="readonly-val" :class="{ warn: hasReceive && lossQty > 0 }">
                    {{ hasReceive ? fmtNum(lossQty) + ' ' + order.unit : '待录入实收' }}
                  </div>
                  <span class="muted small">下单 − 实收，系统自动核算</span>
                </div>
                <div class="entry-item">
                  <label>损耗率</label>
                  <div class="readonly-val" :class="{ warn: hasReceive && lossRate >= threshold }">
                    {{ hasReceive ? lossRate.toFixed(1) + '%' : '—' }}
                    <span v-if="hasReceive && lossRate >= threshold" class="tag tag-orange">达阈值</span>
                  </div>
                  <span class="muted small">预警阈值 {{ threshold }}%</span>
                </div>
              </div>

              <div class="entry-grid">
                <div class="field">
                  <label>质检结论（来自质检报告，不可改）</label>
                  <div class="qc-readonly" :class="{ bad: qualityResultOfPreview === '不合格' }">
                    <span v-if="qualityResultOfPreview">{{ qualityResultOfPreview }}</span>
                    <span v-else class="muted">暂无质检报告</span>
                    <span v-if="data?.quality?.inspector" class="qc-meta">
                      {{ data.quality.inspector }} ·
                      {{ String(data.quality.inspectTime || '').replace('T', ' ').slice(0, 16) }}
                    </span>
                  </div>
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
                  <label>
                    损耗原因 / 入库备注
                    <span v-if="hasReceive && lossQty > 0" class="req-mark">有损耗必填</span>
                  </label>
                  <el-input
                    v-model="form.remark"
                    type="textarea"
                    :rows="2"
                    maxlength="200"
                    show-word-limit
                    :placeholder="hasReceive && lossQty > 0
                      ? '必须说明损耗原因（不少于 5 字），如：运输途中压伤 20kg，已与农户确认'
                      : '无损耗可留空；如有异常请说明'"
                  />
                  <span v-if="hasReceive && lossQty > 0 && form.remark.trim().length < 5" class="field-warn">
                    还差 {{ 5 - form.remark.trim().length }} 字：损耗原因会写进结算单，后续对账要看
                  </span>
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
                  <template v-if="hasReceive">
                    {{ fmtNum(orderQty) }} − {{ fmtNum(receiveQty) }} =
                    <b>{{ fmtNum(lossQty) }} {{ order.unit }}</b>
                  </template>
                  <span v-else class="muted">待录入实收数量</span>
                </span>
              </div>
              <div class="s-row">
                <span class="s-key">损耗扣款</span>
                <span class="s-formula">
                  <template v-if="hasReceive">
                    {{ fmtNum(lossQty) }} × {{ fmtMoney(unitPrice) }} =
                    <b class="minus">−¥{{ fmtMoney(lossDeduction) }}</b>
                  </template>
                  <span v-else class="muted">录入实收后自动计算</span>
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
                <span class="st-amount">{{ hasReceive ? '¥' + fmtMoney(actualAmount) : '待录入实收' }}</span>
              </div>
              <div class="s-sub">
                {{ hasReceive
                  ? `订单金额 ¥${fmtMoney(orderAmount)} − 损耗扣款 ¥${fmtMoney(lossDeduction)} − 质检扣款 ¥${fmtMoney(qualityDeduction)}`
                  : '录入实收数量后自动核算扣款与实际结算金额' }}
              </div>

              <div v-if="hasReceive && lossRate >= threshold" class="warn-bar">
                <el-icon><Warning /></el-icon>
                <span>损耗率 {{ lossRate }}% 已达阈值，确认后将自动生成结算单并扣减农户信用分 −2</span>
              </div>

              <div class="s-actions">
                <el-button plain @click="reject">拒收</el-button>
                <el-button type="primary" :loading="submitting" :disabled="!canInbound || !hasReceive" @click="submit">
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
import { computed, nextTick, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { purchaserApi } from '@/api'
import { useUserStore } from '@/store/user'
import StatusTag from '@/components/StatusTag.vue'
import { fmtDate, fmtMoney, fmtNum } from '@/utils/format'
import { useAutoRefresh } from '@/utils/useAutoRefresh'
import { loadTodoCenter } from '@/utils/todoCenter'

const route = useRoute()
const router = useRouter()
const goOrders = () => router.push('/purchaser/orders')
const store = useUserStore()

const loading = ref(false)
const submitting = ref(false)
const data = ref(null)
const recent = ref([])
const lastResult = ref(null)
const orderNoQuery = ref(String(route.query.orderNo || ''))
/* 本食堂全部「已送达」订单：入库页的核心从"单笔查询"变成"待办清单逐笔处理" */
const pending = reactive({ total: 0, items: [] })

const form = reactive({
  receiveQuantity: null,
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
const currentIndex = computed(() => {
  if (!order.value) return -1
  return pending.items.findIndex((p) => p.orderId === order.value.id)
})
/* 下一笔：清单里排在当前单之前的都不算（已经处理或被跳过），只取当前之后的第一条 */
const nextPending = computed(() => {
  const list = pending.items
  if (!list.length) return null
  const i = currentIndex.value
  if (i < 0) return list[0]
  return list[i + 1] || null
})
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
/**
 * 实收是否已录入。
 * 原来实收默认预填下单量，页面一进来就是「0 损耗」；改成空值后如果不加这个开关，
 * 空值会被当成 0 算出「损耗 100%、实结 ¥0.00」——比原来更误导。
 * 未录入时所有损耗/金额一律显示「待录入」，确认按钮也不可点。
 */
const hasReceive = computed(() =>
  form.receiveQuantity !== null && form.receiveQuantity !== undefined && form.receiveQuantity !== ''
)
const lossQty = computed(() => Math.max(0, round2(orderQty.value - receiveQty.value)))
const lossRate = computed(() =>
  orderQty.value ? round2((lossQty.value * 100) / orderQty.value) : 0
)
const lossDeduction = computed(() => round2(lossQty.value * unitPrice.value))
const qualityDeduction = computed(() =>
  qualityResultOfPreview.value === '不合格' ? round2(orderAmount.value * 0.2) : 0
)
const actualAmount = computed(() =>
  Math.max(0, round2(orderAmount.value - lossDeduction.value - qualityDeduction.value))
)

function normResult(v) {
  if (!v) return ''
  return String(v).includes('不合格') ? '不合格' : '合格'
}

/**
 * 实收数量不再预填下单量。
 * 预填等于「点一下就是零损耗」，实秤这一环被绕过去了——用户反馈的
 * 「随便点点就行」在入库环节就体现在这里。
 */
const initForm = () => {
  form.receiveQuantity = null
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

const loadPending = async () => {
  try {
    const res = await purchaserApi.inboundPending(50)
    const d = res.data || {}
    pending.items = Array.isArray(d.items) ? d.items : []
    pending.total = Number(d.total ?? pending.items.length)
  } catch (e) {
    pending.items = []
    pending.total = 0
  }
}

/** 清单点一行：直接载入这一单，不再要求用户输单号 */
const pick = async (item) => {
  if (!item) return
  await loadPreview(item.orderNo)
  // 焦点落到实收数量输入框，扫码/实秤后可以一路键盘操作
  nextTick(() => {
    const el = document.querySelector('.pc-entry input, .entry-grid input')
    if (el && !el.disabled) el.focus()
  })
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

/* 扫枪本质是键盘输入：点这里把焦点放进查询框，扫完自动回车查询。
   原来只弹一句"演示环境请手动输入"，属于点了没用的假按钮。 */
const scan = () => {
  const el = document.querySelector('.scan-row input')
  if (!el) return
  el.focus()
  if (el.select) el.select()
  ElMessage.success('已就绪：用扫枪对准货箱条码扫一下，扫完自动带出这一单')
}

const reject = () => {
  ElMessage.warning('拒收需联系质检员现场处理，并登记异常上报单')
}

const submit = async () => {
  if (!order.value) return ElMessage.warning('请先查询待入库订单')
  if (!canInbound.value) return ElMessage.warning('该订单当前状态不可入库')
  if (form.receiveQuantity === null || form.receiveQuantity === undefined) {
    return ElMessage.warning('请先实秤并录入实收数量')
  }
  const qty = Number(form.receiveQuantity || 0)
  if (qty <= 0) return ElMessage.warning('实收数量必须大于 0')
  if (qty > orderQty.value) return ElMessage.warning('实收数量不能大于下单数量')
  // 有损耗必须写清原因：这条会写进结算单，后续对账、追责都要看
  if (lossQty.value > 0 && form.remark.trim().length < 5) {
    ElMessage.warning('存在损耗，请说明损耗原因（不少于 5 字），已帮你定位到备注框')
    // 只弹一句 toast 用户还得自己找是哪一栏——直接把人送到输入框
    nextTick(() => {
      const el = document.querySelector('.entry-grid textarea')
      if (el) { el.focus(); el.scrollIntoView({ block: 'center', behavior: 'smooth' }) }
    })
    return
  }
  // 差异较大时再确认一次，避免手滑输错少一个 0
  if (lossRate.value >= threshold.value) {
    try {
      await ElMessageBox.confirm(
        `实收 ${fmtNum(qty)} ${order.value.unit}，损耗 ${fmtNum(lossQty.value)} ${order.value.unit}` +
        `（${lossRate.value}%），已达预警阈值 ${threshold.value}%。确认后自动生成结算单并扣减农户信用 -2。`,
        '损耗已达阈值',
        { type: 'warning', confirmButtonText: '确认入库', cancelButtonText: '返回核对' }
      )
    } catch (e) {
      return
    }
  }
  submitting.value = true
  try {
    const res = await purchaserApi.inbound({
      orderId: order.value.id,
      receiveQuantity: qty,
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

    // 处理完一笔不能把页面清空让人自己找下一笔：先记住"接下来该验哪一单"
    const nextNo = nextPending.value?.orderNo || ''
    orderNoQuery.value = ''
    await loadRecent()
    await loadPending()

    // 待办徽标跟着减一，否则顶栏还写着 3 件，用户会以为没成功
    loadTodoCenter()

    if (nextNo) {
      await loadPreview(nextNo)
      ElMessage.info('已自动切到下一笔 ' + nextNo + '，还剩 ' + pending.total + ' 笔待入库')
    } else {
      data.value = null
      ElMessage.success('待入库订单已全部处理完')
    }
  } catch (e) {
    /* 错误提示由请求拦截器统一处理 */
  } finally {
    submitting.value = false
  }
}

useAutoRefresh(() => { loadRecent(); loadPending() })
onMounted(async () => {
  loadRecent()
  await loadPending()
  // 从待办/通知进来：统一认 ?focus=<订单号>（老入口可能用 ?orderNo=，一并兼容）；
  // 都没带就从清单第一笔开始，不用用户自己找
  const no = String(route.query.focus || route.query.orderNo || '') || pending.items[0]?.orderNo || ''
  if (no) loadPreview(no)
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
  font-size: var(--fs-xs);
  color: var(--c-muted-2);
  line-height: 1.6;
}

.block-bar {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 14px;
  padding: 10px 12px;
  border-radius: 10px;
  background: var(--warn-soft);
  border: 1px solid var(--warn);
  color: var(--ink);
  font-size: var(--fs-sm);
  line-height: 1.6;
}

.block-bar .el-icon {
  color: var(--warn);
  font-size: var(--fs-lg);
  flex: none;
}

.block-bar .bb-text {
  flex: 1;
}

.done-banner {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 14px 18px;
  margin-bottom: 14px;
  border-color: var(--accent-soft);
  background: linear-gradient(135deg, #f2fdf6, #e8f8ee);
}

.db-icon {
  font-size: var(--fs-display);
  color: var(--c-primary);
  flex-shrink: 0;
}

.db-title {
  font-size: var(--fs-base);
  font-weight: 600;
  color: var(--ink);
}

.db-sub {
  margin-top: 4px;
  font-size: var(--fs-sm);
  color: #3f6d55;
}

.db-sub b {
  color: var(--ink);
  font-family: var(--font-num);
  font-size: var(--fs-base);
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
  font-size: var(--fs-base);
  align-items: baseline;
  min-width: 0;
}

.info-item .k {
  color: var(--c-muted);
  flex-shrink: 0;
  width: 62px;
  font-size: var(--fs-sm);
}

.info-item .v {
  color: var(--ink);
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
  font-size: var(--fs-base);
  font-weight: 600;
  color: var(--ink);
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
  font-size: var(--fs-sm);
  color: var(--c-muted);
}

.readonly-val {
  height: 32px;
  display: flex;
  align-items: center;
  gap: 7px;
  padding: 0 11px;
  border-radius: 8px;
  background: var(--surface-2);
  border: 1px solid var(--c-border);
  font-family: var(--font-num);
  font-size: var(--fs-base);
  font-weight: 600;
  color: var(--ink);
}

.readonly-val.warn {
  color: var(--warn);
  background: #fdf7ee;
  border-color: #f3e3c8;
}

/* 质检结论只读展示：原来是个可自由选择的下拉，选了也不生效 */
.qc-readonly {
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: 6px 11px;
  border-radius: 8px;
  background: var(--surface-2);
  border: 1px solid var(--line);
  font-size: var(--fs-base);
  font-weight: 600;
  color: var(--ok);
  min-height: 32px;
  justify-content: center;
}

.qc-readonly.bad {
  color: var(--danger);
  border-color: var(--danger);
  background: color-mix(in srgb, var(--danger) 7%, transparent);
}

.qc-meta {
  font-size: var(--fs-2xs);
  font-weight: 400;
  color: var(--muted);
}

.req-mark {
  margin-left: 6px;
  font-size: var(--fs-2xs);
  color: var(--danger);
  font-weight: 600;
}

.field-warn {
  font-size: var(--fs-2xs);
  color: var(--danger);
  line-height: 1.4;
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
  font-size: var(--fs-sm);
}

.s-key {
  color: var(--c-muted);
  flex-shrink: 0;
}

.s-formula {
  font-family: var(--font-num);
  color: var(--ink-2);
  text-align: right;
}

.s-formula b {
  font-weight: 600;
}

.s-formula .minus {
  color: var(--warn);
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
  background: linear-gradient(135deg, var(--surface-2), var(--accent-soft));
  border: 1px solid var(--accent-soft);
}

.st-label {
  font-size: var(--fs-sm);
  color: var(--accent);
  font-weight: 500;
}

.st-amount {
  font-family: var(--font-num);
  font-size: var(--fs-display);
  font-weight: 700;
  color: var(--ink);
  letter-spacing: -0.6px;
}

.s-sub {
  font-size: var(--fs-xs);
  color: var(--c-muted-2);
  line-height: 1.7;
}

.warn-bar {
  display: flex;
  align-items: flex-start;
  gap: 7px;
  padding: 10px 12px;
  border-radius: 9px;
  background: var(--warn-soft);
  border: 1px solid #f3e3c8;
  color: var(--warn);
  font-size: var(--fs-xs);
  line-height: 1.65;
}

.warn-bar .el-icon {
  font-size: var(--fs-base);
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

/* ---------------- 待入库清单 ---------------- */
.pending-card { margin-bottom: 14px; }
.pc-count {
  font-family: var(--font-num); font-size: var(--fs-2xs); font-weight: 700;
  color: #fff; background: var(--warn); border-radius: 999px; padding: 0 7px;
  margin-left: 6px; vertical-align: 1px;
}
.pc-pos {
  font-size: var(--fs-2xs); font-weight: 500; color: var(--accent);
  background: var(--accent-soft); border-radius: 999px; padding: 1px 8px; margin-left: 8px;
}
.pc-list { padding: 6px 10px 10px; }
.pc-item {
  display: flex; align-items: center; gap: 10px; width: 100%; text-align: left;
  background: var(--surface); border: 1px solid var(--line); border-radius: var(--radius-md);
  padding: 9px 12px; margin-bottom: 7px; cursor: pointer; transition: all .14s var(--ease);
}
.pc-item:last-child { margin-bottom: 0; }
.pc-item:hover { border-color: var(--accent); background: var(--accent-soft); }
.pc-item.on { border-color: var(--accent); background: var(--accent-soft); box-shadow: inset 3px 0 0 var(--accent); }
.pc-idx {
  flex: none; width: 20px; height: 20px; border-radius: 50%; display: inline-flex;
  align-items: center; justify-content: center; font-family: var(--font-num);
  font-size: var(--fs-2xs); color: var(--muted); background: var(--surface-2); border: 1px solid var(--line);
}
.pc-item.on .pc-idx { color: #fff; background: var(--accent); border-color: var(--accent); }
.pc-main { display: flex; flex-direction: column; gap: 3px; min-width: 0; }
.pc-l1 { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; font-size: var(--fs-sm); }
.pc-l1 b { color: var(--ink); }
.pc-prod { color: var(--ink); }
.pc-l2 { font-size: var(--fs-xs); color: var(--ink-2); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.pc-side { flex: none; display: flex; flex-direction: column; align-items: flex-end; gap: 3px; }
.pc-temp { font-size: var(--fs-2xs); font-family: var(--font-num); color: var(--ok); }
.pc-temp.bad { color: var(--danger); font-weight: 700; }
.pc-go { font-size: var(--fs-xs); color: var(--accent); font-weight: 600; }
.pc-empty { padding: 16px 18px 20px; font-size: var(--fs-xs); color: var(--muted); }
</style>
