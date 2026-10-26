<template>
  <div class="page" v-loading="loading">
    <div class="page-head">
      <div>
        <h2 class="page-title">农产品档案</h2>
        <p class="page-sub">统一维护农产品品类、计量单位、参考价与储存要求，支撑货源发布与价格预警</p>
      </div>
      <div class="page-actions">
        <el-button plain @click="importTemplate"><el-icon><Download /></el-icon>导入模板</el-button>
        <el-button type="primary" @click="openCreate"><el-icon><Plus /></el-icon>新增产品</el-button>
      </div>
    </div>

    <!-- 统计卡片 -->
    <div class="stat-grid">
      <StatCard
        label="在管品类" :value="fmtNum(stats.categories)" icon="Files"
        foot="规格 SKU" color="blue"
      />
      <StatCard
        label="在售农产品" :value="fmtNum(stats.products)" icon="Apple"
        :foot="'累计 ' + fmtNum(stats.total)" color="green"
      />
      <StatCard
        label="供货农户" :value="fmtNum(stats.farmers)" icon="Connection"
        foot="供货关系数" color="teal"
      />
      <StatCard
        label="价格波动预警" :value="fmtNum(stats.priceWarnings)" icon="Warning"
        foot="超阈值" color="orange"
      />
    </div>

    <div class="card table-card">
      <!-- 筛选条 -->
      <div class="filter-bar">
        <el-input
          v-model="query.keyword" placeholder="搜索产品名称 / 编码" clearable
          style="width: 232px" @keyup.enter="search" @clear="search"
        >
          <template #prefix><el-icon><Search /></el-icon></template>
        </el-input>
        <el-select v-model="query.category" placeholder="产品大类" clearable style="width: 140px" @change="search">
          <el-option label="全部大类" value="" />
          <el-option v-for="c in BIG_CATEGORIES" :key="c" :label="c" :value="c" />
        </el-select>
        <el-select v-model="query.status" placeholder="销售状态" clearable style="width: 132px" @change="search">
          <el-option label="全部状态" value="" />
          <el-option label="在售" value="在售" />
          <el-option label="停售" value="停售" />
        </el-select>
        <div class="filter-spacer"></div>
        <el-button plain @click="reset"><el-icon><Refresh /></el-icon>重置</el-button>
        <el-button type="primary" @click="search"><el-icon><Search /></el-icon>查询</el-button>
      </div>

      <!-- 品类 Tabs -->
      <div class="tab-bar">
        <button
          v-for="t in tabs" :key="t.name" class="tab-btn" :class="{ active: activeTab === t.name }"
          @click="selectTab(t.name)"
        >
          {{ t.name }}<span class="tab-num">{{ t.value }}</span>
        </button>
      </div>

      <div class="table-wrap">
        <el-table :data="rows" style="width: 100%" empty-text="暂无符合条件的农产品">
          <el-table-column label="农产品" min-width="198">
            <template #default="{ row }">
              <div class="cell-main">{{ row.name }}</div>
              <div class="cell-sub mono">{{ row.productNo }}</div>
            </template>
          </el-table-column>
          <el-table-column label="产品大类" width="112">
            <template #default="{ row }">
              <div>{{ row.categoryName || '—' }}</div>
              <div class="cell-sub">{{ row.subCategory || '—' }}</div>
            </template>
          </el-table-column>
          <el-table-column label="单位" width="72">
            <template #default="{ row }"><span class="small muted">{{ row.unit || '—' }}</span></template>
          </el-table-column>
          <el-table-column label="参考均价" width="106" align="right">
            <template #default="{ row }">
              <span class="money" style="color: #15803d">¥ {{ fmtMoney(row.avgPrice) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="近期价格区间" width="142">
            <template #default="{ row }">
              <span class="money small">¥ {{ fmtMoney(row.priceMin) }}</span>
              <span class="muted"> ~ </span>
              <span class="money small">{{ fmtMoney(row.priceMax) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="供货农户" width="100">
            <template #default="{ row }">
              <span class="money">{{ row.farmerCount ?? 0 }}</span>
              <span class="small muted"> 家</span>
            </template>
          </el-table-column>
          <el-table-column label="状态" width="88">
            <template #default="{ row }">
              <span class="tag tag-dot" :class="row.status === '在售' ? 'tag-green' : 'tag-gray'">{{ row.status }}</span>
            </template>
          </el-table-column>
          <el-table-column label="储存要求" width="108">
            <template #default="{ row }"><span class="small muted">{{ row.storageReq || '—' }}</span></template>
          </el-table-column>
          <el-table-column label="操作" width="170" fixed="right">
            <template #default="{ row }">
              <div class="op-links">
                <span class="link" @click="openPrice(row)">查看价格趋势</span>
                <span class="link" @click="openEdit(row)">编辑</span>
              </div>
            </template>
          </el-table-column>
        </el-table>
      </div>

      <div class="pager">
        <span>共 {{ total }} 条记录</span>
        <el-pagination
          :current-page="query.page" :page-size="query.size" :total="total"
          :page-sizes="[10, 20, 50]" layout="sizes, prev, pager, next, jumper" background
          @size-change="onSizeChange" @current-change="onPageChange"
        />
      </div>
    </div>

    <!-- 价格趋势 -->
    <el-dialog
      v-model="priceVisible" :title="'价格趋势 · ' + (priceProduct?.name || '')" width="760px"
      destroy-on-close :close-on-click-modal="false"
    >
      <div v-loading="priceLoading" class="price-wrap">
        <div class="price-metrics">
          <div class="pm-item">
            <div class="pm-label">最高价</div>
            <div class="pm-value warn">¥ {{ fmtMoney(priceStats.max) }}</div>
          </div>
          <div class="pm-item">
            <div class="pm-label">最低价</div>
            <div class="pm-value">¥ {{ fmtMoney(priceStats.min) }}</div>
          </div>
          <div class="pm-item">
            <div class="pm-label">均价</div>
            <div class="pm-value primary">¥ {{ fmtMoney(priceStats.avg) }}</div>
          </div>
          <div class="pm-item pm-meta">
            <div class="pm-label">统计口径</div>
            <div class="pm-text">
              {{ priceStats.days ? '近 ' + priceStats.days + ' 天成交均价' : '暂无历史成交记录' }}
              · 单位：元 / {{ priceProduct?.unit || 'kg' }}
            </div>
          </div>
        </div>

        <div class="chart-head">
          <div class="chart-title">价格走势</div>
          <div class="inline-legend">
            <span><i style="background: #16a34a"></i>均价</span>
            <span><i style="background: #f59e0b"></i>最高价</span>
            <span><i style="background: #0d9488"></i>最低价</span>
          </div>
        </div>
        <BaseChart v-if="priceHistory.length" :option="priceOption" height="248px" />
        <div v-else class="empty chart-empty">该产品暂无价格历史记录，可先维护参考价与价格区间</div>

        <div class="price-tip">
          <el-icon><InfoFilled /></el-icon>
          <span>价格区间波动超过 28% 将进入「价格波动预警」列表，建议同步核查货源报价。</span>
        </div>
      </div>
      <template #footer>
        <el-button @click="priceVisible = false">关闭</el-button>
        <el-button type="primary" plain @click="openEdit(priceProduct)">编辑产品</el-button>
      </template>
    </el-dialog>

    <!-- 新增 / 编辑 -->
    <el-dialog
      v-model="formVisible" :title="form.id ? '编辑农产品' : '新增农产品'" width="620px"
      :close-on-click-modal="false"
    >
      <el-form ref="formRef" :model="form" :rules="rules" label-width="92px">
        <div class="form-grid">
          <el-form-item label="产品名称" prop="name">
            <el-input v-model="form.name" placeholder="如：大白菜" maxlength="30" />
          </el-form-item>
          <el-form-item label="产品大类" prop="bigId">
            <el-select
              v-model="form.bigId" placeholder="请选择大类" style="width: 100%"
              @change="onBigChange"
            >
              <el-option v-for="c in categories" :key="c.id" :label="c.name" :value="c.id" />
            </el-select>
          </el-form-item>
          <el-form-item label="产品子类" prop="subId">
            <el-select
              v-model="form.subId" placeholder="请先选择大类" :disabled="!form.bigId"
              style="width: 100%"
            >
              <el-option v-for="s in subOptions" :key="s.id" :label="s.name" :value="s.id" />
            </el-select>
          </el-form-item>
          <el-form-item label="计量单位" prop="unit">
            <el-select v-model="form.unit" placeholder="请选择单位" filterable allow-create style="width: 100%">
              <el-option v-for="u in UNITS" :key="u" :label="u" :value="u" />
            </el-select>
          </el-form-item>
          <el-form-item label="参考均价" prop="avgPrice">
            <el-input-number
              v-model="form.avgPrice" :min="0" :max="9999" :precision="2" :step="0.1"
              controls-position="right" style="width: 100%"
            />
          </el-form-item>
          <el-form-item label="最低价">
            <el-input-number
              v-model="form.priceMin" :min="0" :max="9999" :precision="2" :step="0.1"
              controls-position="right" style="width: 100%"
            />
          </el-form-item>
          <el-form-item label="最高价">
            <el-input-number
              v-model="form.priceMax" :min="0" :max="9999" :precision="2" :step="0.1"
              controls-position="right" style="width: 100%"
            />
          </el-form-item>
          <el-form-item label="储存要求">
            <el-select v-model="form.storageReq" placeholder="请选择储存要求" style="width: 100%">
              <el-option v-for="s in STORAGE" :key="s" :label="s" :value="s" />
            </el-select>
          </el-form-item>
          <el-form-item label="销售状态">
            <el-radio-group v-model="form.status">
              <el-radio value="在售">在售</el-radio>
              <el-radio value="停售">停售</el-radio>
            </el-radio-group>
          </el-form-item>
        </div>
      </el-form>
      <template #footer>
        <el-button @click="formVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submit">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { adminApi } from '@/api'
import StatCard from '@/components/StatCard.vue'
import BaseChart from '@/components/BaseChart.vue'
import { fmtMoney, fmtNum } from '@/utils/format'

const BIG_CATEGORIES = ['叶菜类', '根茎类', '茄果类', '瓜果类', '菌菇类', '有机菜']
const STORAGE = ['冷链配送', '冷链保鲜', '常温通风']
const UNITS = ['kg', '斤', '份', '箱', '袋', '盒']

const loading = ref(false)
const rows = ref([])
const total = ref(0)
const stats = ref({})
const categories = ref([])
const query = reactive({ page: 1, size: 10, keyword: '', category: '', status: '' })
let seq = 0

const tabs = computed(() => stats.value.tabs || [{ name: '全部', value: 0 }])
const activeTab = computed(() => query.category || '全部')

const blankForm = () => ({
  id: null,
  name: '',
  bigId: null,
  subId: null,
  unit: 'kg',
  avgPrice: null,
  priceMin: null,
  priceMax: null,
  storageReq: '冷链配送',
  status: '在售'
})
const form = reactive(blankForm())
const formVisible = ref(false)
const saving = ref(false)
const formRef = ref(null)

const rules = {
  name: [{ required: true, message: '请输入产品名称', trigger: 'blur' }],
  bigId: [{ required: true, message: '请选择产品大类', trigger: 'change' }],
  subId: [{ required: true, message: '请选择产品子类', trigger: 'change' }],
  unit: [{ required: true, message: '请选择计量单位', trigger: 'change' }],
  avgPrice: [{ required: true, message: '请输入参考均价', trigger: 'blur' }]
}

const subOptions = computed(() => {
  const big = categories.value.find((c) => c.id === form.bigId)
  return big?.children || []
})

const onBigChange = () => {
  form.subId = null
}

const buildParams = () => {
  const p = { page: query.page, size: query.size }
  if (query.keyword) p.keyword = query.keyword.trim()
  if (query.category) p.category = query.category
  if (query.status) p.status = query.status
  return p
}

const load = async () => {
  const my = ++seq
  loading.value = true
  try {
    const res = await adminApi.products(buildParams())
    if (my !== seq) return
    const data = res.data || {}
    rows.value = data.page?.records || []
    total.value = Number(data.page?.total || 0)
    stats.value = data.stats || {}
  } finally {
    if (my === seq) loading.value = false
  }
}

const loadCategories = async () => {
  try {
    const res = await adminApi.categories()
    categories.value = res.data || []
  } catch (e) {
    categories.value = []
  }
}

const search = () => {
  query.page = 1
  load()
}

const reset = () => {
  query.keyword = ''
  query.category = ''
  query.status = ''
  query.page = 1
  load()
}

const selectTab = (name) => {
  query.category = name === '全部' ? '' : name
  query.page = 1
  load()
}

const onSizeChange = (size) => {
  query.size = size
  query.page = 1
  load()
}

const onPageChange = (page) => {
  query.page = page
  load()
}

const importTemplate = () => {
  ElMessage.success('已下载《农产品导入模板》，请按品类、单位与参考价填写后上传')
}

const openCreate = () => {
  Object.assign(form, blankForm())
  formVisible.value = true
  formRef.value?.clearValidate()
}

const openEdit = (row) => {
  if (!row) return
  const big = categories.value.find((c) => c.name === row.categoryName)
  const sub = big?.children?.find((s) => s.name === row.subCategory)
  Object.assign(form, blankForm(), {
    id: row.id,
    name: row.name || '',
    bigId: big?.id ?? null,
    subId: sub?.id ?? row.categoryId ?? null,
    unit: row.unit || 'kg',
    avgPrice: row.avgPrice === null || row.avgPrice === undefined ? null : Number(row.avgPrice),
    priceMin: row.priceMin === null || row.priceMin === undefined ? null : Number(row.priceMin),
    priceMax: row.priceMax === null || row.priceMax === undefined ? null : Number(row.priceMax),
    storageReq: row.storageReq || '冷链配送',
    status: row.status || '在售'
  })
  priceVisible.value = false
  formVisible.value = true
  formRef.value?.clearValidate()
}

const submit = () => {
  formRef.value.validate(async (valid) => {
    if (!valid) return
    const priceMin = form.priceMin === null || form.priceMin === undefined ? form.avgPrice : form.priceMin
    const priceMax = form.priceMax === null || form.priceMax === undefined ? form.avgPrice : form.priceMax
    if (Number(priceMax) < Number(priceMin)) {
      ElMessage.warning('最高价不能低于最低价，请检查价格区间')
      return
    }
    saving.value = true
    try {
      const payload = {
        name: form.name,
        categoryId: form.subId || form.bigId,
        unit: form.unit,
        avgPrice: form.avgPrice,
        priceMin,
        priceMax,
        storageReq: form.storageReq,
        status: form.status
      }
      if (form.id) payload.id = form.id
      await adminApi.saveProduct(payload)
      ElMessage.success(form.id ? '农产品档案已更新' : '农产品已创建')
      formVisible.value = false
      await load()
    } catch (e) {
      /* 请求拦截器已提示 */
    } finally {
      saving.value = false
    }
  })
}

/* ---------------- 价格趋势 ---------------- */
const priceVisible = ref(false)
const priceLoading = ref(false)
const priceProduct = ref(null)
const priceHistory = ref([])

const openPrice = async (row) => {
  if (!row) return
  priceProduct.value = row
  priceHistory.value = []
  priceVisible.value = true
  priceLoading.value = true
  try {
    const res = await adminApi.priceHistory(row.id)
    priceHistory.value = res.data || []
  } catch (e) {
    priceHistory.value = []
  } finally {
    priceLoading.value = false
  }
}

const priceStats = computed(() => {
  const list = priceHistory.value
  const p = priceProduct.value || {}
  if (list.length) {
    const max = Math.max(...list.map((x) => Number(x.max)))
    const min = Math.min(...list.map((x) => Number(x.min)))
    const avg = list.reduce((s, x) => s + Number(x.avg), 0) / list.length
    return { max, min, avg, days: list.length }
  }
  return { max: p.priceMax, min: p.priceMin, avg: p.avgPrice, days: 0 }
})

const priceOption = computed(() => {
  const list = priceHistory.value
  const dates = list.map((x) => String(x.date).slice(5))
  const base = (name, color, data, dash) => ({
    name,
    type: 'line',
    smooth: true,
    symbol: 'circle',
    symbolSize: 5,
    data,
    lineStyle: { width: dash ? 1.5 : 2.4, color, type: dash ? 'dashed' : 'solid' },
    itemStyle: { color }
  })
  const avgSeries = {
    ...base('均价', '#16a34a', list.map((x) => Number(x.avg)), false),
    areaStyle: {
      color: {
        type: 'linear', x: 0, y: 0, x2: 0, y2: 1,
        colorStops: [
          { offset: 0, color: 'rgba(22,163,74,0.26)' },
          { offset: 1, color: 'rgba(22,163,74,0.01)' }
        ]
      }
    }
  }
  return {
    grid: { left: 8, right: 18, top: 30, bottom: 6, containLabel: true },
    tooltip: {
      trigger: 'axis',
      backgroundColor: 'rgba(15,43,32,.92)',
      borderWidth: 0,
      textStyle: { color: '#fff', fontSize: 12 },
      valueFormatter: (v) => (v === null || v === undefined ? '—' : '¥ ' + Number(v).toFixed(2))
    },
    legend: {
      top: 0, right: 0, itemWidth: 10, itemHeight: 6,
      textStyle: { fontSize: 11, color: '#6b7280' }
    },
    xAxis: {
      type: 'category',
      boundaryGap: false,
      data: dates,
      axisLine: { lineStyle: { color: '#e5eae8' } },
      axisTick: { show: false },
      axisLabel: { color: '#8b9c96', fontSize: 11 }
    },
    yAxis: {
      type: 'value',
      scale: true,
      splitLine: { lineStyle: { color: '#f1f5f3', type: 'dashed' } },
      axisLabel: { color: '#8b9c96', fontSize: 11, formatter: '{value}' }
    },
    series: [
      avgSeries,
      base('最高价', '#f59e0b', list.map((x) => Number(x.max)), true),
      base('最低价', '#0d9488', list.map((x) => Number(x.min)), true)
    ]
  }
})

onMounted(() => {
  load()
  loadCategories()
})
</script>

<style scoped>
.table-card {
  overflow: hidden;
}

.tab-bar {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  padding: 11px 18px;
  border-bottom: 1px solid var(--c-border);
}

.tab-btn {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  height: 29px;
  padding: 0 12px;
  border: none;
  border-radius: 15px;
  background: #f4f7f5;
  color: var(--c-text-2);
  font-size: 12.5px;
  cursor: pointer;
  transition: all 0.16s ease;
}

.tab-btn:hover {
  background: #eaf5ee;
  color: var(--c-primary-dark);
}

.tab-btn.active {
  background: var(--c-primary-soft);
  color: var(--c-primary-dark);
  font-weight: 600;
  box-shadow: inset 0 0 0 1px #bfe8cf;
}

.tab-num {
  font-family: var(--font-num);
  font-size: 11.5px;
  color: var(--c-muted-2);
}

.tab-btn.active .tab-num {
  color: var(--c-primary-dark);
}

.cell-main {
  font-size: 13px;
  font-weight: 600;
  color: #1b2a24;
  line-height: 1.35;
}

.cell-sub {
  font-size: 11.5px;
  color: var(--c-muted-2);
  margin-top: 2px;
}

.op-links {
  display: flex;
  align-items: center;
}

.price-wrap {
  min-height: 300px;
}

.price-metrics {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 10px;
  margin-bottom: 14px;
}

.pm-item {
  padding: 10px 12px;
  border-radius: 10px;
  background: #f7fbf9;
  border: 1px solid var(--c-border);
}

.pm-label {
  font-size: 11.5px;
  color: var(--c-muted);
}

.pm-value {
  font-family: var(--font-num);
  font-size: 18px;
  font-weight: 700;
  margin-top: 4px;
  color: #1b2a24;
}

.pm-value.warn {
  color: #b45309;
}

.pm-value.primary {
  color: #15803d;
}

.pm-meta {
  background: #fff;
  border-style: dashed;
}

.pm-text {
  font-size: 11.5px;
  color: var(--c-muted);
  line-height: 1.55;
  margin-top: 5px;
}

.chart-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 4px;
}

.chart-title {
  font-size: 13px;
  font-weight: 600;
  color: #1b2a24;
}

.inline-legend {
  display: inline-flex;
  align-items: center;
  gap: 12px;
  font-size: 11.5px;
  color: var(--c-muted);
}

.inline-legend span {
  display: inline-flex;
  align-items: center;
  gap: 5px;
}

.inline-legend i {
  width: 10px;
  height: 3px;
  border-radius: 2px;
}

.chart-empty {
  padding: 70px 0;
  border: 1px dashed var(--c-border);
  border-radius: 10px;
  margin-top: 6px;
}

.price-tip {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-top: 10px;
  font-size: 11.5px;
  color: var(--c-muted-2);
}

.form-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 0 14px;
}
</style>
