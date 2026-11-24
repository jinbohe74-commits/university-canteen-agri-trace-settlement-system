<template>
  <div class="page db-page" v-loading="loading">
    <!-- 页头：真实刷新时间 + 实时连接状态，不再伪造增长率 -->
    <div class="page-head db-head">
      <div>
        <h2 class="page-title">运营总览</h2>
        <p class="page-sub">平台全局态势 · 数据截至 {{ data.serverTime || '—' }} · {{ data.today || '' }}</p>
      </div>
      <div class="page-actions">
        <span class="db-live" :class="{ on: rt.connected }">
          <i></i>{{ rt.connected ? '实时同步中' : '轮询同步中' }}
        </span>
        <el-button plain @click="load"><el-icon><Refresh /></el-icon>刷新</el-button>
        <el-button type="primary" @click="exportReport"><el-icon><Download /></el-icon>导出报表</el-button>
      </div>
    </div>

    <!-- 主指标带：1 个主指标 + 3 个次指标，明确主次而不是四张同款卡片 -->
    <section class="db-hero">
      <div class="db-primary db-drill" role="button" tabindex="0"
           @click="drill('平台累计交易额', fmtWan(cards.totalAmount))"
           @keydown.enter.prevent="drill('平台累计交易额', fmtWan(cards.totalAmount))">
        <div class="db-eyebrow">平台累计交易额 <span>GMV</span></div>
        <div class="db-primary-value">¥ {{ fmtWan(cards.totalAmount) }}</div>
        <div class="db-primary-foot">已剔除取消与不合格订单 · 覆盖全部食堂与认证农户</div>
        <span class="db-primary-go">明细 ›</span>
        <div class="db-spark" aria-label="近 6 个完整月订单量走势">
          <div v-for="(m, i) in chartTrend" :key="i" class="db-spark-col"
               :title="monthLabel(m.m) + '：' + m.c + ' 笔'">
            <i :style="{ height: sparkHeight(m.c) }"></i>
          </div>
        </div>
        <div class="db-spark-axis">
          <span v-for="(m, i) in chartTrend" :key="i">{{ monthLabel(m.m) }}</span>
        </div>
      </div>
      <div class="db-minis">
        <div class="db-mini db-drill" role="button" tabindex="0"
             @click="drill('订单总量', fmtNum(cards.totalOrders))"
             @keydown.enter.prevent="drill('订单总量', fmtNum(cards.totalOrders))">
          <div class="db-mini-top"><span>订单总量</span><b>{{ fmtNum(cards.totalOrders) }}</b></div>
          <div class="db-mini-foot">全平台累计 · 近 6 个月新增 {{ fmtNum(recentOrders) }} 笔</div>
          <span class="db-drill-go">明细 ›</span>
        </div>
        <div class="db-mini db-drill db-mini--track" role="button" tabindex="0"
             @click="drill('质检合格率', (cards.passRate ?? 0) + '%')"
             @keydown.enter.prevent="drill('质检合格率', (cards.passRate ?? 0) + '%')">
          <div class="db-mini-top"><span>质检合格率</span><b>{{ cards.passRate ?? 0 }}<em>%</em></b></div>
          <div class="db-mini-foot">批次抽检一次合格率 · 不合格批次自动驳回</div>
          <div class="db-track"><i :style="{ width: (cards.passRate || 0) + '%' }"></i></div>
          <span class="db-drill-go">明细 ›</span>
        </div>
        <div class="db-mini db-drill" role="button" tabindex="0"
             @click="drill('认证农户', fmtNum(cards.farmers))"
             @keydown.enter.prevent="drill('认证农户', fmtNum(cards.farmers))">
          <div class="db-mini-top"><span>认证农户</span><b>{{ fmtNum(cards.farmers) }}<em>家</em></b></div>
          <div class="db-mini-foot">含合作社与生产基地 · 本月新增 {{ fmtNum(cards.farmersGrowth || 0) }} 家</div>
          <span class="db-drill-go">明细 ›</span>
        </div>
      </div>
    </section>

    <!-- 趋势 + 品类 -->
    <div class="db-row db-row-a">
      <SectionCard title="近 6 个月订单量趋势" class="db-card">
        <template #extra><span class="db-unit">单位：笔 · 不含当月（本月已成交 {{ curMonthCount }} 笔）</span></template>
        <BaseChart :option="orderTrendOption" height="236px" />
      </SectionCard>
      <SectionCard title="采购品类分布" class="db-card">
        <template #extra><span class="db-unit">按有效订单</span></template>
        <div class="db-donut">
          <BaseChart :option="categoryOption" height="190px" />
          <div class="db-donut-legend">
            <div v-for="(c, i) in categoryShare.slice(0, 5)" :key="c.name" class="db-leg-row">
              <span class="db-leg-dot" :style="{ background: palette[i % palette.length] }"></span>
              <span class="db-leg-name">{{ c.name }}</span>
              <b>{{ percentOf(c.cnt) }}%</b>
            </div>
            <div v-if="!categoryShare.length" class="db-empty">暂无数据</div>
          </div>
        </div>
      </SectionCard>
    </div>

    <!-- 价格行情 + 实时动态 -->
    <div class="db-row db-row-a">
      <SectionCard title="价格行情" class="db-card">
        <template #extra>
          <div class="db-inline-legend">
            <span v-for="(s, i) in priceSeries" :key="s.productId">
              <i :style="{ background: palette[i % palette.length] }"></i>{{ s.name }}
            </span>
          </div>
        </template>

        <!-- 行情自选区：原来后端写死「只有两个品项」，用户想看别的品种无从下手 -->
        <div class="pf-bar">
          <el-select
            v-model="priceIds" size="small" multiple collapse-tags collapse-tags-tooltip
            :max-collapse-tags="2" :multiple-limit="priceMaxSeries"
            placeholder="选择品项（可多选对比）" class="pf-prod" @change="onPriceChange"
          >
            <!-- 按一级品类分组：27 个品项平铺太难找 -->
            <el-option-group
              v-for="g in priceGroups" :key="g.name" :label="g.name"
            >
              <el-option
                v-for="p in g.items" :key="p.productId"
                :label="p.name" :value="p.productId"
              >
                <span class="pf-opt-name">{{ p.name }}</span>
                <span class="pf-opt-meta">¥{{ p.avgPrice }} · {{ p.orderCount }} 单</span>
              </el-option>
            </el-option-group>
          </el-select>
          <el-select v-model="priceDays" size="small" class="pf-days" @change="load">
            <el-option :value="7" label="近 7 天" />
            <el-option :value="30" label="近 30 天" />
            <el-option :value="90" label="近 90 天" />
            <el-option :value="180" label="近半年" />
          </el-select>
        </div>

        <BaseChart v-if="priceTrend.length" :option="priceOption" height="214px" />
        <div v-else class="db-empty" style="height:214px;display:flex;align-items:center;justify-content:center">
          {{ priceIds.length ? '所选品项在该时间段内没有成交记录，换个品项或放宽时间范围' : '暂无价格行情数据' }}
        </div>
        <div v-if="priceTrend.length" class="pf-source">
          <b v-if="priceRange" class="pf-range">{{ priceRange }}</b>
          <span>{{ priceSource }}</span>
        </div>
      </SectionCard>
      <SectionCard title="与我相关的通知" class="db-card">
        <template #extra><span class="db-unit">发给我的</span></template>
        <div class="db-feed">
          <div v-for="n in notif.items.slice(0, 6)" :key="n.id" class="db-feed-item"
               :class="{ 'is-link': !!n.route, 'is-unread': !n.isRead }" @click="openFeed(n)">
            <span class="db-feed-dot" :class="{ off: n.isRead }"></span>
            <div class="grow">
              <div class="db-feed-title">{{ n.title }}<span v-if="n.bizNo" class="mono"> · {{ n.bizNo }}</span></div>
              <div class="db-feed-meta">{{ n.senderName || '系统' }} · {{ feedTime(n.createTime) }}</div>
            </div>
          </div>
          <div v-if="!notif.items.length" class="db-empty">
            暂无发给你的通知 · 有与你相关的订单、质检、配送、结算变动时会出现在这里
          </div>
        </div>
      </SectionCard>
    </div>

    <!-- 待办 / 结算 / 信用 -->
    <div class="db-row db-row-b">
      <SectionCard title="待办事项" class="db-card">
        <template #extra><span class="db-unit">{{ todos.length }} 项待跟进</span></template>
        <div class="db-list">
          <div v-for="(t, i) in todos" :key="i" class="db-todo" @click="openTodo(t)">
            <span class="db-todo-bar" :class="'lv-' + (t.color || 'teal')"></span>
            <div class="grow">
              <div class="db-todo-title">{{ t.title }}</div>
              <div class="db-todo-desc">{{ t.desc }}</div>
            </div>
            <el-icon class="db-go"><ArrowRight /></el-icon>
          </div>
          <div v-if="!todos.length" class="db-empty">暂无待办事项</div>
        </div>
      </SectionCard>

      <SectionCard title="本月结算概况" class="db-card">
        <template #extra><span class="db-unit">月结 · 每月 5 日</span></template>
        <div class="db-settle-main">
          <div class="db-settle-label">实际结算金额</div>
          <div class="db-settle-value">¥ {{ fmtMoney(settlement.actual) }}</div>
          <div class="db-settle-sub">
            应结 ¥ {{ fmtMoney(settlement.total) }} · 损耗扣款 ¥ {{ fmtMoney(settlement.loss) }}
          </div>
        </div>
        <div class="db-kv"><span>结算单</span><b>{{ settlement.count || 0 }} 笔</b></div>
        <div class="db-kv"><span>待审核 / 已结算</span><b>{{ settlement.pending || 0 }} / {{ settlement.settled || 0 }}</b></div>
        <div class="db-kv">
          <span>平均损耗率</span>
          <b :class="Number(settlement.avgLossRate) >= Number(settlement.lossThreshold) ? 'is-warn' : 'is-ok'">
            {{ settlement.avgLossRate || 0 }}%
          </b>
        </div>
        <div class="db-threshold">
          阈值 {{ settlement.lossThreshold || 5 }}% ·
          {{ Number(settlement.avgLossRate) < Number(settlement.lossThreshold) ? '低于预警线' : '已超预警线' }}
        </div>
      </SectionCard>

      <SectionCard title="农户信用 Top5" class="db-card">
        <template #extra><span class="db-unit">基础分 80</span></template>
        <div class="db-list">
          <div v-for="(f, i) in creditTop5" :key="f.name" class="db-rank">
            <span class="db-rank-no" :class="'r-' + (i + 1)">{{ String(i + 1).padStart(2, '0') }}</span>
            <div class="grow">
              <div class="db-rank-name">{{ f.name }}</div>
              <div class="db-rank-type">{{ f.type }}</div>
            </div>
            <b class="db-rank-score">{{ f.creditScore }}</b>
          </div>
          <div v-if="!creditTop5.length" class="db-empty">暂无数据</div>
        </div>
      </SectionCard>
    </div>

    <!-- 食堂采购占比：全宽横条 -->
    <SectionCard title="各食堂采购占比" class="db-card">
      <template #extra><span class="db-unit">按订单笔数与金额</span></template>
      <div class="db-shares">
        <div v-for="(c, i) in canteenShare" :key="c.name" class="db-share">
          <div class="db-share-top">
            <span class="db-share-name">{{ c.name }}</span>
            <b>{{ c.percent }}%</b>
          </div>
          <div class="db-share-track">
            <i :style="{ width: Number(c.percent) + '%', background: palette[i % palette.length] }"></i>
          </div>
          <div class="db-share-foot">{{ c.cnt }} 笔 · ¥ {{ fmtWan(c.amount) }}</div>
        </div>
        <div v-if="!canteenShare.length" class="db-empty">暂无数据</div>
      </div>
    </SectionCard>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { adminApi } from '@/api'
import { openStatDrill } from '@/utils/statDrill'
import BaseChart from '@/components/BaseChart.vue'
import SectionCard from '@/components/SectionCard.vue'
import { fmtMoney, fmtNum, fmtWan } from '@/utils/format'
import { useAutoRefresh } from '@/utils/useAutoRefresh'
import { realtimeState } from '@/utils/realtime'
import { notifState, readNotification } from '@/utils/notification'
import { exportSectionsCsv, stamped } from '@/utils/export'
import { openWorkItem } from '@/utils/workItem'

const router = useRouter()
const route = useRoute()
const loading = ref(false)
const data = ref({})
const rt = realtimeState()
const notif = notifState()

/** 主指标带是自绘卡片（不是 StatCard），也接同一套指标明细契约 */
const drill = (label, value) => openStatDrill(label, value, route.path)

const cards = computed(() => data.value.cards || {})
const orderTrend = computed(() => data.value.orderTrend || [])
const categoryShare = computed(() => data.value.categoryShare || [])
const priceTrend = computed(() => data.value.priceTrend || [])
const priceSeries = computed(() => data.value.priceSeries || [])
const priceOptions = computed(() => data.value.priceOptions || [])
const priceMaxSeries = computed(() => Number(data.value.priceMaxSeries) || 4)
const priceSource = computed(() => data.value.priceSource || '')

/* ---- 行情自选：品项多选（最多 4 条对比）+ 时间窗 ---- */
const priceIds = ref([])
const priceDays = ref(90)
/** 用户是否主动改过选择：没改过就沿用后端给的默认（成交最活跃的两个） */
let priceTouched = false
/** 按品类分组，避免 27 个品项平铺成一长条 */
const priceGroups = computed(() => {
  const map = new Map()
  priceOptions.value.forEach((p) => {
    const k = p.categoryName || '其他'
    if (!map.has(k)) map.set(k, [])
    map.get(k).push(p)
  })
  return [...map.entries()].map(([name, items]) => ({ name, items }))
})
const priceQuery = () => {
  const q = { priceDays: priceDays.value }
  if (priceTouched && priceIds.value.length) q.priceProducts = priceIds.value.join(',')
  return q
}
const onPriceChange = () => {
  priceTouched = true
  if (!priceIds.value.length) {
    ElMessage.warning('至少选择一个品项，否则行情图没有数据')
    return
  }
  if (priceIds.value.length > priceMaxSeries.value) {
    priceIds.value = priceIds.value.slice(0, priceMaxSeries.value)
    ElMessage.info(`最多同时对比 ${priceMaxSeries.value} 个品项`)
  }
  load()
}
/** 显示库内实际日期区间，避免「本周」与实际数据不符。
    只返回日期本身，单位与口径统一放到图表下方的说明行，免得和图例挤在一起被误读。 */
const priceRange = computed(() => {
  const ds = [...new Set(priceTrend.value.map(x => String(x.statDate || '').slice(5)))]
  if (!ds.length) return ''
  return ds.length === 1 ? ds[0] : ds[0] + ' ~ ' + ds[ds.length - 1]
})
const todos = computed(() => data.value.todos || [])
const creditTop5 = computed(() => data.value.creditTop5 || [])
const canteenShare = computed(() => data.value.canteenShare || [])
const settlement = computed(() => data.value.settlement || {})

/* 图表颜色全部来自设计 token：切换浅色/暖纸/深色时重新取值并重绘，
   不再写死 #16a34a 这类只适配浅色的色值（L-031：结构/颜色分层）。 */
const themeTick = ref(0)
let modeObserver = null
const C = computed(() => {
  themeTick.value
  const cs = getComputedStyle(document.documentElement)
  const get = (n, fb) => (cs.getPropertyValue(n).trim() || fb)
  return {
    accent: get('--accent', '#16a34a'),
    accent2: get('--accent-2', '#c2703a'),
    ink: get('--ink', '#16241f'),
    ink2: get('--ink-2', '#4b5b54'),
    muted: get('--muted', '#7c8b84'),
    line: get('--line', '#dfe7e3'),
    surface: get('--surface', '#ffffff'),
    surface2: get('--surface-2', '#f4f7f6'),
    soft: get('--accent-soft', '#e8f5ed')
  }
})
const palette = computed(() => {
  themeTick.value
  const cs = getComputedStyle(document.documentElement)
  const get = (n, fb) => (cs.getPropertyValue(n).trim() || fb)
  return [get('--accent', '#16a34a'), get('--accent-2', '#c2703a'),
    get('--info', '#3b82f6'), get('--gold', '#d9a441'), get('--ok', '#0d9488'),
    get('--c-purple', '#8b5cf6'), get('--c-cyan', '#06b6d4')]
})

/* 当月是「不完整月份」：11 月只有 1-10 日的数据，直接和整月并列会让趋势线
   在末尾断崖式下跌，看起来像业务崩了。加 * 标记并在图例处说明。 */
const curYm = new Date().toISOString().slice(0, 7)
const isPartial = (m) => String(m || '').slice(0, 7) === curYm
const monthLabel = (m) => {
  if (!m) return ''
  const name = Number(String(m).split('-')[1]) + '月'
  return isPartial(m) ? name + '*' : name
}
const feedTime = (s) => String(s || '').replace('T', ' ').slice(5, 16) || '—'
/** 看板里的通知同样可点：标已读 + 打开该单据的具体问题与可执行动作 */
const openFeed = async (n) => {
  if (!n.isRead) await readNotification(n.id)
  await openWorkItem({
    scope: 'notification', bizType: n.bizType, bizNo: n.bizNo,
    orderId: n.orderId, title: (n.title || '业务通知') + ' · 单据详情'
  })
}
/** 待办不再只跳列表页：按 todoKey 展开「到底是哪几单、每单什么问题」 */
const openTodo = (t) => openWorkItem({
  scope: 'todo', todoKey: t.todoKey, title: (t.module || '待办') + ' · ' + (t.title || '')
})
const totalCategory = computed(() => categoryShare.value.reduce((s, c) => s + Number(c.cnt), 0))
const percentOf = (v) => totalCategory.value ? Math.round((Number(v) / totalCategory.value) * 100) : 0
const percentBar = (v, max) => (max ? Math.min(100, Math.round(Number(v || 0) / Number(max) * 100)) : 0) + '%'
const recentOrders = computed(() => orderTrend.value.reduce((s, x) => s + (Number(x.c) || 0), 0))
const maxTrend = computed(() => Math.max(1, ...orderTrend.value.map(x => Number(x.c) || 0)))
const sparkHeight = (c) => Math.max(8, Math.round(Number(c || 0) / maxTrend.value * 100)) + '%'

const axisBase = (C) => ({
  axisLine: { lineStyle: { color: C.line } },
  axisTick: { show: false },
  axisLabel: { color: C.muted, fontSize: 11 }
})
const tooltipBase = (C) => ({
  backgroundColor: C.surface, borderColor: C.line, borderWidth: 1,
  textStyle: { color: C.ink, fontSize: 12 }
})

/* 折线只画完整月份。当月天数不全，画进去就是一条断崖，
   加星号说明只是在解释错觉，不如直接不画。当月另以文字给出。 */
const chartTrend = computed(() => {
  const full = orderTrend.value.filter((x) => !isPartial(x.m))
  return full.length >= 2 ? full : orderTrend.value
})
const curMonthCount = computed(() => {
  const hit = orderTrend.value.find((x) => isPartial(x.m))
  return Number(hit?.c || 0)
})
const orderTrendOption = computed(() => {
  const c = C.value
  return {
    grid: { left: 6, right: 14, top: 24, bottom: 4, containLabel: true },
    tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' }, ...tooltipBase(c) },
    xAxis: { type: 'category', data: chartTrend.value.map(x => monthLabel(x.m)), ...axisBase(c) },
    yAxis: {
      type: 'value', ...axisBase(c),
      splitLine: { lineStyle: { color: c.line, type: 'dashed' } }
    },
    series: [{
      name: '订单笔数', type: 'line', smooth: true, symbol: 'circle', symbolSize: 6,
      data: orderTrend.value.map(x => x.c),
      lineStyle: { width: 2.6, color: c.accent },
      itemStyle: { color: c.accent, borderColor: c.surface, borderWidth: 2 },
      areaStyle: {
        color: {
          type: 'linear', x: 0, y: 0, x2: 0, y2: 1,
          colorStops: [{ offset: 0, color: c.accent + '55' }, { offset: 1, color: c.accent + '05' }]
        }
      },
      label: { show: true, position: 'top', color: c.ink2, fontSize: 11, fontWeight: 600 }
    }]
  }
})

const categoryOption = computed(() => {
  const c = C.value
  return {
    tooltip: { trigger: 'item', ...tooltipBase(c), formatter: '{b}<br/>{c} 笔（{d}%）' },
    series: [{
      type: 'pie', radius: ['58%', '80%'], center: ['50%', '50%'],
      avoidLabelOverlap: true, label: { show: false },
      itemStyle: { borderColor: c.surface, borderWidth: 2 },
      data: categoryShare.value.map((x, i) => ({
        name: x.name, value: x.cnt, itemStyle: { color: palette.value[i % palette.value.length] }
      }))
    }]
  }
})

const priceOption = computed(() => {
  const c = C.value
  const dates = [...new Set(priceTrend.value.map(x => String(x.statDate).slice(5)))]
  const seriesOf = (s, color) => ({
    name: s.name, type: 'line', smooth: true, symbol: 'circle', symbolSize: 5,
    // 稀疏数据（某天没成交）用 null 断开，不要把点用直线「编」出来
    connectNulls: false,
    data: dates.map(d => {
      const hit = priceTrend.value.find(x => String(x.productId) === String(s.productId)
        && String(x.statDate).slice(5) === d)
      return hit ? Number(hit.avgPrice) : null
    }),
    lineStyle: { width: 2.4, color }, itemStyle: { color },
    emphasis: { focus: 'series' },
    areaStyle: {
      color: {
        type: 'linear', x: 0, y: 0, x2: 0, y2: 1,
        colorStops: [{ offset: 0, color: color + '2e' }, { offset: 1, color: color + '00' }]
      }
    }
  })
  return {
    grid: { left: 6, right: 16, top: 22, bottom: 6, containLabel: true },
    tooltip: {
      trigger: 'axis', ...tooltipBase(c),
      formatter: (ps) => {
        if (!ps || !ps.length) return ''
        const head = `<div style="font-weight:600;margin-bottom:4px">${ps[0].axisValue}</div>`
        return head + ps.map(p => {
          const q = priceTrend.value.find(x => String(x.productId) === String(
            priceSeries.value.find(s => s.name === p.seriesName)?.productId)
            && String(x.statDate).slice(5) === p.axisValue)
          const vol = q ? ` · ${q.qty}kg / ${q.cnt}单` : ''
          return `<div style="display:flex;justify-content:space-between;gap:14px">
            <span>${p.marker}${p.seriesName}</span>
            <span style="font-weight:600">¥ ${p.value == null ? '—' : Number(p.value).toFixed(2)}${vol}</span>
          </div>`
        }).join('')
      }
    },
    xAxis: { type: 'category', boundaryGap: false, data: dates, ...axisBase(c) },
    yAxis: {
      type: 'value', scale: true, ...axisBase(c),
      splitLine: { lineStyle: { color: c.line, type: 'dashed' } }
    },
    series: priceSeries.value.map((s, i) => seriesOf(s, palette.value[i % palette.value.length]))
  }
})

const load = async () => {
  loading.value = true
  try {
    const res = await adminApi.dashboard(priceQuery())
    data.value = res.data || {}
    // 首次进入：把后端默认选中的品项同步回选择器，让用户看得见「现在在看哪几个」
    if (!priceTouched && !priceIds.value.length) {
      priceIds.value = (res.data?.priceSeries || []).map(s => s.productId)
    }
    if (res.data?.priceDays && !priceTouched) priceDays.value = res.data.priceDays
  } finally {
    loading.value = false
  }
}

/* 真导出：每段一个工作表，所有数字来自当前接口结果，不编造统计。
   以前把 8 段不同列数的报表拼进同一个 CSV → Excel 里全挤在 Sheet1，列互相错位。 */
const exportReport = () => {
  const d = data.value
  const sections = [
    { title: '核心指标', note: '口径：平台累计快照，取自 /admin/dashboard 当前返回',
      header: ['指标', '数值'], rows: [
      ['累计交易额(元)', cards.value.totalAmount], ['订单总量(笔)', cards.value.totalOrders],
      ['质检合格率(%)', cards.value.passRate], ['认证农户(家)', cards.value.farmers],
      ['数据时间', d.serverTime || d.today || '']
    ] },
    { title: '近 6 个月订单量', note: '口径：按创建月份聚合全部有效订单',
      header: ['月份', '订单笔数'],
      rows: orderTrend.value.map(x => [x.m, x.c]) },
    { title: '采购品类分布', note: '口径：按商品一级品类统计订单笔数',
      header: ['品类', '订单笔数'],
      rows: categoryShare.value.map(x => [x.name, x.cnt]) },
    { title: '价格行情', note: `口径：${priceSource.value}；时间窗近 ${priceDays.value} 天`,
      header: ['日期', '产品', '品类', '均价(元/kg)', '成交量(kg)', '成交笔数'],
      rows: priceTrend.value.map(x => {
        const info = priceOptions.value.find(p => String(p.productId) === String(x.productId))
        return [String(x.statDate).slice(0, 10), x.product,
          info?.categoryName || '—', x.avgPrice, x.qty, x.cnt]
      }) },
    { title: '待办事项', note: '口径：当前管理员账号的待处理事项',
      header: ['模块', '事项', '说明'],
      rows: todos.value.map(x => [x.module, x.title, x.desc]) },
    { title: '本月结算', note: '口径：本月结算单汇总，单位元',
      header: ['项目', '数值'], rows: [
      ['应结金额', settlement.value.total], ['损耗扣款', settlement.value.loss],
      ['实际结算', settlement.value.actual], ['结算单笔数', settlement.value.count],
      ['平均损耗率(%)', settlement.value.avgLossRate]
    ] },
    { title: '农户信用 Top5', note: '口径：按当前信用分降序取前 5 家',
      header: ['农户', '类型', '信用分'],
      rows: creditTop5.value.map(x => [x.name, x.type, x.creditScore]) },
    { title: '各食堂采购占比', note: '口径：按收货食堂聚合订单笔数与金额',
      header: ['食堂', '占比(%)', '订单笔数', '金额'],
      rows: canteenShare.value.map(x => [x.name, x.percent, x.cnt, x.amount]) }
  ]
  const n = exportSectionsCsv(stamped('平台运营报表', 'xlsx'), sections)
  ElMessage.success(n ? `报表已下载（${sections.length} 个工作表 · ${n} 行数据）` : '暂无可导出的报表数据')
}

useAutoRefresh(load)
onMounted(() => {
  load()
  modeObserver = new MutationObserver(() => { themeTick.value++ })
  modeObserver.observe(document.documentElement, { attributes: true, attributeFilter: ['data-mode'] })
})
onBeforeUnmount(() => { if (modeObserver) modeObserver.disconnect() })
</script>

<style scoped>
/* 全部颜色走设计 token，三档明度下都是同一套版式（L-031） */
.db-head .page-sub { margin-top: 5px; }
.db-live {
  display: inline-flex; align-items: center; gap: 6px;
  height: 32px; padding: 0 11px; border-radius: var(--radius);
  border: 1px solid var(--line); background: var(--surface-2);
  font-size: var(--fs-xs); color: var(--ink-2); white-space: nowrap;
}
.db-live i { width: 7px; height: 7px; border-radius: 50%; background: var(--muted); }
.db-live.on i { background: var(--accent); box-shadow: 0 0 0 3px var(--accent-soft); }

.db-hero {
  display: grid; grid-template-columns: minmax(0, 1.05fr) minmax(0, 1fr);
  gap: 14px; margin-bottom: 14px;
}
.db-primary, .db-minis > div, .db-card {
  background: var(--surface); border: 1px solid var(--line);
  border-radius: var(--radius-lg);
}
.db-primary { position: relative; padding: 22px 24px 16px; overflow: hidden; }
/* 主指标带可点：和 StatCard 一样要自己说出来，避免又变成「华而不实」的装饰数字 */
.db-drill { cursor: pointer; transition: border-color .18s var(--ease), box-shadow .18s var(--ease); }
.db-drill:hover, .db-drill:focus-visible { border-color: var(--accent); box-shadow: var(--shadow-md); outline: none; }
.db-drill-go {
  position: absolute; right: 12px; bottom: 8px;
  font-size: var(--fs-xs); font-weight: 700; color: var(--accent);
  /* 常显浅色版：只在 hover 才出现时，鼠标一移动就消失，容易被当成「点不动」 */
  opacity: .55; transition: opacity .18s var(--ease), transform .18s var(--ease);
}
.db-drill:hover .db-drill-go, .db-drill:focus-visible .db-drill-go { opacity: 1; transform: translateX(2px); }
/* 主指标卡的可点提示放右上角：右下角是柱状图月份轴，放那里会被柱子盖住 */
.db-primary-go {
  position: absolute; right: 20px; top: 18px;
  font-size: var(--fs-xs); font-weight: 700; color: var(--accent);
  opacity: .55; transition: opacity .18s var(--ease), transform .18s var(--ease);
}
.db-drill:hover .db-primary-go, .db-drill:focus-visible .db-primary-go { opacity: 1; transform: translateX(2px); }
.db-primary > .db-spark-axis { margin-bottom: 12px; }
.db-primary::before {
  content: ''; position: absolute; left: 0; top: 0; bottom: 0; width: 4px;
  background: var(--accent);
}
.db-eyebrow { font-size: var(--fs-sm); font-weight: 600; color: var(--ink-2); letter-spacing: .04em; }
.db-eyebrow span { margin-left: 8px; font-size: var(--fs-2xs); color: var(--muted); letter-spacing: .12em; }
.db-primary-value {
  margin-top: 12px; font-family: var(--font-num);
  font-size: clamp(30px, 3.4vw, 46px); font-weight: 700;
  line-height: 1.1; letter-spacing: -.02em; color: var(--ink);
}
.db-primary-foot { margin-top: 8px; font-size: var(--fs-xs); color: var(--muted); }
.db-spark {
  display: flex; align-items: flex-end; gap: 10px;
  height: 54px; margin-top: 18px;
  border-bottom: 1px solid var(--line);
}
.db-spark-col {
  flex: 1; min-width: 0; height: 100%;
  display: flex; align-items: flex-end; justify-content: center;
}
.db-spark-col i {
  display: block; width: 54%; max-width: 20px; border-radius: 3px 3px 0 0;
  background: linear-gradient(180deg, var(--accent), var(--accent-soft));
  opacity: .62; transition: opacity .18s var(--ease), transform .18s var(--ease);
}
.db-spark-col:hover i { opacity: .92; }
.db-spark-col:last-child i { opacity: 1; }
.db-spark-axis {
  display: flex; gap: 10px; margin-top: 7px;
  font-size: var(--fs-2xs); color: var(--muted);
}
.db-spark-axis span { flex: 1; min-width: 0; text-align: center; }

.db-minis { display: grid; grid-template-rows: repeat(3, minmax(0, 1fr)); gap: 10px; }
.db-mini { position: relative; padding: 13px 18px; display: flex; flex-direction: column; justify-content: center; }
.db-mini-top { display: flex; align-items: baseline; justify-content: space-between; gap: 10px; }
.db-mini-top > span { font-size: var(--fs-sm); color: var(--ink-2); }
.db-mini-top b { font-family: var(--font-num); font-size: var(--fs-xl); font-weight: 700; color: var(--ink); }
.db-mini-top em { font-size: var(--fs-xs); font-style: normal; color: var(--muted); margin-left: 3px; }
.db-mini-foot { margin-top: 3px; font-size: var(--fs-2xs); color: var(--muted); }
/* 带进度条的小卡：底部给「明细 ›」留出独立一行，否则进度条会横穿文字 */
.db-mini--track { padding-bottom: 26px; }
.db-track { height: 4px; margin-top: 9px; border-radius: 99px; background: var(--surface-2); overflow: hidden; }
.db-track i { display: block; height: 100%; border-radius: 99px; background: var(--accent); }
.db-track.warm i { background: var(--accent-2); }

.db-row { display: grid; gap: 14px; margin-bottom: 14px; }
.db-row-a { grid-template-columns: minmax(0, 1.6fr) minmax(0, 1fr); }
.db-row-b { grid-template-columns: repeat(3, minmax(0, 1fr)); }
.db-card { min-width: 0; }
.db-unit { font-size: var(--fs-xs); color: var(--muted); }
.db-empty { padding: 22px 8px; text-align: center; font-size: var(--fs-xs); color: var(--muted); }

.db-donut { display: flex; align-items: center; gap: 8px; }
.db-donut > :first-child { flex: 1.05; min-width: 0; }
.db-donut-legend { flex: 1; min-width: 0; }
.db-leg-row { display: flex; align-items: center; gap: 7px; padding: 5px 0; font-size: var(--fs-xs); }
.db-leg-dot { width: 8px; height: 8px; border-radius: 2px; flex: 0 0 auto; }
.db-leg-name { flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; color: var(--ink-2); }
.db-leg-row b { font-family: var(--font-num); color: var(--ink); }
.db-inline-legend {
  display: inline-flex; align-items: center; justify-content: flex-end;
  gap: 8px 12px; font-size: var(--fs-xs); color: var(--muted);
  flex-wrap: wrap; max-width: 100%;
}
.db-inline-legend span { display: inline-flex; align-items: center; gap: 5px; white-space: nowrap; }
.db-inline-legend i { width: 10px; height: 3px; border-radius: 2px; flex: 0 0 auto; }

/* 行情自选区：品项多选 + 时间窗 */
.pf-bar {
  display: flex; align-items: center; gap: 8px; flex-wrap: wrap;
  padding: 0 16px 10px;
}
.pf-prod { flex: 1; min-width: 190px; max-width: 300px; }
.pf-days { width: 104px; }
.pf-opt-name { float: left; }
.pf-opt-meta { float: right; color: var(--muted); font-size: var(--fs-xs); margin-left: 14px; }
.pf-source {
  padding: 0 16px 10px;
  font-size: var(--fs-xs); color: var(--muted);
  display: flex; flex-wrap: wrap; align-items: center; gap: 4px 8px;
}
.pf-range {
  font-weight: 600; color: var(--ink-2);
  font-family: var(--font-num);
  padding-right: 8px;
  border-right: 1px solid var(--line);
}

.db-feed { max-height: 214px; overflow-y: auto; }
.db-feed-item { display: flex; gap: 10px; padding: 9px 2px; border-bottom: 1px solid var(--line); }
.db-feed-item:last-child { border-bottom: 0; }
.db-feed-dot {
  width: 7px; height: 7px; margin-top: 6px; border-radius: 50%;
  background: var(--accent); box-shadow: 0 0 0 3px var(--accent-soft); flex: 0 0 auto;
}
.db-feed-dot.off { background: var(--muted); box-shadow: none; opacity: .45; }
.db-feed-item.is-link { cursor: pointer; }
.db-feed-item.is-link:hover .db-feed-title { color: var(--accent); }
.db-feed-item.is-unread .db-feed-title { color: var(--ink); font-weight: 700; }
.db-feed-title { font-size: var(--fs-sm); line-height: 1.5; color: var(--ink-2); }
.db-feed-title .mono { font-family: var(--font-num); font-weight: 400; color: var(--muted); }
.db-feed-meta { margin-top: 3px; font-size: var(--fs-2xs); color: var(--muted); }

.db-list { max-height: 214px; overflow-y: auto; }
.db-todo { display: flex; align-items: center; gap: 10px; padding: 9px 2px; border-bottom: 1px solid var(--line); cursor: pointer; }
.db-todo:last-child { border-bottom: 0; }
.db-todo:hover .db-todo-title { color: var(--accent); }
.db-todo-bar { width: 3px; height: 28px; border-radius: 2px; background: var(--ok); flex: 0 0 auto; }
.db-todo-bar.lv-amber, .db-todo-bar.lv-orange { background: var(--warn); }
.db-todo-bar.lv-red { background: var(--danger); }
.db-todo-title { font-size: var(--fs-sm); color: var(--ink); }
.db-todo-desc { margin-top: 2px; font-size: var(--fs-2xs); color: var(--muted); }
.db-go { color: var(--muted); font-size: var(--fs-base); }

.db-settle-main { padding: 4px 0 14px; border-bottom: 1px solid var(--line); margin-bottom: 8px; }
.db-settle-label { font-size: var(--fs-xs); color: var(--ink-2); }
.db-settle-value { margin-top: 6px; font-family: var(--font-num); font-size: var(--fs-display); font-weight: 700; color: var(--ink); }
.db-settle-sub { margin-top: 5px; font-size: var(--fs-2xs); color: var(--muted); }
.db-kv { display: flex; justify-content: space-between; padding: 6px 0; font-size: var(--fs-xs); color: var(--ink-2); }
.db-kv b { color: var(--ink); font-family: var(--font-num); }
.db-kv b.is-warn { color: var(--warn); }
.db-kv b.is-ok { color: var(--accent); }
.db-threshold { margin-top: 8px; padding-top: 8px; border-top: 1px solid var(--line); font-size: var(--fs-2xs); color: var(--muted); }

.db-rank { display: flex; align-items: center; gap: 10px; padding: 8px 2px; border-bottom: 1px solid var(--line); }
.db-rank:last-child { border-bottom: 0; }
.db-rank-no {
  width: 24px; height: 24px; display: grid; place-items: center; flex: 0 0 auto;
  border-radius: var(--radius-sm); background: var(--surface-2);
  font-family: var(--font-num); font-size: var(--fs-xs); font-weight: 700; color: var(--ink-2);
}
.db-rank-no.r-1 { background: var(--accent-soft); color: var(--accent); }
.db-rank-name { font-size: var(--fs-sm); color: var(--ink); }
.db-rank-type { margin-top: 2px; font-size: var(--fs-2xs); color: var(--muted); }
.db-rank-score { font-family: var(--font-num); font-size: var(--fs-md); color: var(--accent); }

.db-shares { display: grid; grid-template-columns: repeat(auto-fit, minmax(200px, 1fr)); gap: 16px 22px; }
.db-share-top { display: flex; justify-content: space-between; font-size: var(--fs-xs); color: var(--ink-2); }
.db-share-top b { font-family: var(--font-num); color: var(--ink); }
.db-share-track { height: 6px; margin: 7px 0 5px; border-radius: 99px; background: var(--surface-2); overflow: hidden; }
.db-share-track i { display: block; height: 100%; border-radius: 99px; }
.db-share-foot { font-size: var(--fs-2xs); color: var(--muted); }

@media (max-width: 1180px) {
  .db-hero, .db-row-a, .db-row-b { grid-template-columns: minmax(0, 1fr); }
  .db-minis { grid-template-rows: none; }
}
</style>
