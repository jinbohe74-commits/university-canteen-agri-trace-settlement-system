<template>
  <div class="page" v-loading="loading">
    <div class="page-head">
      <div>
        <h2 class="page-title">智能匹配</h2>
        <p class="page-sub">{{ subtitle }}</p>
      </div>
      <div class="page-actions">
        <el-button plain :disabled="!demandId" @click="loadMatch">
          <el-icon><Refresh /></el-icon>重新匹配
        </el-button>
        <el-button type="primary" @click="weightVisible = true">
          <el-icon><MagicStick /></el-icon>查看权重
        </el-button>
      </div>
    </div>

    <!-- 需求信息条 + 公式 -->
    <div v-if="demand" class="match-hero">
      <div class="mh-left">
        <div class="mh-label">采购需求</div>
        <div class="mh-title">
          {{ demand.productName }} <b>{{ fmtNum(demand.quantity) }}</b> {{ demand.unit }}
        </div>
        <div class="mh-sub">
          期望 ¥{{ fmtMoney(demand.expectedPrice) }}/{{ demand.unit }} · 截止 {{ fmtDate(demand.deadline) }} · {{ demand.canteenName }}
        </div>
        <div class="mh-tags">
          <span class="tag tag-gray">{{ demand.demandNo }}</span>
          <span class="tag tag-teal">{{ demand.deliveryMode }}</span>
          <StatusTag type="demand" :value="demand.status" />
        </div>
      </div>
      <div class="mh-formula">
        <div class="mf-title">综合得分 = {{ formulaText }}</div>
        <div class="mf-note">权重之和 = {{ weightSumText }}，系统按得分降序推荐，信用优先保障食品安全</div>
      </div>
    </div>

    <!-- 需求选择条 -->
    <div v-if="standalone" class="card selector-bar">
      <div class="row gap-8">
        <el-icon class="sel-icon"><Filter /></el-icon>
        <span class="bold">选择采购需求</span>
        <span class="muted small">仅列出「处理中」的需求单，选中后即可查看匹配结果</span>
      </div>
      <div class="row gap-8">
        <el-select v-model="demandId" filterable placeholder="请选择处理中的采购需求" style="width: 380px">
          <el-option
            v-for="d in pending"
            :key="d.id"
            :label="d.demandNo + ' · ' + d.productName + ' ' + fmtNum(d.quantity) + (d.unit || 'kg')"
            :value="d.id"
          />
        </el-select>
        <el-button type="primary" :disabled="!demandId" @click="loadMatch">开始匹配</el-button>
      </div>
    </div>

    <!-- 排名列表 -->
    <div v-if="results.length" class="rank-list">
      <div v-for="r in results" :key="r.sourceId" class="card match-card" :class="{ best: r.best }">
        <div class="mc-rank" :class="'rk-' + r.rank">{{ r.rank }}</div>

        <div class="mc-body">
          <div class="mc-line1">
            <span class="mc-farmer">{{ r.farmerName }}</span>
            <span class="tag tag-gray">{{ r.farmerType }}</span>
            <span v-if="r.best" class="tag tag-green">最佳匹配</span>
            <span v-if="r.quantityEnough === false" class="tag tag-orange">数量不足</span>
            <span class="muted small mc-deal">累计成交 {{ r.dealCount || 0 }} 笔</span>
          </div>
          <div class="mc-line2">
            {{ r.productName }} · 报价
            <b class="mc-price">¥{{ fmtMoney(r.price) }}/{{ r.unit }}</b> · 可供
            <b>{{ fmtNum(r.quantity) }} {{ r.unit }}</b> · 信用
            <b>{{ fmtNum(r.creditScore, 0) }}</b> · 距离
            <b>{{ fmtNum(r.distanceKm, 0) }} km</b>
          </div>
          <div class="mc-factors">
            <div v-for="f in factorsOf(r)" :key="f.name" class="factor">
              <span class="f-name">{{ f.name }}</span>
              <span class="f-track"><i :style="{ width: clamp(f.value) + '%', background: f.color }"></i></span>
              <span class="f-val">{{ fmtNum(f.value, 0) }}</span>
            </div>
          </div>
        </div>

        <div class="mc-right">
          <div class="score-label">综合得分</div>
          <div class="score-val">{{ fmtNum(r.score, 1) }}</div>
          <el-button type="primary" size="small" class="mc-btn" @click="openOrder(r)">下单</el-button>
          <span class="link" @click="goSource(r)">查看货源</span>
        </div>
      </div>
    </div>

    <div v-else-if="!loading" class="card">
      <div class="empty">
        {{ demandId ? '暂无符合条件的有效货源，请放宽需求或稍后重试' : '请先选择采购需求，系统将自动计算五因子匹配得分' }}
      </div>
    </div>

    <!-- 底部提示 -->
    <div v-if="results.length" class="match-hint">
      <el-icon><InfoFilled /></el-icon>
      <span>
        已为您从 <b>{{ validCount }}</b> 家有效货源中筛选出综合得分最高的
        <b>{{ results.length }}</b> 家。推荐结果随农户报价、库存与信用实时更新；点击「下单」将生成采购订单并通知农户。
      </span>
    </div>

    <!-- 下单弹窗 -->
    <!-- 权重说明：采购员无权改，但必须能看清「怎么算出来的」，
         而不是点一下弹一句"由管理员配置"就结束 -->
    <el-dialog v-model="weightVisible" title="匹配权重说明" width="480px">
      <div class="weight-list">
        <div v-for="w in weightItems" :key="w.key" class="weight-row">
          <span class="weight-k">{{ w.label }}</span>
          <span class="weight-bar">
            <span class="weight-fill" :style="{ width: weightPct(w.value) + '%' }"></span>
          </span>
          <span class="weight-v mono">{{ w.value }}</span>
        </div>
      </div>
      <div class="weight-formula">综合得分 = {{ formulaText }}（权重之和 {{ weightSumText }}）</div>
      <div class="weight-note">
        权重由管理员在「系统参数配置」中统一维护，采购员侧只读；
        得分越高的货源排在越前，同等分数下信用分优先，保证食品安全。
      </div>
      <template #footer>
        <el-button type="primary" @click="weightVisible = false">知道了</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="orderVisible" title="采购下单" width="500px">
      <div v-if="current">
        <div class="order-box">
          <div class="ob-grid">
            <div class="ob-item"><span class="k">产品</span><span class="v">{{ current.productName }}</span></div>
            <div class="ob-item"><span class="k">供货农户</span><span class="v">{{ current.farmerName }}</span></div>
            <div class="ob-item"><span class="k">单价</span><span class="v money">¥{{ fmtMoney(current.price) }}/{{ current.unit }}</span></div>
            <div class="ob-item"><span class="k">可供数量</span><span class="v">{{ fmtNum(current.quantity) }} {{ current.unit }}</span></div>
          </div>

          <el-form label-position="top" class="order-form">
            <el-form-item :label="'下单数量（' + current.unit + '）'">
              <el-input-number
                v-model="orderForm.quantity"
                :min="1"
                :max="Number(current.quantity)"
                :step="10"
                controls-position="right"
                style="width: 100%"
              />
            </el-form-item>
            <el-form-item label="期望交付时间">
              <el-date-picker
                v-model="orderForm.expectedDelivery"
                type="datetime"
                format="YYYY-MM-DD HH:mm"
                value-format="YYYY-MM-DDTHH:mm:ss"
                placeholder="选择期望交付时间"
                style="width: 100%"
              />
            </el-form-item>
          </el-form>

          <div class="ob-total">
            <span>预计订单金额</span>
            <b class="money">¥{{ orderAmount }}</b>
          </div>
        </div>
      </div>
      <template #footer>
        <el-button @click="orderVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="submitOrder">确认下单</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { purchaserApi } from '@/api'
import StatusTag from '@/components/StatusTag.vue'
import { fmtDate, fmtMoney, fmtNum } from '@/utils/format'
import { useAutoRefresh } from '@/utils/useAutoRefresh'

const route = useRoute()
const router = useRouter()

const standalone = !route.query.demandId
const loading = ref(false)
const submitting = ref(false)
const data = ref({})
const pending = ref([])
const demandId = ref(route.query.demandId ? Number(route.query.demandId) : null)

const orderVisible = ref(false)
const weightVisible = ref(false)
const current = ref(null)
const orderForm = reactive({ quantity: 1, expectedDelivery: '' })

const demand = computed(() => data.value.demand || null)
const results = computed(() => data.value.results || [])
const weights = computed(() => data.value.weights || {})
const validCount = computed(() => Number(data.value.validCount ?? results.value.length))

const subtitle = computed(() =>
  demand.value
    ? `需求单 ${demand.value.demandNo} · 基于五因子加权模型自动推荐 Top5 货源`
    : '基于五因子加权模型自动推荐 Top5 货源'
)

const weightItems = computed(() => [
  { key: 'category', label: '品类', value: fmtNum(weights.value.category ?? 0, 2) },
  { key: 'price', label: '价格', value: fmtNum(weights.value.price ?? 0, 2) },
  { key: 'quantity', label: '数量', value: fmtNum(weights.value.quantity ?? 0, 2) },
  { key: 'credit', label: '信用', value: fmtNum(weights.value.credit ?? 0, 2) },
  { key: 'distance', label: '距离/历史', value: fmtNum(weights.value.distance ?? 0, 2) }
])

const formulaText = computed(() => weightItems.value.map((w) => `${w.value}×${w.label}`).join(' + '))

const weightSumText = computed(() => {
  const w = weights.value
  const calc =
    Number(w.category || 0) +
    Number(w.price || 0) +
    Number(w.quantity || 0) +
    Number(w.credit || 0) +
    Number(w.distance || 0)
  return fmtNum(data.value.weightSum ?? calc, 2)
})

const clamp = (v) => Math.max(0, Math.min(100, Number(v || 0)))

const factorsOf = (r) => [
  { name: '品类匹配', value: Number(r.categoryScore || 0), color: '#16a34a' },
  { name: '价格', value: Number(r.priceScore || 0), color: '#22c55e' },
  { name: '数量', value: Number(r.quantityScore || 0), color: '#0d9488' },
  { name: '信用', value: Number(r.creditFactor || 0), color: '#3b82f6' },
  { name: '距离', value: Number(r.distanceScore || 0), color: '#8b5cf6' }
]

const defaultDelivery = () => {
  const d = new Date()
  d.setDate(d.getDate() + 1)
  const p = (n) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}T08:00:00`
}

const orderAmount = computed(() => {
  const qty = Number(orderForm.quantity || 0)
  const price = Number(current.value?.price || 0)
  return fmtMoney((qty * price).toFixed(2))
})

const loadPending = async () => {
  try {
    const res = await purchaserApi.demands({ page: 1, size: 20, status: '处理中' })
    pending.value = res.data?.page?.records || []
  } catch (e) {
    pending.value = []
  }
}

const loadMatch = async () => {
  if (!demandId.value) return ElMessage.warning('请先选择采购需求')
  loading.value = true
  try {
    const res = await purchaserApi.match(demandId.value)
    data.value = res.data || {}
  } catch (e) {
    data.value = {}
  } finally {
    loading.value = false
  }
}

/* 权重只读展示：条形宽度按各项占合计的比例算，让"哪项影响最大"一眼可见 */
const weightPct = (v) => {
  const sum = Number(weightSumText.value) || 0
  const n = Number(v) || 0
  if (!sum) return 0
  return Math.max(2, Math.round((n / sum) * 100))
}

const openOrder = (r) => {
  current.value = r
  const demandQty = Number(demand.value?.quantity || 0) || Number(r.quantity)
  orderForm.quantity = Math.max(1, Math.min(demandQty, Number(r.quantity)))
  orderForm.expectedDelivery = defaultDelivery()
  orderVisible.value = true
}

const submitOrder = async () => {
  if (!current.value) return
  const qty = Number(orderForm.quantity || 0)
  if (qty <= 0) return ElMessage.warning('下单数量必须大于 0')
  if (qty > Number(current.value.quantity)) return ElMessage.warning('下单数量不能超过货源可供数量')
  if (!orderForm.expectedDelivery) return ElMessage.warning('请选择期望交付时间')
  submitting.value = true
  try {
    await purchaserApi.createOrder({
      demandId: demandId.value,
      sourceId: current.value.sourceId,
      quantity: qty,
      expectedDelivery: orderForm.expectedDelivery
    })
    ElMessage.success('下单成功，已通知农户接单')
    orderVisible.value = false
    router.push('/purchaser/orders')
  } catch (e) {
    /* 错误提示由请求拦截器统一处理 */
  } finally {
    submitting.value = false
  }
}

const goSource = (r) => {
  router.push({ path: '/purchaser/sources', query: { keyword: r.productName } })
}

useAutoRefresh(loadPending)
onMounted(async () => {
  if (demandId.value) {
    loadMatch()
    return
  }
  await loadPending()
  // 默认选中第一条处理中需求，保证首屏即展示 Top5 推荐
  if (!demandId.value && pending.value.length) {
    demandId.value = pending.value[0].id
    loadMatch()
  }
})
</script>

<style scoped>
.match-hero {
  display: grid;
  grid-template-columns: minmax(0, 1.1fr) minmax(0, 1fr);
  gap: 18px;
  align-items: center;
  padding: 16px 20px;
  border-radius: var(--radius);
  border: 1px solid var(--accent-soft);
  background: linear-gradient(135deg, var(--surface-2), var(--accent-soft));
  box-shadow: var(--shadow-card);
  margin-bottom: 14px;
}

.mh-label {
  font-size: var(--fs-xs);
  letter-spacing: 1px;
  color: var(--accent);
  font-weight: 600;
}

.mh-title {
  margin-top: 4px;
  font-size: var(--fs-xl);
  font-weight: 700;
  color: var(--ink);
  letter-spacing: -0.3px;
}

.mh-title b {
  font-family: var(--font-num);
  font-size: var(--fs-xl);
}

.mh-sub {
  margin-top: 5px;
  font-size: var(--fs-sm);
  color: #3f6d55;
}

.mh-tags {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 9px;
}

.mh-formula {
  padding: 12px 14px;
  border-radius: 10px;
  background: #fff;
  border: 1px dashed var(--accent-soft);
}

.mf-title {
  font-size: var(--fs-base);
  font-weight: 600;
  color: var(--ink);
  line-height: 1.6;
  font-family: var(--font-num);
  word-break: break-word;
}

.mf-note {
  margin-top: 7px;
  font-size: var(--fs-xs);
  color: var(--muted);
  line-height: 1.6;
}

.selector-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 14px;
  flex-wrap: wrap;
  padding: 14px 18px;
  margin-bottom: 14px;
}

.sel-icon {
  color: var(--c-primary);
  font-size: var(--fs-md);
}

.rank-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.match-card {
  display: grid;
  grid-template-columns: 44px minmax(0, 1fr) 132px;
  gap: 16px;
  align-items: center;
  padding: 15px 18px;
  transition: box-shadow 0.18s, transform 0.18s;
}

.match-card:hover {
  transform: translateY(-1px);
  box-shadow: 0 8px 22px rgba(16, 42, 32, 0.09);
}

.match-card.best {
  border-color: var(--accent-soft);
  background: linear-gradient(180deg, #f8fdfa, #ffffff 62%);
  box-shadow: 0 4px 18px rgba(22, 163, 74, 0.12);
}

.mc-rank {
  width: 38px;
  height: 38px;
  border-radius: 11px;
  display: grid;
  place-items: center;
  font-family: var(--font-num);
  font-size: var(--fs-md);
  font-weight: 700;
  background: var(--surface-2);
  color: var(--muted);
  flex-shrink: 0;
}

.rk-1 {
  background: linear-gradient(135deg, #fde68a, var(--warn));
  color: #7c2d12;
  box-shadow: 0 3px 8px rgba(245, 158, 11, 0.28);
}

.rk-2 {
  background: linear-gradient(135deg, #eef1f4, #b9c1ca);
  color: var(--ink-2);
}

.rk-3 {
  background: linear-gradient(135deg, #fed7aa, #ea580c);
  color: #7c2d12;
}

.mc-line1 {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.mc-farmer {
  font-size: var(--fs-md);
  font-weight: 700;
  color: #14372a;
}

.mc-deal {
  margin-left: auto;
}

.mc-line2 {
  margin-top: 5px;
  font-size: var(--fs-sm);
  color: var(--c-muted);
}

.mc-line2 b {
  color: var(--ink);
  font-weight: 600;
}

.mc-line2 .mc-price {
  color: var(--accent);
  font-family: var(--font-num);
}

.mc-factors {
  display: grid;
  grid-template-columns: repeat(5, minmax(0, 1fr));
  gap: 10px;
  margin-top: 10px;
}

.factor {
  display: flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
}

.f-name {
  font-size: var(--fs-xs);
  color: var(--c-muted-2);
  white-space: nowrap;
  flex-shrink: 0;
}

.f-track {
  position: relative;
  flex: 1;
  height: 5px;
  border-radius: 3px;
  background: var(--surface-2);
  overflow: hidden;
  min-width: 24px;
}

.f-track i {
  position: absolute;
  left: 0;
  top: 0;
  bottom: 0;
  border-radius: 3px;
  display: block;
}

.f-val {
  font-family: var(--font-num);
  font-size: var(--fs-xs);
  font-weight: 600;
  color: var(--ink-2);
  width: 24px;
  text-align: right;
  flex-shrink: 0;
}

.mc-right {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 3px;
  padding-left: 16px;
  border-left: 1px dashed var(--c-border);
}

.score-label {
  font-size: var(--fs-xs);
  color: var(--c-muted-2);
  letter-spacing: 0.5px;
}

.score-val {
  font-family: var(--font-num);
  font-size: var(--fs-display);
  font-weight: 700;
  color: var(--accent);
  letter-spacing: -0.6px;
  line-height: 1.1;
}

.mc-btn {
  width: 100%;
  margin-top: 6px;
}

.match-hint {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  margin-top: 14px;
  padding: 12px 16px;
  border-radius: 10px;
  border: 1px dashed #cfe6d9;
  background: #f6fbf8;
  font-size: var(--fs-sm);
  line-height: 1.75;
  color: #4b6b5b;
}

.match-hint .el-icon {
  color: var(--c-primary);
  font-size: var(--fs-base);
  margin-top: 2px;
  flex-shrink: 0;
}

.match-hint b {
  color: var(--accent);
  font-family: var(--font-num);
  font-size: var(--fs-base);
}

.order-box .ob-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 9px 16px;
  padding: 12px 14px;
  border-radius: 10px;
  background: var(--surface-2);
}

.ob-item {
  display: flex;
  gap: 8px;
  font-size: var(--fs-sm);
  align-items: baseline;
}

.ob-item .k {
  color: var(--c-muted);
  flex-shrink: 0;
  width: 58px;
}

.ob-item .v {
  color: var(--ink);
  font-weight: 500;
}

.order-form {
  margin-top: 14px;
}

.order-form :deep(.el-form-item) {
  margin-bottom: 14px;
}

.ob-total {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding-top: 12px;
  border-top: 1px dashed var(--c-border);
  font-size: var(--fs-sm);
  color: var(--c-muted);
}

.ob-total .money {
  font-size: var(--fs-xl);
  color: var(--accent);
}

@media (max-width: 1180px) {
  .mc-factors {
    grid-template-columns: repeat(3, minmax(0, 1fr));
  }
}

/* 权重说明弹窗 */
.weight-list {
  display: flex;
  flex-direction: column;
  gap: 9px;
}

.weight-row {
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: var(--fs-sm);
}

.weight-k {
  width: 64px;
  color: var(--ink-2);
  flex: none;
}

.weight-bar {
  flex: 1;
  height: 8px;
  border-radius: 5px;
  background: var(--surface-2);
  overflow: hidden;
}

.weight-fill {
  display: block;
  height: 100%;
  border-radius: 5px;
  background: var(--accent);
}

.weight-v {
  width: 40px;
  text-align: right;
  color: var(--ink);
  flex: none;
}

.weight-formula {
  margin-top: 12px;
  padding: 9px 12px;
  border-radius: 8px;
  background: var(--accent-soft);
  font-size: var(--fs-sm);
  color: var(--ink-2);
}

.weight-note {
  margin-top: 8px;
  font-size: var(--fs-xs);
  color: var(--c-muted-2);
  line-height: 1.7;
}
</style>
