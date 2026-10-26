<template>
  <div class="page" v-loading="loading">
    <!-- teal 欢迎横幅 -->
    <div class="hero">
      <div class="hero-left">
        <div class="hero-avatar">{{ store.user?.avatar || '赵' }}</div>
        <div>
          <div class="hero-title">{{ store.user?.realName }}，今天有 {{ stats.pending || 0 }} 批待质检</div>
          <div class="hero-sub">
            质检配送中心 · 今日已完成 {{ stats.todayInspected || 0 }} 批次质检、
            {{ stats.delivering || 0 }} 趟配送，质检合格率 {{ stats.passRate || 0 }}%
          </div>
        </div>
      </div>
      <div class="hero-right">
        <div class="hero-num">{{ stats.passRate || 0 }}%</div>
        <div class="hero-num-label">已检批次</div>
      </div>
      <span class="hero-glow"></span>
    </div>

    <div class="stat-grid">
      <StatCard label="待质检" :value="stats.pending || 0" icon="AlarmClock" color="orange" foot="排队中" />
      <StatCard label="今日已检" :value="stats.todayInspected || 0" icon="DocumentChecked"
                color="teal" :foot="'合格 ' + (stats.todayQualified || 0) + ' 批次'" />
      <StatCard label="配送中" :value="stats.delivering || 0" icon="Van" color="blue" foot="在途批次" />
      <StatCard label="不合格" :value="stats.unqualified || 0" icon="Warning" color="red" foot="需复核" />
    </div>

    <div class="grid-main">
      <SectionCard title="今日质检任务" no-pad>
        <template #extra>待质检 {{ stats.pending || 0 }}</template>
        <el-table :data="records" style="width: 100%" header-cell-class-name="th">
          <el-table-column label="批次号" min-width="188">
            <template #default="{ row }">
              <span v-if="row.batchNo" class="mono">{{ row.batchNo }}</span>
              <span v-else class="muted small">待生成</span>
            </template>
          </el-table-column>
          <el-table-column label="产品" min-width="100">
            <template #default="{ row }">{{ row.productName }}</template>
          </el-table-column>
          <el-table-column prop="farmerName" label="供货农户" min-width="130" show-overflow-tooltip />
          <el-table-column label="数量" width="92" align="right">
            <template #default="{ row }">{{ fmtNum(row.quantity) }} {{ row.unit }}</template>
          </el-table-column>
          <el-table-column prop="canteenName" label="收货食堂" min-width="100" />
          <el-table-column label="状态" width="96" align="center">
            <template #default="{ row }"><StatusTag type="order" :value="row.status" /></template>
          </el-table-column>
          <el-table-column label="操作" width="150" align="center">
            <template #default="{ row }">
              <span class="link" @click="openDetail(row)">详情</span>
              <span class="link" @click="goQuality(row)">质检录入</span>
            </template>
          </el-table-column>
          <template #empty>
            <div class="empty">暂无待质检任务，农户发货后会自动进入队列</div>
          </template>
        </el-table>
        <div class="pager">
          <span>共 {{ total }} 条记录</span>
          <el-pagination
            v-model:current-page="page" :page-size="size" :total="total"
            layout="prev, pager, next" background small @current-change="load"
          />
        </div>
      </SectionCard>

      <div class="side-col">
        <SectionCard title="近 7 日合格率">
          <template #extra><span class="muted small">一批次一检</span></template>
          <BaseChart :option="trendOption" height="212px" />
        </SectionCard>
        <div class="hint-bar">
          <el-icon><InfoFilled /></el-icon>
          <span>质检合格后方可生成批次二维码并进入配送环节，不合格批次将驳回并扣减信用。</span>
        </div>
      </div>
    </div>

    <OrderDetailDrawer v-model="drawer" :order-id="currentId" endpoint="admin" />
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { inspectorApi } from '@/api'
import { useUserStore } from '@/store/user'
import StatCard from '@/components/StatCard.vue'
import SectionCard from '@/components/SectionCard.vue'
import StatusTag from '@/components/StatusTag.vue'
import BaseChart from '@/components/BaseChart.vue'
import OrderDetailDrawer from '@/components/OrderDetailDrawer.vue'
import { fmtNum } from '@/utils/format'

const router = useRouter()
const store = useUserStore()

const loading = ref(false)
const records = ref([])
const total = ref(0)
const page = ref(1)
const size = ref(8)
const stats = ref({})
const trend = ref([])
const drawer = ref(false)
const currentId = ref(null)

const trendOption = computed(() => ({
  grid: { left: 4, right: 10, top: 22, bottom: 2, containLabel: true },
  tooltip: {
    trigger: 'axis',
    backgroundColor: 'rgba(15,43,32,.92)',
    borderWidth: 0,
    textStyle: { color: '#fff', fontSize: 12 }
  },
  xAxis: {
    type: 'category',
    data: trend.value.map((t) => t.day),
    axisLine: { lineStyle: { color: '#e5eae8' } },
    axisTick: { show: false },
    axisLabel: { color: '#8b9c96', fontSize: 10.5 }
  },
  yAxis: {
    type: 'value',
    min: 80,
    max: 100,
    splitLine: { lineStyle: { color: '#f1f5f3', type: 'dashed' } },
    axisLabel: { color: '#8b9c96', fontSize: 10.5, formatter: '{value}%' }
  },
  series: [
    {
      type: 'bar',
      barWidth: '44%',
      data: trend.value.map((t) => (t.cnt ? Math.round((t.ok / t.cnt) * 1000) / 10 : 0)),
      itemStyle: {
        borderRadius: [5, 5, 0, 0],
        color: {
          type: 'linear', x: 0, y: 0, x2: 0, y2: 1,
          colorStops: [
            { offset: 0, color: '#2dd4bf' },
            { offset: 1, color: '#0d9488' }
          ]
        }
      },
      label: { show: true, position: 'top', color: '#0f766e', fontSize: 10.5 }
    }
  ]
}))

const load = async () => {
  loading.value = true
  try {
    const res = await inspectorApi.tasks({ page: page.value, size: size.value })
    const d = res.data || {}
    records.value = d.records || []
    total.value = d.total || 0
    stats.value = d.stats || {}
    trend.value = d.trend || []
  } finally {
    loading.value = false
  }
}

const openDetail = (row) => {
  currentId.value = row.id
  drawer.value = true
}

const goQuality = (row) => {
  router.push({ path: '/inspector/quality', query: { orderId: row.id } })
}

onMounted(load)
</script>

<style scoped>
.hero {
  position: relative;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 20px;
  padding: 22px 26px;
  border-radius: 14px;
  margin-bottom: 16px;
  overflow: hidden;
  color: #fff;
  background: linear-gradient(120deg, #0f766e 0%, #0d9488 52%, #115e59 100%);
  box-shadow: 0 10px 26px rgba(13, 148, 136, 0.2);
}

.hero-left {
  display: flex;
  align-items: center;
  gap: 14px;
  position: relative;
  z-index: 2;
}

.hero-avatar {
  width: 46px;
  height: 46px;
  border-radius: 13px;
  display: grid;
  place-items: center;
  font-size: 19px;
  font-weight: 700;
  background: rgba(255, 255, 255, 0.18);
  border: 1px solid rgba(255, 255, 255, 0.24);
}

.hero-title {
  font-size: 18px;
  font-weight: 700;
  letter-spacing: 0.3px;
}

.hero-sub {
  margin-top: 6px;
  font-size: 12.5px;
  color: rgba(230, 255, 251, 0.78);
}

.hero-right {
  text-align: right;
  position: relative;
  z-index: 2;
}

.hero-num {
  font-family: var(--font-num);
  font-size: 30px;
  font-weight: 700;
  line-height: 1;
}

.hero-num-label {
  margin-top: 6px;
  font-size: 11.5px;
  color: rgba(230, 255, 251, 0.7);
}

.hero-glow {
  position: absolute;
  right: -60px;
  top: -100px;
  width: 320px;
  height: 320px;
  border-radius: 50%;
  background: radial-gradient(circle, rgba(255, 255, 255, 0.16), transparent 66%);
}

.grid-main {
  display: grid;
  grid-template-columns: minmax(0, 1.55fr) minmax(0, 1fr);
  gap: 14px;
  align-items: start;
}

.side-col {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.hint-bar {
  display: flex;
  gap: 8px;
  padding: 12px 14px;
  border-radius: 10px;
  background: #e6f6f4;
  border: 1px solid #cdeee8;
  color: #0f766e;
  font-size: 12px;
  line-height: 1.7;
}
</style>
