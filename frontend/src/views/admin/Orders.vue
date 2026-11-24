<template>
  <div class="page" v-loading="loading">
    <div class="page-head">
      <div>
        <h2 class="page-title">订单监管</h2>
        <p class="page-sub">全流程监管订单状态流转，支持超时预警、催办与追溯</p>
      </div>
      <div class="page-actions">
        <el-button plain :loading="exporting" @click="exportOrders">
          <el-icon><Download /></el-icon>导出订单
        </el-button>
        <el-button type="primary" @click="printStats">
          <el-icon><Printer /></el-icon>打印统计
        </el-button>
      </div>
    </div>

    <!-- 统计卡片 -->
    <div class="stat-grid">
      <StatCard
        label="待接单" :value="fmtNum(stats.pending)" icon="Bell" color="orange"
        foot="需农户响应"
      />
      <StatCard
        label="配送中" :value="fmtNum(stats.delivering)" icon="Van" color="blue"
        foot="在途批次"
      />
      <StatCard
        label="待结算" :value="fmtNum(stats.pendingSettle)" icon="Wallet" color="teal"
        foot="待审核"
      />
      <StatCard
        label="今日订单" :value="fmtNum(stats.today)" icon="Tickets" color="green"
        :foot="'金额 ¥ ' + fmtWan(stats.todayAmount)"
      />
    </div>

    <!-- 状态机 -->
    <SectionCard title="订单全生命周期状态机">
      <template #extra>
        <span class="muted small">当前选中：</span>
        <b class="mono cur-no">{{ current ? current.orderNo : '—' }}</b>
        <span v-if="current" class="cur-status">（{{ current.statusName }}）</span>
        <span v-if="canceled" class="tag tag-gray" style="margin-left: 8px">订单已取消</span>
      </template>

      <div class="flow">
        <div
          v-for="(s, i) in STEPS"
          :key="s.status"
          class="flow-node"
          :class="{ done: i < activeIdx, active: i === activeIdx }"
        >
          <div class="flow-dot">{{ i + 1 }}</div>
          <div class="flow-name">{{ s.name }}</div>
          <div class="flow-cnt">{{ countOf(s.status) }} 笔</div>
        </div>
      </div>
      <div class="flow-note">
        <el-icon><InfoFilled /></el-icon>
        <span>
          状态流转由业务动作驱动（农户接单 / 质检录入 / 冷链配送 / 食堂入库 / 结算审核），
          管理员可对超时订单发起催办，异常订单可取消。
        </span>
      </div>
    </SectionCard>

    <!-- 列表 -->
    <div class="card">
      <div class="filter-bar">
        <el-input
          v-model="query.keyword"
          placeholder="订单号 / 产品 / 农户"
          clearable
          @keyup.enter="search"
        >
          <template #prefix><el-icon><Search /></el-icon></template>
        </el-input>
        <el-select v-model="query.canteenId" placeholder="收货食堂" clearable @change="search">
          <el-option label="全部食堂" value="" />
          <el-option v-for="c in canteens" :key="c.id" :label="c.name" :value="c.id" />
        </el-select>
        <el-select v-model="query.status" placeholder="订单状态" clearable @change="search" style="width: 132px">
          <el-option label="全部状态" value="" />
          <el-option v-for="s in statusDict" :key="s.status" :label="s.name" :value="s.status" />
          <el-option label="已取消" :value="99" />
        </el-select>
        <el-select v-model="query.days" placeholder="下单时间" @change="search">
          <el-option label="全部时间" value="" />
          <el-option label="近 7 天" :value="7" />
          <el-option label="近 30 天" :value="30" />
        </el-select>
        <el-button type="primary" @click="search">
          <el-icon><Search /></el-icon>查询
        </el-button>
        <el-button plain @click="reset">
          <el-icon><RefreshLeft /></el-icon>重置
        </el-button>
        <div class="filter-spacer"></div>
        <span class="muted small">共 {{ list.total }} 笔订单</span>
      </div>

      <div class="table-wrap">
        <el-table
          :data="list.records"
          highlight-current-row
          @row-click="onRowClick"
          style="width: 100%"
        >
          <el-table-column label="订单号" width="150">
            <template #default="{ row }">
              <span class="mono link" @click.stop="openDetail(row)">{{ row.orderNo }}</span>
            </template>
          </el-table-column>
          <el-table-column label="产品" min-width="140">
            <template #default="{ row }">
              <div class="cell-main">{{ row.productName }}</div>
              <div class="cell-sub">{{ row.categoryName }}</div>
            </template>
          </el-table-column>
          <el-table-column label="供货农户" min-width="140">
            <template #default="{ row }">
              <div class="cell-main">{{ row.farmerName }}</div>
              <div class="cell-sub">{{ row.farmerType }}</div>
            </template>
          </el-table-column>
          <el-table-column prop="canteenName" label="收货食堂" min-width="110" />
          <el-table-column label="数量" width="110" align="right">
            <template #default="{ row }">
              <span class="mono">{{ qty(row.quantity) }} {{ row.unit }}</span>
            </template>
          </el-table-column>
          <el-table-column label="订单金额" width="110" align="right">
            <template #default="{ row }">
              <span class="money">¥ {{ fmtMoney(row.totalAmount) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="状态" width="100" align="center">
            <template #default="{ row }">
              <StatusTag type="order" :value="row.status" />
            </template>
          </el-table-column>
          <el-table-column label="下单时间" width="120">
            <template #default="{ row }">
              <span class="mono muted">{{ fmtDate(row.createTime) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="150" align="right" fixed="right">
            <template #default="{ row }">
              <span class="link" @click.stop="openDetail(row)">详情</span>
              <span class="link" @click.stop="openDetail(row)">追踪</span>
              <span v-if="Number(row.status) === 10" class="link urge" @click.stop="urge(row)">催办</span>
            </template>
          </el-table-column>
          <template #empty>
            <div class="empty">当前筛选条件下暂无订单</div>
          </template>
        </el-table>
      </div>

      <div class="pager">
        <span>第 {{ list.page }} / {{ list.pages || 1 }} 页 · 每页 {{ list.size }} 条 · 共 {{ list.total }} 条</span>
        <el-pagination
          background
          layout="prev, pager, next, jumper"
          :total="list.total"
          :page-size="list.size"
          :current-page="list.page"
          @current-change="onPage"
        />
      </div>
    </div>

    <OrderDetailDrawer v-model="drawer" :order-id="drawerId" endpoint="admin" />
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { useFocusLocator, makeListFocus } from '@/utils/focusLocator'
import { ElMessage, ElMessageBox } from 'element-plus'
import { adminApi, commonApi } from '@/api'
import StatCard from '@/components/StatCard.vue'
import SectionCard from '@/components/SectionCard.vue'
import StatusTag from '@/components/StatusTag.vue'
import OrderDetailDrawer from '@/components/OrderDetailDrawer.vue'
import { fmtDate, fmtMoney, fmtNum, fmtWan, today } from '@/utils/format'
import { useAutoRefresh } from '@/utils/useAutoRefresh'

/* 订单状态机节点（与后端 statusDict 一致） */
const STEPS = [
  { status: 10, name: '待接单' },
  { status: 20, name: '已接单' },
  { status: 30, name: '待质检' },
  { status: 40, name: '质检通过' },
  { status: 50, name: '配送中' },
  { status: 70, name: '已入库' },
  { status: 80, name: '待结算' },
  { status: 90, name: '已结算' },
  { status: 100, name: '已完成' }
]

const STEP_INDEX = { 10: 0, 20: 1, 30: 2, 40: 3, 41: 2, 50: 4, 60: 4, 70: 5, 80: 6, 90: 7, 100: 8, 99: -1 }

const loading = ref(false)
const exporting = ref(false)
const stats = ref({})
const statusDict = ref([])
const canteens = ref([])
const list = reactive({ records: [], total: 0, page: 1, size: 10, pages: 0 })
const query = reactive({ page: 1, size: 10, status: '', canteenId: '', keyword: '', days: '' })

const current = ref(null)
const drawer = ref(false)
const drawerId = ref(null)

const tab = computed(() => (query.status === '' || query.status === null ? 'all' : String(query.status)))
const canceled = computed(() => Number(current.value?.status) === 99)
const activeIdx = computed(() => {
  if (!current.value) return -1
  return STEP_INDEX[Number(current.value.status)] ?? -1
})

const countOf = (status) => Number((stats.value.statusCount || {})[String(status)] || 0)

/* 状态分段控件是「按状态看订单」的唯一入口：
   由后端状态字典驱动，覆盖全部状态，不再与下拉框重复（上一版两处并存，
   用户不知道该点哪一个，且下拉里还有 5 个状态在标签里根本选不到）。 */
const tabs = computed(() => {
  const sc = stats.value.statusCount || {}
  const dict = (statusDict.value || []).map((s) => ({
    key: String(s.status),
    label: s.name,
    count: Number(sc[String(s.status)] || 0)
  }))
  return [
    { key: 'all', label: '全部', count: Number(stats.value.total || 0) },
    ...dict,
    { key: '99', label: '已取消', count: Number(sc['99'] || 0) }
  ]
})

const qty = (v) => {
  if (v === null || v === undefined) return '—'
  return fmtNum(v, Number.isInteger(Number(v)) ? 0 : 2)
}

/* 查询参数：剔除空值，避免后端类型转换异常 */
const params = () => {
  const p = { ...query }
  Object.keys(p).forEach((k) => {
    if (p[k] === '' || p[k] === null || p[k] === undefined) delete p[k]
  })
  return p
}

const load = async () => {
  loading.value = true
  try {
    const res = await adminApi.orders(params())
    const d = res.data || {}
    Object.assign(list, d.page || { records: [], total: 0, page: 1, size: 10, pages: 0 })
    stats.value = d.stats || {}
    statusDict.value = d.statusDict || []
    if (!current.value || !list.records.some((r) => r.id === current.value.id)) {
      current.value = list.records[0] || null
    }
  } catch (e) {
    /* 拦截器已提示 */
  } finally {
    loading.value = false
  }
}

const loadCanteens = async () => {
  try {
    const res = await commonApi.canteens()
    canteens.value = res.data || []
  } catch (e) {
    canteens.value = []
  }
}

const search = () => {
  query.page = 1
  load()
}

const reset = () => {
  query.keyword = ''
  query.canteenId = ''
  query.status = ''
  query.days = ''
  query.page = 1
  load()
}

const switchTab = (key) => {
  query.status = key === 'all' ? '' : Number(key)
  query.page = 1
  load()
}

const onPage = (p) => {
  query.page = p
  load()
}

const onRowClick = (row) => {
  current.value = row
}

const openDetail = (row) => {
  current.value = row
  drawerId.value = row.id
  drawer.value = true
}

const urge = async (row) => {
  try {
    await ElMessageBox.confirm(
      `确认向供货农户「${row.farmerName}」催办订单 ${row.orderNo}？系统将发送站内消息与短信通知尽快接单。`,
      '催办订单',
      { type: 'warning', confirmButtonText: '发送催办', cancelButtonText: '取消' }
    )
  } catch (e) {
    return
  }
  try {
    await adminApi.urgeOrder(row.id)
    ElMessage.success('催办通知已发送：' + row.orderNo)
    load()
  } catch (e) {
    /* 拦截器已提示 */
  }
}

const printStats = () => {
  ElMessage.success(
    `统计视图已发送打印：订单 ${fmtNum(stats.value.total)} 笔 · 待接单 ${fmtNum(stats.value.pending)} · ` +
      `配送中 ${fmtNum(stats.value.delivering)} · 待结算 ${fmtNum(stats.value.pendingSettle)}`
  )
}

const downloadCsv = (filename, header, rows) => {
  const esc = (v) => '"' + String(v === null || v === undefined ? '' : v).replace(/"/g, '""') + '"'
  const csv = [header.map(esc).join(','), ...rows.map((r) => r.map(esc).join(','))].join('\r\n')
  const blob = new Blob(['\ufeff' + csv], { type: 'text/csv;charset=utf-8;' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = filename
  a.click()
  URL.revokeObjectURL(url)
}

const exportOrders = async () => {
  exporting.value = true
  try {
    const res = await adminApi.orders({ ...params(), page: 1, size: 500 })
    const rows = res.data?.page?.records || []
    if (!rows.length) {
      ElMessage.warning('当前筛选条件下暂无可导出的订单')
      return
    }
    downloadCsv(
      `订单监管_${today()}.csv`,
      ['订单号', '产品', '品类', '供货农户', '收货食堂', '数量', '单位', '订单金额', '状态', '下单时间', '批次号'],
      rows.map((r) => [
        r.orderNo, r.productName, r.categoryName, r.farmerName, r.canteenName,
        qty(r.quantity), r.unit, r.totalAmount, r.statusName, fmtDate(r.createTime), r.batchNo || ''
      ])
    )
    ElMessage.success(`已导出 ${rows.length} 条订单记录`)
  } catch (e) {
    /* 拦截器已提示 */
  } finally {
    exporting.value = false
  }
}

useAutoRefresh(load)
/* 从通知/待办工作台带 ?focus=<单号> 进来时，自动筛选并打开这一单 */
useFocusLocator(makeListFocus({ query, load, rows: () => list.records, open: openDetail }))
onMounted(() => {
  loadCanteens()
  load()
})
</script>

<style scoped>
.cur-no {
  color: var(--ink);
  font-weight: 700;
}

.cur-status {
  color: var(--accent);
  font-size: var(--fs-sm);
  font-weight: 600;
}

/* 状态机 */
.flow {
  display: flex;
  align-items: flex-start;
  padding: 4px 0 2px;
}

.flow-node {
  flex: 1;
  min-width: 0;
  position: relative;
  text-align: center;
  padding: 0 2px;
}

.flow-node::before {
  content: '';
  position: absolute;
  left: 50%;
  top: 13px;
  width: 100%;
  height: 2px;
  background: #eef2f0;
  z-index: 0;
}

.flow-node:last-child::before {
  display: none;
}

.flow-node.done::before {
  background: #bbf7d0;
}

.flow-dot {
  position: relative;
  z-index: 1;
  width: 26px;
  height: 26px;
  margin: 0 auto;
  border-radius: 50%;
  display: grid;
  place-items: center;
  font-size: var(--fs-xs);
  font-weight: 700;
  background: #f4f7f6;
  color: var(--muted);
  box-shadow: 0 0 0 1px #e2e8e5;
  transition: all 0.2s;
}

.flow-node.done .flow-dot {
  background: #dcfce7;
  color: var(--accent);
  box-shadow: 0 0 0 1px var(--accent-soft);
}

.flow-node.active .flow-dot {
  background: linear-gradient(135deg, #34d399, var(--accent));
  color: #fff;
  box-shadow: 0 0 0 3px rgba(34, 197, 94, 0.16);
}

.flow-name {
  margin-top: 7px;
  font-size: var(--fs-sm);
  color: var(--muted);
  white-space: nowrap;
}

.flow-node.done .flow-name {
  color: var(--ink-2);
}

.flow-node.active .flow-name {
  color: var(--accent);
  font-weight: 700;
}

.flow-cnt {
  margin-top: 2px;
  font-size: var(--fs-2xs);
  color: #b6c2bd;
  font-family: var(--font-num);
}

.flow-note {
  display: flex;
  align-items: flex-start;
  gap: 6px;
  margin-top: 14px;
  padding-top: 11px;
  border-top: 1px dashed var(--c-border);
  font-size: var(--fs-xs);
  color: var(--c-muted);
  line-height: 1.7;
}

.flow-note .el-icon {
  color: var(--c-primary);
  margin-top: 2px;
}

/* Tabs */
.tabs-row {
  display: flex;
  flex-wrap: nowrap;
  overflow-x: auto;
  scrollbar-width: thin;
  align-items: center;
  padding: 8px 14px 0;
  border-bottom: 1px solid var(--c-border);
}
.tabs-row .tab-btn { flex: 0 0 auto; }

.tab-btn {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 7px 12px 9px;
  border: none;
  border-bottom: 2px solid transparent;
  background: transparent;
  font-family: inherit;
  font-size: var(--fs-base);
  color: var(--muted);
  cursor: pointer;
  transition: color 0.16s;
}

.tab-btn:hover {
  color: var(--accent);
}

.tab-btn.on {
  color: var(--accent);
  font-weight: 600;
  border-bottom-color: var(--accent);
}

.tab-num {
  min-width: 18px;
  padding: 0 6px;
  border-radius: 10px;
  background: var(--surface-2);
  color: var(--muted);
  font-family: var(--font-num);
  font-size: var(--fs-xs);
  line-height: 16px;
  text-align: center;
}

.tab-btn.on .tab-num {
  background: var(--accent-soft);
  color: var(--accent);
}

/* 单元格 */
.cell-main {
  font-size: var(--fs-base);
  font-weight: 600;
  color: var(--ink);
}

.cell-sub {
  margin-top: 2px;
  font-size: var(--fs-xs);
  color: var(--muted);
}

.link.urge {
  color: var(--warn);
}

.link.urge:hover {
  color: var(--warn);
}
</style>
