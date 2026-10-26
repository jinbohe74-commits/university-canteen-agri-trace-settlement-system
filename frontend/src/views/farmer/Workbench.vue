<template>
  <div class="page" v-loading="loading">
    <div class="page-head">
      <div>
        <h2 class="page-title">我的工作台</h2>
        <p class="page-sub">
          {{ data.today || today() }} · {{ farmer.type || '农户/合作社' }} · {{ farmer.address || '—' }}
        </p>
      </div>
      <div class="page-actions">
        <span class="date-chip"><el-icon><Calendar /></el-icon>今日 {{ data.today || today() }}</span>
        <el-button plain @click="load"><el-icon><Refresh /></el-icon>刷新</el-button>
        <el-button type="primary" @click="router.push('/farmer/sources')">
          <el-icon><Plus /></el-icon>发布货源
        </el-button>
      </div>
    </div>

    <!-- 欢迎横幅 -->
    <div class="welcome">
      <span class="deco deco-1"></span>
      <span class="deco deco-2"></span>
      <div class="w-left">
        <div class="w-avatar">{{ initial }}</div>
        <div class="w-text">
          <div class="w-hello">{{ farmer.contact || '农户' }}，早上好！</div>
          <div class="w-line">
            <b>{{ farmer.name || '我的合作社' }}</b>
            <span class="w-sep">·</span>
            <span>{{ todayTip }}</span>
          </div>
        </div>
      </div>
      <div class="w-metrics">
        <div class="w-metric">
          <div class="wm-num">{{ score }}</div>
          <div class="wm-label">信用分</div>
        </div>
        <div class="w-divider"></div>
        <div class="w-metric">
          <div class="wm-label">资质状态</div>
          <div class="wm-cert">
            <span class="cert-dot"></span>{{ farmer.certStatus || '待认证' }}
          </div>
        </div>
      </div>
    </div>

    <!-- 统计卡片 -->
    <div class="stat-grid">
      <StatCard
        label="待接单" :value="pending" icon="Tickets" color="orange"
        foot="需 3 小时内响应"
      />
      <StatCard
        label="配送中" :value="delivering" icon="Van" color="blue"
        foot="在途批次"
      />
      <StatCard
        label="本月收入" :value="'¥ ' + fmtMoney(cards.monthIncome)" icon="Money" color="green"
        :trend="growthText" :up="(monthGrowth ?? 0) >= 0" foot="已结算入账"
      />
      <StatCard
        label="信用分" :value="score" suffix=" 分" icon="Star" color="purple"
        :foot="'等级：' + creditLevel"
      />
    </div>

    <!-- 收入趋势 + 待处理订单 -->
    <div class="grid-2">
      <SectionCard title="近 6 个月销售收入" :body-style="false">
        <template #extra><span class="muted small">单位：万元</span></template>
        <BaseChart :option="incomeOption" height="200px" />
      </SectionCard>

      <SectionCard title="待处理订单" :body-style="false" noPad>
        <template #extra>
          <span class="link" @click="router.push('/farmer/orders')">查看全部订单 →</span>
        </template>
        <div class="todo-list">
          <div
            v-for="o in todoOrders" :key="o.id" class="todo-row"
            @click="openOrder(o)"
          >
            <div class="grow">
              <div class="row gap-8">
                <span class="mono">{{ o.orderNo }}</span>
                <span class="muted small">{{ fmtDate(o.createTime, false) }}</span>
              </div>
              <div class="todo-meta">
                {{ o.productName }} · {{ o.canteenName }} · {{ fmtNum(o.quantity) }} {{ o.unit }}
              </div>
            </div>
            <div class="todo-right">
              <div class="money">¥ {{ fmtMoney(o.totalAmount) }}</div>
              <StatusTag type="order" :value="o.status" />
            </div>
          </div>
          <div v-if="!todoOrders.length" class="empty">暂无待处理订单，保持货源充足即可</div>
        </div>
      </SectionCard>
    </div>

    <!-- 在售货源 + 信用分明细 -->
    <div class="grid-2 grid-lower">
      <SectionCard title="我的在售货源" :body-style="false" noPad>
        <template #extra>
          <span class="muted small">{{ data.sourceCount ?? sources.length }} 个在售</span>
        </template>
        <div class="src-list">
          <div v-for="s in sources" :key="s.id" class="src-row">
            <span class="src-dot"></span>
            <span class="src-name">{{ s.productName }}</span>
            <span class="src-qty">{{ fmtNum(s.quantity) }} {{ s.unit }}</span>
            <span class="src-price">¥ {{ fmtMoney(s.price) }} / {{ s.unit }}</span>
          </div>
          <div v-if="!sources.length" class="empty">暂无在售货源，去发布一条吧</div>
        </div>
        <div class="src-tip">
          <el-icon><InfoFilled /></el-icon>
          保持货源充足、报价合理，可提升智能匹配排名与成交率。
        </div>
      </SectionCard>

      <SectionCard title="信用分明细" :body-style="false" noPad>
        <template #extra><span class="muted small">当前 {{ score }} 分</span></template>
        <div class="credit-list">
          <div v-for="c in creditDetail" :key="c.id" class="credit-row">
            <div class="grow">
              <div class="credit-reason">{{ c.reason }}</div>
              <div class="credit-sub">
                订单 #{{ c.orderId }} · {{ fmtDate(c.createTime, false) }}
              </div>
            </div>
            <div class="credit-delta" :class="Number(c.changeScore) >= 0 ? 'down-up' : 'down-loss'">
              {{ Number(c.changeScore) > 0 ? '+' : '' }}{{ fmtNum(c.changeScore, Number(c.changeScore) % 1 === 0 ? 0 : 1) }}
            </div>
          </div>
          <div v-if="!creditDetail.length" class="empty">暂无信用变动记录</div>
        </div>
      </SectionCard>
    </div>

    <OrderDetailDrawer v-model="detailVisible" :order-id="detailId" endpoint="farmer" />
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { farmerApi } from '@/api'
import StatCard from '@/components/StatCard.vue'
import SectionCard from '@/components/SectionCard.vue'
import BaseChart from '@/components/BaseChart.vue'
import StatusTag from '@/components/StatusTag.vue'
import OrderDetailDrawer from '@/components/OrderDetailDrawer.vue'
import { fmtDate, fmtMoney, fmtNum, today } from '@/utils/format'

const router = useRouter()
const loading = ref(false)
const data = ref({})
const detailVisible = ref(false)
const detailId = ref(null)

const farmer = computed(() => data.value.farmer || {})
const cards = computed(() => data.value.cards || {})
const incomeTrend = computed(() => data.value.incomeTrend || [])
const todoOrders = computed(() => data.value.todoOrders || [])
const sources = computed(() => data.value.sources || [])
const creditDetail = computed(() => data.value.creditDetail || [])

const pending = computed(() => Number(cards.value.pendingOrders || 0))
const delivering = computed(() => Number(cards.value.delivering || 0))
const score = computed(() => {
  const v = cards.value.creditScore ?? farmer.value.creditScore
  return v === null || v === undefined ? '—' : Number(v)
})

const initial = computed(() => {
  const name = farmer.value.contact || farmer.value.name || '农'
  return String(name).slice(0, 1)
})

const creditLevel = computed(() => {
  const v = Number(score.value)
  if (v >= 90) return '优秀'
  if (v >= 85) return '良好'
  return '一般'
})

const todayTip = computed(() => {
  if (pending.value || delivering.value) {
    return `今日有 ${pending.value} 笔订单待处理，${delivering.value} 笔配送中，请及时安排采摘与发货`
  }
  return '今日暂无待处理订单，请保持货源充足，及时响应新订单'
})

const monthGrowth = computed(() => {
  const list = incomeTrend.value
  if (list.length < 2) return null
  const cur = Number(list[list.length - 1].amount || 0)
  const prev = Number(list[list.length - 2].amount || 0)
  if (!prev) return null
  return Math.round(((cur - prev) / prev) * 1000) / 10
})

const growthText = computed(() => {
  if (monthGrowth.value === null) return ''
  const sign = monthGrowth.value >= 0 ? '+' : ''
  return `较上月 ${sign}${monthGrowth.value}%`
})

const monthLabel = (m) => (m ? Number(String(m).split('-')[1]) + '月' : '')

const incomeOption = computed(() => {
  const list = incomeTrend.value
  return {
    grid: { left: 6, right: 14, top: 28, bottom: 4, containLabel: true },
    tooltip: {
      trigger: 'axis',
      axisPointer: { type: 'shadow' },
      backgroundColor: 'rgba(15,43,32,.92)',
      borderWidth: 0,
      textStyle: { color: '#fff', fontSize: 12 },
      valueFormatter: (v) => '¥ ' + Number(v).toFixed(2) + ' 万元'
    },
    xAxis: {
      type: 'category',
      data: list.map((x) => monthLabel(x.m)),
      axisLine: { lineStyle: { color: '#e5eae8' } },
      axisTick: { show: false },
      axisLabel: { color: '#8b9c96', fontSize: 11.5 }
    },
    yAxis: {
      type: 'value',
      splitLine: { lineStyle: { color: '#f1f5f3', type: 'dashed' } },
      axisLabel: { color: '#8b9c96', fontSize: 11, formatter: '{value}' }
    },
    series: [
      {
        name: '销售收入',
        type: 'bar',
        barWidth: '42%',
        data: list.map((x) => Number((Number(x.amount || 0) / 10000).toFixed(2))),
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
        label: {
          show: true,
          position: 'top',
          color: '#15803d',
          fontSize: 11,
          fontWeight: 600,
          formatter: (p) => Number(p.value).toFixed(2)
        }
      }
    ]
  }
})

const openOrder = (o) => {
  detailId.value = o.id
  detailVisible.value = true
}

const load = async () => {
  loading.value = true
  try {
    const res = await farmerApi.workbench()
    data.value = res.data || {}
  } finally {
    loading.value = false
  }
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

/* ---------------- 欢迎横幅 ---------------- */
.welcome {
  position: relative;
  overflow: hidden;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 24px;
  min-height: 132px;
  padding: 22px 28px;
  margin-bottom: 16px;
  border-radius: 14px;
  color: #fff;
  background: linear-gradient(115deg, #0d3b29 0%, #15613f 44%, #16a34a 100%);
  box-shadow: 0 10px 26px rgba(16, 61, 40, 0.18);
}

.deco {
  position: absolute;
  border-radius: 50%;
  pointer-events: none;
}

.deco-1 {
  right: 12%;
  top: -74px;
  width: 220px;
  height: 220px;
  background: radial-gradient(circle, rgba(255, 255, 255, 0.14), transparent 68%);
}

.deco-2 {
  left: 32%;
  bottom: -96px;
  width: 260px;
  height: 260px;
  background: radial-gradient(circle, rgba(74, 222, 128, 0.22), transparent 70%);
}

.w-left {
  position: relative;
  z-index: 1;
  display: flex;
  align-items: center;
  gap: 16px;
  min-width: 0;
}

.w-avatar {
  width: 58px;
  height: 58px;
  flex-shrink: 0;
  border-radius: 16px;
  display: grid;
  place-items: center;
  font-size: 24px;
  font-weight: 700;
  letter-spacing: 1px;
  background: rgba(255, 255, 255, 0.16);
  border: 1px solid rgba(255, 255, 255, 0.3);
  box-shadow: inset 0 0 18px rgba(255, 255, 255, 0.08);
}

.w-text {
  min-width: 0;
}

.w-hello {
  font-size: 20px;
  font-weight: 700;
  letter-spacing: 0.3px;
}

.w-line {
  margin-top: 8px;
  font-size: 12.5px;
  color: rgba(255, 255, 255, 0.82);
  line-height: 1.6;
}

.w-line b {
  color: #fff;
  font-weight: 600;
}

.w-sep {
  margin: 0 6px;
  color: rgba(255, 255, 255, 0.45);
}

.w-metrics {
  position: relative;
  z-index: 1;
  flex-shrink: 0;
  display: flex;
  align-items: center;
  gap: 22px;
  padding: 14px 24px;
  border-radius: 12px;
  background: rgba(255, 255, 255, 0.1);
  border: 1px solid rgba(255, 255, 255, 0.16);
  backdrop-filter: blur(2px);
}

.w-divider {
  width: 1px;
  height: 42px;
  background: rgba(255, 255, 255, 0.18);
}

.wm-num {
  font-family: var(--font-num);
  font-size: 34px;
  font-weight: 700;
  line-height: 1;
  letter-spacing: -0.5px;
  color: #dcfce7;
}

.wm-label {
  margin-top: 6px;
  font-size: 11.5px;
  color: rgba(255, 255, 255, 0.6);
}

.wm-cert {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-top: 8px;
  font-size: 15px;
  font-weight: 600;
}

.cert-dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: #4ade80;
  box-shadow: 0 0 0 3px rgba(74, 222, 128, 0.2);
}

/* ---------------- 布局 ---------------- */
.grid-2 {
  display: grid;
  grid-template-columns: minmax(0, 1.62fr) minmax(0, 1fr);
  gap: 14px;
  margin-bottom: 14px;
}

.grid-lower {
  grid-template-columns: minmax(0, 1.05fr) minmax(0, 1fr);
  margin-bottom: 0;
}

/* ---------------- 待处理订单 ---------------- */
.todo-list {
  display: flex;
  flex-direction: column;
  min-height: 200px;
  padding: 2px 18px;
}

.todo-row {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 14px 4px;
  border-bottom: 1px dashed var(--c-border);
  cursor: pointer;
  border-radius: 6px;
  transition: background 0.18s;
}

.todo-row:last-child {
  border-bottom: none;
}

.todo-row:hover {
  background: #f7fbf9;
}

.todo-meta {
  margin-top: 4px;
  font-size: 12px;
  color: var(--c-muted);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.todo-right {
  flex-shrink: 0;
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: 5px;
}

.todo-right .money {
  font-size: 13.5px;
  color: #111827;
}

/* ---------------- 在售货源 ---------------- */
.src-list {
  display: flex;
  flex-direction: column;
  padding: 8px 18px 4px;
}

.src-row {
  display: flex;
  align-items: center;
  gap: 9px;
  padding: 7.5px 0;
  font-size: 12.5px;
}

.src-dot {
  width: 6px;
  height: 6px;
  flex-shrink: 0;
  border-radius: 50%;
  background: #22c55e;
  box-shadow: 0 0 0 3px rgba(34, 197, 94, 0.1);
}

.src-name {
  color: #1b2a24;
  font-weight: 500;
  width: 82px;
  flex-shrink: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.src-qty {
  margin-left: auto;
  font-family: var(--font-num);
  color: var(--c-muted);
  font-size: 12px;
}

.src-price {
  width: 104px;
  text-align: right;
  font-family: var(--font-num);
  font-weight: 600;
  color: #15803d;
  font-size: 12px;
}

.src-tip {
  display: flex;
  align-items: flex-start;
  gap: 6px;
  margin: 8px 18px 16px;
  padding: 9px 11px;
  border-radius: 8px;
  background: #f2fbf5;
  border: 1px solid #dcf0e4;
  font-size: 11.5px;
  line-height: 1.6;
  color: #4b7a60;
}

.src-tip .el-icon {
  margin-top: 1px;
  color: #16a34a;
}

/* ---------------- 信用明细 ---------------- */
.credit-list {
  display: flex;
  flex-direction: column;
  min-height: 200px;
  padding: 2px 18px;
}

.credit-row {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 11px 0;
  border-bottom: 1px dashed var(--c-border);
}

.credit-row:last-child {
  border-bottom: none;
}

.credit-reason {
  font-size: 12.5px;
  color: #1b2a24;
  font-weight: 500;
  line-height: 1.5;
}

.credit-sub {
  margin-top: 3px;
  font-size: 11px;
  color: var(--c-muted-2);
}

.credit-delta {
  flex-shrink: 0;
  font-family: var(--font-num);
  font-size: 14px;
  font-weight: 700;
}

.down-up {
  color: #15803d;
}

.down-loss {
  color: #dc2626;
}

@media (max-width: 1180px) {
  .grid-2,
  .grid-lower {
    grid-template-columns: minmax(0, 1fr);
  }

  .welcome {
    flex-direction: column;
    align-items: flex-start;
  }

  .w-metrics {
    width: 100%;
    justify-content: space-around;
  }
}
</style>
