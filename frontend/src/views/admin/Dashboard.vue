<template>
  <div class="page" v-loading="loading">
    <div class="page-head">
      <div>
        <h2 class="page-title">数据看板</h2>
        <p class="page-sub">平台运营全局态势 · 数据截至 {{ data.serverTime || '—' }}</p>
      </div>
      <div class="page-actions">
        <span class="date-chip"><el-icon><Calendar /></el-icon>今日 {{ data.today }}</span>
        <el-button plain @click="load"><el-icon><Refresh /></el-icon>刷新</el-button>
        <el-button type="primary" @click="exportReport"><el-icon><Download /></el-icon>导出报表</el-button>
      </div>
    </div>

    <!-- 统计卡片 -->
    <div class="stat-grid">
      <StatCard
        label="订单总量（笔）" :value="fmtNum(cards.totalOrders)" icon="Tickets"
        :trend="'较上月 +' + (cards.orderGrowth || 0) + '%'" :up="true" foot="全平台累计订单" color="green"
      />
      <StatCard
        label="交易总额" :value="'¥ ' + fmtWan(cards.totalAmount)" icon="Money"
        :trend="'较上月 +' + (cards.amountGrowth || 0) + '%'" :up="true" foot="已剔除取消与不合格订单" color="blue"
      />
      <StatCard
        label="质检合格率" :value="(cards.passRate ?? 0)" suffix="%" icon="CircleCheck"
        :trend="'较上月 +' + (cards.passRateGrowth || 0) + '%'" :up="true" foot="批次抽检一次合格率" color="teal"
      />
      <StatCard
        label="认证农户（家）" :value="cards.farmers" icon="UserFilled"
        :trend="'本月新增 ' + (cards.farmersGrowth || 0)" :up="true" foot="含合作社与生产基地" color="orange"
      />
    </div>

    <!-- 趋势 + 品类 -->
    <div class="grid-2">
      <SectionCard title="近 6 个月订单量趋势">
        <template #extra>
          <span class="legend-dot"><i></i>订单笔数</span>
        </template>
        <BaseChart :option="orderTrendOption" height="228px" />
      </SectionCard>

      <SectionCard title="采购品类分布">
        <template #extra><span class="muted small">按有效订单统计</span></template>
        <div class="donut-wrap">
          <BaseChart :option="categoryOption" height="196px" />
          <div class="donut-legend">
            <div v-for="(c, i) in categoryShare" :key="c.name" class="dl-row">
              <span class="dl-dot" :style="{ background: palette[i % palette.length] }"></span>
              <span class="dl-name">{{ c.name }}</span>
              <span class="dl-val">{{ percentOf(c.cnt) }}%</span>
            </div>
            <div v-if="!categoryShare.length" class="empty">暂无数据</div>
          </div>
        </div>
      </SectionCard>
    </div>

    <!-- 价格 + 待办 -->
    <div class="grid-2">
      <SectionCard title="本周价格行情">
        <template #extra>
          <div class="inline-legend">
            <span><i style="background: #16a34a"></i>大白菜</span>
            <span><i style="background: #f59e0b"></i>西红柿</span>
            <span class="muted">单位：元 / kg</span>
          </div>
        </template>
        <BaseChart :option="priceOption" height="214px" />
      </SectionCard>

      <SectionCard title="待办事项">
        <template #extra><span class="muted small">共 {{ todos.length }} 项待跟进</span></template>
        <div class="todo-list">
          <div v-for="(t, i) in todos" :key="i" class="todo-item" @click="router.push(t.path)">
            <span class="todo-bar" :style="{ background: colorOf(t.color) }"></span>
            <div class="grow">
              <div class="todo-title">{{ t.title }}</div>
              <div class="todo-desc">{{ t.desc }}</div>
            </div>
            <div class="todo-tag">
              <span class="tag" :class="'tag-' + (t.color === 'amber' ? 'orange' : t.color === 'red' ? 'red' : 'teal')">
                {{ t.module }}
              </span>
              <el-icon class="go"><ArrowRight /></el-icon>
            </div>
          </div>
          <div v-if="!todos.length" class="empty">暂无待办事项</div>
        </div>
      </SectionCard>
    </div>

    <!-- 信用 + 食堂占比 + 结算 -->
    <div class="grid-3">
      <SectionCard title="农户信用 Top5">
        <template #extra><span class="muted small">基础分 80</span></template>
        <div class="credit-list">
          <div v-for="(f, i) in creditTop5" :key="f.name" class="credit-row">
            <span class="rank" :class="'rank-' + (i + 1)">{{ i + 1 }}</span>
            <div class="grow">
              <div class="credit-name">{{ f.name }}</div>
              <div class="credit-type">{{ f.type }}</div>
            </div>
            <div class="credit-score">
              <span>{{ f.creditScore }}</span>
              <el-progress
                :percentage="Number(f.creditScore)" :stroke-width="4" :show-text="false"
                :color="Number(f.creditScore) >= 90 ? '#22c55e' : '#f59e0b'" style="width: 54px"
              />
            </div>
          </div>
          <div v-if="!creditTop5.length" class="empty">暂无数据</div>
        </div>
      </SectionCard>

      <SectionCard title="各食堂采购占比">
        <div class="share-list">
          <div v-for="(c, i) in canteenShare" :key="c.name" class="share-row">
            <div class="share-top">
              <span class="share-name">{{ c.name }}</span>
              <span class="share-val">{{ c.percent }}%</span>
            </div>
            <el-progress
              :percentage="Number(c.percent)" :stroke-width="6" :show-text="false"
              :color="palette[i % palette.length]"
            />
            <div class="share-foot">{{ c.cnt }} 笔订单 · ¥ {{ fmtWan(c.amount) }}</div>
          </div>
          <div v-if="!canteenShare.length" class="empty">暂无数据</div>
        </div>
      </SectionCard>

      <SectionCard title="本月结算概况">
        <template #extra><span class="muted small">月结 · 每月 5 日</span></template>
        <div class="settle-amount">
          <div class="sa-label">实际结算金额</div>
          <div class="sa-value">¥ {{ fmtMoney(settlement.actual) }}</div>
          <div class="sa-sub">
            应结 ¥ {{ fmtMoney(settlement.total) }} · 损耗扣款 ¥ {{ fmtMoney(settlement.loss) }}
          </div>
        </div>
        <div class="settle-rows">
          <div class="sr-item">
            <span>结算单</span><b>{{ settlement.count || 0 }} 笔</b>
          </div>
          <div class="sr-item">
            <span>待审核 / 已结算</span><b>{{ settlement.pending || 0 }} / {{ settlement.settled || 0 }}</b>
          </div>
          <div class="sr-item">
            <span>平均损耗率</span>
            <b :class="Number(settlement.avgLossRate) >= Number(settlement.lossThreshold) ? 'warn' : 'ok'">
              {{ settlement.avgLossRate || 0 }}%
            </b>
          </div>
        </div>
        <div class="threshold-note">
          阈值 {{ settlement.lossThreshold || 5 }}% ·
          {{ Number(settlement.avgLossRate) < Number(settlement.lossThreshold) ? '低于预警线' : '已超预警线' }}
        </div>
      </SectionCard>
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { adminApi } from '@/api'
import StatCard from '@/components/StatCard.vue'
import SectionCard from '@/components/SectionCard.vue'
import BaseChart from '@/components/BaseChart.vue'
import { fmtMoney, fmtNum, fmtWan } from '@/utils/format'

const router = useRouter()
const loading = ref(false)
const data = ref({})
const palette = ['#16a34a', '#22c55e', '#0d9488', '#3b82f6', '#f59e0b', '#8b5cf6', '#06b6d4']

const cards = computed(() => data.value.cards || {})
const orderTrend = computed(() => data.value.orderTrend || [])
const categoryShare = computed(() => data.value.categoryShare || [])
const priceTrend = computed(() => data.value.priceTrend || [])
const todos = computed(() => data.value.todos || [])
const creditTop5 = computed(() => data.value.creditTop5 || [])
const canteenShare = computed(() => data.value.canteenShare || [])
const settlement = computed(() => data.value.settlement || {})

const colorOf = (c) => (c === 'amber' ? '#f59e0b' : c === 'red' ? '#ef4444' : '#0d9488')

const totalCategory = computed(() => categoryShare.value.reduce((s, c) => s + Number(c.cnt), 0))
const percentOf = (v) => {
  if (!totalCategory.value) return 0
  return Math.round((Number(v) / totalCategory.value) * 100)
}

const monthLabel = (m) => (m ? Number(String(m).split('-')[1]) + '月' : '')

const orderTrendOption = computed(() => ({
  grid: { left: 8, right: 16, top: 26, bottom: 4, containLabel: true },
  tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' }, backgroundColor: 'rgba(15,43,32,.92)', borderWidth: 0, textStyle: { color: '#fff', fontSize: 12 } },
  xAxis: {
    type: 'category',
    data: orderTrend.value.map((x) => monthLabel(x.m)),
    axisLine: { lineStyle: { color: '#e5eae8' } },
    axisTick: { show: false },
    axisLabel: { color: '#8b9c96', fontSize: 11.5 }
  },
  yAxis: {
    type: 'value',
    splitLine: { lineStyle: { color: '#f1f5f3', type: 'dashed' } },
    axisLabel: { color: '#8b9c96', fontSize: 11 }
  },
  series: [
    {
      name: '订单笔数',
      type: 'bar',
      data: orderTrend.value.map((x) => x.c),
      barWidth: '42%',
      itemStyle: {
        borderRadius: [6, 6, 0, 0],
        color: {
          type: 'linear', x: 0, y: 0, x2: 0, y2: 1,
          colorStops: [
            { offset: 0, color: '#4ade80' },
            { offset: 1, color: '#16a34a' }
          ]
        }
      },
      label: { show: true, position: 'top', color: '#15803d', fontSize: 11, fontWeight: 600 }
    }
  ]
}))

const categoryOption = computed(() => ({
  tooltip: {
    trigger: 'item',
    backgroundColor: 'rgba(15,43,32,.92)',
    borderWidth: 0,
    textStyle: { color: '#fff', fontSize: 12 },
    formatter: '{b}<br/>{c} 笔（{d}%）'
  },
  series: [
    {
      type: 'pie',
      radius: ['54%', '76%'],
      center: ['50%', '50%'],
      avoidLabelOverlap: true,
      itemStyle: { borderColor: '#fff', borderWidth: 2 },
      label: { show: false },
      data: categoryShare.value.map((c, i) => ({
        name: c.name,
        value: c.cnt,
        itemStyle: { color: palette[i % palette.length] }
      }))
    }
  ]
}))

const priceOption = computed(() => {
  const dates = [...new Set(priceTrend.value.map((x) => String(x.statDate).slice(5)))]
  const seriesOf = (name, color) => ({
    name,
    type: 'line',
    smooth: true,
    symbol: 'circle',
    symbolSize: 5,
    data: dates.map((d) => {
      const hit = priceTrend.value.find((x) => x.product === name && String(x.statDate).slice(5) === d)
      return hit ? Number(hit.avgPrice) : null
    }),
    lineStyle: { width: 2.4, color },
    itemStyle: { color },
    areaStyle: {
      color: {
        type: 'linear', x: 0, y: 0, x2: 0, y2: 1,
        colorStops: [
          { offset: 0, color: color + '33' },
          { offset: 1, color: color + '00' }
        ]
      }
    }
  })
  return {
    grid: { left: 8, right: 18, top: 22, bottom: 6, containLabel: true },
    tooltip: {
      trigger: 'axis',
      backgroundColor: 'rgba(15,43,32,.92)',
      borderWidth: 0,
      textStyle: { color: '#fff', fontSize: 12 },
      valueFormatter: (v) => (v === null ? '—' : '¥ ' + Number(v).toFixed(2))
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
    series: [seriesOf('大白菜', '#16a34a'), seriesOf('西红柿', '#f59e0b')]
  }
})

const load = async () => {
  loading.value = true
  try {
    const res = await adminApi.dashboard()
    data.value = res.data || {}
  } finally {
    loading.value = false
  }
}

const exportReport = () => {
  ElMessage.success('报表已生成：平台运营数据（' + (data.value.today || '') + '）')
}

onMounted(load)
</script>

<style scoped>
.date-chip {
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

.grid-2 {
  display: grid;
  grid-template-columns: minmax(0, 1.62fr) minmax(0, 1fr);
  gap: 14px;
  margin-bottom: 14px;
}

.grid-3 {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 14px;
}

.legend-dot {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  font-size: 11.5px;
  color: var(--c-muted);
}

.legend-dot i {
  width: 8px;
  height: 8px;
  border-radius: 2px;
  background: linear-gradient(180deg, #4ade80, #16a34a);
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

.donut-wrap {
  display: flex;
  align-items: center;
  gap: 6px;
}

.donut-wrap > :first-child {
  flex: 1.05;
  min-width: 0;
}

.donut-legend {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 9px;
  padding-right: 6px;
}

.dl-row {
  display: flex;
  align-items: center;
  gap: 7px;
  font-size: 12.5px;
}

.dl-dot {
  width: 8px;
  height: 8px;
  border-radius: 2px;
  flex-shrink: 0;
}

.dl-name {
  color: var(--c-text-2);
}

.dl-val {
  margin-left: auto;
  font-weight: 600;
  color: #111827;
  font-family: var(--font-num);
}

.todo-list {
  display: flex;
  flex-direction: column;
  min-height: 214px;
}

.todo-item {
  cursor: pointer;
  transition: background 0.18s;
  border-radius: 8px;
  padding-left: 4px;
}

.todo-item:hover {
  background: #f7fbf9;
}

.todo-tag {
  display: flex;
  align-items: center;
  gap: 4px;
  flex-shrink: 0;
}

.go {
  font-size: 12px;
  color: var(--c-muted-2);
}

.credit-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
  min-height: 170px;
}

.credit-row {
  display: flex;
  align-items: center;
  gap: 10px;
}

.rank {
  width: 20px;
  height: 20px;
  border-radius: 6px;
  display: grid;
  place-items: center;
  font-size: 11.5px;
  font-weight: 700;
  background: #f1f5f4;
  color: #64748b;
  flex-shrink: 0;
}

.rank-1 {
  background: #fef3c7;
  color: #b45309;
}

.rank-2 {
  background: #e8f1ff;
  color: #1d4ed8;
}

.rank-3 {
  background: #fee9d7;
  color: #c2410c;
}

.credit-name {
  font-size: 12.5px;
  font-weight: 600;
  color: #1b2a24;
}

.credit-type {
  font-size: 11px;
  color: var(--c-muted-2);
  margin-top: 2px;
}

.credit-score {
  display: flex;
  align-items: center;
  gap: 7px;
  font-family: var(--font-num);
  font-size: 13px;
  font-weight: 700;
  color: #15803d;
}

.share-list {
  display: flex;
  flex-direction: column;
  gap: 13px;
  min-height: 170px;
}

.share-top {
  display: flex;
  justify-content: space-between;
  font-size: 12.5px;
  margin-bottom: 5px;
}

.share-name {
  color: var(--c-text-2);
  font-weight: 500;
}

.share-val {
  font-family: var(--font-num);
  font-weight: 700;
  color: #111827;
}

.share-foot {
  font-size: 11px;
  color: var(--c-muted-2);
  margin-top: 4px;
}

.settle-amount {
  padding: 14px;
  border-radius: 11px;
  background: linear-gradient(135deg, #f0fdf4, #e6f8ee);
  border: 1px solid #d6f0e0;
}

.sa-label {
  font-size: 11.5px;
  color: #15803d;
}

.sa-value {
  font-family: var(--font-num);
  font-size: 24px;
  font-weight: 700;
  color: #14532d;
  margin: 5px 0 3px;
  letter-spacing: -0.5px;
}

.sa-sub {
  font-size: 11px;
  color: #4b7a60;
}

.settle-rows {
  margin-top: 12px;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.sr-item {
  display: flex;
  justify-content: space-between;
  font-size: 12.5px;
  color: var(--c-muted);
}

.sr-item b {
  color: #1b2a24;
  font-weight: 600;
}

.sr-item b.ok {
  color: #15803d;
}

.sr-item b.warn {
  color: #b45309;
}

.threshold-note {
  margin-top: 10px;
  padding-top: 9px;
  border-top: 1px dashed var(--c-border);
  font-size: 11.5px;
  color: var(--c-muted-2);
}
</style>
