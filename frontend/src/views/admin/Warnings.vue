<template>
  <div class="page" v-loading="loading">
    <div class="page-head">
      <div>
        <h2 class="page-title">食安预警管理</h2>
        <p class="page-sub">集中监测证照、质检、价格、订单逾期四类风险，阈值后台可配置</p>
      </div>
      <div class="page-actions">
        <el-button plain @click="showRules"><el-icon><Setting /></el-icon>预警规则</el-button>
        <el-button type="primary" plain @click="showSubscribe"><el-icon><Bell /></el-icon>预警订阅</el-button>
      </div>
    </div>

    <!-- 四类彩色统计卡 -->
    <div class="stat-grid">
      <StatCard label="证照过期" :value="fmtNum(stats.cert)" icon="Postcard" color="red" foot="高风险" />
      <StatCard label="质检不合格" :value="fmtNum(stats.quality)" icon="CircleClose" color="orange" foot="需跟进" />
      <StatCard label="价格波动" :value="fmtNum(stats.price)" icon="TrendCharts" color="blue" foot="超阈值" />
      <StatCard label="订单逾期" :value="fmtNum(stats.overdue)" icon="AlarmClock" color="teal" foot="需催办" />
    </div>

    <!-- 趋势 + 分布 -->
    <div class="chart-grid">
      <SectionCard title="近 30 天预警趋势">
        <template #extra>
          <span class="muted small">共 {{ fmtNum(stats.total) }} 条 · 待处理 {{ fmtNum(stats.pending) }} 条</span>
        </template>
        <BaseChart :option="trendOption" height="230px" />
      </SectionCard>

      <SectionCard title="预警类型分布">
        <template #extra><span class="muted small">按类型统计</span></template>
        <div class="donut-wrap">
          <BaseChart :option="typeOption" height="196px" />
          <div class="donut-legend">
            <div v-for="t in typeCount" :key="t.type" class="dl-row">
              <span class="dl-dot" :style="{ background: colorOfType(t.type) }"></span>
              <span class="dl-name">{{ t.type }}</span>
              <span class="dl-val">{{ shareOf(t.cnt) }}%</span>
            </div>
            <div v-if="!typeCount.length" class="empty">暂无数据</div>
          </div>
        </div>
      </SectionCard>
    </div>

    <!-- 列表 -->
    <div class="card">
      <div class="filter-bar">
        <el-input
          v-model="query.keyword"
          placeholder="预警对象 / 预警内容"
          clearable
          @keyup.enter="search"
        >
          <template #prefix><el-icon><Search /></el-icon></template>
        </el-input>
        <el-select v-model="query.type" placeholder="预警类型" @change="search">
          <el-option label="全部类型" value="" />
          <el-option v-for="t in typeCount" :key="t.type" :label="t.type" :value="t.type" />
        </el-select>
        <el-select v-model="query.level" placeholder="风险等级" @change="search">
          <el-option label="全部等级" value="" />
          <el-option label="高" value="高" />
          <el-option label="中" value="中" />
          <el-option label="低" value="低" />
        </el-select>
        <el-select v-model="query.status" placeholder="处理状态" @change="search">
          <el-option label="全部状态" value="" />
          <el-option label="待处理" value="待处理" />
          <el-option label="待确认" value="待确认" />
          <el-option label="已完成" value="已完成" />
          <el-option label="已忽略" value="已忽略" />
        </el-select>
        <el-button type="primary" @click="search">
          <el-icon><Search /></el-icon>查询
        </el-button>
        <el-button plain @click="reset">
          <el-icon><RefreshLeft /></el-icon>重置
        </el-button>
        <div class="filter-spacer"></div>
        <el-button type="primary" @click="oneClickHandle">
          <el-icon><MagicStick /></el-icon>一键处置
        </el-button>
      </div>

      <div class="table-wrap">
        <el-table :data="list.records" style="width: 100%" @selection-change="onSelectionChange">
          <el-table-column type="selection" width="46" />
          <el-table-column label="风险等级" width="94" align="center">
            <template #default="{ row }">
              <StatusTag type="risk" :value="row.riskLevel" />
            </template>
          </el-table-column>
          <el-table-column label="预警类型" width="118">
            <template #default="{ row }">
              <span class="tag" :class="tagOfType(row.warningType)">{{ row.warningType }}</span>
            </template>
          </el-table-column>
          <el-table-column label="预警对象" min-width="150">
            <template #default="{ row }">
              <div class="cell-main">{{ row.target }}</div>
              <div class="cell-sub mono">{{ row.warningNo }}</div>
            </template>
          </el-table-column>
          <el-table-column label="预警内容" min-width="270">
            <template #default="{ row }">
              <div class="content-cell">{{ row.content }}</div>
            </template>
          </el-table-column>
          <el-table-column label="触发时间" width="122">
            <template #default="{ row }">
              <span class="mono muted">{{ fmtDate(row.triggerTime) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="状态" width="96" align="center">
            <template #default="{ row }">
              <StatusTag type="warning" :value="row.status" />
            </template>
          </el-table-column>
          <el-table-column label="操作" width="112" align="right">
            <template #default="{ row }">
              <span class="link" @click.stop="openView(row)">查看</span>
              <span class="link" @click.stop="openHandle(row)">处理</span>
            </template>
          </el-table-column>
          <template #empty>
            <div class="empty">当前筛选条件下暂无预警</div>
          </template>
        </el-table>
      </div>

      <div class="pager">
        <span>第 {{ list.page }} / {{ list.pages || 1 }} 页 · 共 {{ list.total }} 条预警 · 证照临期 {{ fmtNum(stats.expiringCerts) }} 家</span>
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

    <!-- 查看弹窗 -->
    <el-dialog v-model="viewVisible" title="预警详情" width="560px">
      <div v-if="viewData.warning" class="w-detail">
        <div class="wd-head">
          <div>
            <div class="wd-no mono">{{ viewData.warning.warningNo }}</div>
            <div class="muted small">{{ viewData.warning.warningType }} · {{ viewData.warning.target }}</div>
          </div>
          <div class="row gap-8">
            <StatusTag type="risk" :value="viewData.warning.riskLevel" />
            <StatusTag type="warning" :value="viewData.warning.status" />
          </div>
        </div>
        <div class="wd-content">{{ viewData.warning.content }}</div>
        <div class="wd-rows">
          <div class="wr"><span>触发时间</span><b>{{ fmtDate(viewData.warning.triggerTime) }}</b></div>
          <div class="wr"><span>处置人</span><b>{{ viewData.warning.handler || '—' }}</b></div>
          <div class="wr"><span>处置时间</span><b>{{ viewData.warning.handleTime ? fmtDate(viewData.warning.handleTime) : '—' }}</b></div>
          <div class="wr"><span>处置备注</span><b>{{ viewData.warning.handleRemark || '—' }}</b></div>
        </div>
        <div class="suggest">
          <div class="suggest-title"><el-icon><MagicStick /></el-icon>处置建议</div>
          <div class="suggest-text">{{ viewData.suggestion || '暂无处置建议' }}</div>
        </div>
      </div>
      <template #footer>
        <el-button plain @click="viewVisible = false">关闭</el-button>
        <el-button type="primary" @click="handleFromView">去处理</el-button>
      </template>
    </el-dialog>

    <!-- 处理弹窗 -->
    <el-dialog v-model="handleVisible" title="处置预警" width="540px" :close-on-click-modal="false">
      <div v-if="handleRow.warningNo" class="w-detail">
        <div class="wd-head">
          <div>
            <div class="wd-no mono">{{ handleRow.warningNo }}</div>
            <div class="muted small">{{ handleRow.warningType }} · {{ handleRow.target }}</div>
          </div>
          <StatusTag type="risk" :value="handleRow.riskLevel" />
        </div>
        <div class="wd-content">{{ handleRow.content }}</div>
        <div class="suggest">
          <div class="suggest-title"><el-icon><MagicStick /></el-icon>处置建议</div>
          <div class="suggest-text">{{ suggestion || '暂无处置建议' }}</div>
        </div>

        <el-form label-width="80px" class="handle-form">
          <el-form-item label="处理结果">
            <el-radio-group v-model="handleForm.status">
              <el-radio value="已完成">已完成</el-radio>
              <el-radio value="已忽略">已忽略</el-radio>
              <el-radio value="待确认">待确认</el-radio>
            </el-radio-group>
          </el-form-item>
          <el-form-item label="处理备注">
            <el-input
              v-model="handleForm.remark"
              type="textarea"
              :rows="3"
              maxlength="120"
              show-word-limit
              placeholder="请填写处置说明，如：已联系农户补交检测报告"
            />
          </el-form-item>
        </el-form>
      </div>
      <template #footer>
        <el-button plain @click="handleVisible = false">取消</el-button>
        <el-button type="primary" :loading="handling" @click="submitHandle">提交处置</el-button>
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
import BaseChart from '@/components/BaseChart.vue'
import { fmtDate, fmtNum } from '@/utils/format'

const TYPE_COLOR = {
  证照预警: '#f59e0b',
  质检预警: '#ef4444',
  价格预警: '#3b82f6',
  逾期预警: '#0d9488'
}

const TYPE_TAG = {
  证照预警: 'tag-orange',
  质检预警: 'tag-red',
  价格预警: 'tag-blue',
  逾期预警: 'tag-teal'
}

const PALETTE = ['#16a34a', '#22c55e', '#0d9488', '#3b82f6', '#f59e0b', '#8b5cf6']

const loading = ref(false)
const stats = ref({})
const list = reactive({ records: [], total: 0, page: 1, size: 10, pages: 0 })
const query = reactive({ page: 1, size: 10, type: '', level: '', status: '', keyword: '' })
const selection = ref([])

const viewVisible = ref(false)
const viewData = ref({})

const handleVisible = ref(false)
const handling = ref(false)
const handleRow = ref({})
const suggestion = ref('')
const handleForm = reactive({ status: '已完成', remark: '' })

const typeCount = computed(() => stats.value.typeCount || [])
const trend = computed(() => stats.value.trend || [])
const typeTotal = computed(() => typeCount.value.reduce((s, t) => s + Number(t.cnt || 0), 0))

const colorOfType = (t) => TYPE_COLOR[t] || PALETTE[Math.abs(String(t).length) % PALETTE.length]
const tagOfType = (t) => TYPE_TAG[t] || 'tag-gray'
const shareOf = (cnt) => {
  if (!typeTotal.value) return 0
  return Math.round((Number(cnt) / typeTotal.value) * 1000) / 10
}

const trendOption = computed(() => ({
  grid: { left: 6, right: 16, top: 22, bottom: 4, containLabel: true },
  tooltip: {
    trigger: 'axis',
    axisPointer: { type: 'shadow' },
    backgroundColor: 'rgba(15,43,32,.92)',
    borderWidth: 0,
    textStyle: { color: '#fff', fontSize: 12 },
    formatter: (p) => p[0].axisValue + '<br/>预警 ' + p[0].data + ' 条'
  },
  xAxis: {
    type: 'category',
    data: trend.value.map((x) => x.day),
    axisLine: { lineStyle: { color: '#e5eae8' } },
    axisTick: { show: false },
    axisLabel: { color: '#8b9c96', fontSize: 10.5, interval: 2 }
  },
  yAxis: {
    type: 'value',
    minInterval: 1,
    splitLine: { lineStyle: { color: '#f1f5f3', type: 'dashed' } },
    axisLabel: { color: '#8b9c96', fontSize: 11 }
  },
  series: [
    {
      name: '预警数量',
      type: 'bar',
      data: trend.value.map((x) => x.cnt),
      barWidth: '52%',
      itemStyle: {
        borderRadius: [5, 5, 0, 0],
        color: {
          type: 'linear', x: 0, y: 0, x2: 0, y2: 1,
          colorStops: [
            { offset: 0, color: '#4ade80' },
            { offset: 1, color: '#16a34a' }
          ]
        }
      }
    }
  ]
}))

const typeOption = computed(() => ({
  tooltip: {
    trigger: 'item',
    backgroundColor: 'rgba(15,43,32,.92)',
    borderWidth: 0,
    textStyle: { color: '#fff', fontSize: 12 },
    formatter: '{b}<br/>{c} 条（{d}%）'
  },
  series: [
    {
      type: 'pie',
      radius: ['56%', '76%'],
      center: ['50%', '50%'],
      avoidLabelOverlap: true,
      itemStyle: { borderColor: '#fff', borderWidth: 2 },
      label: { show: false },
      emphasis: { scaleSize: 4 },
      data: typeCount.value.map((t) => ({
        name: t.type,
        value: t.cnt,
        itemStyle: { color: colorOfType(t.type) }
      }))
    }
  ]
}))

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
    const res = await adminApi.warnings(params())
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
  query.type = ''
  query.level = ''
  query.status = ''
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

const openView = async (row) => {
  viewData.value = { warning: row, suggestion: '' }
  viewVisible.value = true
  try {
    const res = await adminApi.warningDetail(row.id)
    viewData.value = res.data || { warning: row, suggestion: '' }
  } catch (e) {
    /* 拦截器已提示 */
  }
}

const loadSuggestion = async (id) => {
  try {
    const res = await adminApi.warningDetail(id)
    return res.data ? res.data.suggestion || '' : ''
  } catch (e) {
    return ''
  }
}

const openHandle = async (row) => {
  handleRow.value = row
  suggestion.value = ''
  handleForm.status = '已完成'
  handleForm.remark = ''
  handleVisible.value = true
  suggestion.value = await loadSuggestion(row.id)
}

const handleFromView = () => {
  const w = viewData.value.warning
  viewVisible.value = false
  if (w) openHandle(w)
}

const submitHandle = async () => {
  handling.value = true
  try {
    await adminApi.handleWarning(handleRow.value.id, {
      status: handleForm.status,
      remark: handleForm.remark
    })
    ElMessage.success('预警已处置：' + handleRow.value.warningNo + ' → ' + handleForm.status)
    handleVisible.value = false
    load()
  } catch (e) {
    /* 拦截器已提示 */
  } finally {
    handling.value = false
  }
}

const oneClickHandle = async () => {
  const ids = list.records.filter((r) => r.status === '待处理').map((r) => r.id)
  if (!ids.length) {
    ElMessage.warning('当前页暂无「待处理」预警可一键处置')
    return
  }
  try {
    await ElMessageBox.confirm(
      '确认对当前页 ' + ids.length + ' 条「待处理」预警执行一键处置？处置结果统一标记为「已完成」。',
      '一键处置',
      { type: 'warning', confirmButtonText: '立即处置', cancelButtonText: '取消' }
    )
  } catch (e) {
    return
  }
  try {
    const res = await adminApi.batchHandleWarning({ ids, status: '已完成' })
    const n = res.data ? res.data.handled : ids.length
    ElMessage.success('已批量处置 ' + (n ?? ids.length) + ' 条预警')
    load()
  } catch (e) {
    /* 拦截器已提示 */
  }
}

const showRules = () => {
  ElMessageBox.alert(
    '<div style="line-height:2;font-size:13px">' +
      '<div><b>证照预警：</b>营业执照 / 检测报告到期前 <b>30 天</b> 起逐日提醒</div>' +
      '<div><b>价格预警：</b>日均价较周参考价波动超过 <b>±15%</b> 即触发</div>' +
      '<div><b>订单逾期：</b>下单后超过 <b>2 天</b> 未接单 / 未发货即触发</div>' +
      '<div><b>损耗预警：</b>入库损耗率超过 <b>5%</b> 阈值即触发</div>' +
      '<div style="margin-top:8px;color:#6b7280;font-size:12px">以上阈值均可在「系统参数配置 → 食安预警阈值」中调整，无需修改代码。</div>' +
      '</div>',
    '食安预警规则',
    { dangerouslyUseHTMLString: true, confirmButtonText: '知道了' }
  )
}

const showSubscribe = () => {
  ElMessageBox.alert(
    '<div style="line-height:2;font-size:13px">' +
      '<div><b>订阅范围：</b>证照预警 / 质检预警 / 价格预警 / 逾期预警（全量）</div>' +
      '<div><b>推送渠道：</b>站内消息 · 短信（由系统参数开关控制）</div>' +
      '<div><b>推送对象：</b>系统管理员、食堂采购员</div>' +
      '<div><b>高频提醒：</b>高风险预警实时推送，中低风险每日 09:00 汇总</div>' +
      '</div>',
    '预警订阅',
    { dangerouslyUseHTMLString: true, confirmButtonText: '知道了' }
  )
}

onMounted(load)
</script>

<style scoped>
.chart-grid {
  display: grid;
  grid-template-columns: minmax(0, 1.58fr) minmax(0, 1fr);
  gap: 14px;
  margin-bottom: 16px;
}

.donut-wrap {
  display: flex;
  align-items: center;
  gap: 6px;
}

.donut-wrap > :first-child {
  flex: 1.02;
  min-width: 0;
}

.donut-legend {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding-right: 4px;
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

/* 表格 */
.cell-main {
  font-size: 12.5px;
  font-weight: 600;
  color: #1b2a24;
}

.cell-sub {
  margin-top: 2px;
  font-size: 11px;
  color: #9ca3af;
}

.content-cell {
  font-size: 12.5px;
  color: #374151;
  line-height: 1.6;
  display: -webkit-box;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
  overflow: hidden;
}

/* 弹窗 */
.w-detail {
  font-size: 13px;
}

.wd-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding-bottom: 12px;
  border-bottom: 1px solid var(--c-border);
}

.wd-no {
  font-size: 13.5px;
  font-weight: 700;
  color: #14532d;
}

.wd-content {
  margin: 12px 0;
  padding: 11px 13px;
  border-radius: 9px;
  background: #f8fbfa;
  border: 1px solid #eef3f1;
  font-size: 12.5px;
  color: #27362f;
  line-height: 1.75;
}

.wd-rows {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.wr {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  font-size: 12.5px;
  color: var(--c-muted);
}

.wr b {
  color: #1b2a24;
  font-weight: 600;
  text-align: right;
}

.suggest {
  margin-top: 14px;
  padding: 11px 13px;
  border-radius: 9px;
  background: #fdf7ec;
  border: 1px solid #f7e3c3;
}

.suggest-title {
  display: flex;
  align-items: center;
  gap: 5px;
  font-size: 12.5px;
  font-weight: 700;
  color: #b45309;
}

.suggest-text {
  margin-top: 5px;
  font-size: 12.5px;
  color: #7c4a08;
  line-height: 1.7;
}

.handle-form {
  margin-top: 16px;
}
</style>
