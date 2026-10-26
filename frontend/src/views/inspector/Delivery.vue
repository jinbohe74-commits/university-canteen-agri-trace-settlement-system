<template>
  <div class="page" v-loading="loading">
    <div class="page-head">
      <div>
        <h2 class="page-title">配送任务</h2>
        <p class="page-sub">冷链车辆调度、温度监控与 GPS 轨迹，送达后扫码交付</p>
      </div>
      <div class="page-actions">
        <el-button plain @click="createTip"><el-icon><Plus /></el-icon>配送单</el-button>
      </div>
    </div>

    <div v-if="!current" class="card card-pad">
      <div class="empty">
        <el-icon style="font-size: 30px; color: #b7c7c1"><Van /></el-icon>
        <div style="margin-top: 8px">暂无配送任务，质检合格的批次将自动进入配送队列</div>
      </div>
    </div>

    <template v-else>
      <div class="grid-d">
        <!-- 当前任务 -->
        <div class="card">
          <div class="card-head">
            <div class="card-title">当前配送任务 {{ current.deliveryNo }}</div>
            <StatusTag type="delivery" :value="current.status" />
          </div>
          <div class="card-pad">
            <div class="veh-row">
              <div class="veh-badge">
                <el-icon><Van /></el-icon>
              </div>
              <div class="grow">
                <div class="veh-name">{{ current.vehicle }} · 冷链车</div>
                <div class="veh-driver">司机 {{ current.driver }} · {{ current.driverPhone }}</div>
              </div>
              <div class="temp-box" :class="{ alarm: current.temperatureAlarm }">
                <div class="temp-val">{{ current.temperature }}<small>℃</small></div>
                <div class="temp-label">
                  {{ current.temperatureAlarm ? '超出 2~8℃ 范围' : '车厢温度正常' }}
                </div>
              </div>
            </div>

            <div class="mileage-row">
              <span>全程里程</span><b>{{ current.mileage }} km</b>
              <span class="sep">·</span>
              <span>时效</span><b>{{ current.startTime ? fmtDate(current.startTime) : '待发车' }}</b>
            </div>

            <div class="route">
              <div class="route-point">
                <span class="dot-green"></span>
                <div>
                  <div class="rp-label">起点</div>
                  <div class="rp-name">{{ current.origin }}</div>
                </div>
              </div>
              <div class="route-line">
                <span class="rl-dash"></span>
                <el-icon><Right /></el-icon>
              </div>
              <div class="route-point end">
                <span class="dot-teal"></span>
                <div>
                  <div class="rp-label">终点</div>
                  <div class="rp-name">{{ current.destination }}</div>
                </div>
              </div>
            </div>

            <div class="act-row">
              <el-button
                v-if="current.status === '待发车' || current.orderStatusName === '质检通过'"
                type="primary" @click="start"
              >开始配送</el-button>
              <el-button
                type="primary" :disabled="current.status === '已送达'" @click="arrive"
              >扫码交付</el-button>
              <el-button plain @click="copyBatch">复制批次号</el-button>
            </div>
          </div>
        </div>

        <!-- 运输节点 -->
        <div class="card">
          <div class="card-head">
            <div class="card-title">运输节点</div>
            <div class="card-extra">{{ (current.traces || []).filter(t => t.done).length }}/{{ (current.traces || []).length }}</div>
          </div>
          <div class="card-pad">
            <div class="timeline">
              <div v-for="(t, i) in current.traces || []" :key="i" class="timeline-item">
                <span class="timeline-dot" :class="{ done: t.done }"></span>
                <div class="timeline-time">{{ t.time }}</div>
                <div class="timeline-title" :class="{ muted: !t.done }">{{ t.title }}</div>
              </div>
            </div>
          </div>
        </div>

        <!-- 随车批次 -->
        <div class="card">
          <div class="card-head">
            <div class="card-title">随车批次</div>
            <div class="card-extra">{{ batchList.length }} 个批次</div>
          </div>
          <div class="card-pad">
            <div v-for="(b, i) in batchList" :key="i" class="batch-item">
              <div class="batch-chip mono" @click="copy(b.batchNo)">{{ b.batchNo }}</div>
              <div class="batch-desc">{{ b.productName }} · {{ fmtNum(b.quantity) }} {{ b.unit }} · {{ b.destination }}</div>
            </div>
            <div v-if="!batchList.length" class="empty" style="padding: 16px 0">暂无随车批次</div>

            <div v-if="alarms.length" class="alarm-box">
              <el-icon><WarningFilled /></el-icon>
              <div>
                <div v-for="(a, i) in alarms" :key="i">
                  {{ a.deliveryNo }}：{{ a.message }}（{{ a.temperature }}℃）
                </div>
              </div>
            </div>
            <div class="temp-tip">车厢温度每 5 分钟上报一次，超出 2~8℃ 范围将自动预警。</div>
          </div>
        </div>
      </div>

      <!-- 今日配送任务 -->
      <SectionCard title="今日配送任务" no-pad>
        <template #extra>共 {{ records.length }} 条</template>
        <el-table :data="records" style="width: 100%">
          <el-table-column prop="deliveryNo" label="配送单号" min-width="150">
            <template #default="{ row }"><span class="mono">{{ row.deliveryNo }}</span></template>
          </el-table-column>
          <el-table-column label="车辆" min-width="140">
            <template #default="{ row }">{{ row.vehicle }}</template>
          </el-table-column>
          <el-table-column prop="driver" label="司机" width="96" />
          <el-table-column label="实时温度" width="112" align="center">
            <template #default="{ row }">
              <span :class="row.temperatureAlarm ? 'temp-bad' : 'temp-ok'">
                {{ row.temperature }} ℃
              </span>
            </template>
          </el-table-column>
          <el-table-column label="目的地" min-width="190">
            <template #default="{ row }">{{ row.destination }} · {{ row.mileage }}km</template>
          </el-table-column>
          <el-table-column label="状态" width="100" align="center">
            <template #default="{ row }"><StatusTag type="delivery" :value="row.status" /></template>
          </el-table-column>
          <el-table-column label="操作" width="130" align="center">
            <template #default="{ row }">
              <span class="link" @click="showTraces(row)">轨迹</span>
              <span class="link" @click="showDetail(row)">详情</span>
            </template>
          </el-table-column>
          <template #empty><div class="empty">暂无配送任务</div></template>
        </el-table>
      </SectionCard>
    </template>

    <!-- 轨迹弹窗 -->
    <el-dialog v-model="traceVisible" title="GPS 轨迹与节点" width="520">
      <div class="timeline">
        <div v-for="(t, i) in dialogTraces" :key="i" class="timeline-item">
          <span class="timeline-dot" :class="{ done: t.done }"></span>
          <div class="timeline-time">{{ t.time }}</div>
          <div class="timeline-title">{{ t.title }}</div>
        </div>
      </div>
    </el-dialog>

    <!-- 详情弹窗 -->
    <el-dialog v-model="detailVisible" title="配送单详情" width="560">
      <el-descriptions :column="2" border size="small">
        <el-descriptions-item label="配送单号">{{ detailRow.deliveryNo }}</el-descriptions-item>
        <el-descriptions-item label="关联订单">{{ detailRow.orderNo }}</el-descriptions-item>
        <el-descriptions-item label="车辆 / 司机">{{ detailRow.vehicle }} · {{ detailRow.driver }}</el-descriptions-item>
        <el-descriptions-item label="联系电话">{{ detailRow.driverPhone }}</el-descriptions-item>
        <el-descriptions-item label="起点">{{ detailRow.origin }}</el-descriptions-item>
        <el-descriptions-item label="终点">{{ detailRow.destination }}</el-descriptions-item>
        <el-descriptions-item label="批次号">
          <span class="mono">{{ detailRow.batchNo || '—' }}</span>
        </el-descriptions-item>
        <el-descriptions-item label="状态">
          <StatusTag type="delivery" :value="detailRow.status" />
        </el-descriptions-item>
      </el-descriptions>
      <div class="temp-edit">
        <span>车厢温度更新</span>
        <el-input-number v-model="newTemp" :min="-5" :max="30" :step="0.5" size="small" />
        <el-button size="small" type="primary" @click="saveTemp">保存</el-button>
      </div>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { inspectorApi } from '@/api'
import SectionCard from '@/components/SectionCard.vue'
import StatusTag from '@/components/StatusTag.vue'
import { fmtDate, fmtNum } from '@/utils/format'

const loading = ref(false)
const current = ref(null)
const records = ref([])
const alarms = ref([])
const traceVisible = ref(false)
const detailVisible = ref(false)
const dialogTraces = ref([])
const detailRow = ref({})
const newTemp = ref(6)

const batchList = computed(() => {
  if (!current.value) return []
  const rows = (records.value || []).filter((r) => r.batchNo)
  const seen = new Set()
  return rows.filter((r) => {
    if (seen.has(r.batchNo)) return false
    seen.add(r.batchNo)
    return true
  })
})

const load = async () => {
  loading.value = true
  try {
    const res = await inspectorApi.deliveries()
    const d = res.data || {}
    current.value = d.current
    records.value = d.records || []
    alarms.value = d.alarms || []
  } finally {
    loading.value = false
  }
}

const start = async () => {
  await inspectorApi.startDelivery(current.value.id)
  ElMessage.success('已开始冷链配送')
  load()
}

const arrive = async () => {
  await inspectorApi.arrive(current.value.id)
  ElMessage.success('已扫码交付，等待食堂确认入库')
  load()
}

const copy = async (text) => {
  if (!text) return
  try {
    await navigator.clipboard.writeText(text)
    ElMessage.success('批次号已复制：' + text)
  } catch (e) {
    ElMessage.info(text)
  }
}

const copyBatch = () => copy(current.value.batchNo)

const showTraces = (row) => {
  dialogTraces.value = row.traces || []
  traceVisible.value = true
}

const showDetail = (row) => {
  detailRow.value = row
  newTemp.value = Number(row.temperature || 6)
  detailVisible.value = true
}

const saveTemp = async () => {
  await inspectorApi.updateTemperature(detailRow.value.id, newTemp.value)
  ElMessage.success('车厢温度已更新')
  detailVisible.value = false
  load()
}

const createTip = () => {
  ElMessage.info('配送单按质检合格批次自动生成，无需手工创建')
}

onMounted(load)
</script>

<style scoped>
.grid-d {
  display: grid;
  grid-template-columns: minmax(0, 1.18fr) minmax(0, 0.92fr) minmax(0, 0.9fr);
  gap: 14px;
  margin-bottom: 14px;
  align-items: start;
}

.veh-row {
  display: flex;
  align-items: center;
  gap: 12px;
  padding-bottom: 14px;
  border-bottom: 1px dashed var(--c-border);
}

.veh-badge {
  width: 40px;
  height: 40px;
  border-radius: 11px;
  display: grid;
  place-items: center;
  background: #e6f6f4;
  color: #0d9488;
  font-size: 19px;
}

.veh-name {
  font-size: 14px;
  font-weight: 700;
  color: #1b2a24;
}

.veh-driver {
  font-size: 12px;
  color: var(--c-muted);
  margin-top: 3px;
}

.temp-box {
  text-align: right;
}

.temp-val {
  font-family: var(--font-num);
  font-size: 26px;
  font-weight: 700;
  color: #0d9488;
  line-height: 1;
}

.temp-val small {
  font-size: 13px;
  margin-left: 1px;
}

.temp-box.alarm .temp-val {
  color: #dc2626;
}

.temp-label {
  font-size: 10.5px;
  color: var(--c-muted-2);
  margin-top: 4px;
}

.temp-box.alarm .temp-label {
  color: #dc2626;
  font-weight: 600;
}

.mileage-row {
  display: flex;
  align-items: center;
  gap: 7px;
  padding: 12px 0;
  font-size: 12.5px;
  color: var(--c-muted);
}

.mileage-row b {
  color: #1b2a24;
  font-family: var(--font-num);
}

.sep {
  color: var(--c-muted-2);
}

.route {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 12px 14px;
  border-radius: 10px;
  background: linear-gradient(120deg, #f2fbf5, #eaf8f2);
  border: 1px solid #d9efe3;
  margin-bottom: 14px;
}

.route-point {
  display: flex;
  align-items: center;
  gap: 8px;
}

.dot-green,
.dot-teal {
  width: 9px;
  height: 9px;
  border-radius: 50%;
  flex-shrink: 0;
}

.dot-green {
  background: #16a34a;
  box-shadow: 0 0 0 3px rgba(22, 163, 74, 0.16);
}

.dot-teal {
  background: #0d9488;
  box-shadow: 0 0 0 3px rgba(13, 148, 136, 0.16);
}

.rp-label {
  font-size: 10.5px;
  color: var(--c-muted-2);
}

.rp-name {
  font-size: 12.5px;
  font-weight: 600;
  color: #1b2a24;
  margin-top: 1px;
}

.route-line {
  flex: 1;
  display: flex;
  align-items: center;
  gap: 4px;
  color: #88c5aa;
}

.rl-dash {
  flex: 1;
  height: 1px;
  background-image: linear-gradient(90deg, #a8d8bf 50%, transparent 0);
  background-size: 8px 1px;
}

.act-row {
  display: flex;
  gap: 9px;
}

.timeline-dot.done {
  background: var(--c-primary);
  border-color: var(--c-primary);
}

.batch-item {
  padding: 9px 0;
  border-bottom: 1px dashed var(--c-border);
}

.batch-item:last-of-type {
  border-bottom: none;
}

.batch-chip {
  display: inline-block;
  font-size: 11.5px;
  font-weight: 600;
  color: #14532d;
  background: #f2fbf5;
  border: 1px dashed #bbe6cb;
  border-radius: 7px;
  padding: 4px 8px;
  cursor: pointer;
  word-break: break-all;
}

.batch-desc {
  font-size: 11.5px;
  color: var(--c-muted);
  margin-top: 5px;
}

.alarm-box {
  display: flex;
  gap: 7px;
  margin-top: 12px;
  padding: 10px 12px;
  border-radius: 9px;
  background: #fdf6e9;
  border: 1px solid #f6e2bd;
  color: #92400e;
  font-size: 11.5px;
  line-height: 1.7;
}

.temp-tip {
  margin-top: 12px;
  font-size: 11.5px;
  color: var(--c-muted-2);
  line-height: 1.7;
}

.temp-bad {
  color: #dc2626;
  font-weight: 700;
}

.temp-ok {
  color: #0f766e;
  font-weight: 600;
}

.temp-edit {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-top: 16px;
  padding-top: 14px;
  border-top: 1px dashed var(--c-border);
  font-size: 12.5px;
  color: var(--c-muted);
}
</style>
