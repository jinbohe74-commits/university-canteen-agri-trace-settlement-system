<template>
  <div class="page" v-loading="loading">
    <div class="page-head">
      <div>
        <h2 class="page-title">配送任务</h2>
        <p class="page-sub">冷链车辆调度、温度监控与 GPS 轨迹，送达后扫码交付</p>
      </div>
    </div>

    <!-- 作业状态条：一眼看清「现在有几件要动手、几件只是留档」，
         原来没有这层，进来直接是一张历史单的大卡片，用户会以为自己点错了 -->
    <div class="task-bar">
      <button type="button" class="tb-cell" :class="{ on: tab === 'active' }" @click="tab = 'active'">
        <span class="tb-n mono">{{ counts.waiting }}</span>
        <span class="tb-k">待发车</span>
      </button>
      <button type="button" class="tb-cell" :class="{ on: tab === 'active' }" @click="tab = 'active'">
        <span class="tb-n mono">{{ counts.shipping }}</span>
        <span class="tb-k">在途</span>
      </button>
      <button type="button" class="tb-cell" :class="{ on: tab === 'done' }" @click="tab = 'done'">
        <span class="tb-n mono">{{ counts.delivered }}</span>
        <span class="tb-k">已交付待入库</span>
      </button>
      <button type="button" class="tb-cell" :class="{ on: tab === 'all' }" @click="tab = 'all'">
        <span class="tb-n mono">{{ records.length }}</span>
        <span class="tb-k">全部</span>
      </button>
      <span class="tb-hint">待发车的单要你安排发车；在途的到货后扫码交付；已交付只是留档，入库由采购员验收</span>
    </div>

    <!-- 没有待办时如实说清楚，不拿历史单冒充「当前任务」 -->
    <div v-if="!current" class="card card-pad empty-work">
      <div class="ew-icon"><el-icon><Van /></el-icon></div>
      <div class="ew-title">当前没有待发车或在途的配送任务</div>
      <div class="ew-sub">
        <template v-if="lastDelivered">
          最近一单 <span class="mono">{{ lastDelivered.deliveryNo }}</span>
          已于 {{ fmtDate(lastDelivered.arriveTime) }} 交付，等待食堂入库。
        </template>
        <template v-else>质检合格的批次会自动进入配送队列，届时这里会出现待发车的单。</template>
      </div>
      <el-button v-if="counts.delivered" plain @click="tab = 'done'">
        查看已交付的 {{ counts.delivered }} 单
      </el-button>
    </div>

    <template v-else>
      <div v-if="isHistory" class="history-note">
        <el-icon><InfoFilled /></el-icon>
        <span class="grow">
          这是 {{ fmtDate(current.arriveTime) }} 交付的历史单，仅供查看；发车与扫码交付动作已结束。
        </span>
        <el-button v-if="activeRows.length" link type="primary" @click="backToActive">
          回到待处理的 {{ activeRows.length }} 单
        </el-button>
      </div>

      <div class="grid-d">
        <!-- 当前任务 / 历史单（点已交付行时才走历史态） -->
        <div class="card">
          <div class="card-head">
            <div class="card-title">
              {{ isHistory ? '历史配送单' : '当前配送任务' }} {{ current.deliveryNo }}
            </div>
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

            <div class="batch-line">
              <span class="bl-k">随货批次</span>
              <span class="bl-v mono">{{ current.batchNo || '未生成' }}</span>
              <span v-if="current.batchTail" class="bl-tail mono">尾号 {{ current.batchTail }}</span>
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
                type="primary" @click="openDepart"
              >发车检查</el-button>
              <el-button
                v-if="current.status === '配送中'"
                type="primary" @click="openArrive"
              >扫码交付</el-button>
              <span v-else-if="current.status === '已送达'" class="muted small">已交付，等待食堂入库</span>
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
                <!-- 未发生的节点时间只是计划值，标「预计」免得被当成实际发生过的时间 -->
                <div class="timeline-time">{{ t.done ? t.time : '预计 ' + t.time }}</div>
                <div class="timeline-title" :class="{ muted: !t.done }">{{ t.title }}</div>
              </div>
            </div>
          </div>
        </div>

        <!-- 本批装载：只列本配送单实际装在车上的批次。
             原来这里列的是「所有在途订单的批次」（15 个，含别的车），
             操作员照着它抄尾号，抄到的往往不是本单批次，于是「输对了也判错」。 -->
        <div class="card">
          <div class="card-head">
            <div class="card-title">本批装载</div>
            <div class="card-extra">{{ batchList.length }} 个批次 · {{ current.vehicle }}</div>
          </div>
          <div class="card-pad">
            <div v-for="(b, i) in batchList" :key="b.id" class="batch-row" :class="{ mine: b.mine }">
              <div class="br-head">
                <span v-if="b.mine" class="br-tag">本单</span>
                <span class="br-no mono" :title="'点击复制 ' + b.batchNo" @click="copy(b.batchNo)">
                  {{ b.batchNo || '未生成批次号' }}
                </span>
              </div>
              <div class="br-sub">
                <span class="br-tail mono">尾号 {{ tail6(b.batchNo) }}</span>
                <span class="br-prod">{{ b.productName }} · {{ fmtNum(b.quantity) }} {{ b.unit }}</span>
              </div>
              <div class="br-meta">
                <div class="bm-row">
                  <span class="bm-k">发车</span>
                  <span class="bm-v mono">{{ b.startTime ? fmtDate(b.startTime) : '尚未发车' }}</span>
                </div>
                <div class="bm-row">
                  <span class="bm-k">去向</span>
                  <span class="bm-v ellipsis">{{ b.destination }}</span>
                  <StatusTag v-if="!b.mine" type="delivery" :value="b.status" />
                </div>
              </div>
            </div>
            <div v-if="!batchList.length" class="empty" style="padding: 16px 0">本单尚未生成批次号，请先完成质检</div>

            <div v-if="alarms.length" class="alarm-box">
              <el-icon><WarningFilled /></el-icon>
              <div>
                <div v-for="(a, i) in alarms" :key="i">
                  {{ a.deliveryNo }}：{{ a.message }}（{{ a.temperature }}℃）
                </div>
              </div>
            </div>
            <div class="temp-tip">交付时按这批次的条码核对，扫到别的车/别的批次会被拦下。</div>
          </div>
        </div>
      </div>

      <!-- 实时轨迹：待发车没有轨迹可画；在途 5 秒刷新；已交付定格 -->
      <SectionCard v-if="current.status !== '待发车'" title="实时轨迹" class="dm-card">
        <template #extra>
          <span class="dm-extra">
            <span class="dm-dot" :class="{ live: current.status === '配送中' }"></span>
            {{ current.status === '配送中' ? '在途 · 实时刷新' : '轨迹定格（已交付）' }}
            <template v-if="trackData.serverTime">· {{ trackData.serverTime }}</template>
          </span>
        </template>
        <div v-loading="trackLoading" class="dm-wrap">
          <DeliveryMap :track="trackData" />
        </div>
        <div v-if="tempSeries.length" class="dm-temp">
          <div class="dm-temp-head">
            <span>车厢温度记录（{{ tempSeries.length }} 次上报）</span>
            <span class="dm-temp-range">冷链要求 2~8℃</span>
          </div>
          <div class="dm-temp-list">
            <span
              v-for="(t, i) in tempSeries" :key="i"
              class="dm-temp-chip" :class="{ bad: t.alarm }"
              :title="`${t.time} · ${t.source === 'depart' ? '发车' : '上报'}`"
            >{{ t.temperature }}℃</span>
          </div>
        </div>
      </SectionCard>

      <!-- 待发车没有轨迹：用一条紧凑提示替代整块空地图 -->
      <div v-else class="track-idle card">
        <span class="ti-dot"></span>
        <div class="ti-main">
          <div class="ti-title">尚未发车，暂无轨迹</div>
          <div class="ti-sub">完成「发车检查」后自动开始记录车厢温度与 GPS 轨迹，到货后在地图上回放这一路。</div>
        </div>
        <el-button plain @click="openDepart">去发车检查</el-button>
      </div>

      <!-- 任务清单：默认只看「待处理」，已交付的 14 条留档不占视线。
           原来一律平铺 15 行，待办单混在历史单里，找活干要先翻半天。 -->
      <SectionCard :title="listTitle" no-pad>
        <template #extra>{{ shownRows.length }} 条 · 点行切换上方作业区</template>
        <el-table
          :data="shownRows" style="width: 100%" highlight-current-row
          :current-row-key="current?.id" row-key="id"
          @row-click="pick"
        >
          <el-table-column prop="deliveryNo" label="配送单号" min-width="146">
            <template #default="{ row }"><span class="mono">{{ row.deliveryNo }}</span></template>
          </el-table-column>
          <el-table-column label="车辆 / 司机" min-width="150">
            <template #default="{ row }">
              <span>{{ row.vehicle }}</span>
              <span class="cell-sub">{{ row.driver }}</span>
            </template>
          </el-table-column>
          <el-table-column label="批次尾号" width="118">
            <template #default="{ row }"><span class="mono">{{ tail6(row.batchNo) }}</span></template>
          </el-table-column>
          <el-table-column label="车厢温度" width="96" align="center">
            <template #default="{ row }">
              <span :class="row.temperatureAlarm ? 'temp-bad' : 'temp-ok'">{{ row.temperature }} ℃</span>
            </template>
          </el-table-column>
          <el-table-column label="目的地 / 里程" min-width="164">
            <template #default="{ row }">
              <span>{{ row.destination }}</span>
              <span class="cell-sub">{{ row.mileage }} km</span>
            </template>
          </el-table-column>
          <el-table-column label="状态" width="96" align="center">
            <template #default="{ row }"><StatusTag type="delivery" :value="row.status" /></template>
          </el-table-column>
          <el-table-column label="操作" width="164" align="center" fixed="right">
            <template #default="{ row }">
              <span v-if="row.status === '待发车'" class="link link-strong" @click.stop="pickAndStart(row)">发车检查</span>
              <span v-else-if="row.status === '配送中'" class="link link-strong" @click.stop="pickAndArrive(row)">扫码交付</span>
              <span v-else class="muted small">已交付</span>
              <span class="link" @click.stop="showDetail(row)">详情</span>
            </template>
          </el-table-column>
          <template #empty>
            <div class="empty">{{ tab === 'active' ? '没有待发车或在途的配送任务' : '暂无记录' }}</div>
          </template>
        </el-table>
      </SectionCard>
    </template>

    <!-- 发车检查：车辆、司机、起始温度三项确认后才允许发车 -->
    <el-dialog v-model="departVisible" title="发车检查" width="480">
      <div class="ck-list">
        <div class="ck-row">
          <span class="ck-k">车辆</span>
          <el-input v-model="departForm.vehicle" size="small" placeholder="确认车牌号" />
        </div>
        <div class="ck-row">
          <span class="ck-k">司机</span>
          <el-input v-model="departForm.driver" size="small" placeholder="确认司机姓名" />
        </div>
        <div class="ck-row">
          <span class="ck-k">起始车厢温度</span>
          <el-input-number v-model="departForm.temperature" :min="-5" :max="30" :step="0.5" size="small" />
          <span class="ck-hint" :class="{ bad: tempOutOfRange }">℃（冷链要求 2~8℃）</span>
        </div>
        <div class="ck-row">
          <span class="ck-k">随车批次</span>
          <span class="mono ck-val">{{ current?.batchNo || '—' }}</span>
        </div>
      </div>
      <div v-if="tempOutOfRange" class="ck-warn">
        <el-icon><WarningFilled /></el-icon>
        当前温度超出 2~8℃，不能发车。请先检查制冷机组或温度记录后再提交。
      </div>
      <template #footer>
        <el-button @click="departVisible = false">取消</el-button>
        <el-button type="primary" :loading="departing" :disabled="tempOutOfRange" @click="doDepart">
          确认发车
        </el-button>
      </template>
    </el-dialog>

    <!-- 扫码交付：扫货箱上的批次条码，或照标签录入（整串 / 尾号 6 位都认） -->
    <el-dialog v-model="arriveVisible" title="扫码交付" width="470">
      <div class="scan-box">
        <div class="scan-tip-line">
          用扫枪扫货箱上的批次条码；没有扫枪时，照标签录入整串批次号或<b>尾号 6 位</b>即可。
        </div>
        <div class="scan-batch">
          <div class="sb-main">
            <span class="sb-k">本单批次</span>
            <span class="sb-v mono">{{ current?.batchNo || '——' }}</span>
          </div>
          <div class="sb-side">
            <span class="sb-tail mono">尾号 {{ current?.batchTail || '——' }}</span>
            <el-button link type="primary" size="small" @click="copy(current?.batchNo)">复制</el-button>
          </div>
        </div>
        <el-input
          v-model="arriveCode" size="large" maxlength="40" class="scan-input"
          placeholder="扫描或录入批次号" clearable @keyup.enter="doArrive"
        >
          <template #prefix><el-icon><Camera /></el-icon></template>
        </el-input>
        <div v-if="arriveError" class="scan-error">{{ arriveError }}</div>
        <div v-else class="scan-hint-muted">扫到其他车辆或其他批次会被拦下，不会误交付。</div>
      </div>
      <template #footer>
        <el-button @click="arriveVisible = false">取消</el-button>
        <el-button type="primary" :loading="arriving" :disabled="!arriveReady" @click="doArrive">
          核对并交付
        </el-button>
      </template>
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
import { computed, onMounted, onBeforeUnmount, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { inspectorApi } from '@/api'
import SectionCard from '@/components/SectionCard.vue'
import StatusTag from '@/components/StatusTag.vue'
import DeliveryMap from '@/components/DeliveryMap.vue'
import { fmtDate, fmtNum } from '@/utils/format'
import { useAutoRefresh } from '@/utils/useAutoRefresh'

const loading = ref(false)
const current = ref(null)
const records = ref([])
const alarms = ref([])
const detailVisible = ref(false)
const detailRow = ref({})
const newTemp = ref(6)

/* ---- 发车检查 ---- */
const departVisible = ref(false)
const departing = ref(false)
const departForm = ref({ vehicle: '', driver: '', temperature: 6 })
const tempOutOfRange = computed(() => {
  const t = Number(departForm.value.temperature)
  return !Number.isFinite(t) || t < 2 || t > 8
})
const openDepart = () => {
  const c = current.value || {}
  departForm.value = {
    vehicle: c.vehicle || '',
    driver: c.driver || '',
    temperature: Number(c.temperature ?? 6)
  }
  departVisible.value = true
}
const doDepart = async () => {
  if (tempOutOfRange.value) return
  if (!departForm.value.vehicle.trim() || !departForm.value.driver.trim()) {
    ElMessage.warning('请先确认车辆与司机')
    return
  }
  departing.value = true
  try {
    await inspectorApi.startDelivery(current.value.id, departForm.value)
    ElMessage.success('已发车，冷链温度开始记录')
    departVisible.value = false
    await load()
  } finally {
    departing.value = false
  }
}

/* ---- 扫码交付 ---- */
const arriveVisible = ref(false)
const arriving = ref(false)
const arriveCode = ref('')
const arriveError = ref('')
/* 与后端同一套归一化：去分隔符 + 转大写，避免扫对了却因空格/横杠被判错 */
const norm = (v) => String(v || '').replace(/[^0-9a-zA-Z]/g, '').toUpperCase()
const arriveReady = computed(() => norm(arriveCode.value).length >= 6)
const openArrive = () => {
  arriveCode.value = ''
  arriveError.value = ''
  arriveVisible.value = true
}
const doArrive = async () => {
  if (!arriveReady.value) {
    arriveError.value = '批次号至少 6 位，可整串粘贴或只录尾号 6 位'
    return
  }
  arriving.value = true
  arriveError.value = ''
  try {
    await inspectorApi.arrive(current.value.id, { batchCode: arriveCode.value.trim() })
    ElMessage.success('批次核对通过，已交付，等待食堂入库')
    arriveVisible.value = false
    await load()
  } catch (e) {
    arriveError.value = e?.message || '核对失败，请重试'
  } finally {
    arriving.value = false
  }
}

/* 本批装载 = 本配送单的批次（在途/待发车时再补上同车未完成的批次）。
   历史单只看它自己那一批：把今天另一趟的批次挂到上个月的历史单上会误导人。 */
const batchList = computed(() => {
  const c = current.value
  if (!c) return []
  const mine = { ...c, mine: true }
  if (c.status === '已送达') return [mine]
  const mates = (records.value || []).filter(
    (r) => r.id !== c.id && r.batchNo && r.vehicle === c.vehicle
      && (r.status === '待发车' || r.status === '配送中')
  )
  return [mine, ...mates]
})

const tail6 = (no) => (no ? String(no).slice(-6) : '——')

/* ---- 作业队列：待处理优先，已交付只留档 ---- */
const tab = ref('active')          // active / done / all
const counts = ref({ waiting: 0, shipping: 0, delivered: 0 })

const activeRows = computed(() =>
  (records.value || []).filter((r) => r.status === '待发车' || r.status === '配送中')
)
const historyRows = computed(() => (records.value || []).filter((r) => r.status === '已送达'))

const shownRows = computed(() => {
  if (tab.value === 'done') return historyRows.value
  if (tab.value === 'all') return [...activeRows.value, ...historyRows.value]
  return activeRows.value
})
const listTitle = computed(() => (tab.value === 'done' ? '已交付待入库' : '待处理配送任务'))
const isHistory = computed(() => current.value?.status === '已送达')
/* 切页签就要切上方作业区：点了「已交付」却还显示待发车的单，等于没切换 */
watch(tab, (v) => {
  if (v === 'done') current.value = historyRows.value[0] || null
  else if (v === 'active') current.value = activeRows.value[0] || null
})
const lastDelivered = computed(() => {
  const rows = historyRows.value.filter((r) => r.arriveTime)
  if (!rows.length) return null
  return rows.slice().sort((a, b) => String(b.arriveTime).localeCompare(String(a.arriveTime)))[0]
})

const load = async () => {
  loading.value = true
  try {
    const res = await inspectorApi.deliveries()
    const d = res.data || {}
    records.value = d.records || []
    alarms.value = d.alarms || []
    counts.value = d.counts || {
      waiting: activeRows.value.filter((r) => r.status === '待发车').length,
      shipping: activeRows.value.filter((r) => r.status === '配送中').length,
      delivered: historyRows.value.length
    }
    // 自动刷新不能把用户正看着的单顶掉：先按 id 找回原选中行
    const keepId = current.value?.id
    const kept = keepId ? records.value.find((r) => r.id === keepId) : null
    let next = kept || d.current
    // 停在「已交付」页签时，别自动跳回待办单
    if (tab.value === 'done' && !next && historyRows.value.length) next = historyRows.value[0]
    current.value = next
  } finally {
    loading.value = false
  }
}

/* 列表里的每一趟都能直接作业，不必回到上方碰运气 */
const pick = (row) => {
  current.value = row
}

const backToActive = () => {
  tab.value = 'active'
  current.value = activeRows.value[0] || null
}

/* 行内操作同样走检查/核对弹窗，不能绕过发车温度与批次校验 */
const pickAndStart = (row) => {
  current.value = row
  openDepart()
}

const pickAndArrive = (row) => {
  current.value = row
  openArrive()
}

/* ---- 实时轨迹：5 秒拉一次，位置与温度都来自后端真实字段 ---- */
const trackData = ref({})
const trackLoading = ref(false)
const tempSeries = computed(() => trackData.value.tempSeries || [])
const loadTrack = async () => {
  const id = current.value?.id
  if (!id) {
    trackData.value = {}
    return
  }
  trackLoading.value = true
  try {
    const res = await inspectorApi.trackDelivery(id)
    trackData.value = res.data || {}
  } catch (e) {
    // 地图拉取失败不能拖垮整个作业页，保留上一次快照即可
  } finally {
    trackLoading.value = false
  }
}
/* 只有在途单才需要 5 秒刷新；已交付的单轨迹早定格了，继续轮询纯属浪费和干扰 */
let trackTimer = null
const syncTrackTimer = () => {
  const needPoll = current.value?.status === '配送中'
  if (needPoll && !trackTimer) {
    trackTimer = setInterval(loadTrack, 5000)
  } else if (!needPoll && trackTimer) {
    clearInterval(trackTimer)
    trackTimer = null
  }
}
watch(() => [current.value?.id, current.value?.status], () => {
  if (current.value?.id) loadTrack()
  syncTrackTimer()
})
onBeforeUnmount(() => {
  if (trackTimer) clearInterval(trackTimer)
  trackTimer = null
})

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

/* 待办里点「去发车 / 看轨迹」会带 focus=订单号过来：直接定位到那一单，
   不再让用户落在列表里自己找（第九轮"只是跳转界面"的同类问题）。 */
const route = useRoute()
function applyFocus() {
  const focus = String(route.query.focus || '').trim()
  if (!focus) return false
  const hit = (records.value || []).find(
    (r) => r.orderNo === focus || r.deliveryNo === focus
  )
  if (!hit) return false
  current.value = hit
  tab.value = hit.status === '已送达' ? 'done' : 'active'
  return true
}

useAutoRefresh(load)
onMounted(async () => {
  await load()
  if (!applyFocus()) {
    // 没有 focus 时也保证默认停在待办单上
    current.value = current.value || activeRows.value[0] || null
  }
  syncTrackTimer()
})
watch(() => route.query.focus, () => { applyFocus() })
</script>

<style scoped>
/* ---- 作业状态条 ---- */
.task-bar {
  display: flex;
  align-items: stretch;
  gap: 10px;
  margin-bottom: 14px;
}

.tb-cell {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 2px;
  min-width: 104px;
  padding: 9px 14px;
  border-radius: 10px;
  border: 1px solid var(--line);
  background: var(--surface);
  cursor: pointer;
  transition: all 0.14s var(--ease);
}

.tb-cell:hover {
  border-color: var(--accent);
}

.tb-cell.on {
  border-color: var(--accent);
  background: var(--accent-soft);
}

.tb-n {
  font-family: var(--font-num);
  font-size: var(--fs-lg);
  font-weight: 700;
  color: var(--ink);
  line-height: 1.1;
}

.tb-cell.on .tb-n {
  color: var(--accent);
}

.tb-k {
  font-size: var(--fs-2xs);
  color: var(--muted);
}

.tb-hint {
  flex: 1;
  align-self: center;
  font-size: var(--fs-xs);
  color: var(--c-muted-2);
  line-height: 1.6;
}

/* ---- 无待办空态 ---- */
.empty-work {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  padding: 34px 20px;
  text-align: center;
}

.ew-icon {
  width: 44px;
  height: 44px;
  border-radius: 12px;
  display: grid;
  place-items: center;
  background: var(--surface-2);
  color: var(--muted);
  font-size: var(--fs-lg);
}

.ew-title {
  font-size: var(--fs-base);
  font-weight: 700;
  color: var(--ink);
}

.ew-sub {
  font-size: var(--fs-xs);
  color: var(--c-muted);
  line-height: 1.7;
  max-width: 460px;
}

/* ---- 历史单提示 ---- */
.history-note {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 12px;
  padding: 9px 12px;
  border-radius: 9px;
  border: 1px dashed var(--line-strong);
  background: var(--surface-2);
  color: var(--ink-2);
  font-size: var(--fs-xs);
}

/* ---- 表格里的次要信息 ---- */
.cell-sub {
  display: block;
  font-size: var(--fs-2xs);
  color: var(--c-muted-2);
  margin-top: 1px;
}

/* ---- 待发车：轨迹占位条 ---- */
.track-idle {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 16px 18px;
  margin-bottom: 14px;
}

.ti-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: var(--line-strong);
  flex: none;
}

.ti-main {
  flex: 1;
  min-width: 0;
}

.ti-title {
  font-size: var(--fs-sm);
  font-weight: 600;
  color: var(--ink);
}

.ti-sub {
  margin-top: 3px;
  font-size: var(--fs-xs);
  color: var(--c-muted);
  line-height: 1.6;
}

.dm-card {
  margin-bottom: 14px;
}

.dm-extra {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: var(--fs-xs);
  color: var(--muted);
}

.dm-dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: var(--muted);
}

.dm-dot.live {
  background: var(--accent);
  box-shadow: 0 0 0 3px color-mix(in srgb, var(--accent) 22%, transparent);
}

.dm-wrap {
  padding: 12px 14px 0;
}

.dm-temp {
  padding: 4px 14px 14px;
}

.dm-temp-head {
  display: flex;
  justify-content: space-between;
  font-size: var(--fs-xs);
  color: var(--muted);
  margin-bottom: 6px;
}

.dm-temp-range {
  color: var(--ink-2);
}

.dm-temp-list {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.dm-temp-chip {
  font-size: var(--fs-2xs);
  font-weight: 600;
  padding: 2px 7px;
  border-radius: 5px;
  background: var(--surface-2);
  border: 1px solid var(--line);
  color: var(--ink-2);
}

.dm-temp-chip.bad {
  color: var(--danger);
  border-color: var(--danger);
  background: color-mix(in srgb, var(--danger) 8%, transparent);
}

/* 发车检查 / 扫码交付弹窗 */
.ck-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.ck-row {
  display: grid;
  grid-template-columns: 108px minmax(0, 1fr) auto;
  align-items: center;
  gap: 10px;
}

.ck-k {
  font-size: var(--fs-sm);
  color: var(--muted);
}

.ck-val {
  font-size: var(--fs-sm);
  color: var(--ink);
}

.ck-hint {
  font-size: var(--fs-xs);
  color: var(--muted);
  white-space: nowrap;
}

.ck-hint.bad {
  color: var(--danger);
  font-weight: 600;
}

.ck-warn {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  margin-top: 14px;
  padding: 10px 12px;
  border-radius: 9px;
  border: 1px solid var(--danger);
  background: color-mix(in srgb, var(--danger) 8%, transparent);
  color: var(--danger);
  font-size: var(--fs-xs);
  line-height: 1.5;
}

.scan-box {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.scan-tip-line {
  font-size: var(--fs-sm);
  color: var(--ink-2);
  line-height: 1.6;
}

/* 批次号：等宽、单行、超长省略，不再换行撑破卡片 */
.scan-batch {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  padding: 10px 12px;
  border-radius: 9px;
  background: var(--accent-soft);
  border: 1px solid var(--line);
  min-width: 0;
}

.sb-main {
  display: flex;
  align-items: baseline;
  gap: 8px;
  min-width: 0;
}

.sb-k {
  font-size: var(--fs-xs);
  color: var(--muted);
  flex-shrink: 0;
}

.sb-v {
  font-size: var(--fs-sm);
  font-weight: 700;
  color: var(--ink);
  letter-spacing: 0.3px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.sb-side {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-shrink: 0;
}

.sb-tail {
  font-size: var(--fs-2xs);
  font-weight: 600;
  color: var(--accent);
  background: var(--surface);
  border: 1px solid var(--line);
  border-radius: 5px;
  padding: 1px 6px;
  letter-spacing: 0.6px;
}

.scan-hint-muted {
  font-size: var(--fs-xs);
  color: var(--muted);
  line-height: 1.6;
}

.scan-input :deep(.el-input__inner) {
  letter-spacing: 4px;
  font-family: var(--font-mono);
  font-weight: 700;
}

.scan-error {
  color: var(--danger);
  font-size: var(--fs-xs);
}

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
  background: var(--accent-soft);
  color: var(--accent);
  font-size: var(--fs-lg);
}

.veh-name {
  font-size: var(--fs-base);
  font-weight: 700;
  color: var(--ink);
}

.veh-driver {
  font-size: var(--fs-sm);
  color: var(--c-muted);
  margin-top: 3px;
}

.temp-box {
  text-align: right;
}

.temp-val {
  font-family: var(--font-num);
  font-size: var(--fs-display);
  font-weight: 700;
  color: var(--accent);
  line-height: 1;
}

.temp-val small {
  font-size: var(--fs-base);
  margin-left: 1px;
}

.temp-box.alarm .temp-val {
  color: var(--danger);
}

.temp-label {
  font-size: var(--fs-2xs);
  color: var(--c-muted-2);
  margin-top: 4px;
}

.temp-box.alarm .temp-label {
  color: var(--danger);
  font-weight: 600;
}

.mileage-row {
  display: flex;
  align-items: center;
  gap: 7px;
  padding: 12px 0;
  font-size: var(--fs-sm);
  color: var(--c-muted);
}

.mileage-row b {
  color: var(--ink);
  font-family: var(--font-num);
}

/* 随货批次：一行内左侧标签、右侧批次与尾号，超长省略不换行 */
.batch-line {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 0 0 12px;
  min-width: 0;
}

.bl-k {
  font-size: var(--fs-xs);
  color: var(--c-muted);
  flex-shrink: 0;
}

.bl-v {
  font-size: var(--fs-xs);
  font-weight: 700;
  color: var(--ink);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  min-width: 0;
}

.bl-tail {
  flex-shrink: 0;
  font-size: var(--fs-2xs);
  font-weight: 600;
  color: var(--accent);
  background: var(--accent-soft);
  border-radius: 5px;
  padding: 1px 6px;
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
  background: linear-gradient(120deg, var(--surface-2), #eaf8f2);
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
  background: var(--accent);
  box-shadow: 0 0 0 3px rgba(22, 163, 74, 0.16);
}

.dot-teal {
  background: var(--accent);
  box-shadow: 0 0 0 3px rgba(13, 148, 136, 0.16);
}

.rp-label {
  font-size: var(--fs-2xs);
  color: var(--c-muted-2);
}

.rp-name {
  font-size: var(--fs-sm);
  font-weight: 600;
  color: var(--ink);
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

/* 本批装载：一张卡里最多几行，行内三行结构，长批次号省略不换行 */
.batch-row {
  padding: 10px 12px;
  border-radius: 9px;
  border: 1px solid var(--line);
  background: var(--surface-2);
  margin-bottom: 8px;
  min-width: 0;
}

.batch-row:last-of-type {
  margin-bottom: 0;
}

.batch-row.mine {
  background: var(--accent-soft);
  border-color: color-mix(in srgb, var(--accent) 28%, var(--line));
}

.br-head {
  display: flex;
  align-items: center;
  gap: 7px;
  min-width: 0;
}

.br-tag {
  flex-shrink: 0;
  font-size: var(--fs-2xs);
  font-weight: 700;
  color: var(--accent-ink);
  background: var(--accent);
  border-radius: 4px;
  padding: 1px 5px;
}

.br-no {
  font-size: var(--fs-xs);
  font-weight: 700;
  color: var(--ink);
  letter-spacing: 0.3px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  cursor: pointer;
}

.br-no:hover {
  color: var(--accent);
}

.br-sub {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 6px;
  min-width: 0;
}

.br-tail {
  flex-shrink: 0;
  font-size: var(--fs-2xs);
  color: var(--ink-2);
  background: var(--surface);
  border: 1px dashed var(--line-strong);
  border-radius: 5px;
  padding: 1px 6px;
  letter-spacing: 0.5px;
}

.br-prod {
  font-size: var(--fs-xs);
  color: var(--c-muted);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.br-meta {
  margin-top: 8px;
  padding-top: 7px;
  border-top: 1px dashed color-mix(in srgb, var(--ink) 10%, transparent);
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.bm-row {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
  font-size: var(--fs-2xs);
}

.bm-k {
  flex-shrink: 0;
  color: var(--c-muted-2);
}

.bm-v {
  color: var(--ink-2);
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.ellipsis {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.alarm-box {
  display: flex;
  gap: 7px;
  margin-top: 12px;
  padding: 10px 12px;
  border-radius: 9px;
  background: #fdf6e9;
  border: 1px solid #f6e2bd;
  color: var(--warn);
  font-size: var(--fs-xs);
  line-height: 1.7;
}

.temp-tip {
  margin-top: 12px;
  font-size: var(--fs-xs);
  color: var(--c-muted-2);
  line-height: 1.7;
}

.temp-bad {
  color: var(--danger);
  font-weight: 700;
}

.temp-ok {
  color: var(--accent);
  font-weight: 600;
}

.temp-edit {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-top: 16px;
  padding-top: 14px;
  border-top: 1px dashed var(--c-border);
  font-size: var(--fs-sm);
  color: var(--c-muted);
}
</style>
