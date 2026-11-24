<template>
  <div class="page" v-loading="loading">
    <div class="page-head">
      <div>
        <h2 class="page-title">订单管理</h2>
        <p class="page-sub">查看食堂采购订单，完成接单、发货与批次绑定，跟踪订单全流程</p>
      </div>
      <div class="page-actions">
        <el-button plain @click="exportOrders"><el-icon><Download /></el-icon>导出订单</el-button>
        <el-button type="primary" plain @click="load"><el-icon><Refresh /></el-icon>刷新</el-button>
      </div>
    </div>

    <div class="stat-grid">
      <StatCard
        label="待接单" :value="countOf(10)" icon="Tickets" color="orange"
        foot="等待响应"
      />
      <StatCard
        label="配送中" :value="countOf(50)" icon="Van" color="blue"
        foot="进行中"
      />
      <StatCard
        label="待结算" :value="countOf(80)" icon="Wallet" color="teal"
        foot="待食堂结算"
      />
      <StatCard
        label="已完成" :value="countOf(100)" icon="CircleCheck" color="green"
        foot="履约良好"
      />
    </div>

    <SectionCard title="订单列表" :body-style="false" noPad>
      <template #extra>
        <span class="muted small">共 {{ allCount }} 笔订单</span>
      </template>

      <div class="tabs-row">
        <el-tabs v-model="tab" class="status-tabs" @tab-change="onTab">
          <el-tab-pane v-for="t in tabItems" :key="t.name" :name="t.name">
            <template #label>
              <span class="tab-label">
                {{ t.label }}<em class="tab-count">{{ t.count }}</em>
              </span>
            </template>
          </el-tab-pane>
        </el-tabs>
      </div>

      <div class="filter-bar">
        <el-input
          v-model="query.keyword" placeholder="订单号 / 产品 / 食堂" clearable class="kw-input"
          @keyup.enter="doSearch"
        >
          <template #prefix><el-icon><Search /></el-icon></template>
        </el-input>
        <!-- 订单状态由上方带数量的分段控件统一表达，这里不再放重复的下拉 -->
        <el-select v-model="query.days" placeholder="下单时间">
          <el-option label="近 30 天" :value="30" />
          <el-option label="近 7 天" :value="7" />
          <el-option label="全部" value="" />
        </el-select>
        <el-button type="primary" plain @click="doSearch">
          <el-icon><Search /></el-icon>查询
        </el-button>
        <el-button @click="reset">重置</el-button>
      </div>

      <div class="table-wrap">
        <el-table
          :data="rows" size="small" class="order-table" :show-overflow-tooltip="true"
          empty-text="暂无符合条件的订单" @row-dblclick="openDetail"
        >
          <el-table-column label="订单号" min-width="138">
            <template #default="{ row }">
              <span class="mono">{{ row.orderNo }}</span>
            </template>
          </el-table-column>
          <el-table-column label="产品" min-width="108">
            <template #default="{ row }">
              <div class="p-name">{{ row.productName }}</div>
              <div class="p-sub">{{ row.categoryName }}</div>
            </template>
          </el-table-column>
          <el-table-column prop="canteenName" label="收货食堂" min-width="96" />
          <el-table-column label="数量" width="94">
            <template #default="{ row }">
              <span class="mono">{{ fmtNum(row.quantity) }}</span>
              <span class="muted small"> {{ row.unit }}</span>
            </template>
          </el-table-column>
          <el-table-column label="订单金额" width="106">
            <template #default="{ row }">
              <span class="money">¥ {{ fmtMoney(row.totalAmount) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="状态" width="88">
            <template #default="{ row }">
              <StatusTag type="order" :value="row.status" />
            </template>
          </el-table-column>
          <el-table-column label="下单时间" width="126">
            <template #default="{ row }">
              <span class="small">{{ fmtDate(row.createTime) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="158" fixed="right">
            <template #default="{ row }">
              <template v-if="row.status === 10">
                <span class="link" @click="accept(row)">接单</span>
                <span class="link link-danger" @click="reject(row)">拒单</span>
              </template>
              <template v-else-if="row.status === 20">
                <span class="link" @click="ship(row)">发货</span>
                <span class="link" @click="openDetail(row)">详情追踪</span>
              </template>
              <template v-else-if="row.status === 30 || row.status === 50">
                <span class="link" @click="openDetail(row)">物流</span>
                <span class="link" @click="copyBatch(row)">批次码</span>
              </template>
              <template v-else-if="row.status === 80">
                <span class="link" @click="router.push('/farmer/settlement')">明细</span>
              </template>
              <template v-else>
                <span class="link" @click="openDetail(row)">详情追踪</span>
              </template>
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

    <OrderDetailDrawer v-model="detailVisible" :order-id="detailId" endpoint="farmer" />
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { useFocusLocator, makeListFocus } from '@/utils/focusLocator'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { farmerApi } from '@/api'
import StatCard from '@/components/StatCard.vue'
import SectionCard from '@/components/SectionCard.vue'
import StatusTag from '@/components/StatusTag.vue'
import OrderDetailDrawer from '@/components/OrderDetailDrawer.vue'
import { fmtDate, fmtMoney, fmtNum } from '@/utils/format'
import { useAutoRefresh } from '@/utils/useAutoRefresh'
import { exportTableDom, stamped } from '@/utils/export'

const router = useRouter()
const route = useRoute()
const loading = ref(false)
const rows = ref([])
const stats = ref({})
const pageInfo = ref({ total: 0, page: 1, size: 10, pages: 0 })
const tab = ref('all')
const detailVisible = ref(false)
const detailId = ref(null)

const query = reactive({ page: 1, size: 10, status: '', keyword: '', days: '' })

const TAB_VALUES = ['10', '20', '30', '50', '80', '100', '99']

const countOf = (k) => Number(stats.value[String(k)] || 0)

const allCount = computed(() =>
  Object.values(stats.value || {}).reduce((sum, v) => sum + Number(v || 0), 0)
)

const tabItems = computed(() => [
  { name: 'all', label: '全部', count: allCount.value },
  { name: '10', label: '待接单', count: countOf(10) },
  { name: '20', label: '已接单', count: countOf(20) },
  { name: '30', label: '待质检', count: countOf(30) },
  { name: '50', label: '配送中', count: countOf(50) },
  { name: '80', label: '待结算', count: countOf(80) },
  { name: '100', label: '已完成', count: countOf(100) },
  { name: '99', label: '已取消', count: countOf(99) }
])

const syncTab = () => {
  const s = String(query.status || '')
  tab.value = s === '' ? 'all' : TAB_VALUES.includes(s) ? s : 'none'
}

const load = async () => {
  loading.value = true
  try {
    const res = await farmerApi.orders({
      page: query.page,
      size: query.size,
      status: query.status || undefined,
      keyword: query.keyword || undefined,
      days: query.days === '' ? undefined : query.days
    })
    const d = res.data || {}
    rows.value = d.page?.records || []
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

const onTab = (name) => {
  if (name === 'none') return
  query.status = name === 'all' ? '' : name
  query.page = 1
  load()
}

const doSearch = () => {
  query.page = 1
  syncTab()
  load()
}

const reset = () => {
  query.page = 1
  query.status = ''
  query.keyword = ''
  query.days = ''
  tab.value = 'all'
  load()
}

const onPage = (p) => {
  query.page = p
  load()
}

const openDetail = (row) => {
  detailId.value = row.id
  detailVisible.value = true
}

const accept = async (row) => {
  try {
    await ElMessageBox.confirm(
      `确认接受订单 ${row.orderNo}？接单后请按时安排采摘与发货。`,
      '确认接单',
      { type: 'success', confirmButtonText: '确认接单', cancelButtonText: '再看看' }
    )
  } catch (e) {
    return
  }
  try {
    await farmerApi.accept(row.id, row.version)
    ElMessage.success('接单成功，请按时安排采摘与发货')
    load()
  } catch (e) {
    ElMessage.warning('订单状态已变化，请刷新后重试')
  }
}

const reject = async (row) => {
  let reason = ''
  try {
    const res = await ElMessageBox.prompt(
      '请填写拒单原因，便于采购方及时调整采购计划',
      '拒绝订单',
      {
        inputPlaceholder: '如：当前产量不足 / 采摘期延后',
        inputValidator: (v) => (v && String(v).trim().length >= 2) || '请至少填写 2 个字的拒单原因',
        confirmButtonText: '确认拒单',
        cancelButtonText: '取消',
        type: 'warning'
      }
    )
    reason = String(res.value || '').trim()
  } catch (e) {
    return
  }
  try {
    await farmerApi.reject(row.id, reason, row.version)
    ElMessage.success('已拒单，订单将退回采购方')
    load()
  } catch (e) {
    ElMessage.warning('订单状态已变化，请刷新后重试')
  }
}

const ship = async (row) => {
  try {
    await ElMessageBox.confirm(
      `确认订单 ${row.orderNo} 已完成采摘并发货？发货后将进入质检环节。`,
      '确认发货',
      { type: 'info', confirmButtonText: '确认发货', cancelButtonText: '取消' }
    )
  } catch (e) {
    return
  }
  try {
    await farmerApi.ship(row.id, row.version)
    ElMessage.success('已确认发货，等待质检')
    load()
  } catch (e) {
    ElMessage.warning('订单状态已变化，请刷新后重试')
  }
}

const copyBatch = async (row) => {
  if (!row.batchNo) {
    ElMessage.info('该订单暂未生成批次码')
    return
  }
  try {
    if (navigator.clipboard?.writeText) {
      await navigator.clipboard.writeText(row.batchNo)
    } else {
      const ta = document.createElement('textarea')
      ta.value = row.batchNo
      document.body.appendChild(ta)
      ta.select()
      document.execCommand('copy')
      ta.remove()
    }
    ElMessage.success('批次码已复制：' + row.batchNo)
  } catch (e) {
    ElMessage.info('批次码：' + row.batchNo)
  }
}

const exportOrders = () => {
  const n = exportTableDom(stamped('农户订单'))
  ElMessage.success(n ? `已导出当前页 ${n} 条记录` : '当前没有可导出的数据')
}

useAutoRefresh(load)
/* 从工作台带 ?focus=<订单号> 进来时自动筛选并打开该订单 */
useFocusLocator(makeListFocus({ query, load, rows: () => rows.value, open: openDetail }))
onMounted(() => {
  /* 「怎么用」引导里的每一步要直接落到对应状态标签，
     否则用户点「去接单」进来还是"全部"，又得自己找——这正是上一轮反馈的问题。 */
  const st = String(route.query.status || '')
  if (st && TAB_VALUES.includes(st)) {
    query.status = st
    syncTab()
  }
  load()
})
</script>

<style scoped>
.kw-input {
  width: 210px !important;
}

.tabs-row {
  padding: 0 18px;
  border-bottom: 1px solid var(--c-border);
}

.status-tabs :deep(.el-tabs__header) {
  margin: 0;
}

.status-tabs :deep(.el-tabs__nav-wrap::after) {
  display: none;
}

.status-tabs :deep(.el-tabs__item) {
  height: 44px;
  line-height: 44px;
  padding: 0 2px;
  margin-right: 24px;
  font-size: var(--fs-base);
  color: var(--c-muted);
}

.status-tabs :deep(.el-tabs__item.is-active) {
  color: var(--c-primary-dark);
  font-weight: 600;
}

.status-tabs :deep(.el-tabs__active-bar) {
  height: 2.5px;
  border-radius: 2px;
  background: var(--c-primary);
}

.status-tabs :deep(.el-tabs__content) {
  display: none;
}

.tab-label {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}

.tab-count {
  display: inline-grid;
  place-items: center;
  min-width: 18px;
  height: 18px;
  padding: 0 5px;
  border-radius: 9px;
  background: #f0f4f2;
  color: var(--muted);
  font-size: var(--fs-2xs);
  font-style: normal;
  font-weight: 600;
  line-height: 1;
}

.status-tabs :deep(.el-tabs__item.is-active) .tab-count {
  background: var(--accent-soft);
  color: var(--accent);
}

.filter-bar :deep(.el-select) {
  width: 144px;
}

.p-name {
  font-size: var(--fs-sm);
  font-weight: 600;
  color: var(--ink);
  line-height: 1.4;
}

.p-sub {
  font-size: var(--fs-2xs);
  color: var(--c-muted-2);
  margin-top: 1px;
}

.link-danger {
  color: var(--danger);
}

.link-danger:hover {
  color: var(--danger);
}

.order-table :deep(.el-table__cell) {
  padding: 8px 0;
}

.order-table :deep(.cell) {
  padding: 0 9px;
}
</style>
