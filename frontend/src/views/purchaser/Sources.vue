<template>
  <div class="page">
    <div class="page-head">
      <div>
        <h2 class="page-title">货源大厅</h2>
        <p class="page-sub">浏览农户在售货源，按品类、价格、信用筛选，可直接下单</p>
      </div>
      <div class="page-actions">
        <el-button plain @click="load"><el-icon><Refresh /></el-icon>刷新货源</el-button>
      </div>
    </div>

    <!-- 筛选条 -->
    <div class="card">
      <div class="filter-bar no-border">
        <el-input
          v-model="filters.keyword"
          placeholder="搜索产品名"
          clearable
          style="width: 180px"
          @keyup.enter="search"
        >
          <template #prefix><el-icon><Search /></el-icon></template>
        </el-input>
        <el-select v-model="filters.category" placeholder="全部品类" clearable style="width: 132px">
          <el-option v-for="c in CATEGORIES" :key="c" :label="c" :value="c" />
        </el-select>
        <div class="num-filter">
          <span class="muted small">最高单价</span>
          <el-input-number
            v-model="filters.priceMax"
            :min="0"
            :max="999"
            :step="0.5"
            :precision="2"
            :controls="false"
            placeholder="元/kg"
            style="width: 88px"
          />
        </div>
        <div class="num-filter">
          <span class="muted small">最低信用</span>
          <el-input-number
            v-model="filters.creditMin"
            :min="0"
            :max="100"
            :step="5"
            :controls="false"
            placeholder="分"
            style="width: 80px"
          />
        </div>
        <el-select v-model="sortModel" style="width: 140px">
          <el-option label="最新上架" value="latest" />
          <el-option label="价格从低到高" value="price" />
          <el-option label="信用优先" value="credit" />
        </el-select>
        <el-button type="primary" @click="search"><el-icon><Search /></el-icon>查询</el-button>
        <el-button plain @click="reset">重置</el-button>
        <div class="filter-spacer"></div>
        <span class="muted small">共 {{ total }} 个在售货源</span>
      </div>
    </div>

    <!-- 货源网格 -->
    <div class="source-grid" v-loading="loading">
      <div v-for="s in rows" :key="s.id" class="card source-card">
        <div class="sc-head">
          <div class="sc-title">
            <span class="sc-name">{{ s.productName }}</span>
            <span class="muted small">{{ s.productNo }}</span>
          </div>
          <span class="tag tag-green">{{ s.categoryName }}</span>
        </div>

        <div class="sc-farmer">
          <el-icon><User /></el-icon>
          <span class="sc-farmer-name">{{ s.farmerName }}</span>
          <span class="tag tag-gray">{{ s.farmerType }}</span>
        </div>

        <div class="sc-price">
          <span class="cur">¥</span>{{ fmtMoney(s.price) }}<span class="unit">/{{ s.unit }}</span>
        </div>
        <div class="sc-avg">市场均价 ¥{{ fmtMoney(s.avgPrice) }} · 可供 <b>{{ fmtNum(s.quantity) }}</b> {{ s.unit }}</div>

        <div class="sc-credit">
          <span class="muted small">信用分</span>
          <b class="credit-num">{{ fmtNum(s.creditScore, 0) }}</b>
          <el-progress
            class="grow"
            :percentage="Number(s.creditScore || 0)"
            :stroke-width="5"
            :show-text="false"
            :color="creditColor(s.creditScore)"
          />
        </div>

        <div class="sc-meta">
          <span><el-icon><Location /></el-icon>{{ fmtNum(s.distanceKm, 0) }} km</span>
          <span><el-icon><Van /></el-icon>{{ s.deliveryMode || '冷链配送' }}</span>
        </div>
        <div class="sc-storage">
          <el-icon><Box /></el-icon>储存要求：{{ s.storageReq || '常温通风' }}
        </div>

        <div class="sc-foot">
          <el-button type="primary" size="small" @click="openOrder(s)">下单</el-button>
          <el-button size="small" plain @click="showFarmer(s)">查看农户</el-button>
        </div>
      </div>

      <div v-if="!loading && !rows.length" class="card empty-card">
        <div class="empty">暂无可售货源，试试放宽价格或信用筛选条件</div>
      </div>
    </div>

    <!-- 分页 -->
    <div class="card pager-card" v-if="total > 0">
      <div class="pager no-border">
        <span>共 {{ total }} 个在售货源 · 第 {{ page }} / {{ pages }} 页</span>
        <el-pagination
          v-model:current-page="page"
          :page-size="size"
          :total="total"
          layout="prev, pager, next"
          background
          @current-change="load"
        />
      </div>
    </div>

    <!-- 下单弹窗 -->
    <el-dialog v-model="orderVisible" title="采购下单" width="500px">
      <div v-if="current" class="order-box">
        <div class="ob-grid">
          <div class="ob-item"><span class="k">产品</span><span class="v">{{ current.productName }}（{{ current.categoryName }}）</span></div>
          <div class="ob-item"><span class="k">供货农户</span><span class="v">{{ current.farmerName }} · {{ current.farmerType }}</span></div>
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
import { fmtMoney, fmtNum } from '@/utils/format'

const route = useRoute()
const router = useRouter()

const CATEGORIES = ['叶菜类', '根茎类', '茄果类', '瓜果类', '菌菇类', '有机菜']

const loading = ref(false)
const rows = ref([])
const total = ref(0)
const pages = ref(1)
const page = ref(1)
const size = ref(8)

const filters = reactive({
  keyword: String(route.query.keyword || ''),
  category: '',
  priceMax: null,
  creditMin: null,
  sort: ''
})

const orderVisible = ref(false)
const submitting = ref(false)
const current = ref(null)
const orderForm = reactive({ quantity: 400, expectedDelivery: '' })

// 排序下拉：默认「最新上架」对应接口 sort 为空，保持 '' | 'price' | 'credit' 契约
const sortModel = computed({
  get: () => filters.sort || 'latest',
  set: (v) => {
    filters.sort = v === 'latest' ? '' : v
  }
})

const creditColor = (v) => {
  const n = Number(v || 0)
  if (n >= 90) return '#16a34a'
  if (n >= 80) return '#0d9488'
  return '#f59e0b'
}

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

const load = async () => {
  loading.value = true
  try {
    const res = await purchaserApi.sources({
      page: page.value,
      size: size.value,
      keyword: filters.keyword.trim() || undefined,
      category: filters.category || undefined,
      priceMax: filters.priceMax === null || filters.priceMax === '' ? undefined : filters.priceMax,
      creditMin: filters.creditMin === null || filters.creditMin === '' ? undefined : filters.creditMin,
      sort: filters.sort || undefined
    })
    const payload = res.data?.page || {}
    rows.value = payload.records || []
    total.value = Number(payload.total || 0)
    pages.value = Number(payload.pages || 1)
  } catch (e) {
    /* 错误提示由请求拦截器统一处理 */
  } finally {
    loading.value = false
  }
}

const search = () => {
  page.value = 1
  load()
}

const reset = () => {
  filters.keyword = ''
  filters.category = ''
  filters.priceMax = null
  filters.creditMin = null
  filters.sort = ''
  page.value = 1
  load()
}

const openOrder = (s) => {
  current.value = s
  orderForm.quantity = Math.max(1, Math.min(400, Number(s.quantity) || 1))
  orderForm.expectedDelivery = defaultDelivery()
  orderVisible.value = true
}

const showFarmer = (s) => {
  ElMessage({
    type: 'success',
    duration: 4200,
    message: `${s.farmerName}（${s.farmerType}）· 信用分 ${fmtNum(s.creditScore, 0)} · 距食堂 ${fmtNum(
      s.distanceKm,
      0
    )} km · 交货方式 ${s.deliveryMode || '冷链配送'} · 在售 ${s.productName} ${fmtNum(s.quantity)}${s.unit}`
  })
}

const submitOrder = async () => {
  if (!current.value) return
  const qty = Number(orderForm.quantity || 0)
  if (qty <= 0) return ElMessage.warning('下单数量必须大于 0')
  if (qty > Number(current.value.quantity)) return ElMessage.warning('下单数量不能超过可供数量')
  if (!orderForm.expectedDelivery) return ElMessage.warning('请选择期望交付时间')
  submitting.value = true
  try {
    await purchaserApi.createOrder({
      demandId: null,
      sourceId: current.value.id,
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

onMounted(load)
</script>

<style scoped>
.filter-bar.no-border {
  border-bottom: none;
}

.pager.no-border {
  border-top: none;
}

.num-filter {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}

.source-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 14px;
  margin-top: 14px;
  min-height: 120px;
}

.empty-card {
  grid-column: 1 / -1;
}

.source-card {
  display: flex;
  flex-direction: column;
  gap: 9px;
  padding: 15px 16px 14px;
  transition: transform 0.18s, box-shadow 0.18s;
}

.source-card:hover {
  transform: translateY(-2px);
  box-shadow: 0 8px 22px rgba(16, 42, 32, 0.1);
}

.sc-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 8px;
}

.sc-title {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}

.sc-name {
  font-size: 15px;
  font-weight: 700;
  color: #14372a;
  line-height: 1.3;
}

.sc-farmer {
  display: flex;
  align-items: center;
  gap: 5px;
  font-size: 12px;
  color: var(--c-muted);
  min-width: 0;
}

.sc-farmer .el-icon {
  font-size: 12px;
  color: var(--c-primary);
  flex-shrink: 0;
}

.sc-farmer-name {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: var(--c-text-2);
}

.sc-price {
  font-family: var(--font-num);
  font-size: 26px;
  font-weight: 700;
  color: #15803d;
  letter-spacing: -0.6px;
  line-height: 1.1;
}

.sc-price .cur {
  font-size: 15px;
  margin-right: 1px;
}

.sc-price .unit {
  font-size: 12px;
  color: var(--c-muted);
  font-weight: 500;
  letter-spacing: 0;
  margin-left: 2px;
}

.sc-avg {
  font-size: 11.5px;
  color: var(--c-muted-2);
  margin-top: -4px;
}

.sc-avg b {
  color: var(--c-text-2);
}

.sc-credit {
  display: flex;
  align-items: center;
  gap: 7px;
  padding: 7px 9px;
  border-radius: 8px;
  background: #f7fbf9;
}

.credit-num {
  font-family: var(--font-num);
  font-size: 13.5px;
  color: #15803d;
  flex-shrink: 0;
}

.sc-meta {
  display: flex;
  align-items: center;
  gap: 14px;
  font-size: 11.5px;
  color: var(--c-muted);
}

.sc-meta span {
  display: inline-flex;
  align-items: center;
  gap: 4px;
}

.sc-storage {
  display: flex;
  align-items: center;
  gap: 5px;
  font-size: 11.5px;
  color: var(--c-muted-2);
  padding-top: 8px;
  border-top: 1px dashed var(--c-border);
}

.sc-foot {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: auto;
  padding-top: 4px;
}

.sc-foot .el-button {
  flex: 1;
}

.pager-card {
  margin-top: 14px;
}

.order-box .ob-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 9px 16px;
  padding: 12px 14px;
  border-radius: 10px;
  background: #f7fbf9;
}

.ob-item {
  display: flex;
  gap: 8px;
  font-size: 12.5px;
  align-items: baseline;
}

.ob-item .k {
  color: var(--c-muted);
  flex-shrink: 0;
  width: 58px;
}

.ob-item .v {
  color: #1b2a24;
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
  font-size: 12.5px;
  color: var(--c-muted);
}

.ob-total .money {
  font-size: 20px;
  color: #15803d;
}
</style>
