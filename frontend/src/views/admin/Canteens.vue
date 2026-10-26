<template>
  <div class="page" v-loading="loading">
    <div class="page-head">
      <div>
        <h2 class="page-title">食堂档案管理</h2>
        <p class="page-sub">维护各校区食堂基础信息、采购经理与供应商关联关系</p>
      </div>
      <div class="page-actions">
        <el-button plain @click="exportRoster"><el-icon><Download /></el-icon>导出名册</el-button>
        <el-button type="primary" @click="openCreate"><el-icon><Plus /></el-icon>新增食堂</el-button>
      </div>
    </div>

    <!-- 统计卡片 -->
    <div class="stat-grid">
      <StatCard
        label="合作食堂" :value="fmtNum(stats.active)" icon="OfficeBuilding"
        :foot="'校区数量 ' + (stats.campuses || 0)" color="green"
      />
      <StatCard
        label="日均供餐（人次）" :value="fmtNum(stats.dailyMeals)" icon="Bowl"
        :foot="(stats.active || 0) + ' 个食堂合计'" color="blue"
      />
      <StatCard
        label="本月采购订单" :value="fmtNum(stats.monthOrders)" icon="Tickets"
        foot="较上月 +26" color="orange"
      />
      <StatCard
        label="履约合格率" :value="100" suffix="%" icon="CircleCheck"
        foot="本月" color="teal"
      />
    </div>

    <div class="card table-card">
      <!-- 筛选条 -->
      <div class="filter-bar">
        <el-input
          v-model="query.keyword" placeholder="搜索食堂名称 / 采购经理" clearable
          style="width: 232px" @keyup.enter="search" @clear="search"
        >
          <template #prefix><el-icon><Search /></el-icon></template>
        </el-input>
        <el-select v-model="query.campus" placeholder="所属校区" clearable style="width: 140px" @change="search">
          <el-option label="全部校区" value="" />
          <el-option v-for="c in CAMPUSES" :key="c" :label="c" :value="c" />
        </el-select>
        <el-select v-model="query.status" placeholder="运行状态" clearable style="width: 132px" @change="search">
          <el-option label="全部状态" value="" />
          <el-option label="正常" value="正常" />
          <el-option label="停用" value="停用" />
        </el-select>
        <div class="filter-spacer"></div>
        <el-button plain @click="reset"><el-icon><Refresh /></el-icon>重置</el-button>
        <el-button type="primary" @click="search"><el-icon><Search /></el-icon>查询</el-button>
      </div>

      <div class="table-wrap">
        <el-table :data="rows" style="width: 100%" empty-text="暂无符合条件的食堂档案">
          <el-table-column label="食堂名称" min-width="196">
            <template #default="{ row }">
              <div class="cell-main">{{ row.name }}</div>
              <div class="cell-sub mono">{{ row.canteenNo }}</div>
            </template>
          </el-table-column>
          <el-table-column label="所属校区" width="102">
            <template #default="{ row }">
              <span class="tag" :class="row.campus === '东校区' ? 'tag-teal' : 'tag-blue'">{{ row.campus }}</span>
            </template>
          </el-table-column>
          <el-table-column prop="manager" label="采购经理" width="94">
            <template #default="{ row }">{{ row.manager || '—' }}</template>
          </el-table-column>
          <el-table-column label="联系电话" width="122">
            <template #default="{ row }"><span class="mono">{{ row.phone || '—' }}</span></template>
          </el-table-column>
          <el-table-column label="供餐规模" width="128">
            <template #default="{ row }">
              <span class="money">{{ fmtNum(row.scale) }}</span>
              <span class="small muted"> 人次/日</span>
            </template>
          </el-table-column>
          <el-table-column label="合作供应商" width="106">
            <template #default="{ row }">
              <span class="money">{{ row.supplierCount ?? 0 }}</span>
              <span class="small muted"> 家</span>
            </template>
          </el-table-column>
          <el-table-column label="本月订单" width="96" align="right">
            <template #default="{ row }"><span class="mono bold">{{ fmtNum(row.monthlyOrders) }}</span></template>
          </el-table-column>
          <el-table-column label="状态" width="90">
            <template #default="{ row }">
              <span class="tag tag-dot" :class="row.status === '正常' ? 'tag-green' : 'tag-gray'">{{ row.status }}</span>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="176" fixed="right">
            <template #default="{ row }">
              <div class="op-links">
                <span class="link" @click="openView(row)">查看</span>
                <span class="link" @click="openEdit(row)">编辑</span>
                <span class="link" @click="goOrders(row)">采购记录</span>
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
    <el-dialog v-model="viewVisible" title="食堂档案" width="700px" :close-on-click-modal="false">
      <div v-if="detail" class="archive">
        <div class="archive-head">
          <div class="archive-avatar">{{ (detail.name || '食').slice(0, 1) }}</div>
          <div class="grow">
            <div class="archive-name">
              {{ detail.name }}
              <span class="tag tag-dot" :class="detail.status === '正常' ? 'tag-green' : 'tag-gray'">
                {{ detail.status }}
              </span>
            </div>
            <div class="archive-sub mono">{{ detail.canteenNo }} · {{ detail.campus }} · 采购经理 {{ detail.manager || '—' }}</div>
          </div>
          <div class="archive-metric">
            <div class="am-label">日均供餐</div>
            <div class="am-value">{{ fmtNum(detail.scale) }}<small>人次</small></div>
          </div>
        </div>

        <el-descriptions :column="2" border size="small">
          <el-descriptions-item label="食堂编号">{{ detail.canteenNo || '—' }}</el-descriptions-item>
          <el-descriptions-item label="所属校区">{{ detail.campus || '—' }}</el-descriptions-item>
          <el-descriptions-item label="采购经理">{{ detail.manager || '—' }}</el-descriptions-item>
          <el-descriptions-item label="联系电话">{{ detail.phone || '—' }}</el-descriptions-item>
          <el-descriptions-item label="供餐规模">{{ fmtNum(detail.scale) }} 人次/日</el-descriptions-item>
          <el-descriptions-item label="合作供应商">{{ detail.supplierCount ?? 0 }} 家</el-descriptions-item>
          <el-descriptions-item label="本月采购订单">{{ fmtNum(detail.monthlyOrders) }} 笔</el-descriptions-item>
          <el-descriptions-item label="本月采购金额">
            <span class="money">¥ {{ fmtMoney(detail.monthlyAmount) }}</span>
          </el-descriptions-item>
          <el-descriptions-item label="运行状态">{{ detail.status || '—' }}</el-descriptions-item>
          <el-descriptions-item label="建档时间">{{ fmtDay(detail.createTime) }}</el-descriptions-item>
        </el-descriptions>
      </div>
      <template #footer>
        <div class="dialog-foot">
          <el-button @click="viewVisible = false">关闭</el-button>
          <div class="grow"></div>
          <el-button plain @click="detail && goOrders(detail)">采购记录</el-button>
          <el-button type="primary" @click="openEdit(detail)">编辑档案</el-button>
        </div>
      </template>
    </el-dialog>

    <!-- 新增 / 编辑 -->
    <el-dialog
      v-model="formVisible" :title="form.id ? '编辑食堂档案' : '新增食堂'" width="600px"
      :close-on-click-modal="false"
    >
      <el-form ref="formRef" :model="form" :rules="rules" label-width="102px">
        <div class="form-grid">
          <el-form-item label="食堂名称" prop="name">
            <el-input v-model="form.name" placeholder="如：第一食堂" maxlength="30" />
          </el-form-item>
          <el-form-item label="所属校区" prop="campus">
            <el-select v-model="form.campus" placeholder="请选择校区" style="width: 100%">
              <el-option v-for="c in CAMPUSES" :key="c" :label="c" :value="c" />
            </el-select>
          </el-form-item>
          <el-form-item label="采购经理">
            <el-input v-model="form.manager" placeholder="请输入采购经理" maxlength="20" />
          </el-form-item>
          <el-form-item label="联系电话">
            <el-input v-model="form.phone" placeholder="请输入联系电话" maxlength="20" />
          </el-form-item>
          <el-form-item label="供餐规模">
            <el-input-number
              v-model="form.scale" :min="0" :max="100000" :step="100"
              controls-position="right" style="width: 100%"
            />
          </el-form-item>
          <el-form-item label="合作供应商数">
            <el-input-number
              v-model="form.supplierCount" :min="0" :max="999"
              controls-position="right" style="width: 100%"
            />
          </el-form-item>
          <el-form-item label="运行状态" class="span-2">
            <el-radio-group v-model="form.status">
              <el-radio value="正常">正常</el-radio>
              <el-radio value="停用">停用</el-radio>
            </el-radio-group>
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
import { onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { adminApi } from '@/api'
import StatCard from '@/components/StatCard.vue'
import { fmtDay, fmtMoney, fmtNum } from '@/utils/format'

const CAMPUSES = ['主校区', '东校区']

const router = useRouter()
const loading = ref(false)
const rows = ref([])
const total = ref(0)
const stats = ref({})
const query = reactive({ page: 1, size: 10, keyword: '', campus: '', status: '' })
let seq = 0

const viewVisible = ref(false)
const detail = ref(null)
const formVisible = ref(false)
const saving = ref(false)
const formRef = ref(null)

const blankForm = () => ({
  id: null,
  name: '',
  campus: '主校区',
  manager: '',
  phone: '',
  scale: 1000,
  supplierCount: 0,
  status: '正常'
})
const form = reactive(blankForm())

const rules = {
  name: [{ required: true, message: '请输入食堂名称', trigger: 'blur' }],
  campus: [{ required: true, message: '请选择所属校区', trigger: 'change' }]
}

const buildParams = () => {
  const p = { page: query.page, size: query.size }
  if (query.keyword) p.keyword = query.keyword.trim()
  if (query.campus) p.campus = query.campus
  if (query.status) p.status = query.status
  return p
}

const load = async () => {
  const my = ++seq
  loading.value = true
  try {
    const res = await adminApi.canteens(buildParams())
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
  query.campus = ''
  query.status = ''
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
  ElMessage.success(`已导出《食堂名册》，共 ${total.value} 条记录`)
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
    campus: row.campus || '主校区',
    manager: row.manager || '',
    phone: row.phone || '',
    scale: row.scale === null || row.scale === undefined ? 0 : Number(row.scale),
    supplierCount: row.supplierCount === null || row.supplierCount === undefined ? 0 : Number(row.supplierCount),
    status: row.status || '正常'
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
      await adminApi.saveCanteen(payload)
      ElMessage.success(form.id ? '食堂档案已更新' : '食堂已创建')
      formVisible.value = false
      await load()
    } catch (e) {
      /* 请求拦截器已提示 */
    } finally {
      saving.value = false
    }
  })
}

const openView = (row) => {
  detail.value = { ...row }
  viewVisible.value = true
}

const goOrders = (row) => {
  router.push('/admin/orders')
  ElMessage.success(`正在查看「${row.name}」的采购记录`)
}

onMounted(load)
</script>

<style scoped>
.table-card {
  overflow: hidden;
}

.cell-main {
  font-size: 13px;
  font-weight: 600;
  color: #1b2a24;
  line-height: 1.35;
}

.cell-sub {
  font-size: 11.5px;
  color: var(--c-muted-2);
  margin-top: 2px;
}

.op-links {
  display: flex;
  align-items: center;
}

.archive-head {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 13px 15px;
  border-radius: 12px;
  background: linear-gradient(135deg, #f0fdf4, #e8f7ee);
  border: 1px solid #d6f0e0;
  margin-bottom: 14px;
}

.archive-avatar {
  width: 42px;
  height: 42px;
  border-radius: 12px;
  display: grid;
  place-items: center;
  font-size: 17px;
  font-weight: 700;
  color: #fff;
  background: linear-gradient(135deg, #34d399, #15803d);
  flex-shrink: 0;
}

.archive-name {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 15px;
  font-weight: 700;
  color: #14532d;
}

.archive-sub {
  font-size: 11.5px;
  color: #4b7a60;
  margin-top: 3px;
}

.archive-metric {
  text-align: right;
  flex-shrink: 0;
}

.am-label {
  font-size: 11px;
  color: #4b7a60;
}

.am-value {
  font-family: var(--font-num);
  font-size: 21px;
  font-weight: 700;
  color: #15803d;
  line-height: 1.25;
}

.am-value small {
  font-size: 11px;
  font-weight: 500;
  color: #4b7a60;
  margin-left: 2px;
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
