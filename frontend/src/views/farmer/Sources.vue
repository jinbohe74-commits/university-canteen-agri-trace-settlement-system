<template>
  <div class="page" v-loading="loading">
    <div class="page-head">
      <div>
        <h2 class="page-title">货源管理</h2>
        <p class="page-sub">维护可供农产品、数量与报价，货源将进入智能匹配候选池</p>
      </div>
      <div class="page-actions">
        <el-button plain @click="exportSources"><el-icon><Download /></el-icon>导出货源</el-button>
        <el-button type="primary" @click="focusForm">
          <el-icon><Plus /></el-icon>发布货源
        </el-button>
      </div>
    </div>

    <div class="stat-grid">
      <StatCard
        label="在售货源" :value="stats.onSale ?? 0" icon="Box" color="green"
        foot="持续供应"
      />
      <StatCard
        label="累计发布" :value="stats.total ?? 0" icon="Goods" color="blue"
        foot="货源订单"
      />
      <StatCard
        label="本月成交" :value="stats.monthDeals ?? 0" icon="TrendCharts" color="teal"
        foot="货源订单"
      />
      <StatCard
        label="已售罄" :value="stats.soldOut ?? 0" icon="SoldOut" color="orange"
        foot="需补货"
      />
    </div>

    <div class="src-layout">
      <!-- 货源表格 -->
      <SectionCard title="我的货源" :body-style="false" noPad>
        <template #extra>
          <span class="muted small">共 {{ pageInfo.total }} 条 · 在售 {{ stats.onSale ?? 0 }}</span>
        </template>

        <div class="filter-bar">
          <el-input
            v-model="query.keyword" placeholder="搜索产品名称" clearable class="search-input"
            @keyup.enter="search"
          >
            <template #prefix><el-icon><Search /></el-icon></template>
          </el-input>
          <el-select v-model="query.status" placeholder="全部状态" clearable @change="search">
            <el-option label="正常" value="正常" />
            <el-option label="已售罄" value="已售罄" />
            <el-option label="下架" value="下架" />
          </el-select>
          <el-button type="primary" plain @click="search"><el-icon><Search /></el-icon>查询</el-button>
        </div>

        <div class="table-wrap">
          <el-table
            :data="records" size="small" class="src-table" :show-overflow-tooltip="true"
            empty-text="暂无货源数据"
          >
            <el-table-column label="产品" min-width="106">
              <template #default="{ row }">
                <div class="p-name">{{ row.productName }}</div>
                <div class="p-no mono">{{ row.productNo }}</div>
              </template>
            </el-table-column>
            <el-table-column prop="categoryName" label="品类" width="60" />
            <el-table-column label="可供数量" width="86">
              <template #default="{ row }">
                <span class="mono">{{ fmtNum(row.quantity) }}</span>
                <span class="muted small"> {{ row.unit }}</span>
              </template>
            </el-table-column>
            <el-table-column label="报价" width="100">
              <template #default="{ row }">
                <span class="money price">¥ {{ fmtMoney(row.price) }}</span>
                <span class="muted small"> /{{ row.unit }}</span>
              </template>
            </el-table-column>
            <el-table-column prop="deliveryMode" label="交货方式" min-width="96" />
            <el-table-column label="储存" width="64">
              <template #default="{ row }">
                <span class="tag" :class="storageCls(row.storageReq)">{{ storageText(row.storageReq) }}</span>
              </template>
            </el-table-column>
            <el-table-column label="状态" width="76">
              <template #default="{ row }">
                <StatusTag type="source" :value="row.status" />
              </template>
            </el-table-column>
            <el-table-column label="操作" width="88" fixed="right">
              <template #default="{ row }">
                <span class="link" @click="edit(row)">编辑</span>
                <span class="link" @click="toggle(row)">
                  {{ row.status === '正常' ? '下架' : '上架' }}
                </span>
              </template>
            </el-table-column>
          </el-table>
        </div>

        <div class="pager">
          <span>共 {{ pageInfo.total }} 条 · 第 {{ pageInfo.page }} / {{ pageInfo.pages || 1 }} 页</span>
          <el-pagination
            size="small" background :pager-count="5" layout="prev, pager, next"
            :total="pageInfo.total" :page-size="pageInfo.size" :current-page="query.page"
            @current-change="onPage"
          />
        </div>
      </SectionCard>

      <!-- 发布 / 编辑表单 -->
      <SectionCard
        :title="mode === 'edit' ? '编辑货源' : '发布货源'" :body-style="false"
        :class="{ 'form-editing': mode === 'edit' }"
      >
        <template #extra>
          <span class="online-tag"><i></i>在线</span>
        </template>

        <el-form
          ref="formRef" :model="form" :rules="rules" label-position="top"
          class="src-form" @submit.prevent
        >
          <el-form-item label="产品名称" prop="productId">
            <el-select
              v-model="form.productId" filterable placeholder="搜索并选择农产品"
              style="width: 100%" @change="onProductChange"
            >
              <el-option
                v-for="p in products" :key="p.id" :label="p.name" :value="p.id"
              >
                <div class="prod-opt">
                  <span>{{ p.name }}</span>
                  <span class="muted small">¥{{ fmtMoney(p.avgPrice) }}/{{ p.unit }}</span>
                </div>
              </el-option>
            </el-select>
          </el-form-item>

          <div class="form-2col">
            <el-form-item label="可供数量（kg）" prop="quantity">
              <el-input-number
                v-model="form.quantity" :min="1" :max="99999" :step="10"
                controls-position="right" style="width: 100%"
              />
            </el-form-item>
            <el-form-item label="报价（元/kg）" prop="price">
              <el-input-number
                v-model="form.price" :min="0.01" :max="9999" :precision="2" :step="0.1"
                controls-position="right" style="width: 100%"
              />
            </el-form-item>
          </div>

          <div class="form-2col">
            <el-form-item label="交货方式" prop="deliveryMode">
              <el-select v-model="form.deliveryMode" style="width: 100%">
                <el-option label="现货·当日发" value="现货·当日发" />
                <el-option label="现货·次日发" value="现货·次日发" />
              </el-select>
            </el-form-item>
            <el-form-item label="储存要求" prop="storageReq">
              <el-select v-model="form.storageReq" style="width: 100%">
                <el-option label="冷链配送" value="冷链配送" />
                <el-option label="冷链保鲜" value="冷链保鲜" />
                <el-option label="常温通风" value="常温通风" />
              </el-select>
            </el-form-item>
          </div>

          <el-form-item label="产地 / 备注" prop="originRemark">
            <el-input
              v-model="form.originRemark" type="textarea" :rows="3"
              maxlength="120" show-word-limit placeholder="填写产地、采摘与质检说明"
            />
          </el-form-item>

          <el-form-item label="产品 / 质检照片">
            <el-upload
              class="upload-box" action="#" :auto-upload="false" :show-file-list="false"
              :limit="6" accept=".jpg,.jpeg,.png"
            >
              <div class="upload-inner">
                <el-icon class="up-icon"><Plus /></el-icon>
                <div class="up-text">点击上传产品/质检照片</div>
                <div class="up-hint">支持 JPG/PNG，最多 6 张</div>
              </div>
            </el-upload>
          </el-form-item>

          <div class="form-actions">
            <el-button v-if="mode === 'edit'" @click="resetForm">取消编辑</el-button>
            <el-button type="primary" :loading="saving" class="submit-btn" @click="submit">
              {{ mode === 'edit' ? '保存修改' : '发布货源' }}
            </el-button>
          </div>
        </el-form>
      </SectionCard>
    </div>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { commonApi, farmerApi } from '@/api'
import StatCard from '@/components/StatCard.vue'
import SectionCard from '@/components/SectionCard.vue'
import StatusTag from '@/components/StatusTag.vue'
import { fmtMoney, fmtNum } from '@/utils/format'

const DEFAULT_REMARK = '产地：景德镇近郊绿源基地；新鲜采摘、农残检测合格，支持食堂直供。'

const loading = ref(false)
const saving = ref(false)
const records = ref([])
const stats = ref({})
const products = ref([])
const pageInfo = ref({ total: 0, page: 1, size: 10, pages: 0 })
const mode = ref('create')
const editId = ref(null)
const editStatus = ref('正常')
const formRef = ref(null)

const query = reactive({ page: 1, size: 10, keyword: '', status: '' })

const form = reactive({
  productId: null,
  quantity: 500,
  price: 1.8,
  unit: 'kg',
  deliveryMode: '现货·当日发',
  storageReq: '冷链配送',
  originRemark: DEFAULT_REMARK
})

const rules = {
  productId: [{ required: true, message: '请选择产品', trigger: 'change' }],
  quantity: [{ required: true, message: '请输入可供数量', trigger: 'change' }],
  price: [{ required: true, message: '请输入报价', trigger: 'change' }]
}

const storageText = (v) => {
  if (!v) return '—'
  if (v.includes('常温')) return '常温'
  if (v.includes('冷链')) return '冷链'
  return v
}

const storageCls = (v) => (String(v || '').includes('常温') ? 'tag-gray' : 'tag-teal')

const load = async () => {
  loading.value = true
  try {
    const res = await farmerApi.sources({
      page: query.page,
      size: query.size,
      keyword: query.keyword || undefined,
      status: query.status || undefined
    })
    const d = res.data || {}
    records.value = d.page?.records || []
    pageInfo.value = {
      total: Number(d.page?.total || 0),
      page: Number(d.page?.page || query.page),
      size: Number(d.page?.size || query.size),
      pages: Number(d.page?.pages || 0)
    }
    stats.value = d.stats || {}
  } finally {
    loading.value = false
  }
}

const loadProducts = async () => {
  try {
    const res = await commonApi.products()
    products.value = res.data || []
  } catch (e) {
    products.value = []
  }
}

const search = () => {
  query.page = 1
  load()
}

const onPage = (p) => {
  query.page = p
  load()
}

const onProductChange = (id) => {
  const p = products.value.find((x) => x.id === id)
  if (!p) return
  if (p.avgPrice !== null && p.avgPrice !== undefined) form.price = Number(p.avgPrice)
  if (p.storageReq) form.storageReq = p.storageReq
  if (p.unit) form.unit = p.unit
}

const focusForm = () => {
  resetForm()
  document.querySelector('.src-form')?.scrollIntoView({ behavior: 'smooth', block: 'center' })
}

const edit = (row) => {
  mode.value = 'edit'
  editId.value = row.id
  editStatus.value = row.status || '正常'
  Object.assign(form, {
    productId: row.productId,
    quantity: Number(row.quantity ?? 1),
    price: Number(row.price ?? 0.01),
    unit: row.unit || 'kg',
    deliveryMode: row.deliveryMode || '现货·当日发',
    storageReq: row.storageReq || '冷链配送',
    originRemark: row.originRemark || DEFAULT_REMARK
  })
  formRef.value?.clearValidate()
}

const resetForm = () => {
  mode.value = 'create'
  editId.value = null
  editStatus.value = '正常'
  Object.assign(form, {
    productId: null,
    quantity: 500,
    price: 1.8,
    unit: 'kg',
    deliveryMode: '现货·当日发',
    storageReq: '冷链配送',
    originRemark: DEFAULT_REMARK
  })
  formRef.value?.clearValidate()
}

const submit = async () => {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return
  saving.value = true
  try {
    const payload = {
      productId: form.productId,
      quantity: form.quantity,
      price: form.price,
      unit: form.unit,
      deliveryMode: form.deliveryMode,
      storageReq: form.storageReq,
      originRemark: form.originRemark,
      status: editId.value ? editStatus.value : '正常'
    }
    if (editId.value) payload.id = editId.value
    await farmerApi.saveSource(payload)
    ElMessage.success(
      editId.value ? '货源修改已保存' : '货源发布成功，已进入智能匹配候选池'
    )
    resetForm()
    query.page = 1
    load()
  } finally {
    saving.value = false
  }
}

const toggle = async (row) => {
  const next = row.status === '正常' ? '下架' : '正常'
  try {
    await farmerApi.sourceStatus(row.id, next)
    ElMessage.success(next === '正常' ? '货源已上架，重新进入智能匹配候选池' : '货源已下架')
    load()
  } catch (e) {
    /* 拦截器已提示 */
  }
}

const exportSources = () => {
  ElMessage.success(`已导出 ${pageInfo.value.total} 条货源数据`)
}

onMounted(async () => {
  await Promise.all([load(), loadProducts()])
})
</script>

<style scoped>
.src-layout {
  display: grid;
  grid-template-columns: minmax(0, 1.7fr) minmax(0, 1fr);
  gap: 14px;
  align-items: start;
}

.search-input {
  width: 186px !important;
}

.p-name {
  font-size: 12.5px;
  font-weight: 600;
  color: #1b2a24;
  line-height: 1.4;
}

.p-no {
  font-size: 10.5px;
  color: var(--c-muted-2);
  margin-top: 1px;
}

.price {
  color: #15803d;
}

.src-table :deep(.el-table__cell) {
  padding: 7px 0;
}

.src-table :deep(.cell) {
  padding: 0 8px;
}

/* ---------------- 表单 ---------------- */
.online-tag {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  height: 20px;
  padding: 0 8px;
  border-radius: 10px;
  background: #e8f7ee;
  color: #15803d;
  font-size: 11px;
  font-weight: 500;
}

.online-tag i {
  width: 5px;
  height: 5px;
  border-radius: 50%;
  background: #22c55e;
  box-shadow: 0 0 0 3px rgba(34, 197, 94, 0.14);
}

.src-form {
  padding: 14px 18px 18px;
}

.src-form :deep(.el-form-item) {
  margin-bottom: 13px;
}

.src-form :deep(.el-form-item__label) {
  font-size: 12px;
  color: var(--c-muted);
  padding-bottom: 4px;
  line-height: 1.4;
}

.form-2col {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px;
}

.form-2col :deep(.el-form-item) {
  min-width: 0;
}

.prod-opt {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 14px;
}

.upload-box {
  width: 100%;
}

.upload-box :deep(.el-upload) {
  width: 100%;
}

.upload-inner {
  width: 100%;
  padding: 14px 10px;
  border: 1px dashed var(--c-border-strong);
  border-radius: 9px;
  background: #fafcfb;
  text-align: center;
  transition: all 0.18s;
}

.upload-inner:hover {
  border-color: var(--c-primary-light);
  background: #f4fbf7;
}

.up-icon {
  font-size: 18px;
  color: #16a34a;
}

.up-text {
  margin-top: 3px;
  font-size: 12.5px;
  color: var(--c-text-2);
}

.up-hint {
  margin-top: 3px;
  font-size: 11px;
  color: var(--c-muted-2);
}

.form-actions {
  display: flex;
  gap: 8px;
  padding-top: 2px;
}

.submit-btn {
  flex: 1;
}

.form-editing {
  box-shadow: 0 0 0 1px rgba(22, 163, 74, 0.24), var(--shadow-card);
}

@media (max-width: 1400px) {
  .src-layout {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
