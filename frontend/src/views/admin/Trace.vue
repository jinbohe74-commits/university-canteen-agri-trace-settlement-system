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
import { ElMessage } from 'element-plus'
import { adminApi } from '@/api'
import TracePanel from '@/components/TracePanel.vue'

const loading = ref(false)
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
    const res = await adminApi.trace(bn)
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
  ElMessage.info('已唤起扫码识别，请将批次二维码对准扫码枪或摄像头')
  if (searchInput.value && searchInput.value.focus) searchInput.value.focus()
}

const exportReport = () => {
  if (!trace.value) {
    ElMessage.warning('请先查询批次溯源信息')
    return
  }
  ElMessage.success('溯源报告已生成：' + trace.value.batchNo)
}

onMounted(async () => {
  loading.value = true
  try {
    const res = await adminApi.trace()
    const d = res.data || {}
    recent.value = d.recent || []
    if (recent.value.length) {
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
  font-size: 14px;
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
  font-size: 12px;
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
  background: #f7faf9;
  border: 1px solid #eaeff0;
  cursor: pointer;
  transition: all 0.16s;
}

.chip:hover {
  border-color: #bbe6cb;
  background: #f2fbf5;
}

.chip.on {
  border-color: #86efac;
  background: #e8f7ee;
  box-shadow: 0 0 0 2px rgba(34, 197, 94, 0.1);
}

.chip-no {
  font-size: 11.5px;
  font-weight: 600;
  color: #15803d;
}

.chip-meta {
  font-size: 10.5px;
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
  background: linear-gradient(135deg, #e8f7ee, #d6f0e0);
  color: #15803d;
  font-size: 26px;
}

.empty-title {
  font-size: 15px;
  font-weight: 700;
  color: #1b2a24;
}

.empty-desc {
  max-width: 540px;
  margin: 8px auto 0;
  font-size: 12.5px;
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
  background: #fdf2e2;
  color: #b45309;
  font-size: 12.5px;
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
  background: #f8fbfa;
  border: 1px solid #eef3f1;
  cursor: pointer;
  transition: all 0.16s;
}

.recent-card:hover {
  border-color: #bbe6cb;
  background: #f2fbf5;
  transform: translateY(-1px);
  box-shadow: 0 6px 16px rgba(16, 42, 32, 0.06);
}

.rc-no {
  font-size: 11px;
  font-weight: 600;
  color: #15803d;
  word-break: break-all;
}

.rc-main {
  margin-top: 6px;
  font-size: 13px;
  font-weight: 700;
  color: #1b2a24;
}

.rc-sub {
  margin-top: 3px;
  font-size: 11.5px;
  color: var(--c-muted);
}

.rc-sub.muted {
  color: var(--c-muted-2);
  font-size: 11px;
}
</style>
