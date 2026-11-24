<template>
  <div class="page" v-loading="loading">
    <div class="page-head">
      <div>
        <h2 class="page-title">农户/合作社管理</h2>
        <p class="page-sub">维护农户、合作社、生产基地档案，审核入驻资质与认证状态</p>
      </div>
      <div class="page-actions">
        <el-button plain @click="exportRoster"><el-icon><Download /></el-icon>导出名册</el-button>
        <el-button type="primary" @click="openCreate"><el-icon><Plus /></el-icon>新增农户</el-button>
      </div>
    </div>

    <!-- 统计卡片 -->
    <div class="stat-grid">
      <StatCard
        label="认证农户/合作社" :value="fmtNum(stats.certified)" icon="UserFilled"
        foot="较上月 +3" color="green"
      />
      <StatCard
        label="入驻生产基地" :value="fmtNum(stats.bases)" icon="OfficeBuilding"
        foot="累计" color="blue"
      />
      <StatCard
        label="待审核" :value="fmtNum(stats.pending)" icon="Clock"
        foot="点击筛出待审核" color="orange" clickable @click="filterPending"
      />
      <StatCard
        label="平均信用分" :value="fmtNum(stats.avgCredit, 1)" icon="Star"
        foot="较上月 +0.8" color="teal"
      />
    </div>

    <div class="card table-card">
      <!-- 筛选条 -->
      <div class="filter-bar">
        <el-input
          v-model="query.keyword" placeholder="搜索农户名称 / 联系人 / 手机号" clearable
          style="width: 232px" @keyup.enter="search" @clear="search"
        >
          <template #prefix><el-icon><Search /></el-icon></template>
        </el-input>
        <!-- 主体类型由下方带数量的分段控件承担，这里不再放重复的下拉 -->
        <el-select v-model="query.status" placeholder="认证状态" clearable style="width: 136px" @change="search">
          <el-option label="全部状态" value="" />
          <el-option v-for="s in STATUS" :key="s" :label="s" :value="s" />
        </el-select>
        <el-select v-model="query.creditLevel" placeholder="信用等级" clearable style="width: 152px" @change="search">
          <el-option label="全部信用等级" value="" />
          <el-option v-for="c in CREDIT_LEVELS" :key="c.value" :label="c.label" :value="c.value" />
        </el-select>
        <div class="filter-spacer"></div>
        <el-button plain @click="reset"><el-icon><Refresh /></el-icon>重置</el-button>
        <el-button type="primary" @click="search"><el-icon><Search /></el-icon>查询</el-button>
      </div>

      <!-- 状态 Tabs -->
      <div class="tab-bar">
        <button
          v-for="t in tabs" :key="t.key" class="tab-btn" :class="{ active: activeTab === t.key }"
          @click="selectTab(t.key)"
        >
          {{ t.label }}<span class="tab-num">{{ t.count }}</span>
        </button>
      </div>

      <div class="table-wrap">
        <el-table :data="rows" style="width: 100%" empty-text="暂无符合条件的农户档案">
          <el-table-column label="农户/合作社" min-width="178">
            <template #default="{ row }">
              <div class="cell-main">{{ row.name }}</div>
              <div class="cell-sub mono">{{ row.farmerNo }}</div>
            </template>
          </el-table-column>
          <el-table-column label="主体类型" width="88">
            <template #default="{ row }">
              <span class="tag" :class="typeTag(row.type)">{{ row.type }}</span>
            </template>
          </el-table-column>
          <el-table-column prop="contact" label="联系人" width="78" />
          <el-table-column label="联系电话" width="114">
            <template #default="{ row }"><span class="mono">{{ row.phone || '—' }}</span></template>
          </el-table-column>
          <el-table-column prop="mainCategory" label="主营品类" width="118" show-overflow-tooltip>
            <template #default="{ row }"><span class="small">{{ row.mainCategory || '—' }}</span></template>
          </el-table-column>
          <el-table-column prop="supplyCapacity" label="供应能力" width="100" show-overflow-tooltip>
            <template #default="{ row }"><span class="small muted">{{ row.supplyCapacity || '—' }}</span></template>
          </el-table-column>
          <el-table-column label="信用分" width="108">
            <template #default="{ row }">
              <div class="credit-cell">
                <span class="credit-num money" :style="{ color: creditColor(row.creditScore) }">
                  {{ fmtNum(row.creditScore, 1) }}
                </span>
                <el-progress
                  :percentage="Math.min(100, Number(row.creditScore) || 0)"
                  :stroke-width="4" :show-text="false"
                  :color="creditColor(row.creditScore)" style="width: 46px"
                />
              </div>
            </template>
          </el-table-column>
          <el-table-column label="认证状态" width="92">
            <template #default="{ row }"><StatusTag type="cert" :value="row.status" /></template>
          </el-table-column>
          <el-table-column label="入驻时间" width="100">
            <template #default="{ row }"><span class="mono small">{{ fmtDay(row.joinTime) }}</span></template>
          </el-table-column>
          <el-table-column label="操作" width="148" fixed="right">
            <template #default="{ row }">
              <div class="op-links">
                <span class="link" @click="openView(row)">查看</span>
                <span class="link" @click="openEdit(row)">编辑</span>
                <span class="link" :class="{ 'link-danger': row.status !== '已停用' }" @click="toggleStatus(row)">
                  {{ row.status === '已停用' ? '启用' : '停用' }}
                </span>
              </div>
            </template>
          </el-table-column>
        </el-table>
      </div>

      <div class="pager">
        <span>共 {{ total }} 条记录</span>
        <el-pagination
          :current-page="query.page" :page-size="query.size" :total="total"
          :page-sizes="[10, 20, 50]" layout="sizes, prev, pager, next, jumper" background
          @size-change="onSizeChange" @current-change="onPageChange"
        />
      </div>
    </div>

    <!-- 查看档案 -->
    <el-dialog v-model="viewVisible" title="农户档案" width="720px" :close-on-click-modal="false">
      <div v-if="detail" class="archive">
        <div class="archive-head">
          <div class="archive-avatar">{{ (detail.name || '农').slice(0, 1) }}</div>
          <div class="grow">
            <div class="archive-name">
              {{ detail.name }}
              <StatusTag type="cert" :value="detail.status" />
            </div>
            <div class="archive-sub mono">{{ detail.farmerNo }} · {{ detail.type }} · 入驻 {{ fmtDay(detail.joinTime) }}</div>
          </div>
          <div class="archive-credit">
            <div class="ac-label">信用分</div>
            <div class="ac-value" :style="{ color: creditColor(detail.creditScore) }">
              {{ fmtNum(detail.creditScore, 1) }}
            </div>
          </div>
        </div>

        <el-descriptions :column="2" border size="small">
          <el-descriptions-item label="档案编号">{{ detail.farmerNo || '—' }}</el-descriptions-item>
          <el-descriptions-item label="主体类型">{{ detail.type || '—' }}</el-descriptions-item>
          <el-descriptions-item label="联系人">{{ detail.contact || '—' }}</el-descriptions-item>
          <el-descriptions-item label="联系电话">{{ detail.phone || '—' }}</el-descriptions-item>
          <el-descriptions-item label="主营品类">{{ detail.mainCategory || '—' }}</el-descriptions-item>
          <el-descriptions-item label="供应能力">{{ detail.supplyCapacity || '—' }}</el-descriptions-item>
          <el-descriptions-item label="产地地址" :span="2">{{ detail.address || '—' }}</el-descriptions-item>
          <el-descriptions-item label="证件号">{{ detail.certNo || '—' }}</el-descriptions-item>
          <el-descriptions-item label="证照到期日">{{ detail.certExpire || '—' }}</el-descriptions-item>
          <el-descriptions-item label="配送距离">
            {{ detail.distanceKm === null || detail.distanceKm === undefined ? '—' : fmtNum(detail.distanceKm, 1) + ' km' }}
          </el-descriptions-item>
          <el-descriptions-item label="信用分">{{ fmtNum(detail.creditScore, 1) }}</el-descriptions-item>
          <el-descriptions-item label="交易次数">{{ fmtNum(detail.dealCount) }} 次</el-descriptions-item>
          <el-descriptions-item label="好评率">{{ fmtNum(detail.goodRate, 1) }}%</el-descriptions-item>
          <el-descriptions-item label="入驻时间">{{ fmtDay(detail.joinTime) }}</el-descriptions-item>
        </el-descriptions>
      </div>
      <template #footer>
        <div class="dialog-foot">
          <el-button @click="viewVisible = false">关闭</el-button>
          <div class="grow"></div>
          <el-button plain @click="openEdit(detail)">编辑档案</el-button>
          <el-button type="primary" @click="viewAction">
            {{ detail?.status === '已认证' ? '停用' : detail?.status === '已停用' ? '启用' : '认证通过' }}
          </el-button>
        </div>
      </template>
    </el-dialog>

    <!-- 新增 / 编辑 -->
    <el-dialog
      v-model="formVisible" :title="form.id ? '编辑农户档案' : '新增农户/合作社'"
      width="660px" :close-on-click-modal="false"
    >
      <el-form ref="formRef" :model="form" :rules="rules" label-width="92px">
        <div class="form-grid">
          <el-form-item label="名称" prop="name">
            <el-input v-model="form.name" placeholder="如：绿源蔬菜合作社" maxlength="40" />
          </el-form-item>
          <el-form-item label="主体类型" prop="type">
            <el-select v-model="form.type" placeholder="请选择主体类型" style="width: 100%">
              <el-option v-for="t in TYPES" :key="t" :label="t" :value="t" />
            </el-select>
          </el-form-item>
          <el-form-item label="联系人" prop="contact">
            <el-input v-model="form.contact" placeholder="请输入联系人姓名" maxlength="20" />
          </el-form-item>
          <el-form-item label="联系电话" prop="phone">
            <el-input v-model="form.phone" placeholder="请输入联系电话" maxlength="20" />
          </el-form-item>
          <el-form-item label="主营品类">
            <el-input v-model="form.mainCategory" placeholder="如：叶菜类·根茎类" maxlength="40" />
          </el-form-item>
          <el-form-item label="供应能力">
            <el-input v-model="form.supplyCapacity" placeholder="如：5,000 kg/日" maxlength="30" />
          </el-form-item>
          <el-form-item label="产地地址" class="span-2">
            <el-input v-model="form.address" placeholder="请输入产地地址" maxlength="80" />
          </el-form-item>
          <el-form-item label="证件号">
            <el-input v-model="form.certNo" placeholder="如：JX2026100037" maxlength="40" />
          </el-form-item>
          <el-form-item label="证照到期日">
            <el-date-picker
              v-model="form.certExpire" type="date" value-format="YYYY-MM-DD"
              placeholder="选择到期日" style="width: 100%"
            />
          </el-form-item>
          <el-form-item label="配送距离">
            <el-input-number
              v-model="form.distanceKm" :min="0" :max="500" :precision="1" :step="0.5"
              controls-position="right" style="width: 100%"
            />
          </el-form-item>
          <el-form-item label="信用分">
            <el-input-number
              v-model="form.creditScore" :min="0" :max="100" :precision="1"
              controls-position="right" style="width: 100%"
            />
          </el-form-item>
          <el-form-item label="认证状态" class="span-2">
            <el-select v-model="form.status" placeholder="请选择状态" style="width: 100%">
              <el-option v-for="s in STATUS" :key="s" :label="s" :value="s" />
            </el-select>
          </el-form-item>
        </div>
      </el-form>
      <template #footer>
        <el-button @click="formVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submit">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { adminApi } from '@/api'
import StatCard from '@/components/StatCard.vue'
import StatusTag from '@/components/StatusTag.vue'
import { fmtDay, fmtNum } from '@/utils/format'
import { useAutoRefresh } from '@/utils/useAutoRefresh'
import { exportTableDom, stamped } from '@/utils/export'

const TYPES = ['合作社', '生产基地', '个体农户']
const STATUS = ['已认证', '待审核', '已停用']
const CREDIT_LEVELS = [
  { value: '优秀', label: '优秀（≥90）' },
  { value: '良好', label: '良好（85-90）' },
  { value: '一般', label: '一般（80-85）' },
  { value: '风险', label: '风险（<80）' }
]

const route = useRoute()
const loading = ref(false)
const rows = ref([])
const total = ref(0)
const stats = ref({})
const query = reactive({ page: 1, size: 10, keyword: '', type: '', status: '', creditLevel: '' })
let seq = 0

const viewVisible = ref(false)
const detail = ref(null)
const formVisible = ref(false)
const saving = ref(false)
const formRef = ref(null)

const blankForm = () => ({
  id: null,
  name: '',
  type: '合作社',
  contact: '',
  phone: '',
  mainCategory: '',
  supplyCapacity: '',
  address: '',
  certNo: '',
  certExpire: '',
  distanceKm: null,
  creditScore: 80,
  status: '待审核'
})
const form = reactive(blankForm())

const rules = {
  name: [{ required: true, message: '请输入农户/合作社名称', trigger: 'blur' }],
  type: [{ required: true, message: '请选择主体类型', trigger: 'change' }],
  contact: [{ required: true, message: '请输入联系人', trigger: 'blur' }],
  phone: [{ required: true, message: '请输入联系电话', trigger: 'blur' }]
}

const countOf = (type) => {
  const hit = (stats.value.typeCount || []).find((t) => t.name === type)
  return Number(hit?.value || 0)
}

/* 分段控件只表达「主体类型」一个维度，带数量。
   认证状态属于另一个维度，走筛选条下拉 + 顶部「待审核」卡片点击，
   不再和类型混在同一排（同一维度两个入口是上一版最容易被绕晕的地方）。 */
const tabs = computed(() => [
  { key: 'all', label: '全部农户', count: Number(stats.value.total || 0) },
  { key: '合作社', label: '合作社', count: countOf('合作社') },
  { key: '生产基地', label: '生产基地', count: countOf('生产基地') },
  { key: '个体农户', label: '个体农户', count: countOf('个体农户') }
])

const activeTab = computed(() => query.type || 'all')

const creditColor = (v) => {
  const n = Number(v) || 0
  if (n >= 90) return '#22c55e'
  if (n >= 85) return '#f59e0b'
  return '#f97316'
}

const typeTag = (type) =>
  type === '合作社' ? 'tag-green' : type === '生产基地' ? 'tag-blue' : 'tag-gray'

const buildParams = () => {
  const p = { page: query.page, size: query.size }
  if (query.keyword) p.keyword = query.keyword.trim()
  if (query.type) p.type = query.type
  if (query.status) p.status = query.status
  if (query.creditLevel) p.creditLevel = query.creditLevel
  return p
}

const load = async () => {
  const my = ++seq
  loading.value = true
  try {
    const res = await adminApi.farmers(buildParams())
    if (my !== seq) return
    const data = res.data || {}
    rows.value = data.page?.records || []
    total.value = Number(data.page?.total || 0)
    stats.value = data.stats || {}
  } finally {
    if (my === seq) loading.value = false
  }
}

const search = () => {
  query.page = 1
  load()
}

const reset = () => {
  query.keyword = ''
  query.type = ''
  query.status = ''
  query.creditLevel = ''
  query.page = 1
  load()
}

/* 点「待审核」卡片 = 筛出待审核农户：把统计数字变成可执行动作，
   也替代了原来混在类型标签里的「待审核」入口 */
const filterPending = () => {
  query.status = '待审核'
  query.page = 1
  load()
}

const selectTab = (key) => {
  // 只切换类型维度，不隐式清空用户已选的认证状态
  query.type = key === 'all' ? '' : key
  query.page = 1
  load()
}

const onSizeChange = (size) => {
  query.size = size
  query.page = 1
  load()
}

const onPageChange = (page) => {
  query.page = page
  load()
}

const exportRoster = () => {
  const n = exportTableDom(stamped('农户合作社名册'))
  ElMessage.success(n ? `已导出当前页 ${n} 条记录` : '当前没有可导出的数据')
}

const openCreate = () => {
  Object.assign(form, blankForm())
  formVisible.value = true
  formRef.value?.clearValidate()
}

const openEdit = (row) => {
  if (!row) return
  Object.assign(form, blankForm(), {
    id: row.id,
    name: row.name || '',
    type: row.type || '合作社',
    contact: row.contact || '',
    phone: row.phone || '',
    mainCategory: row.mainCategory || '',
    supplyCapacity: row.supplyCapacity || '',
    address: row.address || '',
    certNo: row.certNo || '',
    certExpire: row.certExpire || '',
    distanceKm: row.distanceKm === null || row.distanceKm === undefined ? null : Number(row.distanceKm),
    creditScore: row.creditScore === null || row.creditScore === undefined ? 80 : Number(row.creditScore),
    status: row.status || '待审核'
  })
  viewVisible.value = false
  formVisible.value = true
  formRef.value?.clearValidate()
}

const submit = () => {
  formRef.value.validate(async (valid) => {
    if (!valid) return
    saving.value = true
    try {
      const payload = { ...form }
      if (!payload.id) delete payload.id
      if (payload.certExpire === '') payload.certExpire = null
      await adminApi.saveFarmer(payload)
      ElMessage.success(form.id ? '农户档案已更新' : '农户档案已创建，等待资质审核')
      formVisible.value = false
      await load()
    } catch (e) {
      /* 请求拦截器已提示 */
    } finally {
      saving.value = false
    }
  })
}

const openView = async (row) => {
  detail.value = { ...row }
  viewVisible.value = true
  try {
    const res = await adminApi.farmer(row.id)
    if (res.data) detail.value = res.data
  } catch (e) {
    /* 保留列表数据 */
  }
}

const setStatus = async (id, name, next) => {
  try {
    await adminApi.farmerStatus(id, next)
    ElMessage.success(
      next === '已认证'
        ? `「${name}」已认证通过`
        : next === '待审核'
          ? `「${name}」已转为待审核`
          : `「${name}」已停用`
    )
    viewVisible.value = false
    await load()
  } catch (e) {
    /* 请求拦截器已提示 */
  }
}

const toggleStatus = async (row) => {
  const next = row.status === '已停用' ? '已认证' : '已停用'
  const label = next === '已认证' ? '启用' : '停用'
  try {
    await ElMessageBox.confirm(
      `确定${label}「${row.name}」吗？${next === '已停用' ? '停用后将同步禁用其登录账号。' : ''}`,
      `${label}农户`,
      { type: 'warning', confirmButtonText: '确定', cancelButtonText: '取消' }
    )
  } catch (e) {
    return
  }
  setStatus(row.id, row.name, next)
}

const viewAction = async () => {
  const row = detail.value
  if (!row) return
  const next = row.status === '已认证' ? '已停用' : '已认证'
  const label = row.status === '待审核' ? '认证通过' : row.status === '已停用' ? '启用' : '停用'
  try {
    await ElMessageBox.confirm(
      `确定将「${row.name}」的状态变更为「${next}」吗？`,
      label,
      { type: 'warning', confirmButtonText: '确定', cancelButtonText: '取消' }
    )
  } catch (e) {
    return
  }
  setStatus(row.id, row.name, next)
}

/* 从待办「去审核」跳进来：定位到该农户并打开档案。
   注意不要在 useFocusLocator 的 onMounted 里再触发一次 load()：
   本页 load() 有 seq 竞态守卫，两次并发会让先发的那次结果被丢弃，
   于是"筛到了却拿不到行"，表现为永远提示未找到。这里串行处理。 */
onMounted(async () => {
  const no = String(route.query.focus || '').trim()
  if (no) {
    // 先把编号填进筛选再拉数据：农户可能不在第一页，光靠前端 find 找不到
    query.keyword = no
    query.status = ''
    query.type = ''
    query.creditLevel = ''
    query.page = 1
  }
  await load()
  if (!no) return
  const hit = (rows.value || []).find(
    (r) => String(r.farmerNo || '') === no || String(r.id) === no
  )
  if (hit) openView(hit)
})

useAutoRefresh(load)
</script>

<style scoped>
.table-card {
  /* 不裁切表格：固定列阴影与横向滚动条必须可见（L-040） */
  overflow: visible;
}

.tab-bar {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  padding: 11px 18px;
  border-bottom: 1px solid var(--c-border);
}

.tab-btn {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  height: 29px;
  padding: 0 12px;
  border: none;
  border-radius: 15px;
  background: #f4f7f5;
  color: var(--c-text-2);
  font-size: var(--fs-sm);
  cursor: pointer;
  transition: all 0.16s ease;
}

.tab-btn:hover {
  background: #eaf5ee;
  color: var(--c-primary-dark);
}

.tab-btn.active {
  background: var(--c-primary-soft);
  color: var(--c-primary-dark);
  font-weight: 600;
  box-shadow: inset 0 0 0 1px #bfe8cf;
}

.tab-num {
  font-family: var(--font-num);
  font-size: var(--fs-xs);
  color: var(--c-muted-2);
}

.tab-btn.active .tab-num {
  color: var(--c-primary-dark);
}

.cell-main {
  font-size: var(--fs-base);
  font-weight: 600;
  color: var(--ink);
  line-height: 1.35;
}

.cell-sub {
  font-size: var(--fs-xs);
  color: var(--c-muted-2);
  margin-top: 2px;
}

.credit-cell {
  display: flex;
  align-items: center;
  gap: 7px;
}

.credit-num {
  font-weight: 700;
  font-size: var(--fs-base);
}

.op-links {
  display: flex;
  align-items: center;
}

.link-danger {
  color: var(--danger);
}

.link-danger:hover {
  color: var(--danger);
}

.archive-head {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 13px 15px;
  border-radius: 12px;
  background: linear-gradient(135deg, var(--surface-2), var(--accent-soft));
  border: 1px solid var(--accent-soft);
  margin-bottom: 14px;
}

.archive-avatar {
  width: 42px;
  height: 42px;
  border-radius: 12px;
  display: grid;
  place-items: center;
  font-size: var(--fs-lg);
  font-weight: 700;
  color: #fff;
  background: linear-gradient(135deg, #34d399, var(--accent));
  flex-shrink: 0;
}

.archive-name {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: var(--fs-md);
  font-weight: 700;
  color: var(--ink);
}

.archive-sub {
  font-size: var(--fs-xs);
  color: var(--muted);
  margin-top: 3px;
}

.archive-credit {
  text-align: right;
  flex-shrink: 0;
}

.ac-label {
  font-size: var(--fs-xs);
  color: var(--muted);
}

.ac-value {
  font-family: var(--font-num);
  font-size: var(--fs-xl);
  font-weight: 700;
  line-height: 1.25;
}

.dialog-foot {
  display: flex;
  align-items: center;
  gap: 10px;
}

.dialog-foot .grow {
  flex: 1;
}

.form-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 0 14px;
}

.form-grid .span-2 {
  grid-column: span 2;
}
</style>
