<template>
  <div class="page">
    <div class="page-head">
      <div>
        <h2 class="page-title">我的订单</h2>
        <p class="page-sub">{{ store.orgName || '本食堂' }}采购订单全流程跟踪，支持收货入库、结算与溯源</p>
      </div>
      <div class="page-actions">
        <el-button plain @click="router.push('/purchaser/demands')">
          <el-icon><EditPen /></el-icon>发布需求
        </el-button>
        <el-button type="primary" @click="exportOrders">
          <el-icon><Download /></el-icon>导出
        </el-button>
      </div>
    </div>

    <!-- 统计卡 -->
    <div class="stat-grid">
      <StatCard label="进行中" :value="fmtNum(ongoing)" foot="配送 / 待接" icon="Van" color="blue" />
      <StatCard label="待入库" :value="fmtNum(stats['60'] || 0)" foot="已送达" icon="Box" color="teal" />
      <StatCard label="待结算" :value="fmtNum(stats['80'] || 0)" foot="待审核" icon="Money" color="orange" />
      <StatCard
        label="本月完成"
        :value="fmtNum(stats['100'] || 0)"
        :foot="'合计 ' + fmtNum(stats['100'] || 0) + ' 笔'"
        icon="CircleCheck"
        color="green"
      />
    </div>

    <div class="card">
      <div class="card-head">
        <div class="card-title">订单列表</div>
        <div class="card-extra">
          <el-radio-group v-model="viewMode" size="small">
            <el-radio-button label="order">按履约单</el-radio-button>
            <el-radio-button label="po">按采购单</el-radio-button>
          </el-radio-group>
        </div>
      </div>

      <div class="filter-bar">
        <el-input
          v-model="keyword"
          :placeholder="viewMode === 'order' ? '订单号 / 产品 / 农户' : '采购单号 / 品名'"
          clearable
          style="width: 200px"
          @keyup.enter="search"
        >
          <template #prefix><el-icon><Search /></el-icon></template>
        </el-input>
        <!-- 状态由下方的分段控件（履约单）或状态下拉（采购单）统一表达，这里不再重复 -->
        <el-select v-if="viewMode === 'order'" v-model="days" placeholder="全部时间" style="width: 118px">
          <el-option label="全部时间" value="" />
          <el-option label="近 7 天" :value="7" />
          <el-option label="近 30 天" :value="30" />
        </el-select>
        <el-button type="primary" @click="search"><el-icon><Search /></el-icon>查询</el-button>
        <el-button plain @click="reset">重置</el-button>
        <div class="filter-spacer"></div>
        <span class="muted small">{{ viewMode === 'order' ? '状态流水以订单状态机为准' : poSummary }}</span>
      </div>

      <el-tabs v-model="status" class="status-tabs" v-show="viewMode === 'order'">
        <el-tab-pane label="全部" name="ALL" />
        <el-tab-pane label="待接单" name="10" />
        <el-tab-pane label="配送中" name="50" />
        <el-tab-pane label="待入库" name="60" />
        <el-tab-pane label="待结算" name="80" />
        <el-tab-pane label="已完成" name="100" />
      </el-tabs>

      <div v-if="viewMode === 'po'" class="po-status-row">
        <el-select v-model="poStatus" placeholder="全部状态" clearable style="width: 130px" @change="poSearch">
          <el-option label="待接单" value="待接单" />
          <el-option label="进行中" value="进行中" />
          <el-option label="已完成" value="已完成" />
          <el-option label="已取消" value="已取消" />
        </el-select>
      </div>

      <div class="table-wrap" v-loading="loading" v-show="viewMode === 'order'">
        <el-table :data="rows" size="default" style="width: 100%">
          <el-table-column label="订单号" min-width="138">
            <template #default="{ row }"><span class="mono">{{ row.orderNo }}</span></template>
          </el-table-column>
          <el-table-column label="产品" min-width="96">
            <template #default="{ row }">
              <span class="bold">{{ row.productName }}</span>
              <span class="muted small"> · {{ row.categoryName }}</span>
            </template>
          </el-table-column>
          <el-table-column prop="farmerName" label="供货农户" min-width="124" show-overflow-tooltip />
          <el-table-column label="数量" width="96" align="right">
            <template #default="{ row }">{{ fmtNum(row.quantity) }} {{ row.unit }}</template>
          </el-table-column>
          <el-table-column label="订单金额" width="110" align="right">
            <template #default="{ row }"><span class="money">¥{{ fmtMoney(row.totalAmount) }}</span></template>
          </el-table-column>
          <el-table-column label="状态" width="94">
            <template #default="{ row }"><StatusTag type="order" :value="row.status" /></template>
          </el-table-column>
          <el-table-column label="下单时间" width="122">
            <template #default="{ row }">{{ fmtDate(row.createTime) }}</template>
          </el-table-column>
          <el-table-column label="操作" width="108" fixed="right">
            <template #default="{ row }">
              <span v-if="row.status === 60" class="link" @click="goInbound(row)">入库</span>
              <template v-else>
                <span class="link" @click="openDetail(row)">详情</span>
                <span v-if="row.batchNo" class="link" @click="goTrace(row)">溯源</span>
              </template>
            </template>
          </el-table-column>
          <template #empty><div class="empty">暂无符合条件的订单</div></template>
        </el-table>
      </div>

      <div class="pager" v-show="viewMode === 'order'">
        <span>共 {{ total }} 笔 · 第 {{ page }} / {{ pages }} 页</span>
        <el-pagination
          v-model:current-page="page"
          :page-size="size"
          :total="total"
          layout="prev, pager, next"
          background
          @current-change="load"
        />
      </div>

      <!-- 按采购单：一次提交的多个品项聚合在一行，点开看各履约子单 -->
      <div v-if="viewMode === 'po'" class="po-view">
        <div class="table-wrap" v-loading="poLoading">
          <el-table :data="poRows" size="default" style="width: 100%">
            <el-table-column label="采购单号" min-width="130">
              <template #default="{ row }"><span class="mono">{{ row.poNo }}</span></template>
            </el-table-column>
            <el-table-column label="品项" min-width="230">
              <template #default="{ row }">
                <span class="po-items">{{ row.itemsText || '—' }}</span>
              </template>
            </el-table-column>
            <el-table-column label="品项数" width="84" align="center">
              <template #default="{ row }">
                <span class="po-cnt">{{ row.orderCount }}</span>
              </template>
            </el-table-column>
            <el-table-column label="合计金额" width="118" align="right">
              <template #default="{ row }"><span class="money">¥{{ fmtMoney(row.totalAmount) }}</span></template>
            </el-table-column>
            <el-table-column label="状态" width="96" align="center">
              <template #default="{ row }">
                <span class="tag" :class="poTagClass(row.status)">{{ row.status }}</span>
              </template>
            </el-table-column>
            <el-table-column label="下单时间" width="122">
              <template #default="{ row }">{{ fmtDate(row.createTime) }}</template>
            </el-table-column>
            <el-table-column label="操作" width="96" align="center" fixed="right">
              <template #default="{ row }">
                <span class="link-strong" @click="openPo(row)">明细</span>
              </template>
            </el-table-column>
            <template #empty><div class="empty">还没有采购单，去货源大厅勾选品项提交</div></template>
          </el-table>
        </div>

        <div class="pager">
          <span>共 {{ poTotal }} 张 · 第 {{ poPage }} / {{ poPages }} 页</span>
          <el-pagination
            v-model:current-page="poPage"
            :page-size="size"
            :total="poTotal"
            layout="prev, pager, next"
            background
            @current-change="loadPo"
          />
        </div>
      </div>
    </div>

    <!-- 采购单明细 -->
    <el-drawer v-model="poDrawer" title="采购单明细" size="620px">
      <template v-if="poDetail">
        <div class="po-head">
          <div class="po-no mono">{{ poDetail.po.poNo }}</div>
          <div class="po-meta">
            <span>{{ poDetail.po.itemCount }} 个品项</span>
            <span class="money">¥{{ fmtMoney(poDetail.po.totalAmount) }}</span>
            <span>{{ fmtDate(poDetail.po.createTime) }} 提交</span>
          </div>
        </div>
        <el-table :data="poDetail.items" size="small" style="width: 100%">
          <el-table-column label="订单号" min-width="130">
            <template #default="{ row }"><span class="mono">{{ row.orderNo }}</span></template>
          </el-table-column>
          <el-table-column prop="productName" label="品项" min-width="90" />
          <el-table-column label="数量" width="96" align="right">
            <template #default="{ row }">{{ fmtNum(row.quantity) }} {{ row.unit }}</template>
          </el-table-column>
          <el-table-column label="金额" width="94" align="right">
            <template #default="{ row }"><span class="money">¥{{ fmtMoney(row.totalAmount) }}</span></template>
          </el-table-column>
          <el-table-column label="状态" width="86" align="center">
            <template #default="{ row }"><StatusTag type="order" :value="row.status" /></template>
          </el-table-column>
        </el-table>
        <div class="po-tip">
          采购单是采购员的一次提交；每行是一张履约子单，质检、配送、入库、结算都按子单走。
        </div>
      </template>
    </el-drawer>

    <OrderDetailDrawer v-model="drawerVisible" :order-id="currentId" endpoint="purchaser" />
  </div>
</template>

<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { useFocusLocator, makeListFocus } from '@/utils/focusLocator'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { purchaserApi } from '@/api'
import { useUserStore } from '@/store/user'
import StatCard from '@/components/StatCard.vue'
import StatusTag from '@/components/StatusTag.vue'
import OrderDetailDrawer from '@/components/OrderDetailDrawer.vue'
import { fmtDate, fmtMoney, fmtNum } from '@/utils/format'
import { useAutoRefresh } from '@/utils/useAutoRefresh'
import { exportTableDom, stamped } from '@/utils/export'

const router = useRouter()
const store = useUserStore()

const loading = ref(false)
const rows = ref([])
const stats = ref({})
const total = ref(0)
const pages = ref(1)
const page = ref(1)
const size = ref(10)
const keyword = ref('')
const status = ref('ALL')
const days = ref('')

const drawerVisible = ref(false)
const currentId = ref(null)

/* ---- 按采购单视图 ---- */
const viewMode = ref('order')
const poRows = ref([])
const poStats = ref({})
const poTotal = ref(0)
const poPages = ref(1)
const poPage = ref(1)
const poStatus = ref('')
const poLoading = ref(false)
const poDrawer = ref(false)
const poDetail = ref(null)

const poTagClass = (s) => {
  if (s === '已完成') return 'tag-green'
  if (s === '进行中') return 'tag-blue'
  if (s === '已取消') return 'tag-gray'
  return 'tag-orange'
}

const loadPo = async () => {
  poLoading.value = true
  try {
    const res = await purchaserApi.purchaseOrders({
      page: poPage.value,
      size: size.value,
      status: poStatus.value || undefined,
      keyword: keyword.value.trim() || undefined
    })
    const payload = res.data || {}
    poRows.value = payload.page?.records || []
    poTotal.value = Number(payload.page?.total || 0)
    poPages.value = Number(payload.page?.pages || 1)
    poStats.value = payload.stats || {}
  } catch (e) {
    /* 错误提示由请求拦截器统一处理 */
  } finally {
    poLoading.value = false
  }
}

const openPo = async (row) => {
  poDrawer.value = true
  poDetail.value = null
  try {
    const res = await purchaserApi.purchaseOrder(row.id)
    poDetail.value = res.data || null
  } catch (e) {
    /* 错误提示由请求拦截器统一处理 */
  }
}

watch(viewMode, (v) => {
  if (v === 'po' && !poRows.value.length) loadPo()
})

const ongoing = computed(
  () =>
    Number(stats.value['10'] || 0) +
    Number(stats.value['20'] || 0) +
    Number(stats.value['30'] || 0) +
    Number(stats.value['40'] || 0) +
    Number(stats.value['50'] || 0)
)

const load = async () => {
  loading.value = true
  try {
    const res = await purchaserApi.orders({
      page: page.value,
      size: size.value,
      status: status.value === 'ALL' ? undefined : Number(status.value),
      keyword: keyword.value.trim() || undefined,
      days: days.value === '' ? undefined : days.value
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

const search = () => {
  if (viewMode.value === 'po') {
    poPage.value = 1
    loadPo()
    return
  }
  page.value = 1
  load()
}

const poSearch = () => {
  poPage.value = 1
  loadPo()
}

const reset = () => {
  keyword.value = ''
  status.value = 'ALL'
  days.value = ''
  poStatus.value = ''
  page.value = 1
  poPage.value = 1
  if (viewMode.value === 'po') {
    loadPo()
  } else {
    load()
  }
}

const poSummary = computed(
  () =>
    `近 30 天 ${fmtNum(poStats.value.last30d)} 张采购单 · 多品项 ${fmtNum(poStats.value.multiItem)} 张`
)

watch(status, () => {
  page.value = 1
  load()
})

watch(days, () => {
  page.value = 1
  load()
})

const goInbound = (row) => {
  router.push('/purchaser/inbound?orderNo=' + row.orderNo)
}

const goTrace = (row) => {
  router.push('/purchaser/trace?batchNo=' + row.batchNo)
}

const openDetail = (row) => {
  currentId.value = row.id
  drawerVisible.value = true
}

const exportOrders = () => {
  const n = exportTableDom(stamped('采购订单'))
  ElMessage.success(n ? `已导出当前页 ${n} 条记录` : '当前没有可导出的数据')
}

useAutoRefresh(load)
/* 从工作台带 ?focus=<订单号> 进来时自动筛选并打开该订单 */
useFocusLocator(makeListFocus({
  load, rows: () => rows.value, open: openDetail,
  beforeLoad: (no) => { keyword.value = no; page.value = 1; status.value = 'ALL'; days.value = '' }
}))
onMounted(load)
</script>

<style scoped>
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

.po-view .filter-bar.no-border {
  border-bottom: none;
}

.po-status-row {
  padding: 0 12px 12px;
}

.po-items {
  display: block;
  line-height: 1.5;
  font-size: var(--fs-sm);
  color: var(--ink-2);
}

.po-cnt {
  display: inline-block;
  min-width: 24px;
  padding: 1px 7px;
  border-radius: 999px;
  background: var(--accent-soft, var(--surface-2));
  color: var(--accent);
  font-weight: 700;
  font-size: var(--fs-sm);
}

.po-head {
  padding: 12px 14px;
  margin-bottom: 12px;
  border-radius: 10px;
  background: var(--surface-2);
}

.po-no {
  font-size: var(--fs-md);
  font-weight: 700;
  margin-bottom: 6px;
}

.po-meta {
  display: flex;
  gap: 16px;
  font-size: var(--fs-sm);
  color: var(--c-muted);
}

.po-tip {
  margin-top: 12px;
  padding: 10px 12px;
  border-radius: 8px;
  background: var(--info-soft);
  color: var(--ink-2);
  font-size: var(--fs-xs);
  line-height: 1.6;
}
</style>
