<template>
  <div class="page">
    <div class="page-head">
      <div>
        <h2 class="page-title">库存与采购建议</h2>
        <p class="page-sub">
          入库自动加库存、领用自动扣库存；按近 {{ historyDays }} 天真实领用量算出日均消耗，再给出本周该补多少
        </p>
      </div>
      <div class="page-actions">
        <el-button plain @click="loadAll"><el-icon><Refresh /></el-icon>刷新</el-button>
      </div>
    </div>

    <div class="stat-grid">
      <StatCard label="在库品项" :value="fmtNum(overview.itemCount)" foot="食堂库房" icon="Box" color="teal" />
      <StatCard
        label="需补货品项"
        :value="fmtNum(overview.needCount)"
        foot="撑不到下周"
        icon="Warning"
        :color="overview.needCount ? 'orange' : 'green'"
      />
      <StatCard
        label="本周建议采购"
        :value="fmtNum(suggest.suggestCount)"
        foot="按消耗测算"
        icon="ShoppingCart"
        color="blue"
      />
      <StatCard
        label="建议采购金额"
        :value="'¥' + fmtMoney(suggest.totalAmount)"
        :foot="'按最近入库价估算'"
        icon="Money"
        color="blue"
      />
    </div>

    <div class="card">
      <div class="card-head">
        <div class="card-title">库房</div>
        <div class="card-extra">
          <el-radio-group v-model="tab" size="small">
            <el-radio-button label="stock">在库清单</el-radio-button>
            <el-radio-button label="suggest">采购建议</el-radio-button>
          </el-radio-group>
        </div>
      </div>

      <div class="filter-bar">
        <el-input v-model="keyword" placeholder="品名 / 编号" clearable style="width: 190px">
          <template #prefix><el-icon><Search /></el-icon></template>
        </el-input>
        <el-checkbox v-if="tab === 'stock'" v-model="onlyLow">只看需补货</el-checkbox>
        <div class="filter-spacer"></div>
        <span class="muted small">
          库存只统计入库与领用流水，不做任何"假定消耗"
        </span>
      </div>

      <!-- 在库清单 -->
      <div v-if="tab === 'stock'" class="table-wrap" v-loading="loading">
        <el-table :data="stockRows" style="width: 100%">
          <el-table-column label="品项" min-width="150">
            <template #default="{ row }">
              <span class="bold">{{ row.productName }}</span>
              <span class="muted small"> · {{ row.productNo }}</span>
            </template>
          </el-table-column>
          <el-table-column prop="categoryName" label="品类" width="94" />
          <el-table-column label="在库" width="112" align="right">
            <template #default="{ row }">
              <span class="stock-num" :class="{ low: row.low }">{{ fmtNum(row.quantity) }}</span>
              <span class="muted small"> {{ row.unit }}</span>
            </template>
          </el-table-column>
          <el-table-column label="安全库存" width="128" align="center">
            <template #default="{ row }">
              <!-- 没设过就留空，用占位显示自动兜底值，避免和「已存 0」混淆 -->
              <el-input-number
                :model-value="Number(row.safetyQty || 0) || undefined"
                :min="0"
                :step="10"
                :controls="false"
                size="small"
                style="width: 78px"
                :placeholder="row.safeAuto && Number(row.effectiveSafe) > 0 ? '自动 ' + fmtNum(row.effectiveSafe) : '未设'"
                :title="row.safeAuto && Number(row.effectiveSafe) > 0 ? `未设置时按近 28 天日均消耗 × 3 天自动兜底：${fmtNum(row.effectiveSafe)}` : '点击可设置安全库存'"
                @change="(v) => changeSafety(row, v)"
              />
            </template>
          </el-table-column>
          <el-table-column label="近 7 天领用" width="112" align="right">
            <template #default="{ row }">{{ fmtNum(row.weekOut) }} {{ row.unit }}</template>
          </el-table-column>
          <el-table-column label="近 28 天领用" width="118" align="right">
            <template #default="{ row }">{{ fmtNum(row.monthOut) }} {{ row.unit }}</template>
          </el-table-column>
          <el-table-column label="状态" width="116" align="center">
            <template #default="{ row }">
              <span v-if="row.low" class="tag tag-red">低于安全库存</span>
              <span v-else-if="row.need" class="tag tag-orange">需补货</span>
              <span v-else-if="Number(row.quantity) > 0" class="tag tag-green">正常</span>
              <span v-else class="tag tag-gray">已用完</span>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="86" align="center" fixed="right">
            <template #default="{ row }">
              <span class="link-strong" @click="openOut(row)">出库</span>
            </template>
          </el-table-column>
          <template #empty><div class="empty">库房还没有记录，入库确认后会自动累加</div></template>
        </el-table>
      </div>

      <!-- 采购建议 -->
      <div v-else>
        <div class="table-wrap" v-loading="loading">
          <el-table
            :data="suggestRows"
            style="width: 100%"
            row-key="productId"
            @selection-change="(rows) => (picked = rows)"
          >
            <el-table-column type="selection" width="46" :selectable="(row) => row.hasData" />
            <el-table-column label="品项" min-width="140">
              <template #default="{ row }">
                <span class="bold">{{ row.productName }}</span>
                <span class="muted small"> · {{ row.unit }}</span>
              </template>
            </el-table-column>
            <el-table-column label="在库" width="100" align="right">
              <template #default="{ row }">{{ fmtNum(row.quantity) }}</template>
            </el-table-column>
            <el-table-column label="近 28 天领用" width="118" align="right">
              <template #default="{ row }">{{ fmtNum(row.monthOut) }}</template>
            </el-table-column>
            <el-table-column label="日均消耗" width="120" align="right">
              <template #default="{ row }">
                <template v-if="row.dailyAvg">
                  {{ fmtNum(row.dailyAvg) }}
                  <span v-if="row.dailyBasis === 'in'" class="auto-tip" title="领用流水还没积累，按近 28 天到货量推算（稳态食堂到货量≈消耗量）">按到货</span>
                </template>
                <span v-else class="muted small">—</span>
              </template>
            </el-table-column>
            <el-table-column label="在库可撑" width="104" align="right">
              <template #default="{ row }">
                <span v-if="row.coverDays !== null && row.coverDays !== undefined">{{ fmtNum(row.coverDays) }} 天</span>
                <span v-else class="muted small">—</span>
              </template>
            </el-table-column>
            <el-table-column label="建议采购量" width="118" align="right">
              <template #default="{ row }">
                <span v-if="row.hasData" class="suggest-num">{{ fmtNum(row.suggestQty) }}</span>
                <span v-else-if="row.reason === 'enough'" class="tag tag-green">库存充足</span>
                <span v-else class="muted small">无消耗流水</span>
              </template>
            </el-table-column>
            <el-table-column label="参考单价" width="104" align="right">
              <template #default="{ row }">
                <span v-if="row.hasData">¥{{ fmtMoney(row.price) }}</span>
                <span v-else class="muted small">—</span>
              </template>
            </el-table-column>
            <el-table-column label="预计金额" width="110" align="right">
              <template #default="{ row }">
                <span v-if="row.hasData" class="money">¥{{ fmtMoney(row.amount) }}</span>
                <span v-else class="muted small">—</span>
              </template>
            </el-table-column>
            <template #empty><div class="empty">没有可用数据：需要先有领用出库记录才能测算消耗</div></template>
          </el-table>
        </div>

        <div class="suggest-foot">
          <span class="muted small">
            建议量 = 日均消耗 × {{ suggest.coverDays }} 天 + 安全库存 − 在库；
            安全库存为 0 时按 {{ suggest.autoSafeDays || 3 }} 天用量自动兜底；
            暂无领用流水的 {{ fmtNum(suggest.idleCount ?? suggest.noDataCount) }} 项不测算
          </span>
          <div class="sf-actions">
            <span class="muted small">已选 {{ picked.length }} 项 · 预计 ¥{{ fmtMoney(pickedAmount) }}</span>
            <el-button type="primary" :loading="generating" @click="generateDemands">
              生成采购需求（{{ picked.length }}）
            </el-button>
          </div>
        </div>
      </div>
    </div>

    <!-- 最近出库 -->
    <SectionCard v-if="tab === 'stock'" title="最近出库" no-pad>
      <template #extra>库存扣减流水</template>
      <el-table :data="overview.recentOut || []" size="small" style="width: 100%">
        <el-table-column prop="productName" label="品项" min-width="120" />
        <el-table-column label="数量" width="100" align="right">
          <template #default="{ row }">{{ fmtNum(row.quantity) }} {{ row.unit }}</template>
        </el-table-column>
        <el-table-column prop="reason" label="类型" width="96" align="center" />
        <el-table-column prop="operator" label="经办人" width="110" />
        <el-table-column label="时间" width="140">
          <template #default="{ row }">{{ fmtDate(row.createTime) }}</template>
        </el-table-column>
        <template #empty><div class="empty">还没有出库记录</div></template>
      </el-table>
    </SectionCard>

    <!-- 出库弹窗 -->
    <el-dialog v-model="outDlg" title="出库 / 领用" width="460px">
      <div v-if="current" class="out-box">
        <div class="ob-line">
          <span class="k">品项</span>
          <span class="v">{{ current.productName }}（在库 {{ fmtNum(current.quantity) }}{{ current.unit }}）</span>
        </div>
        <el-form label-position="top">
          <el-form-item :label="'出库数量（' + current.unit + '）'">
            <el-input-number
              v-model="outForm.quantity"
              :min="0.01"
              :max="Number(current.quantity)"
              :step="10"
              :controls="false"
              style="width: 100%"
            />
          </el-form-item>
          <el-form-item label="出库类型">
            <el-radio-group v-model="outForm.reason">
              <el-radio-button label="领用" />
              <el-radio-button label="损耗" />
              <el-radio-button label="退货" />
              <el-radio-button label="盘点调整" />
            </el-radio-group>
          </el-form-item>
          <el-form-item label="备注">
            <el-input v-model="outForm.remark" maxlength="200" placeholder="如：周三午餐领用" />
          </el-form-item>
        </el-form>
      </div>
      <template #footer>
        <el-button @click="outDlg = false">取消</el-button>
        <el-button type="primary" :loading="outSubmitting" @click="submitOut">确认出库</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { purchaserApi } from '@/api'
import StatCard from '@/components/StatCard.vue'
import SectionCard from '@/components/SectionCard.vue'
import { fmtDate, fmtMoney, fmtNum } from '@/utils/format'
import { useAutoRefresh } from '@/utils/useAutoRefresh'

const router = useRouter()
const tab = ref('stock')
const loading = ref(false)
const keyword = ref('')
const onlyLow = ref(false)

const overview = ref({ rows: [], recentOut: [], itemCount: 0, lowCount: 0, needCount: 0 })
const suggest = ref({ items: [], suggestCount: 0, noDataCount: 0, totalAmount: 0, coverDays: 7, historyDays: 28 })
const picked = ref([])
const generating = ref(false)

const historyDays = computed(() => suggest.value.historyDays || 28)

const stockRows = computed(() => {
  const kw = keyword.value.trim()
  return (overview.value.rows || []).filter((r) => {
    if (onlyLow.value && !r.need) return false
    if (!kw) return true
    return (r.productName || '').includes(kw) || (r.productNo || '').includes(kw)
  })
})

const suggestRows = computed(() => {
  const kw = keyword.value.trim()
  return (suggest.value.items || []).filter((r) =>
    !kw ? true : (r.productName || '').includes(kw)
  )
})

const pickedAmount = computed(() =>
  picked.value.reduce((s, r) => s + Number(r.amount || 0), 0).toFixed(2)
)

const loadAll = async () => {
  loading.value = true
  try {
    const [a, b] = await Promise.all([purchaserApi.stock(), purchaserApi.stockSuggestions()])
    overview.value = a.data || {}
    suggest.value = b.data || {}
  } catch (e) {
    /* 错误提示由请求拦截器统一处理 */
  } finally {
    loading.value = false
  }
}

const changeSafety = async (row, v) => {
  const val = Number(v || 0)
  try {
    await purchaserApi.stockSetSafety(row.productId, val)
    ElMessage.success(val > 0 ? `「${row.productName}」安全库存已设为 ${val}${row.unit}` : `「${row.productName}」已改回自动兜底`)
    // 状态/KPI 都按后端口径算，改完直接重拉，避免前端自己拼一套判断
    await loadAll()
  } catch (e) {
    /* 错误提示由请求拦截器统一处理 */
  }
}

const outDlg = ref(false)
const outSubmitting = ref(false)
const current = ref(null)
const outForm = reactive({ quantity: 10, reason: '领用', remark: '' })

const openOut = (row) => {
  current.value = row
  outForm.quantity = Math.min(10, Number(row.quantity) || 1)
  outForm.reason = '领用'
  outForm.remark = ''
  outDlg.value = true
}

const submitOut = async () => {
  if (!current.value) return
  const q = Number(outForm.quantity || 0)
  if (q <= 0) return ElMessage.warning('出库数量必须大于 0')
  if (q > Number(current.value.quantity)) return ElMessage.warning('出库数量不能超过在库数量')
  outSubmitting.value = true
  try {
    const res = await purchaserApi.stockOut({
      productId: current.value.productId,
      quantity: q,
      reason: outForm.reason,
      remark: outForm.remark
    })
    ElMessage.success(res.message || '出库成功')
    outDlg.value = false
    await loadAll()
  } catch (e) {
    /* 错误提示由请求拦截器统一处理 */
  } finally {
    outSubmitting.value = false
  }
}

const generateDemands = async () => {
  if (!picked.value.length) return ElMessage.warning('请先勾选要补货的品项')
  generating.value = true
  try {
    const res = await purchaserApi.stockCreateDemands(picked.value.map((r) => r.productId))
    ElMessage.success(res.message || '已生成采购需求')
    picked.value = []
    router.push('/purchaser/demands')
  } catch (e) {
    /* 错误提示由请求拦截器统一处理 */
  } finally {
    generating.value = false
  }
}

useAutoRefresh(loadAll)
onMounted(loadAll)
</script>

<style scoped>
.stock-num {
  font-weight: 700;
  font-size: var(--fs-md);
}

.stock-num.low {
  color: var(--danger);
}

.suggest-num {
  font-weight: 700;
  color: var(--accent);
}

/* 安全库存自动兜底的提示：不在输入框里，避免误以为已落库 */
.auto-tip {
  display: inline-block;
  margin-left: 4px;
  padding: 0 4px;
  border-radius: 3px;
  font-size: var(--fs-xs);
  color: var(--c-text-3);
  background: var(--c-fill-2);
  cursor: help;
}

.suggest-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 12px 14px;
  border-top: 1px solid var(--c-border);
}

.sf-actions {
  display: flex;
  align-items: center;
  gap: 12px;
}

.ob-line {
  display: flex;
  gap: 10px;
  margin-bottom: 12px;
  padding: 10px 12px;
  border-radius: 8px;
  background: var(--surface-2);
  font-size: var(--fs-sm);
}

.ob-line .k {
  color: var(--c-muted);
  width: 44px;
  flex-shrink: 0;
}
</style>
