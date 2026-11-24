<template>
  <div class="page">
    <div class="page-head">
      <div>
        <h2 class="page-title">需求大厅</h2>
        <p class="page-sub">食堂发布的采购需求都在这里，报价后由采购员横向比价定标，中标即生成采购单</p>
      </div>
      <div class="page-actions">
        <el-button plain @click="load"><el-icon><Refresh /></el-icon>刷新</el-button>
      </div>
    </div>

    <div class="stat-grid">
      <StatCard label="可报价需求" :value="fmtNum(total)" foot="处理中" icon="Tickets" color="blue" />
      <StatCard label="我的报价" :value="fmtNum(myQuotes.length)" foot="累计" icon="EditPen" color="teal" />
      <StatCard
        label="已中标"
        :value="fmtNum(myQuotes.filter((q) => q.status === '中标').length)"
        foot="等待签约发货"
        icon="CircleCheck"
        color="green"
      />
      <StatCard
        label="待定标"
        :value="fmtNum(myQuotes.filter((q) => q.status === '待选').length)"
        foot="已报价未出结果"
        icon="Clock"
        color="orange"
      />
    </div>

    <div class="card">
      <div class="card-head">
        <div class="card-title">采购需求</div>
        <div class="card-extra">
          <el-radio-group v-model="tab" size="small">
            <el-radio-button label="hall">可报价需求</el-radio-button>
            <el-radio-button label="mine">我的报价</el-radio-button>
          </el-radio-group>
        </div>
      </div>

      <div class="filter-bar">
        <el-input
          v-model="keyword"
          placeholder="品名 / 需求号"
          clearable
          style="width: 200px"
          @keyup.enter="search"
        >
          <template #prefix><el-icon><Search /></el-icon></template>
        </el-input>
        <el-button type="primary" @click="search"><el-icon><Search /></el-icon>查询</el-button>
        <el-button plain @click="reset">重置</el-button>
        <div class="filter-spacer"></div>
        <span class="muted small">只有「已认证」农户可以报价</span>
      </div>

      <div v-if="tab === 'hall'" class="table-wrap" v-loading="loading">
        <el-table :data="rows" style="width: 100%">
          <el-table-column label="需求号" min-width="126">
            <template #default="{ row }"><span class="mono">{{ row.demandNo }}</span></template>
          </el-table-column>
          <el-table-column label="品项" min-width="112">
            <template #default="{ row }">
              <span class="bold">{{ row.productName }}</span>
            </template>
          </el-table-column>
          <el-table-column label="需求数量" width="110" align="right">
            <template #default="{ row }">{{ fmtNum(row.quantity) }} {{ row.unit }}</template>
          </el-table-column>
          <el-table-column label="期望单价" width="108" align="right">
            <template #default="{ row }">
              <span class="money">¥{{ fmtMoney(row.expectedPrice) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="食堂" min-width="120" show-overflow-tooltip>
            <template #default="{ row }">{{ row.canteenName || '—' }}</template>
          </el-table-column>
          <el-table-column label="截止时间" width="128">
            <template #default="{ row }">
              <span :class="deadlineClass(row.deadline)">{{ fmtDate(row.deadline) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="已有报价" width="92" align="center">
            <template #default="{ row }">
              <span class="qcnt">{{ row.realQuoteCount || 0 }} 家</span>
            </template>
          </el-table-column>
          <el-table-column label="我的报价" width="112" align="center">
            <template #default="{ row }">
              <span v-if="row.myPrice" class="money">¥{{ fmtMoney(row.myPrice) }}</span>
              <span v-else class="muted small">未报价</span>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="96" align="center" fixed="right">
            <template #default="{ row }">
              <span class="link-strong" @click="openQuote(row)">
                {{ row.myPrice ? '改价' : '报价' }}
              </span>
            </template>
          </el-table-column>
          <template #empty><div class="empty">当前没有可报价的需求</div></template>
        </el-table>
      </div>

      <div v-else class="table-wrap" v-loading="loading">
        <el-table :data="myQuotes" style="width: 100%">
          <el-table-column label="需求号" min-width="126">
            <template #default="{ row }"><span class="mono">{{ row.demandNo }}</span></template>
          </el-table-column>
          <el-table-column prop="productName" label="品项" min-width="100" />
          <el-table-column label="我的报价" width="106" align="right">
            <template #default="{ row }"><span class="money">¥{{ fmtMoney(row.price) }}</span></template>
          </el-table-column>
          <el-table-column label="可供数量" width="106" align="right">
            <template #default="{ row }">{{ fmtNum(row.quantity) }} {{ row.unit }}</template>
          </el-table-column>
          <el-table-column label="承诺送达" width="96" align="center">
            <template #default="{ row }">{{ row.deliveryDays }} 天</template>
          </el-table-column>
          <el-table-column label="食堂" min-width="110" show-overflow-tooltip>
            <template #default="{ row }">{{ row.canteenName || '—' }}</template>
          </el-table-column>
          <el-table-column label="报价状态" width="96" align="center">
            <template #default="{ row }">
              <span class="tag" :class="quoteTagClass(row.status)">{{ row.status }}</span>
            </template>
          </el-table-column>
          <el-table-column label="需求状态" width="96" align="center">
            <template #default="{ row }">{{ row.demandStatus }}</template>
          </el-table-column>
          <template #empty><div class="empty">还没有报过价，去「可报价需求」看看</div></template>
        </el-table>
      </div>

      <div class="pager" v-if="tab === 'hall'">
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

    <el-dialog v-model="dlg" title="提交报价" width="520px">
      <div v-if="current" class="qd">
        <div class="qd-head">
          <span class="qd-name">{{ current.productName }}</span>
          <span class="muted small">{{ current.demandNo }} · {{ fmtNum(current.quantity) }}{{ current.unit }}</span>
        </div>
        <div class="qd-meta">
          <span>期望单价 ¥{{ fmtMoney(current.expectedPrice) }}</span>
          <span>食堂：{{ current.canteenName || '—' }}</span>
          <span>截止：{{ fmtDate(current.deadline) }}</span>
        </div>
        <el-form label-position="top">
          <el-form-item :label="'报价单价（元/' + current.unit + '）'">
            <el-input-number
              v-model="form.price"
              :min="0.01"
              :step="0.1"
              :precision="2"
              :controls="false"
              style="width: 100%"
            />
          </el-form-item>
          <el-form-item :label="'可供数量（' + current.unit + '）'">
            <el-input-number
              v-model="form.quantity"
              :min="1"
              :max="Number(current.quantity) || 1"
              :step="10"
              :controls="false"
              style="width: 100%"
            />
          </el-form-item>
          <el-form-item label="承诺送达（天）">
            <el-input-number v-model="form.deliveryDays" :min="1" :max="15" style="width: 100%" />
          </el-form-item>
          <el-form-item label="质量承诺">
            <el-input v-model="form.qualityNote" maxlength="200" placeholder="如：可提供产地证明、农残自检报告" />
          </el-form-item>
        </el-form>
      </div>
      <template #footer>
        <el-button @click="dlg = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="submitQuote">提交报价</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { farmerApi } from '@/api'
import StatCard from '@/components/StatCard.vue'
import { fmtDate, fmtMoney, fmtNum } from '@/utils/format'
import { useAutoRefresh } from '@/utils/useAutoRefresh'

const tab = ref('hall')
const loading = ref(false)
const rows = ref([])
const myQuotes = ref([])
const total = ref(0)
const pages = ref(1)
const page = ref(1)
const size = ref(10)
const keyword = ref('')

const dlg = ref(false)
const submitting = ref(false)
const current = ref(null)
const form = reactive({ price: 0, quantity: 0, deliveryDays: 2, qualityNote: '' })

const quoteTagClass = (s) => {
  if (s === '中标') return 'tag-green'
  if (s === '落选') return 'tag-gray'
  return 'tag-orange'
}

const deadlineClass = (d) => {
  if (!d) return 'muted small'
  const diff = new Date(d).getTime() - Date.now()
  if (diff < 12 * 3600 * 1000) return 'due-soon'
  return ''
}

const load = async () => {
  loading.value = true
  try {
    const res = await farmerApi.demandHall({
      page: page.value,
      size: size.value,
      keyword: keyword.value.trim() || undefined
    })
    const payload = res.data || {}
    rows.value = payload.page?.records || []
    total.value = Number(payload.page?.total || 0)
    pages.value = Number(payload.page?.pages || 1)
    myQuotes.value = payload.myQuotes || []
  } catch (e) {
    /* 错误提示由请求拦截器统一处理 */
  } finally {
    loading.value = false
  }
}

const search = () => {
  page.value = 1
  load()
}

const reset = () => {
  keyword.value = ''
  page.value = 1
  load()
}

const openQuote = (row) => {
  current.value = row
  form.price = Number(row.myPrice || row.expectedPrice || 0)
  form.quantity = Number(row.quantity || 0)
  form.deliveryDays = 2
  form.qualityNote = '按需求标准供货，可提供产地证明'
  dlg.value = true
}

const submitQuote = async () => {
  if (!current.value) return
  if (!form.price || form.price <= 0) return ElMessage.warning('请填写报价单价')
  if (!form.quantity || form.quantity <= 0) return ElMessage.warning('请填写可供数量')
  if (Number(form.quantity) > Number(current.value.quantity)) {
    return ElMessage.warning(`需求数量为 ${fmtNum(current.value.quantity)}${current.value.unit}，可供数量不能超过它`)
  }
  submitting.value = true
  try {
    const res = await farmerApi.submitQuote({
      demandId: current.value.id,
      price: form.price,
      quantity: form.quantity,
      deliveryDays: form.deliveryDays,
      qualityNote: form.qualityNote
    })
    ElMessage.success(res.message || '报价已提交')
    dlg.value = false
    load()
  } catch (e) {
    /* 错误提示由请求拦截器统一处理 */
  } finally {
    submitting.value = false
  }
}

useAutoRefresh(load)
onMounted(load)
</script>

<style scoped>
.qcnt {
  font-weight: 700;
  color: var(--accent);
}

.due-soon {
  color: var(--danger);
  font-weight: 700;
}

.qd-head {
  display: flex;
  align-items: baseline;
  gap: 10px;
  margin-bottom: 6px;
}

.qd-name {
  font-size: var(--fs-lg);
  font-weight: 700;
}

.qd-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 14px;
  margin-bottom: 14px;
  padding: 10px 12px;
  border-radius: 8px;
  background: var(--surface-2);
  font-size: var(--fs-sm);
  color: var(--c-muted);
}
</style>
