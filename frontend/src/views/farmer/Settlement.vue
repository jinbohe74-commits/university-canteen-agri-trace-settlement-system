<template>
  <div class="page" v-loading="loading">
    <div class="page-head">
      <div>
        <h2 class="page-title">结算与收入</h2>
        <p class="page-sub">查看结算单、损耗扣款明细与收入统计</p>
      </div>
      <div class="page-actions">
        <el-button plain @click="exportBill"><el-icon><Download /></el-icon>导出对账单</el-button>
        <el-button type="primary" plain @click="load"><el-icon><Refresh /></el-icon>刷新</el-button>
      </div>
    </div>

    <div class="stat-grid three">
      <StatCard
        label="本月收入" :value="'¥ ' + fmtMoney(monthIncome)" icon="Money" color="green"
        foot="已结算入账"
      />
      <StatCard
        label="累计收入" :value="'¥ ' + fmtMoney(income.totalIncome)" icon="Wallet" color="blue"
        :foot="`已结算 ${income.settledCount ?? 0} 笔`"
      />
      <StatCard
        label="待审核结算单" :value="income.pendingCount ?? 0" suffix=" 笔" icon="DocumentChecked" color="orange"
        foot="等待平台审核"
      />
    </div>

    <SectionCard title="结算单列表" :body-style="false" noPad>
      <template #extra>
        <span class="muted small">共 {{ pageInfo.total }} 笔 · 核对无误后按月结算</span>
      </template>

      <div class="filter-bar">
        <el-select v-model="query.status" placeholder="全部状态" clearable @change="search">
          <el-option label="待审核" value="待审核" />
          <el-option label="已结算" value="已结算" />
          <el-option label="已驳回" value="已驳回" />
        </el-select>
        <el-button type="primary" plain @click="search">
          <el-icon><Search /></el-icon>查询
        </el-button>
        <el-button @click="reset">重置</el-button>
        <span class="filter-spacer"></span>
        <span class="loss-tip"><el-icon><InfoFilled /></el-icon>损耗按实收数量自动核算</span>
      </div>

      <div class="table-wrap">
        <el-table
          :data="rows" size="small" class="settle-table" :show-overflow-tooltip="true"
          empty-text="暂无结算记录" @row-click="openDetail"
        >
          <el-table-column label="结算单号" min-width="126">
            <template #default="{ row }">
              <span class="mono">{{ row.settlementNo }}</span>
            </template>
          </el-table-column>
          <el-table-column label="订单号" min-width="132">
            <template #default="{ row }">
              <span class="mono muted">{{ row.orderNo }}</span>
            </template>
          </el-table-column>
          <el-table-column prop="productName" label="产品" min-width="86" />
          <el-table-column label="下单 / 实收" width="106">
            <template #default="{ row }">
              <span class="mono">{{ fmtNum(row.orderQuantity) }}</span>
              <span class="muted"> / </span>
              <span class="mono">{{ fmtNum(row.receiveQuantity) }}</span>
              <span class="muted small"> {{ row.unit }}</span>
            </template>
          </el-table-column>
          <el-table-column label="单价" width="86">
            <template #default="{ row }">
              <span class="mono">¥ {{ fmtMoney(row.unitPrice) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="订单金额" width="98">
            <template #default="{ row }">
              <span class="money">¥ {{ fmtMoney(row.totalAmount) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="损耗扣款" width="94">
            <template #default="{ row }">
              <span class="loss-money">- ¥ {{ fmtMoney(row.lossDeduction) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="实付金额" width="106">
            <template #default="{ row }">
              <span class="money pay">¥ {{ fmtMoney(row.actualAmount) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="状态" width="86">
            <template #default="{ row }">
              <StatusTag type="settlement" :value="row.status" />
            </template>
          </el-table-column>
          <el-table-column label="结算时间" width="118">
            <template #default="{ row }">
              <span class="small">{{ fmtDate(row.confirmTime) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="64" fixed="right">
            <template #default="{ row }">
              <span class="link" @click.stop="openDetail(row)">明细</span>
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

    <!-- 结算明细 -->
    <el-dialog v-model="dialogVisible" title="结算单明细" width="580px" class="settle-dialog">
      <template v-if="current">
        <div class="dlg-head">
          <div>
            <div class="dlg-no mono">{{ current.settlementNo }}</div>
            <div class="muted small">关联订单 {{ current.orderNo }}</div>
          </div>
          <StatusTag type="settlement" :value="current.status" />
        </div>

        <div class="dlg-product">
          <div class="dp-name">{{ current.productName }}</div>
          <div class="dp-meta">
            {{ current.canteenName }} · 下单 {{ fmtNum(current.orderQuantity) }} {{ current.unit }} ·
            实收 {{ fmtNum(current.receiveQuantity) }} {{ current.unit }}
          </div>
        </div>

        <div class="amount-box">
          <div class="amt-row">
            <span>订单金额</span><b>¥ {{ fmtMoney(current.totalAmount) }}</b>
          </div>
          <div class="amt-row minus">
            <span>损耗扣款</span><b>- ¥ {{ fmtMoney(current.lossDeduction) }}</b>
          </div>
          <div class="amt-row minus">
            <span>质检扣款</span><b>- ¥ {{ fmtMoney(current.deduction) }}</b>
          </div>
          <div class="amt-divider"></div>
          <div class="amt-row total">
            <span>实付金额</span><b>¥ {{ fmtMoney(current.actualAmount) }}</b>
          </div>
        </div>

        <div class="loss-note">
          <el-icon><InfoFilled /></el-icon>
          <span>
            生鲜损耗按实收数量自动核算：下单 {{ fmtNum(current.orderQuantity) }} {{ current.unit }}，
            实收 {{ fmtNum(current.receiveQuantity) }} {{ current.unit }}，损耗率
            <b>{{ current.lossRate ?? 0 }}%</b>。
          </span>
        </div>

        <el-descriptions :column="2" border size="small" class="dlg-desc">
          <el-descriptions-item label="结算单号">
            <span class="mono">{{ current.settlementNo }}</span>
          </el-descriptions-item>
          <el-descriptions-item label="订单号">
            <span class="mono">{{ current.orderNo }}</span>
          </el-descriptions-item>
          <el-descriptions-item label="供货农户">{{ current.farmerName }}</el-descriptions-item>
          <el-descriptions-item label="收货食堂">{{ current.canteenName }}</el-descriptions-item>
          <el-descriptions-item label="成交单价">
            ¥ {{ fmtMoney(current.unitPrice) }} / {{ current.unit }}
          </el-descriptions-item>
          <el-descriptions-item label="审核人">{{ current.auditor || '—' }}</el-descriptions-item>
          <el-descriptions-item label="创建时间">{{ fmtDate(current.createTime) }}</el-descriptions-item>
          <el-descriptions-item label="结算时间">{{ fmtDate(current.confirmTime) }}</el-descriptions-item>
          <el-descriptions-item label="备注" :span="2">{{ current.remark || '—' }}</el-descriptions-item>
        </el-descriptions>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { farmerApi } from '@/api'
import StatCard from '@/components/StatCard.vue'
import SectionCard from '@/components/SectionCard.vue'
import StatusTag from '@/components/StatusTag.vue'
import { fmtDate, fmtMoney, fmtNum } from '@/utils/format'
import { useAutoRefresh } from '@/utils/useAutoRefresh'
import { exportTableDom, stamped } from '@/utils/export'

const loading = ref(false)
const route = useRoute()
const rows = ref([])
const income = ref({})
const monthIncome = ref(0)
const pageInfo = ref({ total: 0, page: 1, size: 10, pages: 0 })
const dialogVisible = ref(false)
const current = ref(null)

const query = reactive({ page: 1, size: 10, status: '' })

const monthIncomeOf = (inc, wb) => {
  const fromWb = wb?.data?.cards?.monthIncome
  if (fromWb !== null && fromWb !== undefined) return Number(fromWb)
  const m = inc.monthIncome
  if (m && typeof m === 'object') return Number(m.monthIncome || 0)
  return Number(m || 0)
}

const load = async () => {
  loading.value = true
  try {
    const [res, wb] = await Promise.all([
      farmerApi.settlements({
        size: query.page,
        pageSize: query.size,
        status: query.status || undefined
      }),
      farmerApi.workbench().catch(() => null)
    ])
    const d = res.data || {}
    rows.value = d.page?.records || []
    pageInfo.value = {
      total: Number(d.page?.total || 0),
      page: Number(d.page?.page || query.page),
      size: Number(d.page?.size || query.size),
      pages: Number(d.page?.pages || 0)
    }
    const raw = d.income || {}
    const inc = raw.data || raw
    income.value = inc || {}
    monthIncome.value = monthIncomeOf(income.value, wb)
  } finally {
    loading.value = false
  }
}

const search = () => {
  query.page = 1
  load()
}

const reset = () => {
  query.page = 1
  query.status = ''
  load()
}

const onPage = (p) => {
  query.page = p
  load()
}

const openDetail = (row) => {
  current.value = row
  dialogVisible.value = true
}

const exportBill = () => {
  const n = exportTableDom(stamped('农户对账单'))
  ElMessage.success(n ? `已导出当前页 ${n} 条记录` : '当前没有可导出的数据')
}

useAutoRefresh(load)
/* 单据工作台「查看我的对账单」带 ?focus=<订单号>：定位到那一笔并打开明细 */
onMounted(async () => {
  await load()
  const no = String(route.query.focus || '').trim()
  if (!no) return
  const hit = (rows.value || []).find(
    (r) => String(r.orderNo || '') === no || String(r.settlementNo || '') === no
  )
  if (hit) openDetail(hit)
})
</script>

<style scoped>
.stat-grid.three {
  grid-template-columns: repeat(3, minmax(0, 1fr));
}

.loss-tip {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  font-size: var(--fs-xs);
  color: var(--c-muted-2);
}

.loss-money {
  font-family: var(--font-num);
  font-weight: 600;
  color: #d97706;
}

.pay {
  color: var(--accent);
  font-weight: 700;
}

.settle-table :deep(.el-table__row) {
  cursor: pointer;
}

.settle-table :deep(.el-table__cell) {
  padding: 8px 0;
}

.settle-table :deep(.cell) {
  padding: 0 8px;
}

/* ---------------- 弹窗 ---------------- */
.dlg-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  padding-bottom: 12px;
  border-bottom: 1px dashed var(--c-border);
}

.dlg-no {
  font-size: var(--fs-base);
  font-weight: 600;
  color: var(--ink);
}

.dlg-product {
  margin: 14px 0 12px;
  padding: 12px 14px;
  border-radius: 10px;
  background: var(--surface-2);
  border: 1px solid var(--c-border);
}

.dp-name {
  font-size: var(--fs-base);
  font-weight: 600;
  color: var(--ink);
}

.dp-meta {
  margin-top: 4px;
  font-size: var(--fs-xs);
  color: var(--c-muted);
}

.amount-box {
  padding: 14px 16px;
  border-radius: 11px;
  background: linear-gradient(135deg, var(--surface-2), #e9f8ef);
  border: 1px solid var(--accent-soft);
}

.amt-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 5px 0;
  font-size: var(--fs-base);
  color: #3f5d4f;
}

.amt-row b {
  font-family: var(--font-num);
  font-size: var(--fs-base);
  color: var(--ink);
}

.amt-row.minus b {
  color: var(--warn);
}

.amt-divider {
  height: 1px;
  margin: 7px 0;
  background: #cfe9da;
}

.amt-row.total {
  font-size: var(--fs-base);
  font-weight: 600;
  color: var(--ink);
}

.amt-row.total b {
  font-size: var(--fs-xl);
  font-weight: 700;
  color: var(--accent);
  letter-spacing: -0.3px;
}

.loss-note {
  display: flex;
  align-items: flex-start;
  gap: 7px;
  margin: 12px 0 14px;
  padding: 10px 12px;
  border-radius: 8px;
  background: var(--warn-soft);
  border: 1px solid #fdecc8;
  font-size: var(--fs-xs);
  line-height: 1.7;
  color: #92620d;
}

.loss-note .el-icon {
  margin-top: 2px;
}

.dlg-desc {
  margin-top: 4px;
}
</style>
