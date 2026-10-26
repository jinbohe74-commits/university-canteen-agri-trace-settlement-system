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
        <div class="card-extra">共 {{ total }} 笔订单 · 数据实时同步</div>
      </div>

      <div class="filter-bar">
        <el-input
          v-model="keyword"
          placeholder="订单号 / 产品 / 农户"
          clearable
          style="width: 200px"
          @keyup.enter="search"
        >
          <template #prefix><el-icon><Search /></el-icon></template>
        </el-input>
        <el-select v-model="status" placeholder="全部状态" style="width: 128px">
          <el-option label="全部状态" value="ALL" />
          <el-option label="待接单" value="10" />
          <el-option label="配送中" value="50" />
          <el-option label="待入库" value="60" />
          <el-option label="待结算" value="80" />
          <el-option label="已完成" value="100" />
        </el-select>
        <el-select v-model="days" placeholder="全部时间" style="width: 118px">
          <el-option label="全部时间" value="" />
          <el-option label="近 7 天" :value="7" />
          <el-option label="近 30 天" :value="30" />
        </el-select>
        <el-button type="primary" @click="search"><el-icon><Search /></el-icon>查询</el-button>
        <el-button plain @click="reset">重置</el-button>
        <div class="filter-spacer"></div>
        <span class="muted small">状态流水以订单状态机为准</span>
      </div>

      <el-tabs v-model="status" class="status-tabs">
        <el-tab-pane label="全部" name="ALL" />
        <el-tab-pane label="待接单" name="10" />
        <el-tab-pane label="配送中" name="50" />
        <el-tab-pane label="待入库" name="60" />
        <el-tab-pane label="待结算" name="80" />
        <el-tab-pane label="已完成" name="100" />
      </el-tabs>

      <div class="table-wrap" v-loading="loading">
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

      <div class="pager">
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
    </div>

    <OrderDetailDrawer v-model="drawerVisible" :order-id="currentId" endpoint="purchaser" />
  </div>
</template>

<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { purchaserApi } from '@/api'
import { useUserStore } from '@/store/user'
import StatCard from '@/components/StatCard.vue'
import StatusTag from '@/components/StatusTag.vue'
import OrderDetailDrawer from '@/components/OrderDetailDrawer.vue'
import { fmtDate, fmtMoney, fmtNum } from '@/utils/format'

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
  page.value = 1
  load()
}

const reset = () => {
  keyword.value = ''
  status.value = 'ALL'
  days.value = ''
  page.value = 1
  load()
}

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
  ElMessage.success(`订单导出已生成，共 ${total.value} 笔${store.orgName ? '（' + store.orgName + '）' : ''}`)
}

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
</style>
