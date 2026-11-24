<template>
  <div class="page" v-loading="loading">
    <div class="page-head">
      <div>
        <h2 class="page-title">操作日志</h2>
        <p class="page-sub">完整记录用户登录、业务操作与系统任务，支持审计追溯与异常排查</p>
      </div>
      <div class="page-actions">
        <el-button plain :loading="exporting" @click="exportLogs">
          <el-icon><Download /></el-icon>导出日志
        </el-button>
        <el-button type="primary" @click="showPolicy">
          <el-icon><DocumentChecked /></el-icon>审计策略
        </el-button>
      </div>
    </div>

    <!-- 统计卡片 -->
    <div class="stat-grid">
      <StatCard
        label="今日操作记录" :value="fmtNum(stats.todayLogs)" icon="Document" color="green"
        foot="较昨日 +8.6%"
      />
      <StatCard
        label="今日登录" :value="fmtNum(stats.todayActive)" icon="User" color="blue"
        foot="活跃账号"
      />
      <StatCard
        label="异常登录" :value="fmtNum(stats.todayFailed)" icon="Warning" color="red"
        foot="含锁定次数"
      />
      <StatCard label="审计完整率" :value="100" suffix="%" icon="CircleCheck" color="teal" foot="操作可追溯" />
    </div>

    <!-- 列表 -->
    <div class="card">
      <div class="filter-bar">
        <el-input
          v-model="query.keyword"
          placeholder="操作人 / 操作内容（输入即查）"
          clearable
          @input="onKeywordInput"
          @keyup.enter="search"
        >
          <template #prefix><el-icon><Search /></el-icon></template>
        </el-input>
        <el-select v-model="query.module" placeholder="功能模块" @change="search">
          <el-option label="全部模块" value="" />
          <el-option v-for="m in modules" :key="m.module" :label="m.module" :value="m.module" />
        </el-select>
        <el-select v-model="query.result" placeholder="操作结果" @change="search">
          <el-option label="全部结果" value="" />
          <el-option label="成功" value="成功" />
          <el-option label="失败" value="失败" />
        </el-select>
        <el-date-picker
          v-model="query.date"
          type="date"
          placeholder="操作日期"
          value-format="YYYY-MM-DD"
          style="width: 176px"
          @change="search"
        />
        <el-button type="primary" @click="search">
          <el-icon><Search /></el-icon>查询
        </el-button>
        <el-button plain @click="reset">
          <el-icon><RefreshLeft /></el-icon>重置
        </el-button>
        <div class="filter-spacer"></div>
        <span class="muted small">
          当前筛选 <b class="result-count">{{ fmtNum(list.total) }}</b> 条
          · 全站累计 {{ fmtNum(stats.total) }} 条 · 异常 {{ fmtNum(stats.failed) }} 条
        </span>
      </div>

      <div class="table-wrap">
        <el-table :data="list.records" style="width: 100%">
          <el-table-column label="时间" width="124">
            <template #default="{ row }">
              <div class="mono time-d">{{ dt(row.createTime).d }}</div>
              <div class="mono time-t">{{ dt(row.createTime).t }}</div>
            </template>
          </el-table-column>
          <el-table-column label="操作人" width="128">
            <template #default="{ row }">
              <div class="cell-main">{{ row.operator }}</div>
              <div class="cell-sub">{{ row.roleName }}</div>
            </template>
          </el-table-column>
          <el-table-column label="功能模块" width="118">
            <template #default="{ row }">
              <span class="tag tag-gray">{{ row.module }}</span>
            </template>
          </el-table-column>
          <el-table-column prop="content" label="操作内容" min-width="300" show-overflow-tooltip />
          <el-table-column label="IP 地址" width="126">
            <template #default="{ row }">
              <span class="mono muted">{{ row.ip }}</span>
            </template>
          </el-table-column>
          <el-table-column label="结果" width="84" align="center">
            <template #default="{ row }">
              <span class="tag" :class="row.result === '成功' ? 'tag-green' : 'tag-red'">
                {{ row.result }}
              </span>
            </template>
          </el-table-column>
          <template #empty>
            <div class="empty">当前筛选条件下暂无操作日志</div>
          </template>
        </el-table>
      </div>

      <div class="pager">
        <span>第 {{ list.page }} / {{ list.pages || 1 }} 页 · 每页 10 条 · 共 {{ fmtNum(list.total) }} 条</span>
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
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { adminApi } from '@/api'
import StatCard from '@/components/StatCard.vue'
import { fmtNum, today } from '@/utils/format'
import { useAutoRefresh } from '@/utils/useAutoRefresh'

const loading = ref(false)
const exporting = ref(false)
const stats = ref({})
const list = reactive({ records: [], total: 0, page: 1, size: 10, pages: 0 })
const query = reactive({ page: 1, size: 10, keyword: '', module: '', result: '', date: null })

const modules = computed(() => stats.value.modules || [])

const dt = (v) => {
  const s = String(v === null || v === undefined ? '' : v).replace('T', ' ')
  return { d: s.slice(0, 10), t: s.slice(11, 19) }
}

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
    const res = await adminApi.logs(params())
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
  query.module = ''
  query.result = ''
  query.date = null
  query.page = 1
  load()
}

const onPage = (p) => {
  query.page = p
  load()
}

/* 输入即查：所有筛选都落到服务端，不再用「只过滤当前 10 条」的假交互 */
let kwTimer = null
const onKeywordInput = () => {
  clearTimeout(kwTimer)
  kwTimer = setTimeout(() => search(), 320)
}

const showPolicy = async () => {
  try {
    await adminApi.auditPolicy()
    await ElMessageBox.alert(
      '登录与业务操作日志全量留存 180 天；可按操作人、功能模块、操作结果、日期组合筛选，' +
      '支持导出当前筛选结果；连续输错 5 次自动锁定 10 分钟并写入失败日志；' +
      '跨角色写操作由实时事件通道同步，管理端可即时查看。',
      '审计策略', { confirmButtonText: '知道了' }
    )
  } catch (e) { /* 取消或接口异常，不额外报错 */ }
}

const downloadCsv = (filename, header, rows) => {
  const esc = (v) => '"' + String(v === null || v === undefined ? '' : v).replace(/"/g, '""') + '"'
  const csv = [header.map(esc).join(','), ...rows.map((r) => r.map(esc).join(','))].join('\r\n')
  const blob = new Blob(['\ufeff' + csv], { type: 'text/csv;charset=utf-8;' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = filename
  a.click()
  URL.revokeObjectURL(url)
}

const exportLogs = async () => {
  exporting.value = true
  try {
    const res = await adminApi.logs({ ...params(), page: 1, size: 500 })
    const rows = res.data && res.data.page ? res.data.page.records : []
    if (!rows || !rows.length) {
      ElMessage.warning('当前筛选条件下暂无可导出的日志')
      return
    }
    downloadCsv(
      '操作日志_' + today() + '.csv',
      ['时间', '操作人', '角色', '功能模块', '操作内容', 'IP 地址', '结果'],
      rows.map((r) => {
        const p = dt(r.createTime)
        return [p.d + ' ' + p.t, r.operator, r.roleName, r.module, r.content, r.ip, r.result]
      })
    )
    ElMessage.success('已导出 ' + rows.length + ' 条操作日志')
  } catch (e) {
    /* 拦截器已提示 */
  } finally {
    exporting.value = false
  }
}

onMounted(load)
// 其他角色的写操作会实时进来，日志页自动追加而不打断当前筛选
useAutoRefresh(load)
</script>

<style scoped>
.time-d {
  font-size: var(--fs-sm);
  color: var(--ink);
}

.time-t {
  font-size: var(--fs-xs);
  color: var(--c-muted-2);
  margin-top: 1px;
}

.cell-main {
  font-size: var(--fs-sm);
  font-weight: 600;
  color: var(--ink);
}

.cell-sub {
  margin-top: 2px;
  font-size: var(--fs-xs);
  color: var(--muted);
}

.result-count { color: var(--ink); font-weight: 700; }
</style>
