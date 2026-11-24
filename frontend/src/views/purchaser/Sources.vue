<template>
  <div class="page">
    <div class="page-head">
      <div>
        <h2 class="page-title">货源大厅</h2>
        <p class="page-sub">按品类、价格、信用筛选货源；勾选多个品项加入采购单，一次提交即可，不用一个个下单</p>
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

        <div class="sc-buy">
          <el-input-number
            v-model="draft[s.id]"
            :min="1"
            :max="Math.max(1, Number(s.quantity) || 1)"
            :step="10"
            :controls="false"
            size="small"
            class="sc-qty"
          />
          <span class="sc-unit">{{ s.unit }}</span>
          <el-button
            :type="inCart(s.id) ? 'success' : 'primary'"
            size="small"
            :plain="inCart(s.id)"
            @click="toggleCart(s)"
          >
            {{ inCart(s.id) ? '已加入 ✓' : '加入采购单' }}
          </el-button>
        </div>
        <div class="sc-foot">
          <span class="link" @click="showFarmer(s)">查看农户</span>
          <span class="muted small">已售 {{ fmtNum(s.soldQuantity, 0) }} {{ s.unit }}</span>
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

    <!-- 采购单购物栏：勾选多个货源后一次提交，替代原来「一个货源一张单」 -->
    <div class="po-bar" v-if="cartList.length">
      <div class="po-bar-left">
        <el-icon class="po-cart"><ShoppingCart /></el-icon>
        <span class="po-count">{{ cartList.length }} 个品项</span>
        <span class="po-amount">¥{{ fmtMoney(cartTotal) }}</span>
        <span class="po-names">{{ cartNames }}</span>
      </div>
      <div class="po-bar-right">
        <el-date-picker
          v-model="poForm.expectedDelivery"
          type="datetime"
          format="YYYY-MM-DD HH:mm"
          value-format="YYYY-MM-DDTHH:mm:ss"
          placeholder="期望到货时间"
          size="small"
          style="width: 186px"
        />
        <el-button size="small" plain @click="clearCart">清空</el-button>
        <el-button size="small" type="primary" :loading="submitting" @click="submitPo">
          提交采购单（{{ cartList.length }}）
        </el-button>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { purchaserApi } from '@/api'
import { fmtMoney, fmtNum } from '@/utils/format'
import { useAutoRefresh } from '@/utils/useAutoRefresh'

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

const submitting = ref(false)
/** 采购单：sourceId -> { source, qty }，同一货源多次加入按最后一次数量覆盖 */
const cart = reactive({})
/** 卡片上的数量草稿，避免「加购」前必须先开弹窗 */
const draft = reactive({})
const poForm = reactive({ expectedDelivery: '' })

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

const cartList = computed(() => Object.values(cart))

const inCart = (id) => !!cart[id]

/** 采购单合计金额：按当前单价快照估算，最终以提交时的服务端算价为准 */
const cartTotal = computed(() =>
  cartList.value
    .reduce((sum, c) => sum + Number(c.qty || 0) * Number(c.source.price || 0), 0)
    .toFixed(2)
)

const cartNames = computed(() =>
  cartList.value
    .slice(0, 4)
    .map((c) => `${c.source.productName} ${fmtNum(c.qty, 0)}${c.source.unit}`)
    .join('、') + (cartList.value.length > 4 ? ` 等 ${cartList.value.length} 项` : '')
)

const toggleCart = (s) => {
  if (cart[s.id]) {
    delete cart[s.id]
    ElMessage.info(`已移出采购单：${s.productName}`)
    return
  }
  const avail = Number(s.quantity || 0)
  if (avail <= 0) {
    ElMessage.warning('该货源已无可供应量')
    return
  }
  let qty = Number(draft[s.id] || 0)
  if (!qty || qty <= 0) qty = Math.min(400, avail)
  if (qty > avail) {
    qty = avail
    ElMessage.warning(`「${s.productName}」最多可供 ${fmtNum(avail, 0)}${s.unit}，已按可供量加入`)
  }
  cart[s.id] = { source: s, qty }
  ElMessage.success(`已加入采购单：${s.productName} ${fmtNum(qty, 0)}${s.unit}`)
}

const clearCart = () => {
  Object.keys(cart).forEach((k) => delete cart[k])
}

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
    // 数量草稿默认给一个「合理起订量」，用户直接点「加入采购单」即可
    rows.value.forEach((r) => {
      if (draft[r.id] === undefined) {
        draft[r.id] = Math.max(1, Math.min(400, Number(r.quantity) || 1))
      }
    })
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

const submitPo = async () => {
  if (!cartList.value.length) return
  for (const c of cartList.value) {
    const qty = Number(c.qty || 0)
    if (qty <= 0) return ElMessage.warning(`「${c.source.productName}」数量必须大于 0`)
    if (qty > Number(c.source.quantity || 0)) {
      return ElMessage.warning(`「${c.source.productName}」数量超过可供量，请调整后再提交`)
    }
  }
  submitting.value = true
  try {
    const res = await purchaserApi.createPurchaseOrder({
      items: cartList.value.map((c) => ({ sourceId: c.source.id, quantity: Number(c.qty) })),
      expectedDelivery: poForm.expectedDelivery || undefined,
      remark: ''
    })
    ElMessage.success(res.message || '采购单已生成，已通知农户接单')
    clearCart()
    router.push('/purchaser/orders')
  } catch (e) {
    /* 错误提示由请求拦截器统一处理 */
  } finally {
    submitting.value = false
  }
}

useAutoRefresh(load)
onMounted(() => {
  poForm.expectedDelivery = defaultDelivery()
  load()
})
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
  font-size: var(--fs-md);
  font-weight: 700;
  color: #14372a;
  line-height: 1.3;
}

.sc-farmer {
  display: flex;
  align-items: center;
  gap: 5px;
  font-size: var(--fs-sm);
  color: var(--c-muted);
  min-width: 0;
}

.sc-farmer .el-icon {
  font-size: var(--fs-sm);
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
  font-size: var(--fs-display);
  font-weight: 700;
  color: var(--accent);
  letter-spacing: -0.6px;
  line-height: 1.1;
}

.sc-price .cur {
  font-size: var(--fs-md);
  margin-right: 1px;
}

.sc-price .unit {
  font-size: var(--fs-sm);
  color: var(--c-muted);
  font-weight: 500;
  letter-spacing: 0;
  margin-left: 2px;
}

.sc-avg {
  font-size: var(--fs-xs);
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
  background: var(--surface-2);
}

.credit-num {
  font-family: var(--font-num);
  font-size: var(--fs-base);
  color: var(--accent);
  flex-shrink: 0;
}

.sc-meta {
  display: flex;
  align-items: center;
  gap: 14px;
  font-size: var(--fs-xs);
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
  font-size: var(--fs-xs);
  color: var(--c-muted-2);
  padding-top: 8px;
  border-top: 1px dashed var(--c-border);
}

.sc-buy {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-top: auto;
  padding-top: 4px;
}

.sc-qty {
  width: 86px;
}

.sc-qty :deep(.el-input__inner) {
  text-align: right;
}

.sc-unit {
  font-size: var(--fs-xs);
  color: var(--c-muted);
}

.sc-buy .el-button {
  flex: 1;
}

.sc-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-top: 8px;
}

/* 采购单购物栏：吸在视口底部，勾选多少品项都看得到合计与提交 */
.po-bar {
  position: sticky;
  bottom: 12px;
  z-index: 30;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-top: 14px;
  padding: 10px 14px;
  border-radius: 12px;
  background: var(--surface);
  border: 1px solid var(--c-border);
  box-shadow: 0 8px 24px rgba(16, 24, 40, 0.14);
}

.po-bar-left {
  display: flex;
  align-items: baseline;
  gap: 10px;
  min-width: 0;
}

.po-cart {
  color: var(--accent);
  font-size: 17px;
  align-self: center;
}

.po-count {
  font-weight: 700;
  font-size: var(--fs-md);
}

.po-amount {
  font-weight: 700;
  color: var(--accent);
  font-size: var(--fs-md);
}

.po-names {
  font-size: var(--fs-xs);
  color: var(--c-muted);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  max-width: 420px;
}

.po-bar-right {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-shrink: 0;
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
</style>
