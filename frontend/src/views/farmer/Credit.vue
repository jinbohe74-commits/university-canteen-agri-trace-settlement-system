<template>
  <div class="page" v-loading="loading">
    <div class="page-head">
      <div>
        <h2 class="page-title">信用评价</h2>
        <p class="page-sub">信用分由履约、质检、损耗与采购评价共同决定，直接影响智能匹配排名</p>
      </div>
      <div class="page-actions">
        <span class="update-chip"><el-icon><Refresh /></el-icon>动态更新</span>
        <el-button plain @click="load"><el-icon><Refresh /></el-icon>刷新</el-button>
      </div>
    </div>

    <div class="credit-top">
      <!-- 信用分总览 -->
      <SectionCard title="信用分总览" :body-style="false">
        <template #extra><span class="muted small">基础分 80 · 满分 100</span></template>
        <div class="score-wrap">
          <div class="score-left">
            <el-progress
              type="dashboard" :percentage="Math.min(100, Number(score))" :width="150"
              :stroke-width="9" :color="scoreColor"
            >
              <template #default>
                <div class="ring-inner">
                  <div class="ring-score">{{ score }}</div>
                  <div class="ring-label">当前信用分</div>
                </div>
              </template>
            </el-progress>
            <div class="level-row">
              <span class="tag" :class="levelTag">{{ level }}</span>
              <span class="muted small">信用等级</span>
            </div>
          </div>

          <div class="score-dims">
            <div class="dims-title">评分维度</div>
            <div v-for="(v, k, i) in dimensions" :key="k" class="dim-row">
              <div class="dim-top">
                <span>{{ k }}</span>
                <b>{{ v }}<small> 分</small></b>
              </div>
              <el-progress
                :percentage="Number(v)" :stroke-width="7" :show-text="false"
                :color="dimColors[i % dimColors.length]"
              />
            </div>
            <div v-if="!Object.keys(dimensions).length" class="empty">暂无维度数据</div>
          </div>
        </div>
      </SectionCard>

      <!-- 农户档案 -->
      <SectionCard title="农户档案" :body-style="false">
        <template #extra><span class="muted small">{{ farmer.farmerNo || '—' }}</span></template>
        <div class="profile">
          <div class="profile-head">
            <div class="p-avatar">{{ initial }}</div>
            <div class="grow">
              <div class="p-name">{{ farmer.name || '—' }}</div>
              <div class="p-type">{{ farmer.type || '农户/合作社' }}</div>
            </div>
            <StatusTag type="cert" :value="farmer.status || farmer.certStatus" />
          </div>
          <div class="profile-rows">
            <div class="p-row">
              <span class="p-k">联系人</span>
              <span class="p-v">{{ farmer.contact || '—' }}</span>
            </div>
            <div class="p-row">
              <span class="p-k">认证状态</span>
              <StatusTag type="cert" :value="farmer.status || farmer.certStatus" />
            </div>
            <div class="p-row">
              <span class="p-k">累计交易</span>
              <span class="p-v mono">{{ farmer.dealCount ?? 0 }} 笔</span>
            </div>
            <div class="p-row">
              <span class="p-k">好评率</span>
              <span class="p-v good">{{ farmer.goodRate ?? 0 }}%</span>
            </div>
            <div class="p-row">
              <span class="p-k">入驻时间</span>
              <span class="p-v small">{{ fmtDate(farmer.joinTime, false) }}</span>
            </div>
          </div>
        </div>
      </SectionCard>
    </div>

    <!-- 信用分明细 -->
    <SectionCard title="信用分明细" :body-style="false" noPad>
      <template #extra><span class="muted small">共 {{ records.length }} 条变动记录</span></template>
      <div class="table-wrap">
        <el-table :data="pagedRecords" size="small" class="credit-table" empty-text="暂无信用变动记录">
          <el-table-column label="时间" width="146">
            <template #default="{ row }">
              <span class="small">{{ fmtDate(row.createTime) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="变动原因" min-width="260">
            <template #default="{ row }">
              <div class="reason">{{ row.reason }}</div>
              <div class="reason-sub">关联订单 #{{ row.orderId }}</div>
            </template>
          </el-table-column>
          <el-table-column label="变动前" width="92" align="center">
            <template #default="{ row }">
              <span class="mono score-num">{{ fmtNum(row.beforeScore, 1) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="变动" width="92" align="center">
            <template #default="{ row }">
              <span class="delta" :class="Number(row.changeScore) >= 0 ? 'delta-up' : 'delta-down'">
                {{ fmtDelta(row.changeScore) }}
              </span>
            </template>
          </el-table-column>
          <el-table-column label="变动后" width="92" align="center">
            <template #default="{ row }">
              <span class="mono score-num after">{{ fmtNum(row.afterScore, 1) }}</span>
            </template>
          </el-table-column>
        </el-table>
      </div>
      <div class="pager">
        <span>共 {{ records.length }} 条 · 第 {{ page }} / {{ totalPages }} 页</span>
        <el-pagination
          size="small" background :pager-count="5" layout="prev, pager, next"
          :total="records.length" :page-size="pageSize" :current-page="page"
          @current-change="onPage"
        />
      </div>
    </SectionCard>

    <!-- 采购评价 -->
    <SectionCard title="采购评价" :body-style="false" noPad>
      <template #extra>
        <span class="muted small">平均 {{ avgScore }} 分 · 共 {{ evalCount }} 条</span>
      </template>
      <div v-if="evaluations.length" class="eval-grid">
        <div v-for="e in evaluations" :key="e.id" class="eval-item">
          <div class="eval-head">
            <StarRate :value="e.overallScore" show-text />
            <span class="muted small">{{ fmtDate(e.createTime, false) }}</span>
          </div>
          <div class="eval-tags">
            <span v-for="t in tagsOf(e.tags)" :key="t" class="tag tag-green">{{ t }}</span>
          </div>
          <div class="eval-content">{{ e.content || '采购方未填写文字评价' }}</div>
          <div class="eval-foot">
            <span>订单 #{{ e.orderId }}</span>
            <span>质检 {{ e.qualityScore }}</span>
            <span>新鲜度 {{ e.freshScore }}</span>
            <span>配送 {{ e.deliveryScore }}</span>
            <span>服务 {{ e.serviceScore }}</span>
            <span>包装 {{ e.packageScore }}</span>
          </div>
        </div>
      </div>
      <div v-else class="empty">暂无采购评价</div>
    </SectionCard>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { farmerApi } from '@/api'
import SectionCard from '@/components/SectionCard.vue'
import StatusTag from '@/components/StatusTag.vue'
import StarRate from '@/components/StarRate.vue'
import { fmtDate, fmtNum } from '@/utils/format'

const loading = ref(false)
const data = ref({})
const page = ref(1)
const pageSize = 8

const farmer = computed(() => data.value.farmer || {})
const records = computed(() => data.value.records || [])
const evaluations = computed(() => data.value.evaluations || [])
const dimensions = computed(() => data.value.dimensions || {})
const avgScore = computed(() => data.value.avgScore ?? '—')
const evalCount = computed(() => data.value.evalCount ?? evaluations.value.length)

const score = computed(() => farmer.value.creditScore ?? 0)
const scoreColor = computed(() => {
  const v = Number(score.value)
  if (v >= 90) return '#16a34a'
  if (v >= 85) return '#0d9488'
  return '#f59e0b'
})

const level = computed(() => {
  const v = Number(score.value)
  if (v >= 90) return '优秀'
  if (v >= 85) return '良好'
  return '一般'
})

const levelTag = computed(() =>
  level.value === '优秀' ? 'tag-green' : level.value === '良好' ? 'tag-teal' : 'tag-orange'
)

const initial = computed(() => String(farmer.value.contact || farmer.value.name || '农').slice(0, 1))

const dimColors = ['#16a34a', '#0d9488', '#3b82f6', '#8b5cf6']

const pagedRecords = computed(() => {
  const start = (page.value - 1) * pageSize
  return records.value.slice(start, start + pageSize)
})

const totalPages = computed(() => Math.ceil(records.value.length / pageSize) || 1)

const onPage = (p) => {
  page.value = p
}

const fmtDelta = (v) => {
  const n = Number(v)
  if (Number.isNaN(n)) return '—'
  const sign = n > 0 ? '+' : ''
  return sign + (Number.isInteger(n) ? n : n.toFixed(1))
}

const tagsOf = (tags) => String(tags || '').split(/[,，]/).map((x) => x.trim()).filter(Boolean)

const load = async () => {
  loading.value = true
  try {
    const res = await farmerApi.credit()
    data.value = res.data || {}
    page.value = 1
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<style scoped>
.update-chip {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  height: 32px;
  padding: 0 12px;
  border-radius: 8px;
  background: #fff;
  border: 1px solid var(--c-border);
  font-size: 12.5px;
  color: var(--c-text-2);
}

.credit-top {
  display: grid;
  grid-template-columns: minmax(0, 1.62fr) minmax(0, 1fr);
  gap: 14px;
  margin-bottom: 14px;
}

/* ---------------- 信用分总览 ---------------- */
.score-wrap {
  display: flex;
  align-items: center;
  gap: 26px;
  padding: 6px 4px 2px;
}

.score-left {
  flex-shrink: 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 4px;
}

.ring-inner {
  display: flex;
  flex-direction: column;
  align-items: center;
  line-height: 1.2;
}

.ring-score {
  font-family: var(--font-num);
  font-size: 34px;
  font-weight: 700;
  color: #14532d;
  letter-spacing: -0.5px;
}

.ring-label {
  margin-top: 3px;
  font-size: 11px;
  color: var(--c-muted);
}

.level-row {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-top: -4px;
}

.score-dims {
  flex: 1;
  min-width: 0;
  padding-left: 4px;
}

.dims-title {
  font-size: 12px;
  color: var(--c-muted);
  margin-bottom: 10px;
}

.dim-row {
  margin-bottom: 12px;
}

.dim-row:last-child {
  margin-bottom: 0;
}

.dim-top {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  margin-bottom: 5px;
  font-size: 12.5px;
  color: #374151;
}

.dim-top b {
  font-family: var(--font-num);
  font-size: 13.5px;
  color: #15803d;
}

.dim-top b small {
  font-size: 10.5px;
  font-weight: 400;
  color: var(--c-muted-2);
}

/* ---------------- 档案 ---------------- */
.profile {
  padding: 2px 0 0;
}

.profile-head {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 12px 12px;
  border-bottom: 1px dashed var(--c-border);
}

.p-avatar {
  width: 40px;
  height: 40px;
  flex-shrink: 0;
  border-radius: 12px;
  display: grid;
  place-items: center;
  color: #fff;
  font-size: 17px;
  font-weight: 700;
  background: linear-gradient(135deg, #34d399, #15803d);
  box-shadow: 0 4px 10px rgba(21, 128, 61, 0.18);
}

.p-name {
  font-size: 14px;
  font-weight: 600;
  color: #1b2a24;
}

.p-type {
  margin-top: 3px;
  font-size: 11.5px;
  color: var(--c-muted);
}

.profile-rows {
  padding: 6px 12px 4px;
}

.p-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  min-height: 30px;
  font-size: 12.5px;
  border-bottom: 1px dashed #f1f5f3;
}

.p-row:last-child {
  border-bottom: none;
}

.p-k {
  color: var(--c-muted);
}

.p-v {
  color: #1b2a24;
  font-weight: 500;
}

.p-v.good {
  color: #15803d;
  font-weight: 700;
  font-family: var(--font-num);
}

/* ---------------- 明细表 ---------------- */
.credit-table :deep(.el-table__cell) {
  padding: 8px 0;
}

.reason {
  font-size: 12.5px;
  color: #1b2a24;
  line-height: 1.5;
}

.reason-sub {
  margin-top: 2px;
  font-size: 10.5px;
  color: var(--c-muted-2);
}

.score-num {
  font-size: 12.5px;
  color: var(--c-muted);
}

.score-num.after {
  color: #1b2a24;
  font-weight: 600;
}

.delta {
  font-family: var(--font-num);
  font-size: 13.5px;
  font-weight: 700;
}

.delta-up {
  color: #15803d;
}

.delta-down {
  color: #dc2626;
}

/* ---------------- 评价 ---------------- */
.eval-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 0 20px;
  padding: 2px 18px 10px;
}

.eval-item {
  padding: 13px 2px;
  border-bottom: 1px dashed var(--c-border);
}

.eval-item:nth-last-child(-n + 2) {
  border-bottom: none;
}

.eval-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
}

.eval-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 5px;
  margin-top: 8px;
}

.eval-content {
  margin-top: 8px;
  font-size: 12.5px;
  line-height: 1.7;
  color: #374151;
}

.eval-foot {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  margin-top: 8px;
  font-size: 10.5px;
  color: var(--c-muted-2);
}

@media (max-width: 1180px) {
  .credit-top {
    grid-template-columns: minmax(0, 1fr);
  }

  .eval-grid {
    grid-template-columns: minmax(0, 1fr);
  }

  .eval-item:nth-last-child(-n + 2) {
    border-bottom: 1px dashed var(--c-border);
  }

  .eval-item:last-child {
    border-bottom: none;
  }
}
</style>
