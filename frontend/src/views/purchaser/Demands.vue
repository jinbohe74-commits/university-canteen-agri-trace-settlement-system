<template>
  <div class="page">
    <div class="page-head">
      <div>
        <h2 class="page-title">采购需求管理</h2>
        <p class="page-sub">发布需求 → 农户报价 → 比价定标；列表默认只看待比价的需求</p>
      </div>
      <div class="page-actions">
        <el-button plain @click="exportDemands"><el-icon><Download /></el-icon>导出需求</el-button>
        <el-button type="primary" @click="publishVisible = true"><el-icon><Plus /></el-icon>发布需求</el-button>
      </div>
    </div>

    <!-- 统计卡 -->
    <div class="stat-grid">
      <StatCard label="待比价" :value="fmtNum(stats.matching || 0)" foot="需要你定标" icon="Search" color="orange" />
      <StatCard label="已成交" :value="fmtNum(stats.dealt || 0)" foot="累计" icon="CircleCheck" color="green" />
      <StatCard label="平均报价数" :value="fmtNum(stats.avgQuotes || 0)" foot="每单货源" icon="Shop" color="blue" />
      <StatCard label="已撤回" :value="fmtNum(stats.withdrawn || 0)" foot="累计" icon="RefreshLeft" color="purple" />
    </div>

    <!-- 需求列表：主视图，发布表单收进抽屉，避免两栏抢版面 -->
    <div class="card">
      <div class="card-head">
        <div class="card-title">需求列表</div>
        <div class="card-extra">共 {{ total }} 条 · 五因子匹配</div>
      </div>

      <div class="filter-bar">
        <el-input
          v-model="keyword"
          placeholder="需求编号 / 产品"
          clearable
          style="width: 210px"
          @keyup.enter="search"
        >
          <template #prefix><el-icon><Search /></el-icon></template>
        </el-input>
        <el-button type="primary" @click="search"><el-icon><Search /></el-icon>查询</el-button>
        <div class="filter-spacer"></div>
        <span class="muted small">需求发布后自动推送同品类认证农户</span>
      </div>

      <!-- 从待办点「去比价」会带 focus=需求编号过来，这里明说定位到了哪一条 -->
      <div v-if="focusNo" class="focus-bar">
        <el-icon><Aim /></el-icon>
        <span class="grow">
          正在定位待办需求 <b class="mono">{{ focusNo }}</b>
          <template v-if="focusHit">，已打开比价面板</template>
          <template v-else>，当前列表里没找到这条</template>
        </span>
        <el-button link type="primary" @click="clearFocus">查看全部需求</el-button>
      </div>

      <el-tabs v-model="status" class="status-tabs">
        <el-tab-pane label="待比价" name="处理中" />
        <el-tab-pane label="已成交" name="已成交" />
        <el-tab-pane label="已完成" name="已完成" />
        <el-tab-pane label="已撤回" name="已撤回" />
        <el-tab-pane label="全部" name="ALL" />
      </el-tabs>

      <div class="table-wrap" v-loading="loading">
        <el-table :data="rows" size="default" style="width: 100%" :row-class-name="rowClass">
          <el-table-column label="需求编号" min-width="146">
            <template #default="{ row }">
              <span class="mono">{{ row.demandNo }}</span>
              <span class="cell-sub">{{ fmtDate(row.createTime).slice(5, 16) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="产品 / 品类" min-width="132">
            <template #default="{ row }">
              <span class="bold">{{ row.productName || '—' }}</span>
              <span class="cell-sub">{{ row.categoryName || '—' }}</span>
            </template>
          </el-table-column>
          <el-table-column label="数量" width="94" align="right">
            <template #default="{ row }">{{ fmtNum(row.quantity) }} {{ row.unit }}</template>
          </el-table-column>
          <el-table-column label="期望单价" width="94" align="right">
            <template #default="{ row }"><span class="money">¥{{ fmtMoney(row.expectedPrice) }}</span></template>
          </el-table-column>
          <el-table-column label="交货截止" width="118">
            <template #default="{ row }">
              <span class="mono">{{ fmtMD(row.deadline) }}</span>
              <span v-if="row.status === '处理中'" class="cell-sub" :class="deadlineLevel(row.deadline)">
                {{ untilText(row.deadline) }}
              </span>
            </template>
          </el-table-column>
          <el-table-column label="报价" width="86" align="center">
            <template #default="{ row }">
              <span v-if="row.quoteCount > 0" class="quote-n mono">{{ row.quoteCount }} 家</span>
              <span v-else-if="row.status === '处理中'" class="tag tag-red">等报价</span>
              <span v-else class="muted">—</span>
            </template>
          </el-table-column>
          <el-table-column label="状态" width="92" align="center">
            <template #default="{ row }"><StatusTag type="demand" :value="row.status" /></template>
          </el-table-column>
          <el-table-column label="操作" width="112" align="center" fixed="right">
            <template #default="{ row }">
              <el-button
                v-if="row.status === '处理中'"
                type="primary" size="small" @click="openDetail(row)"
              >比价定标</el-button>
              <el-button v-else size="small" plain @click="openDetail(row)">查看</el-button>
            </template>
          </el-table-column>
          <template #empty><div class="empty">当前分类下没有需求</div></template>
        </el-table>
      </div>

      <div class="pager">
        <span>共 {{ total }} 条 · 第 {{ page }} / {{ pages }} 页</span>
        <el-pagination
          v-model:current-page="page"
          :page-size="size"
          :total="total"
          layout="prev, pager, next"
          background
          @current-change="load"
        />
      </div>
    </div>

    <!-- 发布表单收进抽屉：日常主任务是看需求、比价定标，发布是低频动作 -->
    <el-drawer v-model="publishVisible" title="发布采购需求" size="440px" append-to-body>
      <el-form label-position="top" class="demand-form">
          <el-form-item label="采购产品">
            <el-select
              v-model="form.productId"
              filterable
              clearable
              placeholder="搜索并选择农产品"
              style="width: 100%"
              @change="onProductChange"
            >
              <el-option
                v-for="p in products"
                :key="p.id"
                :label="p.name + ' · 参考 ¥' + fmtMoney(p.avgPrice) + '/' + (p.unit || 'kg')"
                :value="p.id"
              />
            </el-select>
          </el-form-item>

          <div class="form-row">
            <el-form-item label="需求数量（kg）">
              <el-input-number
                v-model="form.quantity"
                :min="1"
                :max="100000"
                :step="50"
                controls-position="right"
                style="width: 100%"
              />
            </el-form-item>
            <el-form-item label="期望单价（元/kg）">
              <el-input-number
                v-model="form.expectedPrice"
                :min="0.01"
                :max="999"
                :step="0.1"
                :precision="2"
                controls-position="right"
                placeholder="如 2.00"
                style="width: 100%"
              />
            </el-form-item>
          </div>

          <el-form-item label="期望交货时间">
            <el-date-picker
              v-model="form.deadline"
              type="datetime"
              format="YYYY-MM-DD HH:mm"
              value-format="YYYY-MM-DDTHH:mm:ss"
              placeholder="选择期望交货时间"
              :disabled-date="disabledPast"
              style="width: 100%"
            />
          </el-form-item>

          <div class="form-row">
            <el-form-item label="配送方式">
              <el-select v-model="form.deliveryMode" style="width: 100%">
                <el-option label="冷藏配送" value="冷藏配送" />
                <el-option label="冷链配送" value="冷链配送" />
                <el-option label="常温配送" value="常温配送" />
              </el-select>
            </el-form-item>
            <el-form-item label="结算方式">
              <el-select v-model="form.settlementMode" style="width: 100%">
                <el-option label="月结" value="月结" />
                <el-option label="周结" value="周结" />
                <el-option label="现结" value="现结" />
              </el-select>
            </el-form-item>
          </div>

          <el-form-item label="质量要求 / 备注">
            <el-input
              v-model="form.qualityReq"
              type="textarea"
              :rows="4"
              maxlength="200"
              show-word-limit
              placeholder="如：要求新鲜、无黄叶、农残检测合格"
            />
          </el-form-item>

          <el-button type="primary" class="publish-btn" :loading="publishing" @click="publish">
            <el-icon><Plus /></el-icon>发布需求
          </el-button>
          <p class="form-hint">发布后系统自动推送至同品类认证农户，并按「品类 / 价格 / 数量 / 信用 / 距离」五因子计算匹配得分。</p>
        </el-form>
    </el-drawer>

    <!-- 需求详情 -->
    <el-dialog v-model="detailVisible" title="需求详情" width="780px">
      <el-descriptions v-if="detailRow" :column="2" border size="small">
        <el-descriptions-item label="需求编号">
          <span class="mono">{{ detailRow.demandNo }}</span>
        </el-descriptions-item>
        <el-descriptions-item label="状态">
          <StatusTag type="demand" :value="detailRow.status" />
        </el-descriptions-item>
        <el-descriptions-item label="产品">{{ detailRow.productName || '—' }}</el-descriptions-item>
        <el-descriptions-item label="品类">{{ detailRow.categoryName || '—' }}</el-descriptions-item>
        <el-descriptions-item label="需求数量">
          {{ fmtNum(detailRow.quantity) }} {{ detailRow.unit }}
        </el-descriptions-item>
        <el-descriptions-item label="期望单价">
          ¥{{ fmtMoney(detailRow.expectedPrice) }} / {{ detailRow.unit }}
        </el-descriptions-item>
        <el-descriptions-item label="期望交货">{{ fmtDate(detailRow.deadline) }}</el-descriptions-item>
        <el-descriptions-item label="配送方式">{{ detailRow.deliveryMode || '—' }}</el-descriptions-item>
        <el-descriptions-item label="结算方式">{{ detailRow.settlementMode || '—' }}</el-descriptions-item>
        <el-descriptions-item label="报价数">{{ detailRow.quoteCount || 0 }} 家</el-descriptions-item>
        <el-descriptions-item label="关联订单">
          <span v-if="detailRow.orderNo" class="mono">{{ detailRow.orderNo }}</span>
          <span v-else class="muted">暂未成交</span>
        </el-descriptions-item>
        <el-descriptions-item label="创建时间">{{ fmtDate(detailRow.createTime) }}</el-descriptions-item>
        <el-descriptions-item label="质量要求 / 备注" :span="2">
          {{ detailRow.qualityReq || '—' }}
        </el-descriptions-item>
      </el-descriptions>

      <!-- 报价比价：农户报价后在这里横向对比并定标 -->
      <div v-if="detailRow" class="cmp">
        <div class="cmp-head">
          <span class="cmp-title">报价比价</span>
          <span v-if="quotes.length" class="muted small">{{ quotes.length }} 家报价 · 按单价从低到高</span>
          <span v-else class="muted small">还没有农户报价，等待需求大厅推送</span>
        </div>
        <el-table v-if="quotes.length" :data="quotes" size="small">
          <el-table-column label="农户" min-width="146">
            <template #default="{ row }">
              <span class="bold">{{ row.farmerName }}</span>
              <span class="tag tag-gray">{{ row.farmerType }}</span>
            </template>
          </el-table-column>
          <el-table-column label="报价单价" width="112" align="right">
            <template #default="{ row }">
              <span class="money">¥{{ fmtMoney(row.price) }}</span>
              <span v-if="row.lowestPrice" class="tag tag-green">最低</span>
            </template>
          </el-table-column>
          <el-table-column label="信用分" width="94" align="center">
            <template #default="{ row }">
              {{ fmtNum(row.creditScore, 0) }}
              <span v-if="row.bestCredit" class="tag tag-blue">最高</span>
            </template>
          </el-table-column>
          <el-table-column label="承诺送达" width="86" align="center">
            <template #default="{ row }">{{ row.deliveryDays }} 天</template>
          </el-table-column>
          <el-table-column label="可供数量" width="94" align="right">
            <template #default="{ row }">{{ fmtNum(row.quantity) }}{{ detailRow.unit }}</template>
          </el-table-column>
          <el-table-column label="状态" width="78" align="center">
            <template #default="{ row }">
              <span class="tag" :class="quoteTag(row.status)">{{ row.status }}</span>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="84" align="center" fixed="right">
            <template #default="{ row }">
              <span
                v-if="row.status === '待选' && detailRow.status === '处理中'"
                class="link-strong"
                @click="award(row)"
              >
                定标
              </span>
              <span v-else class="muted small">—</span>
            </template>
          </el-table-column>
        </el-table>
        <div v-if="quotes.length" class="cmp-tip">
          定标后自动生成采购单并通知中标农户，其余报价置为落选，需求随即变为「已成交」。
        </div>
      </div>
      <template #footer>
        <el-button @click="detailVisible = false">关闭</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { commonApi, purchaserApi } from '@/api'
import StatCard from '@/components/StatCard.vue'
import StatusTag from '@/components/StatusTag.vue'
import { fmtDate, fmtMoney, fmtNum } from '@/utils/format'
import { useAutoRefresh } from '@/utils/useAutoRefresh'
import { exportTableDom, stamped } from '@/utils/export'

const route = useRoute()

const loading = ref(false)
const publishing = ref(false)
const publishVisible = ref(false)
const rows = ref([])
const total = ref(0)
const pages = ref(1)
const page = ref(1)
const size = ref(10)
const keyword = ref('')
/* 默认只看「待比价」：这一屏就是"我要干的活"；
   全部/已成交是历史，翻得到但不占第一眼（原来看全部，满屏同类历史单） */
const status = ref('处理中')
const stats = ref({})
const products = ref([])

/* 待办带过来的需求编号：定位到的那一条要高亮，并直接打开比价面板 */
const focusNo = ref('')
const focusHit = ref(false)

const detailVisible = ref(false)
const detailRow = ref(null)
const quotes = ref([])
const awarding = ref(null)

const DEFAULT_REQ = '要求新鲜、无黄叶、农残检测合格；需提供批次溯源二维码。'

const form = reactive({
  productId: null,
  quantity: 400,
  expectedPrice: null,
  deadline: '',
  deliveryMode: '冷链配送',
  settlementMode: '月结',
  qualityReq: DEFAULT_REQ
})

const fmtMD = (v) => (v ? fmtDate(v).slice(5, 16) : '—')

const disabledPast = (d) => d.getTime() < Date.now() - 86400000

/* 截止时间语义：待比价的需求只关心"还剩多久"，不是"哪天下的" */
const untilText = (deadline) => {
  if (!deadline) return ''
  const min = Math.round((new Date(deadline).getTime() - Date.now()) / 60000)
  if (min < 0) return '已过截止 ' + Math.ceil(-min / 1440) + ' 天'
  if (min < 60) return '剩 ' + Math.max(1, min) + ' 分钟'
  const h = Math.round(min / 60)
  if (h < 24) return '剩 ' + h + ' 小时'
  return '剩 ' + Math.round(h / 24) + ' 天'
}
const deadlineLevel = (deadline) => {
  if (!deadline) return ''
  const min = Math.round((new Date(deadline).getTime() - Date.now()) / 60000)
  if (min < 0) return 'dl-danger'
  if (min < 24 * 60) return 'dl-warn'
  return ''
}

const rowClass = ({ row }) => (row.demandNo === focusNo.value ? 'row-focus' : '')

const load = async () => {
  loading.value = true
  try {
    const res = await purchaserApi.demands({
      page: page.value,
      size: size.value,
      keyword: keyword.value.trim() || undefined,
      status: status.value === 'ALL' ? undefined : status.value
    })
    const payload = res.data || {}
    rows.value = payload.page?.records || []
    total.value = Number(payload.page?.total || 0)
    pages.value = Number(payload.page?.pages || 1)
    stats.value = payload.stats || {}
  } catch (e) {
    /* 错误提示由请求拦截器统一处理 */
  } finally {
    loading.value = false
  }
}

const loadProducts = async () => {
  try {
    const res = await commonApi.products()
    products.value = res.data || []
  } catch (e) {
    products.value = []
  }
}

const search = () => {
  page.value = 1
  load()
}

watch(status, () => {
  page.value = 1
  load()
})

const onProductChange = (id) => {
  const hit = products.value.find((p) => p.id === id)
  if (hit && !form.expectedPrice) {
    form.expectedPrice = Number(hit.avgPrice || 0) || null
  }
}

const publish = async () => {
  if (!form.productId) return ElMessage.warning('请选择采购产品')
  if (!form.quantity || Number(form.quantity) <= 0) return ElMessage.warning('需求数量必须大于 0')
  if (!form.expectedPrice || Number(form.expectedPrice) <= 0) return ElMessage.warning('请填写期望单价')
  if (!form.deadline) return ElMessage.warning('请选择期望交货时间')
  publishing.value = true
  try {
    await purchaserApi.saveDemand({
      productId: form.productId,
      quantity: form.quantity,
      expectedPrice: form.expectedPrice,
      deadline: form.deadline,
      deliveryMode: form.deliveryMode,
      settlementMode: form.settlementMode,
      qualityReq: form.qualityReq
    })
    ElMessage.success('需求发布成功，系统已启动智能匹配')
    form.quantity = 400
    form.expectedPrice = null
    form.deadline = ''
    form.qualityReq = DEFAULT_REQ
    publishVisible.value = false
    page.value = 1
    status.value = '处理中'
    await load()
  } catch (e) {
    /* 错误提示由请求拦截器统一处理 */
  } finally {
    publishing.value = false
  }
}

const openDetail = (row) => {
  detailRow.value = row
  detailVisible.value = true
  loadQuotes(row.id)
}

/* ---- 待办深链：/purchaser/demands?focus=X2026... ---- */
async function applyFocus() {
  const focus = String(route.query.focus || '').trim()
  if (!focus) return
  focusNo.value = focus
  keyword.value = focus
  status.value = 'ALL'   // 待办里的需求可能已经成交，不能被默认筛选挡住
  page.value = 1
  await load()
  const hit = (rows.value || []).find((r) => r.demandNo === focus)
  focusHit.value = !!hit
  if (hit) openDetail(hit)
}

const clearFocus = () => {
  focusNo.value = ''
  focusHit.value = false
  keyword.value = ''
  status.value = '处理中'
  page.value = 1
  load()
}

/** 比价表：每次打开详情都重新拉，避免看到别处定标后的过期状态 */
const loadQuotes = async (demandId) => {
  quotes.value = []
  try {
    const res = await purchaserApi.demandQuotes(demandId)
    quotes.value = res.data?.quotes || []
  } catch (e) {
    /* 错误提示由请求拦截器统一处理 */
  }
}

const quoteTag = (s) => {
  if (s === '中标') return 'tag-green'
  if (s === '落选') return 'tag-gray'
  return 'tag-orange'
}

const award = async (q) => {
  try {
    await ElMessageBox.confirm(
      `将「${q.farmerName}」定为中标供应商：单价 ¥${fmtMoney(q.price)}，可供 ${fmtNum(q.quantity)}，` +
        `承诺 ${q.deliveryDays} 天送达。定标后立即生成采购单，其余报价置为落选。`,
      '确认定标',
      { type: 'warning', confirmButtonText: '确认定标', cancelButtonText: '再想想' }
    )
  } catch (e) {
    return
  }
  awarding.value = q.id
  try {
    const res = await purchaserApi.awardQuote(detailRow.value.id, q.id)
    /* 定标后留在需求页：用户可能还有第二条要比价，
       一杆子把人弹到订单页，等于让他重新找回来（L-043 同类问题） */
    ElMessage.success((res.message || '已定标并生成采购单') + '，可在「我的订单」查看')
    detailVisible.value = false
    await load()
  } catch (e) {
    /* 错误提示由请求拦截器统一处理 */
  } finally {
    awarding.value = null
  }
}

const exportDemands = () => {
  const n = exportTableDom(stamped('采购需求'))
  ElMessage.success(n ? `已导出当前页 ${n} 条记录` : '当前没有可导出的数据')
}

useAutoRefresh(load)
onMounted(async () => {
  loadProducts()
  await load()
  applyFocus()
})
watch(() => route.query.focus, () => { applyFocus() })
</script>

<style scoped>
/* 定位条：从待办带 focus 过来时，明说定位到了哪一条 */
.focus-bar {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 10px 12px 0;
  padding: 9px 12px;
  border-radius: 9px;
  border: 1px solid color-mix(in srgb, var(--accent) 30%, var(--line));
  background: var(--accent-soft);
  color: var(--ink-2);
  font-size: var(--fs-xs);
}

.focus-bar b {
  color: var(--accent);
}

.cell-sub {
  display: block;
  font-size: var(--fs-2xs);
  color: var(--c-muted-2);
  margin-top: 1px;
}

.cell-sub.dl-warn {
  color: var(--warn);
  font-weight: 600;
}

.cell-sub.dl-danger {
  color: var(--danger);
  font-weight: 700;
}

.quote-n {
  font-weight: 700;
  color: var(--ink);
}

/* 定位到的那一行要能一眼认出来，不能只是"跳过来了" */
:deep(.el-table__row.row-focus) {
  background: var(--accent-soft);
}

:deep(.el-table__row.row-focus td) {
  background: var(--accent-soft);
}

.status-tabs {
  padding: 0 12px;
}

.status-tabs :deep(.el-tabs__header) {
  margin: 0;
}

.status-tabs :deep(.el-tabs__nav-wrap::after) {
  height: 1px;
  background: var(--c-border);
}

.status-tabs :deep(.el-tabs__content) {
  display: none;
}

.demand-form :deep(.el-form-item) {
  margin-bottom: 14px;
}

.demand-form :deep(.el-form-item__label) {
  font-size: var(--fs-sm);
  color: var(--c-muted);
  padding-bottom: 4px;
}

.form-row {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
  gap: 12px;
}

.publish-btn {
  width: 100%;
  height: 38px;
  margin-top: 2px;
  font-weight: 600;
}

.form-hint {
  margin: 10px 0 0;
  font-size: var(--fs-xs);
  line-height: 1.7;
  color: var(--c-muted-2);
}

/* 报价比价：紧跟在需求信息下方，避免用户看完需求还要再点一次才知道有没有人报价 */
.cmp {
  margin-top: 16px;
  padding-top: 14px;
  border-top: 1px solid var(--c-border);
}

.cmp-head {
  display: flex;
  align-items: baseline;
  gap: 10px;
  margin-bottom: 10px;
}

.cmp-title {
  font-size: var(--fs-md);
  font-weight: 700;
}

.cmp-tip {
  margin-top: 10px;
  padding: 9px 12px;
  border-radius: 8px;
  background: var(--info-soft);
  color: var(--ink-2);
  font-size: var(--fs-xs);
  line-height: 1.6;
}
</style>
