<template>
  <div class="page" v-loading="loading">
    <div class="page-head">
      <div>
        <h2 class="page-title">结算审核</h2>
        <p class="page-sub">依据生鲜损耗结算规则，审核入库实收数量与扣款，支持批量结算与凭证导出</p>
      </div>
      <div class="page-actions">
        <el-button plain :loading="exporting" @click="exportBills">
          <el-icon><Download /></el-icon>导出对账单
        </el-button>
        <el-button type="primary" :disabled="!selection.length" @click="batchAudit">
          <el-icon><DocumentChecked /></el-icon>批量审核
          <span v-if="selection.length">（{{ selection.length }}）</span>
        </el-button>
      </div>
    </div>

    <!-- 统计卡片 -->
    <div class="stat-grid">
      <StatCard
        label="待审核结算单" :value="fmtNum(stats.pendingCount)" icon="DocumentChecked" color="orange"
        :foot="'金额 ¥ ' + fmtMoney(stats.pendingAmount)"
      />
      <StatCard
        label="已结算" :value="fmtNum(stats.settledCount)" icon="CircleCheck" color="green"
        :foot="'本月 ' + fmtNum(stats.monthCount) + ' 笔'"
      />
      <StatCard
        label="损耗扣款" :value="'¥ ' + fmtMoney(stats.monthLoss)" icon="TrendCharts" color="red"
        foot="本月累计"
      />
      <StatCard
        label="实际结算" :value="'¥ ' + fmtMoney(stats.monthActual)" icon="Money" color="teal"
        foot="本月"
      />
    </div>

    <!-- 规则 + 示例 -->
    <div class="rules-grid">
      <SectionCard title="生鲜损耗结算规则">
        <template #extra><span class="muted small">月结 · 每月 5 日</span></template>
        <div class="formula">实付金额 = 订单金额 − 损耗扣款 − 质检扣款</div>
        <div class="formula-note">
          <el-icon><InfoFilled /></el-icon>
          <span>支持部分收货，按实际入库数量结算；损耗与质检不合格部分自动扣款。</span>
        </div>
        <div class="rule-grid">
          <div v-for="r in RULES" :key="r.name" class="rule-item">
            <div class="rule-name">{{ r.name }}</div>
            <div class="rule-desc">{{ r.desc }}</div>
          </div>
        </div>
      </SectionCard>

      <div class="card">
        <div class="card-head">
          <div class="card-title">结算示例</div>
          <div class="card-extra">取列表首条订单核算</div>
        </div>
        <div class="card-pad">
          <div class="eg-box">
            <div class="eg-head">
              示例：{{ egOrder.productName }}订单
              <span class="mono">{{ egOrder.orderNo }}</span>
            </div>
            <div class="eg-facts">
              <span>下单 <b>{{ eg.q }}</b> kg</span>
              <span>实收 <b>{{ eg.r }}</b> kg</span>
              <span>单价 <b>¥ {{ eg.price.toFixed(2) }}</b></span>
              <span>损耗率 <b>{{ eg.lossRate }}%</b></span>
            </div>
            <div class="eg-steps">
              <div class="eg-line">
                <span>① 订单金额</span>
                <span class="mono">{{ eg.q }} × {{ eg.price.toFixed(2) }} = ¥ {{ fmtMoney(eg.amount) }}</span>
              </div>
              <div class="eg-line">
                <span>② 损耗扣款</span>
                <span class="mono">({{ eg.q }} − {{ eg.r }}) × {{ eg.price.toFixed(2) }} = ¥ {{ fmtMoney(eg.lossFee) }}</span>
              </div>
              <div class="eg-line">
                <span>③ 质检扣款</span>
                <span class="mono">¥ {{ fmtMoney(eg.qcFee) }}</span>
              </div>
            </div>
            <div class="eg-amount">
              <span>实际结算金额</span>
              <b>¥ {{ fmtMoney(eg.actual) }}</b>
            </div>
            <div class="eg-tip">
              损耗率达到 {{ eg.lossRate }}% 阈值线，系统自动提醒，累计将影响农户信用分（-2）
            </div>
          </div>
        </div>
      </div>
    </div>

    <!-- 列表 -->
    <div class="card">
      <div class="filter-bar">
        <el-input
          v-model="query.keyword"
          placeholder="结算单号 / 订单号 / 农户"
          clearable
          @keyup.enter="search"
        >
          <template #prefix><el-icon><Search /></el-icon></template>
        </el-input>
        <el-select v-model="query.status" placeholder="审核状态" @change="search">
          <el-option label="全部状态" value="" />
          <el-option label="待审核" value="待审核" />
          <el-option label="已结算" value="已结算" />
          <el-option label="已驳回" value="已驳回" />
        </el-select>
        <el-date-picker
          v-model="query.period"
          type="month"
          placeholder="结算周期"
          value-format="YYYY-MM"
          style="width: 176px"
          @change="search"
        />
        <el-button type="primary" @click="search">
          <el-icon><Search /></el-icon>查询
        </el-button>
        <el-button plain @click="reset">
          <el-icon><RefreshLeft /></el-icon>重置
        </el-button>
        <div class="filter-spacer"></div>
        <span class="muted small">共 {{ list.total }} 张结算单</span>
      </div>

      <div class="table-wrap">
        <el-table :data="list.records" style="width: 100%" @selection-change="onSelectionChange">
          <el-table-column type="selection" width="46" />
          <el-table-column label="结算单 / 订单" min-width="170">
            <template #default="{ row }">
              <div class="cell-main mono">{{ row.settlementNo }}</div>
              <div class="cell-sub mono">{{ row.orderNo }}</div>
            </template>
          </el-table-column>
          <el-table-column label="农户" min-width="138">
            <template #default="{ row }">
              <div class="cell-main">{{ row.farmerName }}</div>
              <div class="cell-sub">{{ row.productName }}</div>
            </template>
          </el-table-column>
          <el-table-column prop="canteenName" label="食堂" min-width="94" />
          <el-table-column label="下单 / 实收" width="122" align="right">
            <template #default="{ row }">
              <span class="mono">{{ qty(row.orderQuantity) }} / {{ qty(row.receiveQuantity) }}</span>
              <span class="muted small"> {{ row.unit }}</span>
            </template>
          </el-table-column>
          <el-table-column label="单价" width="84" align="right">
            <template #default="{ row }">
              <span class="mono">¥ {{ row.unitPrice }}</span>
            </template>
          </el-table-column>
          <el-table-column label="订单金额" width="104" align="right">
            <template #default="{ row }">
              <span class="mono">¥ {{ fmtMoney(row.totalAmount) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="损耗扣款" width="102" align="right">
            <template #default="{ row }">
              <span v-if="Number(row.lossDeduction) > 0" class="deduct mono">
                -¥ {{ fmtMoney(row.lossDeduction) }}
              </span>
              <span v-else class="muted mono">¥ 0.00</span>
            </template>
          </el-table-column>
          <el-table-column label="实付金额" width="110" align="right">
            <template #default="{ row }">
              <span class="money pay">¥ {{ fmtMoney(row.actualAmount) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="状态" width="96" align="center">
            <template #default="{ row }">
              <StatusTag type="settlement" :value="row.status" />
            </template>
          </el-table-column>
          <el-table-column label="操作" width="120" align="right">
            <template #default="{ row }">
              <span class="link" @click.stop="openDetail(row)">明细</span>
              <span v-if="row.status === '待审核'" class="link" @click.stop="openAudit(row)">审核</span>
              <span class="link" @click.stop="openVoucher(row)">凭证</span>
            </template>
          </el-table-column>
          <template #empty>
            <div class="empty">当前筛选条件下暂无结算单</div>
          </template>
        </el-table>
      </div>

      <div class="pager">
        <span>第 {{ list.page }} / {{ list.pages || 1 }} 页 · 共 {{ list.total }} 条</span>
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

    <!-- 明细弹窗 -->
    <el-dialog v-model="detailVisible" title="结算明细" width="640px" :close-on-click-modal="false">
      <div v-loading="detailLoading" class="dlg-body">
        <template v-if="detail">
          <div class="dlg-head">
            <div>
              <div class="dlg-no mono">{{ detail.settlement?.settlementNo }}</div>
              <div class="muted small">订单 {{ detail.settlement?.orderNo }} · {{ detail.settlement?.farmerName }}</div>
            </div>
            <StatusTag type="settlement" :value="detail.settlement?.status" />
          </div>

          <div class="amount-grid">
            <div class="amount-item">
              <span>订单金额</span>
              <b class="mono">¥ {{ fmtMoney(detail.settlement?.totalAmount) }}</b>
            </div>
            <div class="amount-item">
              <span>损耗扣款</span>
              <b class="mono deduct">-¥ {{ fmtMoney(detail.settlement?.lossDeduction) }}</b>
            </div>
            <div class="amount-item">
              <span>质检扣款</span>
              <b class="mono deduct">-¥ {{ fmtMoney(detail.settlement?.deduction) }}</b>
            </div>
            <div class="amount-item highlight">
              <span>实际结算</span>
              <b class="mono">¥ {{ fmtMoney(detail.settlement?.actualAmount) }}</b>
            </div>
          </div>

          <div class="sub-title">金额构成</div>
          <div class="item-list">
            <div v-for="(it, i) in detail.items || []" :key="i" class="item-row">
              <span class="item-name">{{ it.itemName }}</span>
              <span class="muted small">{{ it.remark }}</span>
              <span class="mono item-amt" :class="it.itemType === 'SUB' ? 'deduct' : ''">
                {{ it.itemType === 'SUB' ? '-' : '+' }}¥ {{ fmtMoney(it.amount) }}
              </span>
            </div>
            <div v-if="!(detail.items || []).length" class="empty">暂无金额明细</div>
          </div>

          <div class="sub-title">结算规则说明</div>
          <div class="rule-lines">
            <div v-for="r in detail.rules || []" :key="r.name" class="rule-line">
              <span class="rule-line-name">{{ r.name }}</span>
              <span class="muted">{{ r.desc }}</span>
            </div>
          </div>
        </template>
      </div>
      <template #footer>
        <el-button type="primary" @click="detailVisible = false">关闭</el-button>
      </template>
    </el-dialog>

    <!-- 审核弹窗 -->
    <el-dialog v-model="auditVisible" title="结算审核" width="520px" :close-on-click-modal="false">
      <div class="audit-summary">
        <div class="as-row">
          <span>结算单号</span><b class="mono">{{ auditRow.settlementNo }}</b>
        </div>
        <div class="as-row">
          <span>订单 / 农户</span><b>{{ auditRow.orderNo }} · {{ auditRow.farmerName }}</b>
        </div>
        <div class="as-row">
          <span>下单 / 实收</span>
          <b class="mono">{{ qty(auditRow.orderQuantity) }} / {{ qty(auditRow.receiveQuantity) }} {{ auditRow.unit }}</b>
        </div>
        <div class="as-row">
          <span>损耗率</span>
          <b :class="Number(auditRow.lossRate) >= 5 ? 'deduct' : ''">{{ auditRow.lossRate ?? 0 }}%</b>
        </div>
        <div class="as-row">
          <span>订单金额</span><b class="mono">¥ {{ fmtMoney(auditRow.totalAmount) }}</b>
        </div>
        <div class="as-row">
          <span>损耗扣款</span><b class="mono deduct">-¥ {{ fmtMoney(auditRow.lossDeduction) }}</b>
        </div>
        <div class="as-row total">
          <span>实际结算</span><b class="mono">¥ {{ fmtMoney(auditRow.actualAmount) }}</b>
        </div>
      </div>

      <el-form label-width="76px" class="audit-form">
        <el-form-item label="审核结果">
          <el-radio-group v-model="auditForm.pass">
            <el-radio :value="true">通过（转已结算）</el-radio>
            <el-radio :value="false">驳回</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="审核备注">
          <el-input
            v-model="auditForm.remark"
            type="textarea"
            :rows="3"
            maxlength="120"
            show-word-limit
            placeholder="请填写审核意见，如：损耗率已核实，同意按实收数量结算"
          />
        </el-form-item>
      </el-form>

      <template #footer>
        <el-button plain @click="auditVisible = false">取消</el-button>
        <el-button type="primary" :loading="auditing" @click="submitAudit">提交审核</el-button>
      </template>
    </el-dialog>

    <!-- 凭证弹窗 -->
    <el-dialog v-model="voucherVisible" title="结算凭证" width="480px">
      <div v-if="voucherRow" class="voucher">
        <div class="voucher-title">农产品直供结算凭证</div>
        <div class="voucher-no mono">{{ voucherRow.settlementNo }}</div>
        <div class="voucher-rows">
          <div class="vr"><span>订单号</span><b class="mono">{{ voucherRow.orderNo }}</b></div>
          <div class="vr"><span>供货农户</span><b>{{ voucherRow.farmerName }}</b></div>
          <div class="vr"><span>收货食堂</span><b>{{ voucherRow.canteenName }}</b></div>
          <div class="vr">
            <span>产品 / 实收</span>
            <b>{{ voucherRow.productName }} · {{ qty(voucherRow.receiveQuantity) }} {{ voucherRow.unit }}</b>
          </div>
          <div class="vr"><span>订单金额</span><b class="mono">¥ {{ fmtMoney(voucherRow.totalAmount) }}</b></div>
          <div class="vr"><span>损耗扣款</span><b class="mono deduct">-¥ {{ fmtMoney(voucherRow.lossDeduction) }}</b></div>
          <div class="vr total"><span>实付金额</span><b class="mono">¥ {{ fmtMoney(voucherRow.actualAmount) }}</b></div>
          <div class="vr"><span>审核状态</span><b>{{ voucherRow.status }}</b></div>
          <div class="vr"><span>审核人</span><b>{{ voucherRow.auditor || '待审核' }}</b></div>
        </div>
      </div>
      <template #footer>
        <el-button plain @click="voucherVisible = false">关闭</el-button>
        <el-button type="primary" @click="downloadVoucher">下载凭证</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { adminApi } from '@/api'
import StatCard from '@/components/StatCard.vue'
import SectionCard from '@/components/SectionCard.vue'
import StatusTag from '@/components/StatusTag.vue'
import { fmtMoney, fmtNum, today } from '@/utils/format'

const RULES = [
  { name: '订单金额', desc: '下单数量 × 成交单价' },
  { name: '损耗数量', desc: '下单数量 − 实收数量' },
  { name: '损耗扣款', desc: '损耗数量 × 成交单价' },
  { name: '质检扣款', desc: '不合格部分按约定比例' }
]

/* 示例参数：按规则公式实时计算，订单号/产品取列表首条 */
const EG = { q: 400, r: 380, price: 1.8 }

const loading = ref(false)
const exporting = ref(false)
const stats = ref({})
const list = reactive({ records: [], total: 0, page: 1, size: 10, pages: 0 })
const query = reactive({ page: 1, size: 10, status: '', period: null, keyword: '' })
const selection = ref([])

const detailVisible = ref(false)
const detailLoading = ref(false)
const detail = ref(null)

const auditVisible = ref(false)
const auditing = ref(false)
const auditRow = ref({})
const auditForm = reactive({ pass: true, remark: '' })

const voucherVisible = ref(false)
const voucherRow = ref(null)

const eg = computed(() => {
  const q = EG.q
  const r = EG.r
  const p = EG.price
  const amount = q * p
  const lossQty = q - r
  const lossFee = lossQty * p
  const qcFee = 0
  return {
    q, r, price: p,
    lossQty,
    lossRate: Math.round((lossQty / q) * 100),
    amount, lossFee, qcFee,
    actual: amount - lossFee - qcFee
  }
})

const egOrder = computed(() => {
  const first = list.records[0]
  return {
    productName: first ? first.productName : '—',
    orderNo: first ? first.orderNo : '—'
  }
})

const qty = (v) => {
  if (v === null || v === undefined) return '—'
  return fmtNum(v, Number.isInteger(Number(v)) ? 0 : 1)
}

/* 查询参数：剔除空值 */
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
    const res = await adminApi.settlements(params())
    const d = res.data || {}
    Object.assign(list, d.page || { records: [], total: 0, page: 1, size: 10, pages: 0 })
    stats.value = d.stats || {}
  } catch (e) {
    /* 拦截器已提示 */
  } finally {
    loading.value = false
  }
}

const search = () => {
  query.page = 1
  load()
}

const reset = () => {
  query.keyword = ''
  query.status = ''
  query.period = null
  query.page = 1
  load()
}

const onPage = (p) => {
  query.page = p
  load()
}

const onSelectionChange = (rows) => {
  selection.value = rows
}

const openDetail = async (row) => {
  detailVisible.value = true
  detail.value = null
  detailLoading.value = true
  try {
    const res = await adminApi.settlementDetail(row.id)
    detail.value = res.data || null
  } catch (e) {
    detailVisible.value = false
  } finally {
    detailLoading.value = false
  }
}

const openAudit = (row) => {
  auditRow.value = row
  auditForm.pass = true
  auditForm.remark = ''
  auditVisible.value = true
}

const submitAudit = async () => {
  auditing.value = true
  try {
    const res = await adminApi.auditSettlement({
      ids: [auditRow.value.id],
      pass: auditForm.pass,
      remark: auditForm.remark || (auditForm.pass ? '审核通过' : '审核驳回')
    })
    const d = res.data || {}
    ElMessage.success(
      auditForm.pass
        ? '审核通过，已转已结算 ' + (d.passed ?? 1) + ' 笔'
        : '已驳回 ' + (d.rejected ?? 1) + ' 笔结算单'
    )
    auditVisible.value = false
    load()
  } catch (e) {
    /* 拦截器已提示 */
  } finally {
    auditing.value = false
  }
}

const batchAudit = async () => {
  const ids = selection.value.map((r) => r.id)
  if (!ids.length) {
    ElMessage.warning('请先勾选待审核的结算单')
    return
  }
  try {
    await ElMessageBox.confirm(
      '确认将选中的 ' + ids.length + ' 张结算单批量审核通过？通过后订单状态同步转为「已结算」。',
      '批量审核',
      { type: 'warning', confirmButtonText: '批量通过', cancelButtonText: '取消' }
    )
  } catch (e) {
    return
  }
  try {
    const res = await adminApi.auditSettlement({ ids, pass: true, remark: '批量审核通过' })
    const d = res.data || {}
    ElMessage.success('批量审核完成：通过 ' + (d.passed ?? 0) + ' 笔，驳回 ' + (d.rejected ?? 0) + ' 笔')
    load()
  } catch (e) {
    /* 拦截器已提示 */
  }
}

const openVoucher = (row) => {
  voucherRow.value = row
  voucherVisible.value = true
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

const downloadVoucher = () => {
  const r = voucherRow.value
  if (!r) return
  downloadCsv(
    '结算凭证_' + r.settlementNo + '.csv',
    ['结算单号', '订单号', '农户', '食堂', '产品', '实收数量', '订单金额', '损耗扣款', '实付金额', '状态', '审核人'],
    [[r.settlementNo, r.orderNo, r.farmerName, r.canteenName, r.productName,
      qty(r.receiveQuantity), r.totalAmount, r.lossDeduction, r.actualAmount, r.status, r.auditor || '待审核']]
  )
  ElMessage.success('结算凭证已导出：' + r.settlementNo)
}

const exportBills = async () => {
  exporting.value = true
  try {
    const res = await adminApi.settlements({ ...params(), page: 1, size: 500 })
    const rows = res.data && res.data.page ? res.data.page.records : []
    if (!rows || !rows.length) {
      ElMessage.warning('当前筛选条件下暂无可导出的对账单')
      return
    }
    downloadCsv(
      '结算对账单_' + today() + '.csv',
      ['结算单号', '订单号', '农户', '食堂', '产品', '下单数量', '实收数量', '单价',
        '订单金额', '损耗扣款', '质检扣款', '实付金额', '状态', '审核人', '创建时间'],
      rows.map((r) => [
        r.settlementNo, r.orderNo, r.farmerName, r.canteenName, r.productName,
        qty(r.orderQuantity), qty(r.receiveQuantity), r.unitPrice, r.totalAmount,
        r.lossDeduction, r.deduction, r.actualAmount, r.status, r.auditor || '', r.createTime
      ])
    )
    ElMessage.success('已导出 ' + rows.length + ' 张结算单')
  } catch (e) {
    /* 拦截器已提示 */
  } finally {
    exporting.value = false
  }
}

onMounted(load)
</script>

<style scoped>
.rules-grid {
  display: grid;
  grid-template-columns: minmax(0, 1.06fr) minmax(0, 1fr);
  gap: 14px;
  margin-bottom: 16px;
  align-items: stretch;
}

.formula {
  padding: 13px 16px;
  border-radius: 10px;
  background: linear-gradient(135deg, #f0fdf4, #e6f8ee);
  border: 1px solid #d6f0e0;
  font-family: var(--font-num);
  font-size: 15px;
  font-weight: 700;
  color: #14532d;
  letter-spacing: 0.2px;
  text-align: center;
}

.formula-note {
  display: flex;
  align-items: flex-start;
  gap: 6px;
  margin: 10px 2px 14px;
  font-size: 11.5px;
  color: var(--c-muted);
  line-height: 1.7;
}

.formula-note .el-icon {
  color: var(--c-primary);
  margin-top: 2px;
}

.rule-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px;
}

.rule-item {
  padding: 10px 12px;
  border-radius: 9px;
  background: #f8fbfa;
  border: 1px solid #eef3f1;
}

.rule-name {
  font-size: 12.5px;
  font-weight: 700;
  color: #1b2a24;
}

.rule-name::before {
  content: '';
  display: inline-block;
  width: 3px;
  height: 11px;
  border-radius: 2px;
  margin-right: 6px;
  background: var(--c-primary-light);
  vertical-align: -1px;
}

.rule-desc {
  margin-top: 4px;
  font-size: 11.5px;
  color: var(--c-muted);
  line-height: 1.6;
}

/* 示例卡 */
.eg-box {
  padding: 14px 16px;
  border-radius: 11px;
  background: linear-gradient(160deg, #f2fbf5, #eaf8ef);
  border: 1px solid #d9f0e2;
}

.eg-head {
  font-size: 13px;
  font-weight: 700;
  color: #14532d;
}

.eg-head .mono {
  font-size: 11.5px;
  color: #15803d;
  font-weight: 600;
}

.eg-facts {
  display: flex;
  flex-wrap: wrap;
  gap: 6px 14px;
  margin: 9px 0 11px;
  font-size: 12px;
  color: #4b7a60;
}

.eg-facts b {
  color: #14532d;
  font-family: var(--font-num);
}

.eg-steps {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 10px 12px;
  border-radius: 9px;
  background: #fff;
  border: 1px dashed #c9e8d5;
}

.eg-line {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  font-size: 12px;
  color: #4b7a60;
}

.eg-line .mono {
  color: #1b2a24;
  font-family: var(--font-num);
}

.eg-amount {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  margin-top: 10px;
  padding: 10px 12px;
  border-radius: 9px;
  background: #15803d;
  color: #fff;
}

.eg-amount span {
  font-size: 12.5px;
  opacity: 0.92;
}

.eg-amount b {
  font-family: var(--font-num);
  font-size: 20px;
  letter-spacing: -0.3px;
}

.eg-tip {
  margin-top: 9px;
  font-size: 11.5px;
  color: #b45309;
  line-height: 1.65;
}

/* 表格 */
.cell-main {
  font-size: 12.5px;
  font-weight: 600;
  color: #1b2a24;
}

.cell-sub {
  margin-top: 2px;
  font-size: 11.5px;
  color: #9ca3af;
}

.deduct {
  color: #b45309;
}

.money.pay {
  color: #14532d;
  font-weight: 700;
}

/* 弹窗 */
.dlg-body {
  min-height: 80px;
}

.dlg-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding-bottom: 12px;
  border-bottom: 1px solid var(--c-border);
}

.dlg-no {
  font-size: 14px;
  font-weight: 700;
  color: #14532d;
}

.amount-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 10px;
  margin: 14px 0 4px;
}

.amount-item {
  padding: 10px 12px;
  border-radius: 9px;
  background: #f8fbfa;
  border: 1px solid #eef3f1;
}

.amount-item span {
  display: block;
  font-size: 11.5px;
  color: var(--c-muted);
  margin-bottom: 5px;
}

.amount-item b {
  font-size: 14px;
  color: #1b2a24;
}

.amount-item.highlight {
  background: #e8f7ee;
  border-color: #cbeed9;
}

.amount-item.highlight b {
  color: #14532d;
}

.sub-title {
  margin: 16px 0 8px;
  font-size: 13px;
  font-weight: 600;
  color: #111827;
}

.item-list {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.item-row {
  display: grid;
  grid-template-columns: 120px minmax(0, 1fr) 110px;
  align-items: center;
  gap: 8px;
  padding: 8px 12px;
  border-radius: 8px;
  background: #f8fbfa;
  font-size: 12.5px;
}

.item-name {
  font-weight: 600;
  color: #27362f;
}

.item-amt {
  text-align: right;
  font-weight: 600;
}

.rule-lines {
  display: flex;
  flex-direction: column;
  gap: 5px;
}

.rule-line {
  display: flex;
  gap: 10px;
  font-size: 12.5px;
  padding: 6px 0;
  border-bottom: 1px dashed var(--c-border);
}

.rule-line:last-child {
  border-bottom: none;
}

.rule-line-name {
  width: 104px;
  flex-shrink: 0;
  font-weight: 600;
  color: #374151;
}

/* 审核弹窗 */
.audit-summary {
  padding: 12px 14px;
  border-radius: 10px;
  background: #f8fbfa;
  border: 1px solid #eef3f1;
}

.as-row {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  font-size: 12.5px;
  color: var(--c-muted);
  padding: 4px 0;
}

.as-row b {
  color: #1b2a24;
  font-weight: 600;
}

.as-row.total {
  margin-top: 6px;
  padding-top: 9px;
  border-top: 1px dashed var(--c-border-strong);
  color: #374151;
}

.as-row.total b {
  font-size: 15px;
  color: #14532d;
  font-weight: 700;
}

.audit-form {
  margin-top: 16px;
}

/* 凭证 */
.voucher {
  padding: 16px;
  border-radius: 10px;
  border: 1px dashed #cbeed9;
  background: #f7fcf9;
}

.voucher-title {
  text-align: center;
  font-size: 14px;
  font-weight: 700;
  letter-spacing: 2px;
  color: #14532d;
}

.voucher-no {
  text-align: center;
  margin: 6px 0 12px;
  font-size: 11.5px;
  color: #4b7a60;
}

.voucher-rows {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.vr {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  font-size: 12.5px;
  color: var(--c-muted);
}

.vr b {
  color: #1b2a24;
  font-weight: 600;
}

.vr.total {
  margin-top: 6px;
  padding-top: 8px;
  border-top: 1px dashed var(--c-border-strong);
}

.vr.total b {
  font-size: 16px;
  color: #14532d;
}
</style>
