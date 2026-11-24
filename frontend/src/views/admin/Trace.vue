<template>
  <div class="page" v-loading="loading">
    <div class="page-head">
      <div>
        <h2 class="page-title">批次溯源查询</h2>
        <p class="page-sub">一扫即溯：从田间到餐桌全链路批次信息、质检报告与哈希存证</p>
      </div>
      <div class="page-actions">
        <el-button plain @click="scanQuery">
          <el-icon><FullScreen /></el-icon>扫码查询
        </el-button>
        <el-button type="primary" @click="exportReport">
          <el-icon><Document /></el-icon>溯源报告
        </el-button>
      </div>
    </div>

    <!-- 搜索卡 -->
    <div class="card card-pad search-card">
      <div class="search-row">
        <el-input
          ref="searchInput"
          v-model="batchNo"
          class="big-input"
          size="large"
          placeholder="输入批次号，如 PC20261025F000024157"
          clearable
          @keyup.enter="doQuery(batchNo)"
        >
          <template #prefix><el-icon><Search /></el-icon></template>
        </el-input>
        <el-button type="primary" size="large" :loading="querying" @click="doQuery(batchNo)">
          <el-icon><Search /></el-icon>查询溯源
        </el-button>
      </div>

      <div class="recent-row">
        <span class="recent-label">
          <el-icon><Clock /></el-icon>最近批次
        </span>
        <div class="chips">
          <span
            v-for="r in recent"
            :key="r.batchNo"
            class="chip"
            :class="{ on: r.batchNo === currentBatch }"
            @click="doQuery(r.batchNo)"
          >
            <span class="chip-no mono">{{ r.batchNo }}</span>
            <span class="chip-meta">{{ r.productName }} · {{ r.farmerName }}</span>
          </span>
          <span v-if="!recent.length" class="muted small">暂无最近批次</span>
        </div>
      </div>
    </div>

    <!-- 溯源结果 -->
    <TracePanel v-if="trace" :trace="trace" />

    <!-- 空态 -->
    <div v-else class="card empty-card">
      <div class="empty-icon"><el-icon><Aim /></el-icon></div>
      <div class="empty-title">输入批次号或扫描二维码查询</div>
      <div class="empty-desc">
        支持按批次号精确查询，展示产地、质检报告、冷链配送、入库与结算的全链路信息及哈希存证
      </div>
      <div v-if="errorMsg" class="empty-err">
        <el-icon><WarningFilled /></el-icon>{{ errorMsg }}
      </div>
      <div v-if="recent.length" class="recent-grid">
        <div v-for="r in recent" :key="r.batchNo" class="recent-card" @click="doQuery(r.batchNo)">
          <div class="rc-no mono">{{ r.batchNo }}</div>
          <div class="rc-main">{{ r.productName }}</div>
          <div class="rc-sub">{{ r.farmerName }}</div>
          <div class="rc-sub muted">{{ r.orderNo }} · {{ r.createTime }}</div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
// 该组件被四个角色的溯源路由共用，必须走公共接口，不能硬编码管理员前缀
import { commonApi } from '@/api'
import TracePanel from '@/components/TracePanel.vue'
import { useAutoRefresh } from '@/utils/useAutoRefresh'
import { exportTextReport, stamped } from '@/utils/export'

const loading = ref(false)
const route = useRoute()
const querying = ref(false)
const batchNo = ref('')
const recent = ref([])
const trace = ref(null)
const errorMsg = ref('')
const searchInput = ref(null)

const currentBatch = computed(() => (trace.value ? trace.value.batchNo : ''))

const doQuery = async (no) => {
  const bn = String(no === null || no === undefined ? '' : no).trim()
  if (!bn) {
    ElMessage.warning('请输入批次号后再查询')
    return
  }
  querying.value = true
  errorMsg.value = ''
  try {
    const res = await commonApi.trace(bn)
    const d = res.data || {}
    if (d.recent) recent.value = d.recent
    if (d.trace) {
      trace.value = d.trace
      batchNo.value = d.trace.batchNo
    } else {
      trace.value = null
      errorMsg.value = '未找到批次【' + bn + '】的溯源信息，请确认批次号是否正确'
    }
  } catch (e) {
    trace.value = null
    errorMsg.value = '未找到批次【' + bn + '】的溯源信息，请确认批次号是否正确'
  } finally {
    querying.value = false
  }
}

const scanQuery = () => {
  // 扫码枪本质是把批次号敲进输入框；这里如实聚焦输入框，不再假称唤起摄像头
  if (searchInput.value && searchInput.value.focus) searchInput.value.focus()
  ElMessage.info('已聚焦批次号输入框：请用扫码枪扫描，或粘贴二维码中的批次号后回车')
}

const exportReport = () => {
  const t = trace.value
  if (!t) {
    ElMessage.warning('请先查询批次溯源信息')
    return
  }
  const lines = [
    '批次号：' + (t.batchNo || '—'),
    '溯源状态：' + (t.complete ? '链路完整' : '待完成入库'),
    '',
    '— 批次基础信息 —',
    `产品：${t.productName || '—'}（${t.categoryName || '—'}）`,
    `供货农户：${t.farmerName || '—'} · 信用 ${t.creditScore ?? '—'} 分`,
    `收货食堂：${t.canteenName || '—'}${t.campus ? '（' + t.campus + '）' : ''}`,
    `下单数量：${t.orderQuantity ?? '—'} ${t.unit || 'kg'} · 实收：${t.receiveQuantity ?? '待入库'}`,
    `成交单价：¥ ${t.unitPrice ?? '—'} / ${t.unit || 'kg'}`,
    `关联订单：${t.orderNo || '—'} · 产地：${t.farmerAddress || '—'}`,
    '',
    '— 质检结论 —',
    `结论：${t.qualityResult || '—'} · 质检员：${t.inspector || '—'} · 时间：${t.inspectTime || '—'}`,
    ...(t.qualityItems || []).map(it => `  · ${it.name || it.item || '项目'}：${it.ok === false ? '不合格' : '合格'}${it.value ? '（' + it.value + '）' : ''}`),
    '',
    '— 链路节点 —',
    ...(t.nodes || []).map((n, i) => `  ${i + 1}. ${n.title || n.name || '节点'} · ${n.time || ''} ${n.desc || ''}`),
    '',
    '存证哈希（SHA-256）：' + (t.hash || '—')
  ]
  exportTextReport(stamped('溯源报告_' + t.batchNo, 'txt'), '批次溯源报告 · ' + t.batchNo, lines)
  ElMessage.success('溯源报告已下载：' + t.batchNo)
}

/* 实时刷新只更新「最近批次」，不覆盖用户正在查看的溯源详情 */
const loadRecent = async () => {
  try {
    const res = await commonApi.trace()
    recent.value = res.data?.recent || []
  } catch (e) { /* 拦截器已提示 */ }
}
useAutoRefresh(loadRecent)

onMounted(async () => {
  loading.value = true
  try {
    const res = await commonApi.trace()
    const d = res.data || {}
    recent.value = d.recent || []
    // 从单据工作台「查看溯源记录」带 ?focus=<批次号或订单号> 进来时，直接查这一批；
    // 否则落到最近一批，不让用户面对空白搜索框
    const focusNo = String(route.query.focus || '').trim()
    if (focusNo) {
      batchNo.value = focusNo
      await doQuery(focusNo)
    } else if (recent.value.length) {
      await doQuery(recent.value[0].batchNo)
    }
  } catch (e) {
    /* 拦截器已提示 */
  } finally {
    loading.value = false
  }
})
</script>

<style scoped>
.search-card {
  margin-bottom: 14px;
}

.search-row {
  display: flex;
  align-items: center;
  gap: 10px;
}

.big-input {
  flex: 1;
  min-width: 0;
}

.big-input :deep(.el-input__wrapper) {
  height: 44px;
  padding: 0 14px;
  border-radius: 10px;
}

.big-input :deep(.el-input__inner) {
  font-size: var(--fs-base);
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  letter-spacing: 0.2px;
}

.search-row :deep(.el-button--large) {
  height: 44px;
  padding: 0 22px;
  border-radius: 10px;
}

.recent-row {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  margin-top: 12px;
  padding-top: 12px;
  border-top: 1px dashed var(--c-border);
}

.recent-label {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  flex-shrink: 0;
  font-size: var(--fs-sm);
  color: var(--c-muted);
  padding-top: 5px;
}

.chips {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.chip {
  display: inline-flex;
  flex-direction: column;
  gap: 1px;
  padding: 5px 10px;
  border-radius: 8px;
  background: var(--surface-2);
  border: 1px solid #eaeff0;
  cursor: pointer;
  transition: all 0.16s;
}

.chip:hover {
  border-color: var(--accent-soft);
  background: var(--surface-2);
}

.chip.on {
  border-color: #86efac;
  background: var(--accent-soft);
  box-shadow: 0 0 0 2px rgba(34, 197, 94, 0.1);
}

.chip-no {
  font-size: var(--fs-xs);
  font-weight: 600;
  color: var(--accent);
}

.chip-meta {
  font-size: var(--fs-2xs);
  color: var(--c-muted-2);
}

/* 空态 */
.empty-card {
  padding: 46px 24px 40px;
  text-align: center;
}

.empty-icon {
  width: 58px;
  height: 58px;
  margin: 0 auto 14px;
  border-radius: 18px;
  display: grid;
  place-items: center;
  background: linear-gradient(135deg, var(--accent-soft), var(--accent-soft));
  color: var(--accent);
  font-size: var(--fs-display);
}

.empty-title {
  font-size: var(--fs-md);
  font-weight: 700;
  color: var(--ink);
}

.empty-desc {
  max-width: 540px;
  margin: 8px auto 0;
  font-size: var(--fs-sm);
  color: var(--c-muted);
  line-height: 1.8;
}

.empty-err {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  margin-top: 14px;
  padding: 6px 12px;
  border-radius: 8px;
  background: var(--warn-soft);
  color: var(--warn);
  font-size: var(--fs-sm);
}

.recent-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(210px, 1fr));
  gap: 12px;
  margin-top: 26px;
  text-align: left;
}

.recent-card {
  padding: 12px 14px;
  border-radius: 10px;
  background: var(--surface-2);
  border: 1px solid var(--surface-2);
  cursor: pointer;
  transition: all 0.16s;
}

.recent-card:hover {
  border-color: var(--accent-soft);
  background: var(--surface-2);
  transform: translateY(-1px);
  box-shadow: 0 6px 16px rgba(16, 42, 32, 0.06);
}

.rc-no {
  font-size: var(--fs-xs);
  font-weight: 600;
  color: var(--accent);
  word-break: break-all;
}

.rc-main {
  margin-top: 6px;
  font-size: var(--fs-base);
  font-weight: 700;
  color: var(--ink);
}

.rc-sub {
  margin-top: 3px;
  font-size: var(--fs-xs);
  color: var(--c-muted);
}

.rc-sub.muted {
  color: var(--c-muted-2);
  font-size: var(--fs-xs);
}
</style>
