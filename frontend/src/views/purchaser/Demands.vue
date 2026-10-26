<template>
  <div class="page">
    <div class="page-head">
      <div>
        <h2 class="page-title">采购需求管理</h2>
        <p class="page-sub">发布采购需求，系统自动推送农户并启动五因子智能匹配</p>
      </div>
      <div class="page-actions">
        <el-button plain @click="exportDemands"><el-icon><Download /></el-icon>导出需求</el-button>
      </div>
    </div>

    <!-- 统计卡 -->
    <div class="stat-grid">
      <StatCard label="匹配中" :value="fmtNum(stats.matching || 0)" foot="需求单" icon="Search" color="orange" />
      <StatCard label="已成交" :value="fmtNum(stats.dealt || 0)" foot="累计" icon="CircleCheck" color="green" />
      <StatCard label="平均报价数" :value="fmtNum(stats.avgQuotes || 0)" foot="每单货源" icon="Shop" color="blue" />
      <StatCard label="已撤回" :value="fmtNum(stats.withdrawn || 0)" foot="累计" icon="RefreshLeft" color="purple" />
    </div>

    <div class="demand-layout">
      <!-- 需求列表 -->
      <div class="card">
        <div class="card-head">
          <div class="card-title">需求列表</div>
          <div class="card-extra">共 {{ total }} 条需求 · 五因子匹配</div>
        </div>

        <div class="filter-bar">
          <el-input
            v-model="keyword"
            placeholder="需求编号 / 产品"
            clearable
            style="width: 190px"
            @keyup.enter="search"
          >
            <template #prefix><el-icon><Search /></el-icon></template>
          </el-input>
          <el-select v-model="status" placeholder="全部状态" style="width: 128px">
            <el-option label="全部状态" value="ALL" />
            <el-option label="处理中" value="处理中" />
            <el-option label="已成交" value="已成交" />
            <el-option label="已撤回" value="已撤回" />
            <el-option label="已完成" value="已完成" />
          </el-select>
          <el-button type="primary" @click="search"><el-icon><Search /></el-icon>查询</el-button>
          <div class="filter-spacer"></div>
          <span class="muted small">需求发布后自动推送同品类认证农户</span>
        </div>

        <el-tabs v-model="status" class="status-tabs">
          <el-tab-pane label="全部" name="ALL" />
          <el-tab-pane label="匹配中" name="处理中" />
          <el-tab-pane label="已成交" name="已成交" />
          <el-tab-pane label="已撤回" name="已撤回" />
        </el-tabs>

        <div class="table-wrap" v-loading="loading">
          <el-table :data="rows" size="default" style="width: 100%">
            <el-table-column label="需求编号" min-width="128">
              <template #default="{ row }"><span class="mono">{{ row.demandNo }}</span></template>
            </el-table-column>
            <el-table-column label="产品" min-width="82">
              <template #default="{ row }"><span class="bold">{{ row.productName || '—' }}</span></template>
            </el-table-column>
            <el-table-column prop="categoryName" label="品类" width="74" />
            <el-table-column label="数量" width="92" align="right">
              <template #default="{ row }">{{ fmtNum(row.quantity) }} {{ row.unit }}</template>
            </el-table-column>
            <el-table-column label="期望价" width="88" align="right">
              <template #default="{ row }"><span class="money">¥{{ fmtMoney(row.expectedPrice) }}</span></template>
            </el-table-column>
            <el-table-column label="期望交货" width="96">
              <template #default="{ row }">{{ fmtMD(row.deadline) }}</template>
            </el-table-column>
            <el-table-column label="状态" width="88">
              <template #default="{ row }"><StatusTag type="demand" :value="row.status" /></template>
            </el-table-column>
            <el-table-column label="报价数" width="70" align="center">
              <template #default="{ row }"><span class="mono">{{ row.quoteCount || 0 }}</span></template>
            </el-table-column>
            <el-table-column label="操作" width="96" fixed="right">
              <template #default="{ row }">
                <span v-if="row.status === '处理中'" class="link" @click="goMatch(row)">匹配结果</span>
                <span v-else-if="row.orderId" class="link" @click="router.push('/purchaser/orders')">订单</span>
                <span v-else class="link" @click="openDetail(row)">详情</span>
              </template>
            </el-table-column>
            <template #empty><div class="empty">暂无采购需求</div></template>
          </el-table>
        </div>

        <div class="pager">
          <span>共 {{ total }} 条 · 第 {{ page }} / {{ pages }} 页</span>
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

      <!-- 发布表单 -->
      <SectionCard title="发布采购需求">
        <el-form label-position="top" class="demand-form">
          <el-form-item label="采购产品">
            <el-select
              v-model="form.productId"
              filterable
              clearable
              placeholder="搜索并选择农产品"
              style="width: 100%"
              @change="onProductChange"
            >
              <el-option
                v-for="p in products"
                :key="p.id"
                :label="p.name + ' · 参考 ¥' + fmtMoney(p.avgPrice) + '/' + (p.unit || 'kg')"
                :value="p.id"
              />
            </el-select>
          </el-form-item>

          <div class="form-row">
            <el-form-item label="需求数量（kg）">
              <el-input-number
                v-model="form.quantity"
                :min="1"
                :max="100000"
                :step="50"
                controls-position="right"
                style="width: 100%"
              />
            </el-form-item>
            <el-form-item label="期望单价（元/kg）">
              <el-input-number
                v-model="form.expectedPrice"
                :min="0.01"
                :max="999"
                :step="0.1"
                :precision="2"
                controls-position="right"
                placeholder="如 2.00"
                style="width: 100%"
              />
            </el-form-item>
          </div>

          <el-form-item label="期望交货时间">
            <el-date-picker
              v-model="form.deadline"
              type="datetime"
              format="YYYY-MM-DD HH:mm"
              value-format="YYYY-MM-DDTHH:mm:ss"
              placeholder="选择期望交货时间"
              :disabled-date="disabledPast"
              style="width: 100%"
            />
          </el-form-item>

          <div class="form-row">
            <el-form-item label="配送方式">
              <el-select v-model="form.deliveryMode" style="width: 100%">
                <el-option label="冷藏配送" value="冷藏配送" />
                <el-option label="冷链配送" value="冷链配送" />
                <el-option label="常温配送" value="常温配送" />
              </el-select>
            </el-form-item>
            <el-form-item label="结算方式">
              <el-select v-model="form.settlementMode" style="width: 100%">
                <el-option label="月结" value="月结" />
                <el-option label="周结" value="周结" />
                <el-option label="现结" value="现结" />
              </el-select>
            </el-form-item>
          </div>

          <el-form-item label="质量要求 / 备注">
            <el-input
              v-model="form.qualityReq"
              type="textarea"
              :rows="4"
              maxlength="200"
              show-word-limit
              placeholder="如：要求新鲜、无黄叶、农残检测合格"
            />
          </el-form-item>

          <el-button type="primary" class="publish-btn" :loading="publishing" @click="publish">
            <el-icon><Plus /></el-icon>发布需求
          </el-button>
          <p class="form-hint">发布后系统自动推送至同品类认证农户，并按「品类 / 价格 / 数量 / 信用 / 距离」五因子计算匹配得分。</p>
        </el-form>
      </SectionCard>
    </div>

    <!-- 需求详情 -->
    <el-dialog v-model="detailVisible" title="需求详情" width="600px">
      <el-descriptions v-if="detailRow" :column="2" border size="small">
        <el-descriptions-item label="需求编号">
          <span class="mono">{{ detailRow.demandNo }}</span>
        </el-descriptions-item>
        <el-descriptions-item label="状态">
          <StatusTag type="demand" :value="detailRow.status" />
        </el-descriptions-item>
        <el-descriptions-item label="产品">{{ detailRow.productName || '—' }}</el-descriptions-item>
        <el-descriptions-item label="品类">{{ detailRow.categoryName || '—' }}</el-descriptions-item>
        <el-descriptions-item label="需求数量">
          {{ fmtNum(detailRow.quantity) }} {{ detailRow.unit }}
        </el-descriptions-item>
        <el-descriptions-item label="期望单价">
          ¥{{ fmtMoney(detailRow.expectedPrice) }} / {{ detailRow.unit }}
        </el-descriptions-item>
        <el-descriptions-item label="期望交货">{{ fmtDate(detailRow.deadline) }}</el-descriptions-item>
        <el-descriptions-item label="配送方式">{{ detailRow.deliveryMode || '—' }}</el-descriptions-item>
        <el-descriptions-item label="结算方式">{{ detailRow.settlementMode || '—' }}</el-descriptions-item>
        <el-descriptions-item label="报价数">{{ detailRow.quoteCount || 0 }} 家</el-descriptions-item>
        <el-descriptions-item label="关联订单">
          <span v-if="detailRow.orderNo" class="mono">{{ detailRow.orderNo }}</span>
          <span v-else class="muted">暂未成交</span>
        </el-descriptions-item>
        <el-descriptions-item label="创建时间">{{ fmtDate(detailRow.createTime) }}</el-descriptions-item>
        <el-descriptions-item label="质量要求 / 备注" :span="2">
          {{ detailRow.qualityReq || '—' }}
        </el-descriptions-item>
      </el-descriptions>
      <template #footer>
        <el-button @click="detailVisible = false">关闭</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { commonApi, purchaserApi } from '@/api'
import StatCard from '@/components/StatCard.vue'
import SectionCard from '@/components/SectionCard.vue'
import StatusTag from '@/components/StatusTag.vue'
import { fmtDate, fmtMoney, fmtNum } from '@/utils/format'

const router = useRouter()

const loading = ref(false)
const publishing = ref(false)
const rows = ref([])
const total = ref(0)
const pages = ref(1)
const page = ref(1)
const size = ref(10)
const keyword = ref('')
const status = ref('ALL')
const stats = ref({})
const products = ref([])

const detailVisible = ref(false)
const detailRow = ref(null)

const DEFAULT_REQ = '要求新鲜、无黄叶、农残检测合格；需提供批次溯源二维码。'

const form = reactive({
  productId: null,
  quantity: 400,
  expectedPrice: null,
  deadline: '',
  deliveryMode: '冷链配送',
  settlementMode: '月结',
  qualityReq: DEFAULT_REQ
})

const fmtMD = (v) => (v ? fmtDate(v).slice(5, 16) : '—')

const disabledPast = (d) => d.getTime() < Date.now() - 86400000

const load = async () => {
  loading.value = true
  try {
    const res = await purchaserApi.demands({
      page: page.value,
      size: size.value,
      keyword: keyword.value.trim() || undefined,
      status: status.value === 'ALL' ? undefined : status.value
    })
    const payload = res.data || {}
    rows.value = payload.page?.records || []
    total.value = Number(payload.page?.total || 0)
    pages.value = Number(payload.page?.pages || 1)
    stats.value = payload.stats || {}
  } catch (e) {
    /* 错误提示由请求拦截器统一处理 */
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
  page.value = 1
  load()
}

watch(status, () => {
  page.value = 1
  load()
})

const onProductChange = (id) => {
  const hit = products.value.find((p) => p.id === id)
  if (hit && !form.expectedPrice) {
    form.expectedPrice = Number(hit.avgPrice || 0) || null
  }
}

const publish = async () => {
  if (!form.productId) return ElMessage.warning('请选择采购产品')
  if (!form.quantity || Number(form.quantity) <= 0) return ElMessage.warning('需求数量必须大于 0')
  if (!form.expectedPrice || Number(form.expectedPrice) <= 0) return ElMessage.warning('请填写期望单价')
  if (!form.deadline) return ElMessage.warning('请选择期望交货时间')
  publishing.value = true
  try {
    await purchaserApi.saveDemand({
      productId: form.productId,
      quantity: form.quantity,
      expectedPrice: form.expectedPrice,
      deadline: form.deadline,
      deliveryMode: form.deliveryMode,
      settlementMode: form.settlementMode,
      qualityReq: form.qualityReq
    })
    ElMessage.success('需求发布成功，系统已启动智能匹配')
    form.quantity = 400
    form.expectedPrice = null
    form.deadline = ''
    form.qualityReq = DEFAULT_REQ
    page.value = 1
    await load()
  } catch (e) {
    /* 错误提示由请求拦截器统一处理 */
  } finally {
    publishing.value = false
  }
}

const goMatch = (row) => {
  router.push('/purchaser/match?demandId=' + row.id)
}

const openDetail = (row) => {
  detailRow.value = row
  detailVisible.value = true
}

const exportDemands = () => {
  ElMessage.success(`已导出 ${total.value} 条采购需求（含期望价、状态与报价数）`)
}

onMounted(() => {
  loadProducts()
  load()
})
</script>

<style scoped>
.demand-layout {
  display: grid;
  grid-template-columns: minmax(0, 1.62fr) minmax(0, 1fr);
  gap: 14px;
  align-items: start;
}

.status-tabs {
  padding: 0 12px;
}

.status-tabs :deep(.el-tabs__header) {
  margin: 0;
}

.status-tabs :deep(.el-tabs__nav-wrap::after) {
  height: 1px;
  background: var(--c-border);
}

.status-tabs :deep(.el-tabs__content) {
  display: none;
}

.demand-form :deep(.el-form-item) {
  margin-bottom: 14px;
}

.demand-form :deep(.el-form-item__label) {
  font-size: 12.5px;
  color: var(--c-muted);
  padding-bottom: 4px;
}

.form-row {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
  gap: 12px;
}

.publish-btn {
  width: 100%;
  height: 38px;
  margin-top: 2px;
  font-weight: 600;
}

.form-hint {
  margin: 10px 0 0;
  font-size: 11.5px;
  line-height: 1.7;
  color: var(--c-muted-2);
}
</style>
